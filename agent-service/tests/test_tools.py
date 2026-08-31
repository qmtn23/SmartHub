import json

import pytest

from app.config import Settings
from app.tools.registry import RunToolContext, build_agent_tools, reset_run_tool_context, set_run_tool_context


class FakeClient:
    def __init__(self):
        self.calls = []

    async def call(self, path, token, payload=None):
        self.calls.append((path, token, payload))
        return {"success": True, "data": [], "bizRefs": [{"bizType": "VOUCHER_ORDER", "bizId": 9}]}


class FakeRetriever:
    def __init__(self):
        self.categories = None

    async def asearch(self, query, categories=None):
        self.categories = categories
        return []


class FakeCache:
    def __init__(self):
        self.values = {}

    async def get(self, key):
        return self.values.get(key)

    async def set(self, key, value, ex=None):
        self.values[key] = value


async def test_agent_tool_lists_are_strictly_isolated():
    tools = build_agent_tools(Settings(_env_file=None), FakeClient(), FakeRetriever())
    assert {item.name for item in tools["general_support_agent"]} == {
        "search_platform_knowledge", "search_external_web", "request_handoff"
    }
    assert "query_current_user_orders" in {item.name for item in tools["transaction_agent"]}
    assert "query_current_user_orders" not in {item.name for item in tools["discovery_agent"]}
    assert "search_shops_by_name" not in {item.name for item in tools["transaction_agent"]}


async def test_current_order_tool_forwards_transaction_token_without_user_id():
    client = FakeClient()
    tools = build_agent_tools(Settings(_env_file=None), client, FakeRetriever())
    order_tool = next(item for item in tools["transaction_agent"] if item.name == "query_current_user_orders")
    context = RunToolContext(active_agent="transaction_agent", token="signed-token", max_calls=6)
    marker = set_run_tool_context(context)
    try:
        result = json.loads(await order_tool.ainvoke({}))
    finally:
        reset_run_tool_context(marker)
    assert result["success"] is True
    assert client.calls == [("/internal/agent-tools/orders/current", "signed-token", None)]
    assert context.business_refs == [{"bizType": "VOUCHER_ORDER", "bizId": 9}]


async def test_rag_category_is_forwarded_only_for_general_agent():
    retriever = FakeRetriever()
    tools = build_agent_tools(Settings(_env_file=None), FakeClient(), retriever)
    rag_tool = next(item for item in tools["general_support_agent"] if item.name == "search_platform_knowledge")
    marker = set_run_tool_context(RunToolContext(active_agent="general_support_agent", token=None, max_calls=6))
    try:
        await rag_tool.ainvoke({"query": "退款", "category": "refund-process"})
    finally:
        reset_run_tool_context(marker)
    assert retriever.categories == ["refund-process"]


async def test_java_tool_rejects_missing_scoped_token():
    tools = build_agent_tools(Settings(_env_file=None), FakeClient(), FakeRetriever())
    order_tool = next(item for item in tools["transaction_agent"] if item.name == "query_current_user_orders")
    marker = set_run_tool_context(RunToolContext(active_agent="transaction_agent", token=None, max_calls=6))
    try:
        with pytest.raises(PermissionError):
            await order_tool.ainvoke({})
    finally:
        reset_run_tool_context(marker)


async def test_completed_java_tool_result_is_reused_on_same_request_retry():
    client = FakeClient()
    cache = FakeCache()
    tools = build_agent_tools(Settings(_env_file=None), client, FakeRetriever())
    order_tool = next(item for item in tools["transaction_agent"] if item.name == "query_current_user_orders")
    context = RunToolContext(
        active_agent="transaction_agent", token="signed-token", max_calls=6,
        request_id="3001", result_cache=cache,
    )
    marker = set_run_tool_context(context)
    try:
        first = await order_tool.ainvoke({})
        second = await order_tool.ainvoke({})
    finally:
        reset_run_tool_context(marker)
    assert second == first
    assert len(client.calls) == 1
