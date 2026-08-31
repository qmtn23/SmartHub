from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage

from app.config import get_settings
from app.graph.builder import SUPERVISOR_PROMPT, _normalize_supervisor_tasks
from app.models.provider import build_chat_model
from app.schemas import SupervisorPlan


def load_cases(path: Path) -> list[dict]:
    return [
        json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and json.loads(line).get("expectedSupervisorTargets")
    ]


def router_tasks(case: dict) -> list[dict]:
    intent_for_agent = {
        "transaction_agent": case["expectedIntent"]
        if case["expectedIntent"] in {"ORDER_QUERY", "VOUCHER_QUERY"} else "ORDER_QUERY",
        "discovery_agent": case["expectedIntent"]
        if case["expectedIntent"] in {"SHOP_LOOKUP", "SHOP_RECOMMENDATION", "HOT_CONTENT"}
        else "SHOP_RECOMMENDATION",
        "general_support_agent": case["expectedIntent"]
        if case["expectedIntent"] in {"GENERAL", "PLATFORM_KNOWLEDGE", "AFTER_SALES_POLICY", "EXTERNAL_INFO"}
        else "AFTER_SALES_POLICY",
    }
    return [
        {"target_agent": agent, "intent": intent_for_agent[agent], "user_goal": case["message"]}
        for agent in case["expectedSupervisorTargets"]
    ]


async def evaluate(path: Path) -> dict[str, float | int]:
    settings = get_settings()
    planner = build_chat_model(settings, router=True).with_structured_output(SupervisorPlan)
    cases = load_cases(path)
    correct = legal = 0
    for case in cases:
        tasks = router_tasks(case)
        result = await planner.ainvoke([
            SystemMessage(content=SUPERVISOR_PROMPT),
            HumanMessage(content=json.dumps({
                "requestId": case["id"],
                "userMessage": case["message"],
                "routerTasks": tasks,
                "primaryIntent": case["expectedIntent"],
            }, ensure_ascii=False)),
        ])
        try:
            normalized = _normalize_supervisor_tasks(result.tasks, max_tasks=3)
            legal += 1
        except ValueError:
            normalized = []
        predicted = [item["target_agent"] for item in normalized]
        correct += int(predicted == case["expectedSupervisorTargets"])
    metrics = {
        "cases": len(cases),
        "target_accuracy": correct / max(len(cases), 1),
        "legal_plan_rate": legal / max(len(cases), 1),
    }
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate v3 Composite Supervisor plans")
    parser.add_argument("--dataset", type=Path, default=Path("evals/router_cases.jsonl"))
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.dataset))
    if result["target_accuracy"] < 0.90 or result["legal_plan_rate"] < 1.0:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
