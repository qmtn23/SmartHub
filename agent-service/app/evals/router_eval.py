from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import get_settings
from app.graph.builder import ROUTER_PROMPT, _normalize_route_tasks
from app.models.provider import build_chat_model
from app.schemas import RouteDecision


def load_cases(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


async def evaluate(path: Path) -> dict[str, float | int]:
    settings = get_settings()
    router = build_chat_model(settings, router=True).with_structured_output(RouteDecision)
    cases = load_cases(path)
    correct = transaction_expected = transaction_found = non_transaction = false_transaction = 0
    complexity_true_positive = complexity_false_positive = complexity_false_negative = 0
    for case in cases:
        decision = await router.ainvoke([
            SystemMessage(content=ROUTER_PROMPT),
            HumanMessage(content=json.dumps({
                "message": case["message"],
                "previousActiveAgent": case.get("previousActiveAgent"),
                "recentMessages": [],
            }, ensure_ascii=False)),
        ])
        if decision.confidence < settings.router_confidence_threshold or decision.clarification_required:
            predicted_agents = ["general_support_agent"]
        else:
            predicted_agents = [item["target_agent"] for item in _normalize_route_tasks(decision, case["message"])[0]]
        expected_agents = case.get("expectedAgents") or [case["expectedAgent"]]
        predicted_mode = "COMPLEX" if len(predicted_agents) > 1 else "SIMPLE"
        expected_mode = case.get("expectedExecutionMode", "COMPLEX" if len(expected_agents) > 1 else "SIMPLE")
        if decision.primary_intent == case["expectedIntent"] and predicted_agents == expected_agents:
            correct += 1
        complexity_true_positive += int(predicted_mode == "COMPLEX" and expected_mode == "COMPLEX")
        complexity_false_positive += int(predicted_mode == "COMPLEX" and expected_mode != "COMPLEX")
        complexity_false_negative += int(predicted_mode != "COMPLEX" and expected_mode == "COMPLEX")
        if "transaction_agent" in expected_agents:
            transaction_expected += 1
            transaction_found += int("transaction_agent" in predicted_agents)
        else:
            non_transaction += 1
            false_transaction += int("transaction_agent" in predicted_agents)
    precision = complexity_true_positive / max(complexity_true_positive + complexity_false_positive, 1)
    recall = complexity_true_positive / max(complexity_true_positive + complexity_false_negative, 1)
    metrics = {
        "cases": len(cases),
        "accuracy": correct / len(cases),
        "transaction_recall": transaction_found / max(transaction_expected, 1),
        "non_transaction_false_tool_rate": false_transaction / max(non_transaction, 1),
        "complexity_f1": 2 * precision * recall / max(precision + recall, 1e-9),
    }
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the v3 structured router against annotated cases")
    parser.add_argument("--dataset", type=Path, default=Path("evals/router_cases.jsonl"))
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.dataset))
    if result["accuracy"] < 0.92 or result["complexity_f1"] < 0.90 or result["transaction_recall"] < 0.97 \
            or result["non_transaction_false_tool_rate"] >= 0.02:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
