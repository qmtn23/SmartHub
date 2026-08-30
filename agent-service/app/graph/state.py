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
    run_id: str
    trace_id: str

    messages: Annotated[list[AnyMessage], add_messages]
    knowledge_context: list[dict[str, Any]]
    business_refs: list[dict[str, Any]]
    draft_response: str
    final_response: str


class CustomerServiceContext(TypedDict):
    tool_access_token: str
