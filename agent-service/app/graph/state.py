import operator
from typing import Annotated, Any, TypedDict

from langchain_core.messages import AnyMessage
from langgraph.graph.message import add_messages


def merge_business_refs(left: list[dict[str, Any]], right: list[dict[str, Any]]) -> list[dict[str, Any]]:
    merged: list[dict[str, Any]] = []
    seen: set[tuple[Any, Any]] = set()
    for item in [*(left or []), *(right or [])]:
        key = (item.get("bizType") or item.get("biz_type"), item.get("bizId") or item.get("biz_id"))
        if key not in seen:
            seen.add(key)
            merged.append(item)
    return merged


class CustomerServiceState(TypedDict, total=False):
    request_id: str
    thread_id: str
    im_chat_id: int
    user_message_id: int
    message: str
    long_term_summary: str
    recent_messages: list[dict[str, Any]]
    previous_active_agent: str | None
    pending_action: dict[str, Any] | None
    requested_graph_version: str
    graph_version: str
    run_id: str
    trace_id: str
    messages: Annotated[list[AnyMessage], add_messages]

    active_agent: str
    primary_intent: str
    execution_mode: str
    orchestrator: str
    route_decision: dict[str, Any]
    pending_tasks: list[dict[str, Any]]
    current_task: dict[str, Any]
    completed_tasks: list[dict[str, Any]]
    route_history: Annotated[list[dict[str, Any]], operator.add]
    handoff_count: int
    handoff_request: dict[str, Any] | None
    clarification_required: bool
    tasks_truncated: bool
    parallel_task: bool

    plan_id: str | None
    supervisor_plan: dict[str, Any]
    synthesis_goal: str
    remaining_tasks: list[dict[str, Any]]
    current_wave: list[dict[str, Any]]
    wave_count: int
    replan_count: int
    supervisor_iterations: int
    parallel_task_count: int
    supervisor_review: dict[str, Any]

    agent_artifacts: Annotated[list[dict[str, Any]], operator.add]
    task_outcomes: Annotated[list[dict[str, Any]], operator.add]
    branch_usage: Annotated[list[dict[str, Any]], operator.add]
    business_refs: Annotated[list[dict[str, Any]], merge_business_refs]

    model_call_count: int
    tool_call_count: int
    prompt_tokens: int
    completion_tokens: int
    draft_response: str
    final_response: str
    run_status: str
    resolution_type: str
    resolution_decision: dict[str, Any]
    action_proposal: dict[str, Any] | None
    handoff_proposal: dict[str, Any] | None
    action_outcome: dict[str, Any] | None
    interrupt_reason: str | None


class CustomerServiceContext(TypedDict):
    tool_access_tokens: dict[str, str]
    request_id: str
    result_cache: Any
    result_ttl_seconds: int
    parallel_semaphore: Any
