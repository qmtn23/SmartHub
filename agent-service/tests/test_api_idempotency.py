import json
from types import SimpleNamespace

import httpx
from fastapi import FastAPI

from app.api import router
from app.config import get_settings


class FakeRedis:
    def __init__(self):
        self.values = {}

    async def get(self, key):
        return self.values.get(key)

    async def set(self, key, value, ex=None, nx=False):
        if nx and key in self.values:
            return False
        self.values[key] = value
        return True

    async def delete(self, key):
        self.values.pop(key, None)


class FakeGraph:
    def __init__(self):
        self.calls = 0

    async def ainvoke(self, graph_input, config, context):
        self.calls += 1
        assert "tool_access_token" not in graph_input
        assert "tool_access_tokens" not in graph_input
        assert context["tool_access_tokens"] == {
            "transaction_agent": "transaction-token",
            "discovery_agent": "discovery-token",
        }
        assert context["request_id"] == "101"
        assert context["result_cache"] is not None
        assert config["configurable"]["checkpoint_ns"] == "customer_service_v3"
        return {
            "final_response": "测试回复",
            "primary_intent": "ORDER_QUERY",
            "active_agent": "transaction_agent",
            "business_refs": [],
            "route_history": [{"agent": "transaction_agent", "intent": "ORDER_QUERY"}],
            "model_call_count": 2,
            "tool_call_count": 1,
            "execution_mode": "SIMPLE",
            "orchestrator": "router",
        }


class FakeResumeGraph(FakeGraph):
    async def aget_state(self, config):
        return SimpleNamespace(values={"request_id": "101"}, next=("transaction_agent",))

    async def ainvoke(self, graph_input, config, context):
        assert graph_input is None
        self.calls += 1
        return {
            "final_response": "恢复后的回复",
            "primary_intent": "ORDER_QUERY",
            "active_agent": "transaction_agent",
            "business_refs": [],
        }


async def test_successful_run_is_cached_by_request_id(monkeypatch):
    monkeypatch.setenv("AGENT_SERVICE_API_KEY", "service-secret")
    get_settings.cache_clear()
    app = FastAPI()
    app.include_router(router)
    redis = FakeRedis()
    graph = FakeGraph()
    app.state.runtime = SimpleNamespace(redis=redis, graph=graph)
    payload = {
        "requestId": "101",
        "threadId": "202",
        "imChatId": 303,
        "userMessageId": 101,
        "message": "你好",
        "longTermSummary": "暂无",
        "recentMessages": [],
        "previousActiveAgent": "general_support_agent",
        "graphVersion": "v2",
        "toolAccessTokens": {
            "transactionAgentToken": "transaction-token",
            "discoveryAgentToken": "discovery-token",
        },
    }
    headers = {"X-Agent-Service-Key": "service-secret", "Idempotency-Key": "101"}
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        first = await client.post("/v1/customer-service/runs", json=payload, headers=headers)
        second = await client.post("/v1/customer-service/runs", json=payload, headers=headers)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["reply"] == "测试回复"
    assert first.json()["graphVersion"] == "v3"
    assert first.json()["activeAgent"] == "transaction_agent"
    assert second.json()["runId"] == first.json()["runId"]
    assert graph.calls == 1
    assert "agent:v3:run:101:result" in redis.values
    get_settings.cache_clear()


async def test_retry_resumes_pending_checkpoint_without_replaying_completed_nodes(monkeypatch):
    monkeypatch.setenv("AGENT_SERVICE_API_KEY", "service-secret")
    get_settings.cache_clear()
    app = FastAPI()
    app.include_router(router)
    graph = FakeResumeGraph()
    app.state.runtime = SimpleNamespace(redis=FakeRedis(), graph=graph)
    payload = {
        "requestId": "101", "threadId": "202", "imChatId": 303, "userMessageId": 101,
        "message": "查订单", "recentMessages": [],
        "toolAccessTokens": {
            "transactionAgentToken": "transaction-token",
            "discoveryAgentToken": "discovery-token",
        },
    }
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post(
            "/v1/customer-service/runs", json=payload,
            headers={"X-Agent-Service-Key": "service-secret", "Idempotency-Key": "101"},
        )
    assert response.status_code == 200
    assert response.json()["reply"] == "恢复后的回复"
    assert graph.calls == 1
    get_settings.cache_clear()
