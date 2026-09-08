from __future__ import annotations

import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

from app.schemas import SceneRouteDecision


@dataclass(frozen=True)
class KeywordRouteResult:
    decision: SceneRouteDecision | None
    requires_llm: bool
    rule_version: str
    scores: dict[str, float]
    matched_rule_ids: list[str]
    reason: str


class KeywordRouter:
    """A conservative first stage for the cascade router.

    Rules only bypass the model when a scene is unambiguous. Ambiguous, negative,
    follow-up, and mixed-scene messages deliberately fall back to the LLM router.
    """

    def __init__(self, config: dict[str, Any]):
        self.config = config
        self.version = str(config.get("version") or "unknown")
        self.accept_threshold = float(config.get("accept_threshold", 0.85))
        self.minimum_margin = float(config.get("minimum_margin", 0.20))
        self.multi_scene_markers = tuple(
            self.normalize(value) for value in config.get("multi_scene_markers", [])
        )
        self.follow_up_phrases = tuple(
            self.normalize(value) for value in config.get("follow_up_phrases", [])
        )
        self.llm_fallback_phrases = tuple(
            self.normalize(value) for value in config.get("llm_fallback_phrases", [])
        )
        self.scenes: dict[str, dict[str, Any]] = dict(config.get("scenes") or {})
        required = {"PRE_SALES", "AFTER_SALES", "HUMAN_HANDOFF"}
        if set(self.scenes) != required:
            raise ValueError(f"router rules must define exactly {sorted(required)}")

    @classmethod
    def from_yaml(cls, path: Path | None = None) -> "KeywordRouter":
        selected = path or Path(__file__).with_name("router_rules.yaml")
        with selected.open("r", encoding="utf-8") as stream:
            config = yaml.safe_load(stream)
        if not isinstance(config, dict):
            raise ValueError("router rules must be a YAML object")
        return cls(config)

    @staticmethod
    def normalize(message: str) -> str:
        value = unicodedata.normalize("NFKC", message or "").lower().strip()
        return "".join(value.split()).rstrip("。！？!?，,；;：:")

    def route(
        self,
        message: str,
        *,
        recent_messages: list[dict[str, Any]] | None = None,
    ) -> KeywordRouteResult:
        normalized = self.normalize(message)
        scores: dict[str, float] = {name: 0.0 for name in self.scenes}
        matched: dict[str, list[tuple[int, str]]] = {name: [] for name in self.scenes}
        blocked: set[str] = set()

        if not normalized:
            return self._fallback(scores, [], "EMPTY_MESSAGE")
        if self._is_follow_up(normalized, recent_messages or []):
            return self._fallback(scores, [], "FOLLOW_UP_REQUIRES_CONTEXT")
        if any(value and value in normalized for value in self.llm_fallback_phrases):
            return self._fallback(scores, [], "SENSITIVE_TEXT_REQUIRES_LLM")

        for scene, definition in self.scenes.items():
            negative = [self.normalize(value) for value in definition.get("negative_phrases", [])]
            if any(value and value in normalized for value in negative):
                blocked.add(scene)
            rules = sorted(
                definition.get("phrases", []),
                key=lambda item: len(self.normalize(str(item.get("text", "")))),
                reverse=True,
            )
            occupied: list[tuple[int, int]] = []
            for rule in rules:
                phrase = self.normalize(str(rule.get("text", "")))
                if not phrase:
                    continue
                start = normalized.find(phrase)
                if start < 0:
                    continue
                end = start + len(phrase)
                if any(start < used_end and end > used_start for used_start, used_end in occupied):
                    continue
                occupied.append((start, end))
                scores[scene] = min(1.0, scores[scene] + float(rule.get("weight", 0)))
                matched[scene].append((start, str(rule.get("id") or phrase)))

        human_ids = [rule_id for _, rule_id in matched["HUMAN_HANDOFF"]]
        if scores["HUMAN_HANDOFF"] >= self.accept_threshold and "HUMAN_HANDOFF" not in blocked:
            return KeywordRouteResult(
                decision=SceneRouteDecision(
                    primary_scene="HUMAN_HANDOFF", scenes=["HUMAN_HANDOFF"], confidence=1.0,
                    clarification_required=False, reason_code="USER_EXPLICIT_HANDOFF",
                ),
                requires_llm=False, rule_version=self.version, scores=scores,
                matched_rule_ids=human_ids, reason="EXPLICIT_HUMAN_HANDOFF",
            )

        ranked = sorted(
            (item for item in ("PRE_SALES", "AFTER_SALES") if item not in blocked),
            key=lambda scene: (
                scores[scene], int(self.scenes[scene].get("priority", 0))
            ),
            reverse=True,
        )
        strong = [scene for scene in ranked if scores[scene] >= self.accept_threshold]
        all_ids = [
            rule_id
            for scene in ("PRE_SALES", "AFTER_SALES", "HUMAN_HANDOFF")
            for _, rule_id in sorted(matched[scene])
        ]
        if len(strong) == 2:
            if any(marker and marker in normalized for marker in self.multi_scene_markers):
                ordered = sorted(strong, key=lambda scene: min(pos for pos, _ in matched[scene]))
                return KeywordRouteResult(
                    decision=SceneRouteDecision(
                        primary_scene=ordered[0], scenes=ordered, confidence=min(scores[item] for item in ordered),
                        clarification_required=False, reason_code="MULTI_SCENE",
                    ),
                    requires_llm=False, rule_version=self.version, scores=scores,
                    matched_rule_ids=all_ids, reason="EXPLICIT_MULTI_SCENE",
                )
            return self._fallback(scores, all_ids, "SCENE_CONFLICT")

        if ranked:
            top = ranked[0]
            second_score = scores[ranked[1]] if len(ranked) > 1 else 0.0
            if scores[top] >= self.accept_threshold and scores[top] - second_score >= self.minimum_margin:
                return KeywordRouteResult(
                    decision=SceneRouteDecision(
                        primary_scene=top, scenes=[top], confidence=scores[top],
                        clarification_required=False, reason_code="SINGLE_SCENE",
                    ),
                    requires_llm=False, rule_version=self.version, scores=scores,
                    matched_rule_ids=[rule_id for _, rule_id in matched[top]], reason="HIGH_CONFIDENCE",
                )
        reason = "NEGATION_OR_CONFLICT" if blocked else "LOW_CONFIDENCE"
        return self._fallback(scores, all_ids, reason)

    def _is_follow_up(self, normalized: str, recent_messages: list[dict[str, Any]]) -> bool:
        if not recent_messages:
            return False
        if normalized in self.follow_up_phrases:
            return True
        return len(normalized) <= 8 and any(normalized.startswith(value) for value in ("那", "这个", "继续", "然后"))

    def _fallback(self, scores: dict[str, float], matched: list[str], reason: str) -> KeywordRouteResult:
        return KeywordRouteResult(
            decision=None, requires_llm=True, rule_version=self.version,
            scores=scores, matched_rule_ids=matched, reason=reason,
        )
