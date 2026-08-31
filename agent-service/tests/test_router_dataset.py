import json
from collections import Counter
from pathlib import Path


def test_router_evaluation_dataset_has_required_coverage():
    path = Path(__file__).parents[1] / "evals" / "router_cases.jsonl"
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert len(cases) >= 120
    assert len({case["id"] for case in cases}) == len(cases)
    kinds = Counter(case["kind"] for case in cases)
    assert kinds["single_intent"] >= 100
    assert kinds["follow_up"] >= 5
    assert kinds["ambiguous"] >= 5
    assert kinds["multi_intent"] >= 5
    assert kinds["prompt_injection"] >= 5
    assert {case["expectedIntent"] for case in cases} == {
        "GENERAL", "PLATFORM_KNOWLEDGE", "AFTER_SALES_POLICY", "ORDER_QUERY", "VOUCHER_QUERY",
        "SHOP_LOOKUP", "SHOP_RECOMMENDATION", "HOT_CONTENT", "EXTERNAL_INFO",
    }
