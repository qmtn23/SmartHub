import asyncio
import json
from unittest.mock import AsyncMock, Mock

import httpx
import pytest
from fastapi import FastAPI
from pydantic import ValidationError

from app.api import router
from app.config import get_settings
from app.context_memory import recent_context, relevant_memory
from app.task_memory import TaskMemoryExtractor, TaskMemoryRequest, TaskMemoryDiff


def request():
    return TaskMemoryRequest(memory={"schemaVersion": 1, "tasks": [{"taskId": "existing"}]}, messages=[
        {"messageId": 1, "senderType": "USER", "content": "预算300元", "createTime": "2026-09-08T10:00:00"}
    ])


def extractor(diff):
    model = Mock()
    model.with_structured_output.return_value.ainvoke = AsyncMock(return_value=diff)
    return TaskMemoryExtractor(model)


async def test_extract_accepts_create_and_explicit_clear():
    diff = {"operations": [
        {"sourceMessageIds": [1], "patch": {"intent": "聚餐选店", "constraints": ["预算300元"]}},
        {"taskId": "existing", "sourceMessageIds": [1], "patch": {"openQuestions": []}},
    ]}
    result = await extractor(diff).extract(request())
    assert result.operations[1].patch.openQuestions == []
    assert result.operations[1].patch.intent is None


@pytest.mark.parametrize("operation", [
    {"taskId": "foreign-task", "sourceMessageIds": [1], "patch": {"status": "RESOLVED"}},
    {"taskId": "existing", "sourceMessageIds": [999], "patch": {"status": "RESOLVED"}},
    {"sourceMessageIds": [1], "patch": {"facts": ["未知意图"]}},
    {"sourceMessageIds": [1], "patch": {"intent": "test", "profile": "用户画像"}},
    {"sourceMessageIds": [1], "patch": {"facts": ["x" * 301]}},
])
async def test_invalid_diff_is_rejected(operation):
    with pytest.raises(ValueError):
        await extractor({"operations": [operation]}).extract(request())


async def test_no_change_is_valid_and_does_not_invent_tasks():
    assert not (await extractor({"operations": []}).extract(request())).operations


def test_context_keeps_more_than_six_and_excludes_current_message():
    messages = [{"messageId": n, "role": "user", "content": f"message {n}"} for n in range(21)]
    selected = recent_context(messages, current_message_id=20)
    assert len(selected) == 19
    assert selected[-1]["messageId"] == 19
    assert len(recent_context(messages, char_budget=150)) < 3


def test_context_never_cuts_a_message_or_json():
    selected = recent_context([{"content": "old"}, {"content": "中" * 2000}], char_budget=50)
    assert selected == []


def test_domain_memory_is_shared_but_filtered():
    summary = json.dumps({"schemaVersion": 1, "tasks": [
        {"taskId": "a", "intent": "餐厅推荐", "domains": ["RECOMMENDATION"], "status": "ACTIVE"},
        {"taskId": "b", "intent": "订单123退款投诉", "domains": ["AFTER_SALES", "COMPLAINT"],
         "status": "ACTIVE", "fieldEvidence": {"intent": {"sources": []}}},
    ]}, ensure_ascii=False)
    for domain in ("AFTER_SALES", "COMPLAINT"):
        result = json.loads(relevant_memory(summary, "订单123", domain=domain))
        assert [t["taskId"] for t in result["tasks"]] == ["b"]
        assert result["historicalOnly"]
    assert relevant_memory("原有摘要", "test") == "原有摘要"
    assert json.loads(relevant_memory('{"schemaVersion":1,"tasks":[]}', "test"))["tasks"] == []


async def test_memory_endpoint_requires_key_and_returns_validated_diff(monkeypatch):
    monkeypatch.setenv("AGENT_SERVICE_API_KEY", "memory-test-key")
    get_settings.cache_clear()
    app = FastAPI()
    app.include_router(router)
    app.state.task_memory = extractor({"operations": []})
    app.state.memory_semaphore = asyncio.Semaphore(2)
    try:
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            payload = request().model_dump()
            unauthorized = await client.post("/v1/customer-service/memory/task-diff", json=payload)
            assert unauthorized.status_code == 401
            ok = await client.post("/v1/customer-service/memory/task-diff", json=payload,
                                   headers={"X-Agent-Service-Key": "memory-test-key"})
            assert ok.status_code == 200
            assert ok.json() == {"operations": []}
            app.state.task_memory.extract = AsyncMock(side_effect=ValueError("bad output"))
            failed = await client.post("/v1/customer-service/memory/task-diff", json=payload,
                                       headers={"X-Agent-Service-Key": "memory-test-key"})
            assert failed.status_code == 503
    finally:
        get_settings.cache_clear()
