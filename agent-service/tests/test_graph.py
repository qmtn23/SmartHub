import asyncio

import pytest
from langchain_core.language_models import BaseChatModel
from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.runnables import RunnableLambda
from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.config import Settings
from app.graph.builder import (
    AllAgentTasksFailedError, RouterInvalidResponseError, SupervisorInvalidPlanError,
    SupervisorInvalidReviewError, build_customer_service_graph,
)
from app.schemas import ResolutionDecision, RouteDecision, SupervisorPlan, SupervisorReview
from app.tools.registry import build_agent_tools


class FakeStructuredModel:
    def __init__(self, responses_by_schema):
        self.responses = {name: iter(values) for name, values in responses_by_schema.items()}

    def with_structured_output(self, schema, include_raw=False):
        def invoke(_):
            value = next(self.responses[schema.__name__])
            if value is None:
                return {"parsed": None, "raw": AIMessage(content="invalid"), "parsing_error": ValueError("invalid")}
            return {"parsed": value, "raw": AIMessage(content="structured"), "parsing_error": None}
        return RunnableLambda(invoke)


class ToolFakeChatModel(GenericFakeChatModel):
    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        return self


class ConcurrentFakeChatModel(BaseChatModel):
    fail_agents: set[str] = set()
    active: int = 0
    max_active: int = 0

    @property
    def _llm_type(self):
        return "concurrent-fake"

    def bind_tools(self, tools, *, tool_choice=None, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=self._response(messages)))])

    async def _agenerate(self, messages, stop=None, run_manager=None, **kwargs):
        self.active += 1
        self.max_active = max(self.max_active, self.active)
        try:
            await asyncio.sleep(0.03)
            system_text = " ".join(str(item.content) for item in messages if item.type == "system")
            agent = "transaction_agent" if "当前用户订单" in system_text else (
                "discovery_agent" if "店铺搜索" in system_text else "general_support_agent"
            )
            if "最终客服回复汇总器" not in system_text and agent in self.fail_agents:
                raise RuntimeError("simulated branch failure")
            return ChatResult(generations=[ChatGeneration(message=AIMessage(content=self._response(messages)))])
        finally:
            self.active -= 1

    def _response(self, messages: list[BaseMessage]) -> str:
        system_text = " ".join(str(item.content) for item in messages if item.type == "system")
        if "最终客服回复汇总器" in system_text:
            return "已综合各项查询结果；未完成的部分已明确说明。"
        return "领域任务查询成功。"


def decision(intent, tasks, confidence=0.95, clarification=False):
    return RouteDecision.model_validate({
        "primaryIntent": intent,
        "tasks": tasks,
        "executionMode": "COMPLEX" if len({item["targetAgent"] for item in tasks}) > 1 else "SIMPLE",
        "complexityReason": "PARALLEL_DOMAINS" if len(tasks) > 1 else "SINGLE_DOMAIN",
        "confidence": confidence,
        "clarificationRequired": clarification,
        "reasonCode": "MULTI_INTENT" if len(tasks) > 1 else "SINGLE_INTENT",
    })


def plan(tasks, plan_id="plan-1"):
    return SupervisorPlan.model_validate({
        "planId": plan_id,
        "tasks": tasks,
        "synthesisGoal": "综合回答用户的全部问题",
        "reasonCode": "DEPENDENCY" if any(item.get("dependsOn") for item in tasks) else "PARALLEL",
    })


def review(action="COMPLETE", new_tasks=None):
    return SupervisorReview.model_validate({
        "action": action,
        "newTasks": new_tasks or [],
        "reasonCode": "MISSING_DOMAIN" if action == "REPLAN" else "COVERAGE_COMPLETE",
    })


def resolution_response():
    return ResolutionDecision.model_validate({
        "resolutionType": "RESPONSE_ONLY", "reasonCode": "ANSWER_ONLY"
    })


def graph_for(model, route_decisions, plans=None, reviews=None, tools_by_agent=None, resolutions=None):
    return build_customer_service_graph(
        model=model,
        router_model=FakeStructuredModel({"RouteDecision": route_decisions}),
        supervisor_model=FakeStructuredModel({
            "SupervisorPlan": plans or [],
            "SupervisorReview": reviews or [],
            "ResolutionDecision": resolutions or [resolution_response()],
        }),
        tools_by_agent=tools_by_agent or {
            "general_support_agent": [], "transaction_agent": [], "discovery_agent": [],
        },
        checkpointer=MemorySaver(),
        settings=Settings(_env_file=None),
    )


