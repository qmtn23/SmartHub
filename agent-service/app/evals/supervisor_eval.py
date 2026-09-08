from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.tools import tool

from app.config import get_settings
from app.graph.builder import CUSTOMER_SERVICE_MASTER_PROMPT
from app.models.provider import build_chat_model
from app.evals.router_eval import AFTER_SALES_INTENTS, PRE_SALES_INTENTS


@tool
def answer_product_faq(task_goal: str) -> str:
    """Run the fixed merchant product FAQ and evidence-backed shopping workflow."""
    return task_goal


@tool
def recommendation_agent(task_goal: str) -> str:
    """Delegate open-ended shop discovery and recommendation."""
    return task_goal


@tool
def after_sales_advisor_agent(task_goal: str) -> str:
    """Delegate order-and-policy after-sales analysis."""
    return task_goal


@tool
def complaint_agent(task_goal: str) -> str:
    """Delegate complaint and dispute analysis."""
    return task_goal


def plain_tool(name: str, description: str):
    @tool(name)
    def value(query: str = "") -> str:
        """Synthetic ordinary tool used only by the Master routing evaluation."""
        return query
    value.description = description
    return value


PRE_TOOLS = [
    answer_product_faq, recommendation_agent,
    plain_tool("search_platform_knowledge", "Retrieve platform and account rules, not product-specific facts."),
    plain_tool("query_vouchers_by_shop_id", "Query purchasable vouchers, stock and validity."),
    plain_tool("search_shops_by_name", "Find a shop by its name."),
    plain_tool("query_shop_by_id", "Query one known shop."),
    plain_tool("query_hot_blogs", "Query the current hot content list."),
]
AFTER_TOOLS = [
    after_sales_advisor_agent, complaint_agent,
    plain_tool("query_current_user_orders", "Query the authenticated user's recent orders."),
    plain_tool("query_shop_by_id", "Query one known shop."),
]


def expected_tool(case: dict) -> str | None:
    intent, message = case["expectedIntent"], case["message"]
    if intent in {"GENERAL", "PLATFORM_KNOWLEDGE"}:
        return "search_platform_knowledge"
    if intent == "SHOP_RECOMMENDATION":
        return "recommendation_agent"
    if intent == "SHOP_LOOKUP":
        return "search_shops_by_name"
    if intent == "VOUCHER_QUERY":
        return "query_vouchers_by_shop_id"
    if intent == "HOT_CONTENT":
        return "query_hot_blogs"
    if intent == "ORDER_QUERY":
        return "query_current_user_orders"
    if intent in AFTER_SALES_INTENTS:
        return "complaint_agent" if any(word in message for word in ("投诉", "纠纷", "商家拒绝")) \
            else "after_sales_advisor_agent"
    return None


def load_cases(path: Path) -> list[dict]:
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    return [case for case in cases if case.get("kind") == "single_intent" and expected_tool(case)]


async def evaluate(path: Path) -> dict[str, float | int]:
    model = build_chat_model(get_settings(), router=True)
    cases = load_cases(path)
    correct = legal = 0
    for case in cases:
        intent = case["expectedIntent"]
        tools = list({item.name: item for item in [*PRE_TOOLS, *AFTER_TOOLS]}.values())
        result = await model.bind_tools(tools).ainvoke([
            SystemMessage(content=CUSTOMER_SERVICE_MASTER_PROMPT), HumanMessage(content=case["message"]),
        ])
        calls = result.tool_calls or []
        legal += int(bool(calls) and all(call["name"] in {item.name for item in tools} for call in calls))
        correct += int(bool(calls) and calls[0]["name"] == expected_tool(case))
    metrics = {
        "cases": len(cases),
        "first_tool_accuracy": correct / max(len(cases), 1),
        "legal_tool_call_rate": legal / max(len(cases), 1),
    }
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    return metrics


def main() -> None:
    parser = argparse.ArgumentParser(description="Evaluate scene Master tool selection")
    parser.add_argument("--dataset", type=Path, default=Path("evals/router_cases.jsonl"))
    args = parser.parse_args()
    result = asyncio.run(evaluate(args.dataset))
    if result["first_tool_accuracy"] < 0.85 or result["legal_tool_call_rate"] < 1.0:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
