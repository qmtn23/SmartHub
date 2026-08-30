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
        assert context["tool_access_token"] == "token"
        return {"final_response": "测试回复", "business_refs": []}


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
        "toolAccessToken": "token",
    }
    headers = {"X-Agent-Service-Key": "service-secret", "Idempotency-Key": "101"}
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        first = await client.post("/v1/customer-service/runs", json=payload, headers=headers)
        second = await client.post("/v1/customer-service/runs", json=payload, headers=headers)
    assert first.status_code == 200
    assert second.status_code == 200
    assert first.json()["reply"] == "测试回复"
    assert second.json()["runId"] == first.json()["runId"]
    assert graph.calls == 1
    get_settings.cache_clear()
