from typing import Annotated, Any, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


class CustomerServiceState(TypedDict, total=False):
    request_id: str
    thread_id: str
    im_chat_id: int
    user_message_id: int
    message: str
    long_term_summary: str
    recent_messages: list[dict[str, Any]]
    previous_active_agent: str | None
    graph_version: str
    run_id: str
    trace_id: str
    messages: Annotated[list[AnyMessage], add_messages]
    active_agent: str
    primary_intent: str
    route_decision: dict[str, Any]
    pending_tasks: list[dict[str, Any]]
    current_task: dict[str, Any]
    completed_tasks: list[dict[str, Any]]
    route_history: list[dict[str, Any]]
    handoff_count: int
    handoff_request: dict[str, Any] | None
    agent_artifacts: list[dict[str, Any]]
    business_refs: list[dict[str, Any]]
    model_call_count: int
    tool_call_count: int
    prompt_tokens: int
    completion_tokens: int
    clarification_required: bool
    tasks_truncated: bool
    draft_response: str
    final_response: str


class CustomerServiceContext(TypedDict):
    tool_access_tokens: dict[str, str]
    request_id: str
    result_cache: Any
    result_ttl_seconds: int
