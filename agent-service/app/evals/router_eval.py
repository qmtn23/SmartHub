from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import get_settings
from app.graph.builder import ROUTER_PROMPT, _normalize_tasks
from app.models.provider import build_chat_model
from app.schemas import RouteDecision


def load_cases(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


async def evaluate(path: Path) -> dict[str, float | int]:
    settings = get_settings()
    router = build_chat_model(settings, router=True).with_structured_output(RouteDecision)
    cases = load_cases(path)
    correct = transaction_expected = transaction_found = non_transaction = false_transaction = 0
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
            predicted_agents = [item["target_agent"] for item in _normalize_tasks(decision, case["message"])[0]]
        expected_agents = case.get("expectedAgents") or [case["expectedAgent"]]
        if decision.primary_intent == case["expectedIntent"] and predicted_agents == expected_agents:
            correct += 1
        if "transaction_agent" in expected_agents:
            transaction_expected += 1
            transaction_found += int("transaction_agent" in predicted_agents)
        else:
            non_transaction += 1
            false_transaction += int("transaction_agent" in predicted_agents)
    metrics = {
        "cases": len(cases),
        "accuracy": correct / len(cases),
        "transaction_recall": transaction_found / max(transaction_expected, 1),
        "non_transaction_false_tool_rate": false_transaction / max(non_transaction, 1),
    }
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate the v2 structured router against annotated cases")
    parser.add_argument("--dataset", type=Path, default=Path("evals/router_cases.jsonl"))
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.dataset))
    if result["accuracy"] < 0.92 or result["transaction_recall"] < 0.97 \
            or result["non_transaction_false_tool_rate"] >= 0.02:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
