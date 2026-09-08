from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class _FaqModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid", alias_generator=_to_camel, populate_by_name=True
    )


FaqStatus = Literal[
    "ANSWERED",
    "PARTIAL",
    "NEEDS_CLARIFICATION",
    "NO_EVIDENCE",
    "CONFLICT",
    "OUT_OF_SCOPE",
    "FAILED",
]
FaqQuestionType = Literal[
    "USAGE_RULE",
    "RESERVATION",
    "SUITABILITY",
    "CONTENTS",
    "RESTRICTIONS",
    "PRICE",
    "STOCK",
    "SALES_VALIDITY",
    "SHOP_INFO",
    "PLATFORM_POLICY",
    "MIXED",
    "OTHER",
]


class FaqAgentDecision(_FaqModel):
    """The model's bounded decision; source payloads are attached by trusted code."""

    status: FaqStatus
    question_type: FaqQuestionType
    answer_points: list[str] = Field(default_factory=list, max_length=8)
    evidence_refs: list[str] = Field(default_factory=list, max_length=12)
    missing_information: list[str] = Field(default_factory=list, max_length=8)
    clarification_question: str | None = Field(default=None, max_length=300)
    conflicts: list[str] = Field(default_factory=list, max_length=8)

    @model_validator(mode="after")
    def validate_terminal_state(self) -> "FaqAgentDecision":
        self.evidence_refs = list(dict.fromkeys(self.evidence_refs))
        self.answer_points = [value.strip() for value in self.answer_points if value.strip()]
        self.missing_information = [
            value.strip() for value in self.missing_information if value.strip()
        ]
        self.conflicts = [value.strip() for value in self.conflicts if value.strip()]
        if self.status in {"ANSWERED", "PARTIAL"} and not self.evidence_refs:
            raise ValueError("answered FAQ decisions require evidenceRefs")
        if self.status == "NEEDS_CLARIFICATION" and not self.clarification_question:
            raise ValueError("clarificationQuestion is required")
        return self


class FaqEvidence(_FaqModel):
    source_type: Literal["MERCHANT_FAQ", "VOUCHER", "SHOP", "PLATFORM_KNOWLEDGE"]
    source_ref: str
    content: Any
    revision: int | str | None = None
    topic: str | None = None
    scope: str | None = None
    live_data: bool = False
    score: float | None = Field(default=None, ge=0, le=1)
    retrieval_channels: list[str] = Field(default_factory=list)


class FaqAgentResult(_FaqModel):
    status: FaqStatus
    question_type: FaqQuestionType
    answer: str
    answer_points: list[str] = Field(default_factory=list)
    canonical_answer_points: list[str] = Field(default_factory=list)
    evidence: list[FaqEvidence] = Field(default_factory=list)
    faq_matches: list[dict[str, Any]] = Field(default_factory=list)
    shopping_advice: list[dict[str, Any]] = Field(default_factory=list)
    missing_information: list[str] = Field(default_factory=list)
    clarification_question: str | None = None
    conflicts: list[str] = Field(default_factory=list)
    rejected_evidence_refs: list[str] = Field(default_factory=list)
    error_code: str | None = None
    context: dict[str, Any] = Field(default_factory=dict)
    workflow_version: str
    rule_version: str
    query_variants: list[str] = Field(default_factory=list)
    retrieval_attempts: int = 0
    model_call_count: int = 0
    prompt_tokens: int = 0
    completion_tokens: int = 0
