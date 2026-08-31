from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda
from langgraph.checkpoint.memory import MemorySaver
import pytest

from app.config import Settings
from app.graph.builder import build_customer_service_graph
from app.graph.builder import RouterInvalidResponseError
from app.schemas import RouteDecision
from app.tools.registry import build_agent_tools


class FakeRouter:
    def __init__(self, decisions):
        self.decisions = iter(decisions)

    def with_structured_output(self, schema, include_raw=False):
        def invoke(_):
            decision = next(self.decisions)
            if decision is None:
                return {"parsed": None, "raw": AIMessage(content="invalid"), "parsing_error": ValueError("invalid")}
            return {"parsed": decision, "raw": AIMessage(content="route"), "parsing_error": None}

        return RunnableLambda(invoke)


class ToolFakeChatModel(GenericFakeChatModel):
    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        return self


def decision(intent, tasks, confidence=0.95, clarification=False):
    return RouteDecision.model_validate(
        {
            "primaryIntent": intent,
            "tasks": tasks,
            "confidence": confidence,
            "clarificationRequired": clarification,
            "reasonCode": "SINGLE_INTENT" if len(tasks) == 1 else "MULTI_INTENT",
        }
    )


def graph_for(model, route_decisions, tools_by_agent=None):
    return build_customer_service_graph(
        model=model,
        router_model=FakeRouter(route_decisions),
        tools_by_agent=tools_by_agent or {
            "general_support_agent": [],
            "transaction_agent": [],
            "discovery_agent": [],
        },
        checkpointer=MemorySaver(),
        settings=Settings(_env_file=None),
    )


def graph_input(message="怎么登录？"):
    return {
        "request_id": "11",
        "thread_id": "22",
        "im_chat_id": 33,
        "user_message_id": 11,
        "message": message,
        "long_term_summary": "暂无",
        "recent_messages": [{"message_id": 11, "role": "user", "content": message}],
        "previous_active_agent": None,
        "graph_version": "v2",
        "run_id": "run",
        "trace_id": "trace",
    }


async def invoke(graph):
    return await graph.ainvoke(
        graph_input(),
        config={
            "configurable": {"thread_id": "22", "checkpoint_ns": "customer_service_v2"},
            "recursion_limit": 20,
        },
        context={
            "tool_access_tokens": {"transaction_agent": "tx", "discovery_agent": "discovery"},
            "request_id": "11", "result_cache": None, "result_ttl_seconds": 86400,
        },
    )


async def test_single_intent_routes_to_general_agent_and_guards_response():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="根据平台规则，可以通过手机号验证码登录。")] ))
    route = decision(
        "PLATFORM_KNOWLEDGE",
        [{"targetAgent": "general_support_agent", "intent": "PLATFORM_KNOWLEDGE", "userGoal": "查询登录规则"}],
    )
    result = await invoke(graph_for(model, [route]))
    assert result["final_response"] == "根据平台规则，可以通过手机号验证码登录。"
    assert result["active_agent"] == "general_support_agent"
    assert result["primary_intent"] == "PLATFORM_KNOWLEDGE"
    assert result["handoff_count"] == 0
    assert len(result["messages"]) == 1


async def test_order_and_refund_policy_run_transaction_then_general_once():
    model = GenericFakeChatModel(
        messages=iter([
            AIMessage(content="订单查询结果：待使用。"),
            AIMessage(content="为你实时查询到订单待使用；根据平台规则，可按退款入口提交申请。"),
        ])
    )
    route = decision(
        "ORDER_QUERY",
        [
            {"targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "解释退款政策"},
            {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查询当前订单"},
        ],
    )
    result = await invoke(graph_for(model, [route]))
    assert [item["agent"] for item in result["route_history"]] == ["transaction_agent", "general_support_agent"]
    assert result["active_agent"] == "general_support_agent"
    assert result["handoff_count"] == 1
    assert len(result["completed_tasks"]) == 2
    assert result["final_response"].startswith("为你实时查询到")


async def test_low_confidence_uses_toolless_general_clarification():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="请问你想查询订单、店铺，还是平台规则呢？")]))
    route = decision(
        "GENERAL",
        [{"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "不确定"}],
        confidence=0.4,
        clarification=True,
    )
    result = await invoke(graph_for(model, [route]))
    assert result["clarification_required"] is True
    assert result["route_history"][0]["agent"] == "general_support_agent"
    assert result["tool_call_count"] == 0


async def test_router_invalid_response_is_repaired_once_then_fails():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="unused")]))
    graph = graph_for(model, [None, None])
    with pytest.raises(RouterInvalidResponseError):
        await invoke(graph)


class FakeClient:
    async def call(self, path, token, payload=None):
        return {"success": True, "data": []}


class FakeRetriever:
    async def asearch(self, query, categories=None):
        return []


async def test_agent_requested_handoff_is_bounded_and_runs_second_agent():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "request_handoff",
            "args": {
                "target_agent": "transaction_agent",
                "target_intent": "VOUCHER_QUERY",
                "context_summary": "查询刚找到店铺的优惠券",
                "reason_code": "NEEDS_TRANSACTION_DATA",
            },
            "id": "handoff-1",
            "type": "tool_call",
        }]),
        AIMessage(content="已找到目标店铺。"),
        AIMessage(content="为你实时查询到该店铺有可用优惠券。"),
    ]))
    tools = build_agent_tools(Settings(_env_file=None), FakeClient(), FakeRetriever())
    route = decision(
        "SHOP_LOOKUP",
        [{"targetAgent": "discovery_agent", "intent": "SHOP_LOOKUP", "userGoal": "查询店铺及优惠券"}],
    )
    result = await invoke(graph_for(model, [route], tools))
    assert [item["agent"] for item in result["route_history"]] == ["discovery_agent", "transaction_agent"]
    assert result["handoff_count"] == 1
    assert result["tool_call_count"] == 1
    assert result["final_response"] == "为你实时查询到该店铺有可用优惠券。"
