import json
import pytest
from langchain_core.messages import AIMessage
from langgraph.checkpoint.memory import MemorySaver
from app.api import _to_response
from app.config import Settings
from app.graph.builder import build_customer_service_graph, AllAgentTasksFailedError
from app.tools.registry import build_agent_tools, RunToolContext, set_run_tool_context, reset_run_tool_context
from test_graph import ToolFakeChatModel, FakeStructuredModel, FakeRetriever, graph_input, invocation_context, scene_decision
from test_product_faq import ENTRY, CONTEXT
from test_tools import FakeCache


class Client:
    def __init__(self): self.calls = 0
    async def call(self, path, token, payload=None):
        assert path == "/internal/agent-tools/faq/validate"
        assert token == "faq-only"
        self.calls += 1
        return {"success": True, "context": CONTEXT, "entries": [ENTRY]}


class Index:
    async def search(self, query, context):
        assert context == CONTEXT
        return [{"faqId": ENTRY["faqId"], "revision": 1}]


async def test_graph_caches_workflow_and_never_publishes_master_fabrication():
    client = Client()
    toolset = build_agent_tools(Settings(_env_file=None), client, FakeRetriever(), Index())
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{"name": "answer_product_faq", "args": {"task_goal": "需要预约吗？"}, "id": f"faq-{i}", "type": "tool_call"}])
        if j == 0 else AIMessage(content="不用预约，保证有座。") for i in range(2) for j in range(2)
    ]))
    structured = FakeStructuredModel({"SceneRouteDecision": [scene_decision(), scene_decision()]})
    graph = build_customer_service_graph(model=model, router_model=structured, supervisor_model=structured,
        tools_by_agent=toolset, checkpointer=MemorySaver(), settings=Settings(_env_file=None))
    context = invocation_context() | {"result_cache": FakeCache(), "tool_access_tokens": {"faq_knowledge": "faq-only"}}
    request = graph_input("需要预约吗？") | {"consultation_context": CONTEXT}
    results = []
    for thread in ["first", "retry"]:
        results.append(await graph.ainvoke(request, config={"configurable": {"thread_id": thread}}, context=context))
    assert client.calls == 2  # recall validation + final validation; retry reused specialist result
    for result in results:
        assert "不用预约" not in result["final_response"]
        assert result["final_response"].startswith(ENTRY["answer"])
        response = _to_response(result, "run", "trace")
        assert response.structured_content["productFaq"]["faqMatches"][0]["faqId"] == ENTRY["faqId"]
        assert response.run_status == "COMPLETED" and response.handoff_proposal is None
        assert "faq-only" not in json.dumps(result, ensure_ascii=False, default=str)


@pytest.mark.parametrize("agent,tokens", [
    ("pre_sales_master_agent", {"faq_knowledge":"faq-only"}),
    ("recommendation_agent", {"faq_knowledge":"faq-only"}),
    ("answer_product_faq", {"voucher_agent":"wrong-scope"})])
async def test_workflow_tool_does_not_accept_other_agents_or_business_tokens(agent, tokens):
    tools = build_agent_tools(Settings(_env_file=None), Client(), FakeRetriever(), Index())
    tool = tools["_product_faq_workflow"][0]
    marker = set_run_tool_context(RunToolContext(active_agent=agent, max_calls=4, tokens=tokens, consultation_context=CONTEXT))
    try:
        with pytest.raises(PermissionError): await tool.ainvoke({"query":"预约"})
    finally:
        reset_run_tool_context(marker)


async def test_index_missing_fails_run_without_automatic_handoff():
    model = ToolFakeChatModel(messages=iter([
        AIMessage(content="", tool_calls=[{"name":"answer_product_faq","args":{"task_goal":"预约"},"id":"faq-1","type":"tool_call"}]),
        AIMessage(content="查询失败。")]))
    structured = FakeStructuredModel({"SceneRouteDecision":[scene_decision()]})
    graph = build_customer_service_graph(model=model, router_model=structured, supervisor_model=structured,
        tools_by_agent=build_agent_tools(Settings(_env_file=None), Client(), FakeRetriever()),
        checkpointer=MemorySaver(), settings=Settings(_env_file=None))
    with pytest.raises(AllAgentTasksFailedError):
        await graph.ainvoke(graph_input("需要预约吗？") | {"consultation_context":CONTEXT},
            config={"configurable":{"thread_id":"failed"}},
            context=invocation_context() | {"tool_access_tokens":{"faq_knowledge":"faq-only"}})
