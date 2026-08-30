from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


def to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class ApiModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class RecentMessage(ApiModel):
    message_id: int
    role: Literal["user", "assistant", "system"]
    content: str


class AgentRunRequest(ApiModel):
    request_id: str
    thread_id: str
    im_chat_id: int
    user_message_id: int
    message: str = Field(min_length=1, max_length=4000)
    long_term_summary: str = "暂无长期会话记忆"
    recent_messages: list[RecentMessage] = Field(default_factory=list, max_length=20)
    tool_access_token: str = Field(min_length=1)


class BusinessReference(ApiModel):
    biz_type: str
    biz_id: int


class AgentRunResponse(ApiModel):
    run_id: str
    reply: str
    intent: str = "GENERAL"
    active_agent: str = "customer_assistant"
    business_refs: list[BusinessReference] = Field(default_factory=list)
    structured_content: Any | None = None
    trace_id: str


class ErrorResponse(ApiModel):
    code: str
    message: str
    retryable: bool
