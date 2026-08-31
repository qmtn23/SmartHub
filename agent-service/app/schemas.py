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
    graph_version: Literal["v2"] = "v2"
    tool_access_tokens: ToolAccessTokens | None = None
    # Rolling-deployment compatibility only. This value is never persisted.
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
    tasks: list[RouteTask] = Field(default_factory=list, max_length=4)
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
    graph_version: Literal["v2"] = "v2"
    route_history: list[dict[str, Any]] = Field(default_factory=list)
    handoff_count: int = 0
    model_call_count: int = 0
    tool_call_count: int = 0
    usage: Usage = Field(default_factory=Usage)


class ErrorResponse(ApiModel):
    code: str
    message: str
    retryable: bool