def graph_input(message="怎么登录？"):
    return {
        "request_id": "11", "thread_id": "22", "im_chat_id": 33, "user_message_id": 11,
        "message": message, "long_term_summary": "暂无",
        "recent_messages": [{"message_id": 11, "role": "user", "content": message}],
        "previous_active_agent": None, "graph_version": "v4", "run_id": "run", "trace_id": "trace",
    }


async def invoke(graph, message="怎么登录？"):
    return await graph.ainvoke(
        graph_input(message),
        config={"configurable": {"thread_id": "22:run", "checkpoint_ns": "customer_service_v4"}, "recursion_limit": 48},
        context={
            "tool_access_tokens": {"transaction_agent": "tx", "discovery_agent": "discovery"},
            "request_id": "11", "result_cache": None, "result_ttl_seconds": 86400,
            "parallel_semaphore": asyncio.Semaphore(2),
        },
    )


async def test_single_intent_uses_router_fast_path_without_supervisor():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="根据平台规则，可以通过手机号验证码登录。")]))
    route = decision("PLATFORM_KNOWLEDGE", [
        {"targetAgent": "general_support_agent", "intent": "PLATFORM_KNOWLEDGE", "userGoal": "查询登录规则"}
    ])
    result = await invoke(graph_for(model, [route]))
    assert result["execution_mode"] == "SIMPLE"
    assert result["orchestrator"] == "router"
    assert result["supervisor_iterations"] == 0
    assert result["final_response"].startswith("根据平台规则")


async def test_dependency_plan_runs_transaction_before_general_then_synthesizes_once():
    model = GenericFakeChatModel(messages=iter([
        AIMessage(content="订单查询结果：待使用。"),
        AIMessage(content="根据平台规则，可以申请退款。"),
        AIMessage(content="为你实时查询到订单待使用；根据平台规则，可以申请退款。"),
    ]))
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查询订单"},
        {"targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "解释退款"},
    ])
    execution_plan = plan([
        {"taskId": "order", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查询订单", "dependsOn": []},
        {"taskId": "policy", "targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "解释退款", "dependsOn": ["order"]},
    ])
    result = await invoke(graph_for(model, [route], [execution_plan], [review()]), "查订单并说明退款规则")
    assert [item["status"] for item in result["task_outcomes"]] == ["SUCCEEDED", "SUCCEEDED"]
    assert result["wave_count"] == 2
    assert result["parallel_task_count"] == 2
    assert result["final_response"].startswith("为你实时查询到")
    assert sum(item["event"] == "RESPONSE_SYNTHESIZED" for item in result["route_history"]) == 1


async def test_two_independent_tasks_execute_concurrently_with_limit_two():
    model = ConcurrentFakeChatModel()
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺"},
    ])
    execution_plan = plan([
        {"taskId": "orders", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": []},
        {"taskId": "shops", "targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺", "dependsOn": []},
    ])
    result = await invoke(graph_for(model, [route], [execution_plan], [review()]), "查订单并推荐店铺")
    assert model.max_active == 2
    assert result["parallel_task_count"] == 2
    assert len([item for item in result["task_outcomes"] if item["status"] == "SUCCEEDED"]) == 2


async def test_three_agents_run_in_two_waves_and_synthesize_exactly_once():
    model = ConcurrentFakeChatModel()
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺"},
        {"targetAgent": "general_support_agent", "intent": "PLATFORM_KNOWLEDGE", "userGoal": "说明规则"},
    ])
    execution_plan = plan([
        {"taskId": "orders", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": []},
        {"taskId": "shops", "targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺", "dependsOn": []},
        {"taskId": "rules", "targetAgent": "general_support_agent", "intent": "PLATFORM_KNOWLEDGE", "userGoal": "说明规则", "dependsOn": []},
    ])
    result = await invoke(graph_for(model, [route], [execution_plan], [review()]), "查订单、推荐店铺并说明规则")
    assert model.max_active == 2
    assert result["wave_count"] == 2
    assert len(result["task_outcomes"]) == 3
    assert sum(item["event"] == "RESPONSE_SYNTHESIZED" for item in result["route_history"]) == 1


async def test_independent_branch_failure_returns_successful_partial_result():
    model = ConcurrentFakeChatModel(fail_agents={"transaction_agent"})
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺"},
    ])
    execution_plan = plan([
        {"taskId": "orders", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": []},
        {"taskId": "shops", "targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺", "dependsOn": []},
    ])
    result = await invoke(graph_for(model, [route], [execution_plan], [review()]), "查订单并推荐店铺")
    assert {item["status"] for item in result["task_outcomes"]} == {"SUCCEEDED", "FAILED"}
    assert "未完成" in result["final_response"]


async def test_failed_prerequisite_skips_dependent_task_and_requests_handoff():
    model = ConcurrentFakeChatModel(fail_agents={"transaction_agent"})
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "退款规则"},
    ])
    execution_plan = plan([
        {"taskId": "orders", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": []},
        {"taskId": "policy", "targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "退款规则", "dependsOn": ["orders"]},
    ])
    result = await invoke(graph_for(model, [route], [execution_plan], [review()]), "查订单并说明退款规则")
    assert result["run_status"] == "HANDOFF_REQUESTED"
    assert result["handoff_proposal"]["reason_code"] == "ALL_REQUIRED_TOOLS_FAILED_FINAL"


async def test_supervisor_replans_at_most_once_with_remaining_budget():
    model = ConcurrentFakeChatModel()
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "退款规则"},
    ])
    initial = plan([
        {"taskId": "orders", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": []},
    ])
    replan = review("REPLAN", [
        {"taskId": "policy", "targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "补充退款规则", "dependsOn": ["orders"]},
    ])
    result = await invoke(graph_for(model, [route], [initial], [replan]), "查订单并说明退款规则")
    assert result["replan_count"] == 1
    assert result["supervisor_iterations"] == 2
    assert len(result["task_outcomes"]) == 2


async def test_low_confidence_uses_toolless_general_clarification():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="请问你想查询订单、店铺，还是平台规则呢？")]))
    route = decision("GENERAL", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "不确定"}
    ], confidence=0.4, clarification=True)
    result = await invoke(graph_for(model, [route]))
    assert result["clarification_required"] is True
    assert result["execution_mode"] == "SIMPLE"
    assert result["tool_call_count"] == 0


