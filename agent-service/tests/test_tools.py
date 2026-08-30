import json

from app.config import Settings
from app.tools.registry import RunToolContext, build_tools, reset_run_tool_context, set_run_tool_context


class FakeClient:
    def __init__(self):
        self.calls = []

    async def call(self, path, token, payload=None):
        self.calls.append((path, token, payload))
        return {"success": True, "data": [], "bizRefs": [{"bizType": "VOUCHER_ORDER", "bizId": 9}]}


class FakeRetriever:
    async def asearch(self, query):
        return []


async def test_current_order_tool_forwards_token_without_user_id():
    client = FakeClient()
    tools = build_tools(Settings(_env_file=None), client, FakeRetriever())
    order_tool = next(item for item in tools if item.name == "query_current_user_orders")
    context = RunToolContext(token="signed-token", max_calls=6)
    marker = set_run_tool_context(context)
    try:
        result = json.loads(await order_tool.ainvoke({}))
    finally:
        reset_run_tool_context(marker)
    assert result["success"] is True
    assert client.calls == [("/internal/agent-tools/orders/current", "signed-token", None)]
    assert context.business_refs == [{"bizType": "VOUCHER_ORDER", "bizId": 9}]
