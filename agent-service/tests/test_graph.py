from langchain_core.language_models.fake_chat_models import GenericFakeChatModel
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import MemorySaver

from app.config import Settings
from app.graph.builder import build_customer_service_graph


class FakeRetriever:
    async def asearch(self, query: str):
        return [{"content": "平台规则内容", "source": "platform-faq.md", "score": 0.9}]


async def test_single_agent_graph_hydrates_retrieves_and_guards_response():
    model = GenericFakeChatModel(messages=iter([AIMessage(content="根据平台规则，可以通过手机号验证码登录。")]))
    graph = build_customer_service_graph(
        model=model,
        tools=[],
        retriever=FakeRetriever(),
        checkpointer=MemorySaver(),
        settings=Settings(_env_file=None),
    )
    result = await graph.ainvoke(
        {
            "request_id": "11",
            "thread_id": "22",
            "im_chat_id": 33,
            "user_message_id": 11,
            "message": "怎么登录？",
            "long_term_summary": "暂无",
            "recent_messages": [{"message_id": 11, "role": "user", "content": "怎么登录？"}],
            "run_id": "run",
            "trace_id": "trace",
        },
        config={"configurable": {"thread_id": "22"}, "recursion_limit": 6},
        context={"tool_access_token": "token"},
    )
    assert result["final_response"] == "根据平台规则，可以通过手机号验证码登录。"
    assert result["knowledge_context"][0]["source"] == "platform-faq.md"
    assert len(result["messages"]) == 1