async def test_router_invalid_response_repairs_once_then_fails():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="unused")]))
    with pytest.raises(RouterInvalidResponseError):
        await invoke(graph_for(model, [None, None]))


async def test_cyclic_supervisor_plan_is_rejected():
    model = ConcurrentFakeChatModel()
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "退款"},
    ])
    cyclic = plan([
        {"taskId": "a", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": ["b"]},
        {"taskId": "b", "targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "退款", "dependsOn": ["a"]},
    ])
    with pytest.raises(SupervisorInvalidPlanError):
        await invoke(graph_for(model, [route], [cyclic, cyclic]), "查订单并退款")


async def test_supervisor_rejects_a_fourth_task_after_single_repair():
    model = ConcurrentFakeChatModel()
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "discovery_agent", "intent": "SHOP_RECOMMENDATION", "userGoal": "推荐店铺"},
    ])
    oversized = plan([
        {"taskId": "one", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "一", "dependsOn": []},
        {"taskId": "two", "targetAgent": "discovery_agent", "intent": "SHOP_LOOKUP", "userGoal": "二", "dependsOn": []},
        {"taskId": "three", "targetAgent": "general_support_agent", "intent": "PLATFORM_KNOWLEDGE", "userGoal": "三", "dependsOn": []},
        {"taskId": "four", "targetAgent": "transaction_agent", "intent": "VOUCHER_QUERY", "userGoal": "四", "dependsOn": []},
    ])
    with pytest.raises(SupervisorInvalidPlanError):
        await invoke(graph_for(model, [route], [oversized, oversized]), "处理四项任务")


async def test_replan_cannot_execute_an_already_successful_agent_again():
    model = ConcurrentFakeChatModel()
    route = decision("ORDER_QUERY", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单"},
        {"targetAgent": "general_support_agent", "intent": "AFTER_SALES_POLICY", "userGoal": "退款规则"},
    ])
    initial = plan([
        {"taskId": "orders", "targetAgent": "transaction_agent", "intent": "ORDER_QUERY", "userGoal": "查订单", "dependsOn": []},
    ])
    illegal_replan = review("REPLAN", [
        {"taskId": "vouchers", "targetAgent": "transaction_agent", "intent": "VOUCHER_QUERY", "userGoal": "再查优惠券", "dependsOn": []},
    ])
    with pytest.raises(SupervisorInvalidReviewError, match="重复执行已成功Agent"):
        await invoke(graph_for(model, [route], [initial], [illegal_replan]), "查订单并退款")


class FakeClient:
    async def call(self, path, token, payload=None):
        return {"success": True, "data": []}


class OrderFakeClient:
    async def call(self, path, token, payload=None):
        return {
            "success": True,
            "data": [{"orderId": 9001, "status": 1, "statusText": "未支付"}],
            "bizRefs": [{"bizType": "VOUCHER_ORDER", "bizId": 9001}],
        }


class FakeRetriever:
    async def asearch(self, query, categories=None):
        return []


