import json

from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from langchain_core.tools import tool

from app.config import Settings
from app.faq.workflow import build_faq_workflow


def knowledge_tool(resolver):
    calls = []

    @tool
    async def search_platform_knowledge(query: str, category: str | None = None) -> str:
        """Search the internal SmartHub knowledge base."""
        calls.append((query, category))
        return json.dumps(resolver(query, category), ensure_ascii=False)

    return search_platform_knowledge, calls


def match(category="platform-faq"):
    return [{
        "chunk_id": "chunk-1", "document_id": "doc-1", "source": "platform-faq.md",
        "category": category, "version": "2", "content": "登录验证码有效期为2分钟。", "score": 0.93,
    }]


async def test_faq_workflow_retrieves_before_generating_grounded_answer():
    search, calls = knowledge_tool(lambda _query, category: match(category or "platform-faq"))
    model = GenericFakeChatModel(messages=iter([AIMessage(content="登录验证码有效期为2分钟。")]))
    workflow = build_faq_workflow(model=model, knowledge_tool=search, settings=Settings(_env_file=None))

    state = await workflow.ainvoke({"question": "登录验证码多久失效？", "recent_messages": []})

    result = state["result"]
    assert calls == [("登录验证码多久失效?", "platform-faq")]
    assert result["status"] == "ANSWERED"
    assert result["grounded"] is True
    assert result["citations"][0]["chunkId"] == "chunk-1"
    assert result["modelCallCount"] == 1


async def test_faq_workflow_retries_once_without_category_filter():
    search, calls = knowledge_tool(lambda _query, category: [] if category else match())
    model = GenericFakeChatModel(messages=iter([AIMessage(content="登录验证码有效期为2分钟。")]))
    settings = Settings(_env_file=None, faq_max_retrieval_attempts=2)
    workflow = build_faq_workflow(model=model, knowledge_tool=search, settings=settings)

    result = (await workflow.ainvoke({"question": "登录验证码多久失效？"}))["result"]

    assert [category for _, category in calls] == ["platform-faq", None]
    assert result["retrievalAttempts"] == 2
    assert result["status"] == "ANSWERED"


async def test_faq_workflow_does_not_generate_without_evidence():
    search, calls = knowledge_tool(lambda _query, _category: [])
    model = GenericFakeChatModel(messages=iter([]))
    workflow = build_faq_workflow(model=model, knowledge_tool=search, settings=Settings(_env_file=None))

    result = (await workflow.ainvoke({"question": "登录验证码有什么隐藏规则？"}))["result"]

    assert len(calls) == 2
    assert result["status"] == "NO_EVIDENCE"
    assert result["grounded"] is False
    assert result["modelCallCount"] == 0


async def test_faq_workflow_rejects_external_search_without_tools_or_model():
    search, calls = knowledge_tool(lambda _query, _category: match())
    model = GenericFakeChatModel(messages=iter([]))
    workflow = build_faq_workflow(model=model, knowledge_tool=search, settings=Settings(_env_file=None))

    result = (await workflow.ainvoke({"question": "帮我联网查今天美元汇率"}))["result"]

    assert calls == []
    assert result["status"] == "OUT_OF_SCOPE"
    assert result["grounded"] is True
    assert result["citations"] == []


async def test_faq_workflow_requests_clarification_for_underspecified_question():
    search, calls = knowledge_tool(lambda _query, _category: match())
    model = GenericFakeChatModel(messages=iter([]))
    workflow = build_faq_workflow(model=model, knowledge_tool=search, settings=Settings(_env_file=None))

    result = (await workflow.ainvoke({"question": "怎么弄"}))["result"]

    assert calls == []
    assert result["status"] == "NEEDS_CLARIFICATION"
    assert result["missingInformation"] == ["具体功能或问题描述"]


async def test_faq_workflow_distinguishes_retrieval_failure_from_no_evidence():
    def unavailable(_query, _category):
        raise RuntimeError("milvus unavailable")

    search, calls = knowledge_tool(unavailable)
    model = GenericFakeChatModel(messages=iter([]))
    workflow = build_faq_workflow(model=model, knowledge_tool=search, settings=Settings(_env_file=None))

    result = (await workflow.ainvoke({"question": "登录验证码规则"}))["result"]

    assert len(calls) == 2
    assert result["status"] == "FAILED"
    assert result["errorCode"] == "KNOWLEDGE_SERVICE_UNAVAILABLE"
