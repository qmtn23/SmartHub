from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import get_settings
from app.graph.builder import ROUTER_PROMPT
from app.models.provider import build_chat_model
from app.routing import KeywordRouter
from app.schemas import SceneRouteDecision


PRE_SALES_INTENTS = {
    "GENERAL", "PLATFORM_KNOWLEDGE", "VOUCHER_QUERY",
    "SHOP_LOOKUP", "SHOP_RECOMMENDATION", "HOT_CONTENT",
}
AFTER_SALES_INTENTS = {
    "AFTER_SALES_POLICY", "ORDER_QUERY", "ORDER_CANCEL", "REFUND_REQUEST",
}


def load_cases(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def expected_scenes(case: dict) -> list[str]:
    intent = case["expectedIntent"]
    if intent == "HUMAN_HANDOFF":
        return ["HUMAN_HANDOFF"]
    message = case["message"]
    result: list[str] = []
    agents = set(case.get("expectedAgents") or [case.get("expectedAgent")])
    if intent in PRE_SALES_INTENTS or "discovery_agent" in agents:
        result.append("PRE_SALES")
    if intent in AFTER_SALES_INTENTS or any(word in message for word in ("订单", "退款", "取消", "售后", "投诉", "纠纷")):
        result.append("AFTER_SALES")
    if not result:
        result.append("PRE_SALES")
    return list(dict.fromkeys(result))


async def evaluate(path: Path) -> dict[str, float | int]:
    settings = get_settings()
    router = build_chat_model(settings, router=True).with_structured_output(SceneRouteDecision)
    keyword_router = KeywordRouter.from_yaml(settings.keyword_router_rules_path)
    cases = load_cases(path)
    correct = human_expected = human_found = after_expected = after_found = 0
    rule_runs = rule_correct = llm_runs = 0
    for case in cases:
        rule_result = keyword_router.route(case["message"])
        decision = rule_result.decision
        if decision is None:
            llm_runs += 1
            decision = await router.ainvoke([
                SystemMessage(content=ROUTER_PROMPT),
                HumanMessage(content=json.dumps({
                    "message": case["message"], "recentMessages": [],
                    "ruleRouter": {"scores": rule_result.scores,
                                   "fallbackReason": rule_result.reason},
                }, ensure_ascii=False)),
            ])
        else:
            rule_runs += 1
        predicted = list(decision.scenes)
        expected = expected_scenes(case)
        correct += int(set(predicted) == set(expected))
        rule_correct += int(rule_result.decision is not None and set(predicted) == set(expected))
        if "HUMAN_HANDOFF" in expected:
            human_expected += 1
            human_found += int(predicted == ["HUMAN_HANDOFF"])
        if "AFTER_SALES" in expected:
            after_expected += 1
            after_found += int("AFTER_SALES" in predicted)
    metrics = {
        "cases": len(cases),
        "scene_accuracy": correct / max(len(cases), 1),
        "human_handoff_recall": human_found / max(human_expected, 1),
        "after_sales_recall": after_found / max(after_expected, 1),
        "rule_coverage": rule_runs / max(len(cases), 1),
        "rule_precision": rule_correct / max(rule_runs, 1),
        "llm_fallback_rate": llm_runs / max(len(cases), 1),
    }
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the v5 three-scene LLM fallback router")
    parser.add_argument("--dataset", type=Path, default=Path("evals/router_cases.jsonl"))
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.dataset))
    if result["scene_accuracy"] < 0.92 or result["human_handoff_recall"] < 0.99 \
            or result["after_sales_recall"] < 0.97 or result["rule_precision"] < 0.98:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
