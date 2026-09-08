import asyncio

import pytest
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from langchain_core.runnables import RunnableLambda
from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.config import Settings
from app.graph.builder import (
    RouterInvalidResponseError, SupervisorInvalidPlanError, build_customer_service_graph,
)
from app.schemas import ResolutionDecision, SceneRouteDecision
from app.tools.registry import build_agent_tools


class FakeStructuredModel:
    def __init__(self, responses_by_schema):
        self.responses = {name: iter(values) for name, values in responses_by_schema.items()}

    def with_structured_output(self, schema, include_raw=False):
        def invoke(_):
            value = next(self.responses[schema.__name__])
            if value is None:
                return {"parsed": None, "raw": AIMessage(content="invalid"),
                        "parsing_error": ValueError("invalid")}
            return {"parsed": value, "raw": AIMessage(content="structured"), "parsing_error": None}
        return RunnableLambda(invoke)


class ToolFakeChatModel(GenericFakeChatModel):
    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        return self


class FakeClient:
    async def call(self, path, token, payload=None):
        if path.endswith("/orders/current"):
            return {
                "success": True,
                "data": [{"orderId": 9001, "status": 1, "statusText": "未支付"}],
                "bizRefs": [{"bizType": "VOUCHER_ORDER", "bizId": 9001}],
            }
        if path.endswith("/vouchers/by-shop"):
            return {"success": True, "data": [{"voucherId": 7, "stock": 9}]}
        return {"success": True, "data": []}


class FakeRetriever:
    async def asearch(self, query, categories=None, limit=None):
        category = categories[0] if categories else "platform-faq"
        return [{
            "chunk_id": "faq-1", "document_id": "platform-faq",
            "content": "平台规则内容", "source": "platform-faq.md",
            "category": category, "version": "1", "score": 0.9,
        }]


def scene_decision(primary="PRE_SALES", scenes=None, confidence=0.95, clarification=False):
    scenes = scenes or [primary]
    return SceneRouteDecision.model_validate({
        "primaryScene": primary, "scenes": scenes, "confidence": confidence,
        "clarificationRequired": clarification,
        "reasonCode": "USER_EXPLICIT_HANDOFF" if primary == "HUMAN_HANDOFF" else (
            "MULTI_SCENE" if len(scenes) > 1 else "SINGLE_SCENE"
        ),
    })


def resolution_response(**overrides):
    value = {"resolutionType": "RESPONSE_ONLY", "reasonCode": "ANSWER_ONLY"}
    value.update(overrides)
    return ResolutionDecision.model_validate(value)


def graph_for(model, route_decisions, resolutions=None):
    tools = build_agent_tools(Settings(_env_file=None), FakeClient(), FakeRetriever())
    structured = FakeStructuredModel({
        "SceneRouteDecision": route_decisions,
        "ResolutionDecision": resolutions or [resolution_response()],
    })
    return build_customer_service_graph(
        model=model, router_model=structured, supervisor_model=structured,
        tools_by_agent=tools, checkpointer=MemorySaver(), settings=Settings(_env_file=None),
    )


def graph_input(message="怎么登录？"):
    return {
        "request_id": "11", "thread_id": "22", "im_chat_id": 33, "user_message_id": 11,
        "message": message, "long_term_summary": "暂无",
        "recent_messages": [{"message_id": 11, "role": "user", "content": message}],
        "previous_active_agent": None, "previous_active_scene": None,
        "previous_active_master": None, "graph_version": "v5", "run_id": "run", "trace_id": "trace",
    }


def invocation_context():
    return {
        "tool_access_tokens": {"transaction_agent": "tx", "discovery_agent": "discovery"},
        "request_id": "11", "result_cache": None, "result_ttl_seconds": 86400,
        "parallel_semaphore": asyncio.Semaphore(2),
    }


async def invoke(graph, message="怎么登录？"):
    return await graph.ainvoke(
        graph_input(message),
        config={"configurable": {"thread_id": "22:run", "checkpoint_ns": "customer_service_v5"},
                "recursion_limit": 48},
        context=invocation_context(),
    )


async def test_pre_sales_master_uses_platform_knowledge_as_plain_tool():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "search_platform_knowledge", "args": {"query": "解释登录规则"},
            "id": "faq-1", "type": "tool_call",
        }]),
        AIMessage(content="根据平台规则，可以使用手机号验证码登录。"),
        AIMessage(content="根据平台规则，可以使用手机号验证码登录。"),
    ]))
    result = await invoke(graph_for(model, []))
    assert result["primary_scene"] == "PRE_SALES"
    assert result["route_source"] == "RULE"
    assert result["active_agent"] == "pre_sales_master_agent"
    assert result["tool_call_count"] == 1
    assert result["final_response"].startswith("根据平台规则")