async def test_simple_mode_keeps_one_bounded_agent_handoff():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "request_handoff",
            "args": {"target_agent": "transaction_agent", "target_intent": "VOUCHER_QUERY",
                     "context_summary": "查询刚找到店铺的优惠券", "reason_code": "NEEDS_TRANSACTION_DATA"},
            "id": "handoff-1", "type": "tool_call",
        }]),
        AIMessage(content="已找到目标店铺。"),
        AIMessage(content="为你实时查询到该店铺有可用优惠券。"),
    ]))
    tools = build_agent_tools(Settings(_env_file=None), FakeClient(), FakeRetriever())
    route = decision("SHOP_LOOKUP", [
        {"targetAgent": "discovery_agent", "intent": "SHOP_LOOKUP", "userGoal": "查询店铺及优惠券"}
    ])
    result = await invoke(graph_for(model, [route], tools_by_agent=tools))
    assert result["handoff_count"] == 1
    assert result["active_agent"] == "transaction_agent"
    assert result["final_response"] == "为你实时查询到该店铺有可用优惠券。"


async def test_action_proposal_interrupts_and_resumes_without_write_tool():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{
            "name": "query_current_user_orders", "args": {}, "id": "orders-1", "type": "tool_call",
        }]),
        AIMessage(content="订单9001当前未支付，可以申请取消。"),
    ]))
    tools = build_agent_tools(Settings(_env_file=None), OrderFakeClient(), FakeRetriever())
    route = decision("ORDER_CANCEL", [
        {"targetAgent": "transaction_agent", "intent": "ORDER_CANCEL", "userGoal": "取消订单9001"}
    ])
    resolution = ResolutionDecision.model_validate({
        "resolutionType": "ACTION_PROPOSAL", "actionType": "CANCEL_UNPAID_ORDER",
        "targetOrderId": 9001, "userFacingSummary": "订单9001当前未支付",
        "confirmationPrompt": "请确认", "reasonCode": "ELIGIBLE_CANCEL",
    })
    graph = graph_for(model, [route], tools_by_agent=tools, resolutions=[resolution])
    config = {"configurable": {"thread_id": "22:run", "checkpoint_ns": "customer_service_v4"},
              "recursion_limit": 48}
    context = {
        "tool_access_tokens": {"transaction_agent": "tx", "discovery_agent": "discovery"},
        "request_id": "11", "result_cache": None, "result_ttl_seconds": 86400,
        "parallel_semaphore": asyncio.Semaphore(2),
    }
    pending = await graph.ainvoke(graph_input("取消订单9001"), config=config, context=context)
    assert pending["run_status"] == "AWAITING_CONFIRMATION"
    assert pending["action_proposal"]["order_id"] == 9001
    resumed = await graph.ainvoke(Command(resume={"action_outcome": {
        "status": "SUCCEEDED", "result_code": "ORDER_CANCELLED", "message": "订单已取消。",
        "business_refs": [{"biz_type": "VOUCHER_ORDER", "biz_id": 9001}],
    }}), config=config, context=context)
    assert resumed["final_response"] == "订单已取消。"
    assert resumed["run_status"] == "COMPLETED"


async def test_existing_pending_action_prevents_second_action_proposal():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="可以继续回答你的新问题。")]))
    route = decision("GENERAL", [
        {"targetAgent": "general_support_agent", "intent": "GENERAL", "userGoal": "其他问题"}
    ])
    resolution = ResolutionDecision.model_validate({
        "resolutionType": "ACTION_PROPOSAL", "actionType": "REQUEST_REFUND",
        "targetOrderId": 9001, "reasonCode": "MODEL_ATTEMPTED_SECOND_ACTION",
    })
    graph = graph_for(model, [route], resolutions=[resolution])
    payload = graph_input("顺便再申请退款")
    payload["pending_action"] = {
        "action_request_id": "action-1", "action_type": "CANCEL_UNPAID_ORDER",
        "target_biz_type": "VOUCHER_ORDER", "target_biz_id": 9001,
        "expires_at": "2026-09-01T01:00:00",
    }
    result = await graph.ainvoke(
        payload,
        config={"configurable": {"thread_id": "22:run", "checkpoint_ns": "customer_service_v4"},
                "recursion_limit": 48},
        context={"tool_access_tokens": {"transaction_agent": "tx", "discovery_agent": "discovery"},
                 "request_id": "11", "result_cache": None, "result_ttl_seconds": 86400,
                 "parallel_semaphore": asyncio.Semaphore(2)},
    )
    assert result["run_status"] == "COMPLETED"
    assert result.get("action_proposal") is None
