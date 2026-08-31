from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


AgentName = Literal["general_support_agent", "transaction_agent", "discovery_agent"]
Intent = Literal[
    "GENERAL", "PLATFORM_KNOWLEDGE", "AFTER_SALES_POLICY", "ORDER_QUERY",
    "VOUCHER_QUERY", "SHOP_LOOKUP", "SHOP_RECOMMENDATION", "HOT_CONTENT", "EXTERNAL_INFO",
]
ExecutionMode = Literal["SIMPLE", "COMPLEX"]


class RecentMessage(ApiModel):
    message_id: int
    role: Literal["user", "assistant", "system"]
    content: str


class ToolAccessTokens(ApiModel):
    transaction_agent_token: str = Field(min_length=1)
    discovery_agent_token: str = Field(min_length=1)


class AgentRunRequest(ApiModel):
    request_id: str
    thread_id: str
    im_chat_id: int
    user_message_id: int
    message: str = Field(min_length=1, max_length=4000)
    long_term_summary: str = "暂无长期会话记忆"
    recent_messages: list[RecentMessage] = Field(default_factory=list, max_length=20)
    previous_active_agent: AgentName | None = None
    # V2 is accepted only for direct-replacement wire compatibility; the service always runs graph v3.
    graph_version: Literal["v2", "v3"] = "v3"
    tool_access_tokens: ToolAccessTokens | None = None
    tool_access_token: str | None = Field(default=None, min_length=1)

    @model_validator(mode="after")
    def require_tool_tokens(self) -> "AgentRunRequest":
        if self.tool_access_tokens is None and not self.tool_access_token:
            raise ValueError("toolAccessTokens is required")
        return self


class RouteTask(ApiModel):
    target_agent: AgentName
    intent: Intent
    user_goal: str = Field(min_length=1, max_length=500)


class RouteDecision(ApiModel):
    primary_intent: Intent
    tasks: list[RouteTask] = Field(default_factory=list, max_length=6)
    execution_mode: ExecutionMode = "SIMPLE"
    complexity_reason: Literal[
        "SINGLE_DOMAIN", "PARALLEL_DOMAINS", "CROSS_DOMAIN_DEPENDENCY",
        "THREE_INTENTS", "AMBIGUOUS", "FOLLOW_UP",
    ] = "SINGLE_DOMAIN"
    confidence: float = Field(ge=0, le=1)
    clarification_required: bool = False
    reason_code: Literal["SINGLE_INTENT", "MULTI_INTENT", "FOLLOW_UP", "AMBIGUOUS", "OUT_OF_SCOPE"]


class HandoffRequest(ApiModel):
    target_agent: AgentName
    target_intent: Intent
    context_summary: str = Field(min_length=1, max_length=500)
    reason_code: Literal[
        "NEEDS_TRANSACTION_DATA", "NEEDS_DISCOVERY_DATA", "NEEDS_POLICY_SUMMARY", "USER_FOLLOW_UP"
    ]


class SupervisorTask(ApiModel):
    task_id: str = Field(min_length=1, max_length=64)
    target_agent: AgentName
    intent: Intent
    user_goal: str = Field(min_length=1, max_length=500)
    depends_on: list[str] = Field(default_factory=list, max_length=3)


class SupervisorPlan(ApiModel):
    plan_id: str = Field(min_length=1, max_length=64)
    tasks: list[SupervisorTask] = Field(min_length=1, max_length=6)
    synthesis_goal: str = Field(min_length=1, max_length=500)
    reason_code: Literal["PARALLEL", "DEPENDENCY", "MIXED", "REPLAN"]


class SupervisorReview(ApiModel):
    action: Literal["COMPLETE", "REPLAN"]
    new_tasks: list[SupervisorTask] = Field(default_factory=list, max_length=3)
    reason_code: Literal[
        "COVERAGE_COMPLETE", "MISSING_DOMAIN", "CONFLICTING_RESULTS", "DEPENDENCY_GAP", "NO_BUDGET"
    ]


class TaskOutcome(ApiModel):
    task_id: str
    target_agent: AgentName
    intent: Intent
    status: Literal["SUCCEEDED", "FAILED", "SKIPPED_DEPENDENCY_FAILED"]
    result: str = ""
    error_code: str | None = None
    business_refs: list[dict[str, Any]] = Field(default_factory=list)
    model_call_count: int = 0
    tool_call_count: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0


class BusinessReference(ApiModel):
    biz_type: str
    biz_id: int


class Usage(ApiModel):
    prompt_tokens: int = 0
    completion_tokens: int = 0


class AgentRunResponse(ApiModel):
    run_id: str
    reply: str
    intent: Intent = "GENERAL"
    active_agent: AgentName = "general_support_agent"
    business_refs: list[BusinessReference] = Field(default_factory=list)
    structured_content: Any | None = None
    trace_id: str
    graph_version: Literal["v3"] = "v3"
    route_history: list[dict[str, Any]] = Field(default_factory=list)
    handoff_count: int = 0
    model_call_count: int = 0
    tool_call_count: int = 0
    usage: Usage = Field(default_factory=Usage)
    execution_mode: ExecutionMode = "SIMPLE"
    plan_id: str | None = None
    supervisor_iterations: int = 0
    parallel_task_count: int = 0
    task_outcomes: list[TaskOutcome] = Field(default_factory=list)
    orchestrator: Literal["router", "supervisor"] = "router"


class ErrorResponse(ApiModel):
    code: str
    message: str
    retryable: bool