async def test_product_faq_workflow_clarifies_without_product_context():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "answer_product_faq", "args": {"task_goal": "这个商品适合吗"},
            "id": "faq-1", "type": "tool_call",
        }]),
        AIMessage(content="登录和账号规则已经综合完成。"),
        AIMessage(content="根据FAQ Agent的多轮检索，登录和账号规则如下。"),
    ]))
    result = await invoke(graph_for(model, [scene_decision()]))
    assert result["task_outcomes"][0]["tool_call_count"] == 0
    assert result["task_outcomes"][0]["status"] == "SUCCEEDED"
    assert result["task_outcomes"][0]["metadata"]["faqResult"]["status"] == "NEEDS_CLARIFICATION"
    assert result["agent_artifacts"][0]["faq_result"]["shoppingAdvice"] == []
    assert result["final_response"].startswith("请先选择")


async def test_master_rejects_more_than_configured_top_level_tasks():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[
            {"name": "query_shop_by_id", "args": {"shop_id": value},
             "id": f"shop-{value}", "type": "tool_call"}
            for value in range(1, 5)
        ]),
        AIMessage(content="四家店铺的结果。"),
    ]))
    with pytest.raises(SupervisorInvalidPlanError, match="超过上限"):
        await invoke(graph_for(model, [scene_decision()]), "比较四家店铺")


async def test_all_required_agent_tools_failed_proposes_handoff():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "recommendation_agent", "args": {"task_goal": "推荐店铺"},
            "id": "faq-1", "type": "tool_call",
        }]),
        AIMessage(content=""),
        AIMessage(content="目前无法取得规则查询结果。"),
    ]))
    result = await invoke(graph_for(model, [scene_decision()]))
    assert result["run_status"] == "HANDOFF_REQUESTED"
    assert result["handoff_proposal"]["reason_code"] == "ALL_REQUIRED_TOOLS_FAILED_FINAL"


async def test_pre_sales_master_calls_voucher_query_as_plain_tool():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "query_vouchers_by_shop_id", "args": {"shop_id": 1},
            "id": "voucher-1", "type": "tool_call",
        }]),
        AIMessage(content="该店当前有可购买优惠券，库存9张。"),
    ]))
    result = await invoke(graph_for(model, [scene_decision()]), "查询店铺1的优惠券库存")
    assert result["tool_call_count"] == 1
    assert result["task_outcomes"] == []
    assert "库存9张" in result["final_response"]


async def test_after_sales_master_calls_order_query_as_plain_tool():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "query_current_user_orders", "args": {},
            "id": "orders-1", "type": "tool_call",
        }]),
        AIMessage(content="为你实时查询到订单9001当前未支付。"),
    ]))
    result = await invoke(graph_for(model, [scene_decision("AFTER_SALES")]), "查询我的订单")
    assert result["primary_scene"] == "AFTER_SALES"
    assert result["active_agent"] == "after_sales_master_agent"
    assert result["business_refs"] == [{"bizType": "VOUCHER_ORDER", "bizId": 9001}]


async def test_cross_scene_master_calls_both_scene_agents_as_tools():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "pre_sales_master_agent", "args": {"task_goal": "查询店铺1优惠券"},
            "id": "pre-1", "type": "tool_call",
        }]),
        AIMessage(content="", tool_calls=[{
            "name": "query_vouchers_by_shop_id", "args": {"shop_id": 1},
            "id": "voucher-1", "type": "tool_call",
        }]),
        AIMessage(content="店铺1有优惠券库存。"),
        AIMessage(content="", tool_calls=[{
            "name": "after_sales_master_agent", "args": {"task_goal": "查询我的订单"},
            "id": "after-1", "type": "tool_call",
        }]),
        AIMessage(content="", tool_calls=[{
            "name": "query_current_user_orders", "args": {},
            "id": "orders-1", "type": "tool_call",
        }]),
        AIMessage(content="订单9001未支付。"),
        AIMessage(content="店铺1有优惠券；你的订单9001当前未支付。"),
    ]))
    route = scene_decision("PRE_SALES", ["PRE_SALES", "AFTER_SALES"])
    result = await invoke(graph_for(model, [route]), "查店铺1优惠券，也查我的订单")
    assert result["scenes"] == ["PRE_SALES", "AFTER_SALES"]
    assert result["active_agent"] == "customer_service_master_agent"
    assert result["tool_call_count"] == 2
    assert result["parallel_task_count"] == 2


async def test_explicit_human_handoff_is_terminal_and_exclusive():
    model = ToolFakeChatModel(messages=iter([]))
    result = await invoke(graph_for(model, [scene_decision("HUMAN_HANDOFF")]), "请转人工客服")
    assert result["run_status"] == "HANDOFF_REQUESTED"
    assert result["handoff_proposal"]["reason_code"] == "USER_EXPLICIT_REQUEST"
    assert result["active_agent"] == "human_handoff_guard"
    assert result["tool_call_count"] == 0


async def test_human_handoff_cannot_be_mixed_with_other_scene():
    with pytest.raises(ValueError, match="exclusive"):
        scene_decision("HUMAN_HANDOFF", ["HUMAN_HANDOFF", "PRE_SALES"])


async def test_low_confidence_falls_back_to_pre_sales_scene():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "answer_product_faq", "args": {"task_goal": "询问一个澄清问题"},
            "id": "faq-1", "type": "tool_call",
        }]),
        AIMessage(content="请问你想咨询店铺、订单，还是售后问题？"),
        AIMessage(content="请问你想咨询店铺、订单，还是售后问题？"),
    ]))
    route = scene_decision("AFTER_SALES", confidence=0.4, clarification=True)
    result = await invoke(graph_for(model, [route]), "帮帮我")
    assert result["primary_scene"] == "PRE_SALES"
    assert result["clarification_required"] is True
    assert result["route_source"] == "LLM"


async def test_router_invalid_response_repairs_once_then_fails():
    model = ToolFakeChatModel(messages=iter([]))
    with pytest.raises(RouterInvalidResponseError):
        await invoke(graph_for(model, [None, None]), "这件事怎么办")


async def test_action_proposal_interrupts_and_resumes_without_write_tool():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "query_current_user_orders", "args": {},
            "id": "orders-1", "type": "tool_call",
        }]),
        AIMessage(content="订单9001当前未支付，可以申请取消。"),
    ]))
    resolution = resolution_response(
        resolutionType="ACTION_PROPOSAL", actionType="CANCEL_UNPAID_ORDER",
        targetOrderId=9001, userFacingSummary="订单9001当前未支付",
        confirmationPrompt="请确认", reasonCode="ELIGIBLE_CANCEL",
    )
    graph = graph_for(model, [scene_decision("AFTER_SALES")], [resolution])
    config = {"configurable": {"thread_id": "22:run", "checkpoint_ns": "customer_service_v5"},
              "recursion_limit": 48}
    context = invocation_context()
    pending = await graph.ainvoke(graph_input("取消订单9001"), config=config, context=context)
    assert pending["run_status"] == "AWAITING_CONFIRMATION"
    assert pending["action_proposal"]["order_id"] == 9001
    resumed = await graph.ainvoke(Command(resume={"action_outcome": {
        "status": "SUCCEEDED", "result_code": "ORDER_CANCELLED", "message": "订单已取消。",
        "business_refs": [{"biz_type": "VOUCHER_ORDER", "biz_id": 9001}],
    }}), config=config, context=context)
    assert resumed["final_response"] == "订单已取消。"


async def test_existing_pending_action_prevents_second_action_proposal():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "query_current_user_orders", "args": {},
            "id": "orders-1", "type": "tool_call",
        }]),
        AIMessage(content="可以继续回答你的新问题。"),
    ]))
    resolution = resolution_response(
        resolutionType="ACTION_PROPOSAL", actionType="REQUEST_REFUND",
        targetOrderId=9001, reasonCode="MODEL_ATTEMPTED_SECOND_ACTION",
    )
    graph = graph_for(model, [scene_decision("AFTER_SALES")], [resolution])
    payload = graph_input("顺便再申请退款")
    payload["pending_action"] = {
        "action_request_id": "action-1", "action_type": "CANCEL_UNPAID_ORDER",
        "target_biz_type": "VOUCHER_ORDER", "target_biz_id": 9001,
        "expires_at": "2026-09-01T01:00:00",
    }
    result = await graph.ainvoke(
        payload,
        config={"configurable": {"thread_id": "22:run", "checkpoint_ns": "customer_service_v5"},
                "recursion_limit": 48},
        context=invocation_context(),
    )
    assert result["run_status"] == "COMPLETED"
    assert result.get("action_proposal") is None
