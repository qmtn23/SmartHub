import json
from collections import Counter
from pathlib import Path


def test_router_evaluation_dataset_has_required_coverage():
    path = Path(__file__).parents[1] / "evals" / "router_cases.jsonl"
    cases = [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert len(cases) >= 360
    assert len({case["id"] for case in cases}) == len(cases)
    kinds = Counter(case["kind"] for case in cases)
    assert kinds["single_intent"] >= 100
    assert kinds["follow_up"] >= 5
    assert kinds["ambiguous"] >= 5
    assert kinds["multi_intent"] >= 5
    assert kinds["prompt_injection"] >= 5
    assert kinds["triple_intent"] >= 25
    assert kinds["parallel_tasks"] >= 20
    assert kinds["dependency_tasks"] >= 15
    assert kinds["partial_failure"] >= 10
    assert kinds["same_agent_multi_intent"] >= 5
    assert kinds["action_intent"] >= 60
    assert kinds["handoff_intent"] >= 20
    assert kinds["action_negation"] >= 20
    complex_cases = [case for case in cases if case.get("expectedExecutionMode") == "COMPLEX"]
    assert len(complex_cases) >= 90
    assert all(1 < len(case["expectedSupervisorTargets"]) <= 3 for case in complex_cases)
    assert {case["expectedIntent"] for case in cases} == {
        "GENERAL", "PLATFORM_KNOWLEDGE", "AFTER_SALES_POLICY", "ORDER_QUERY", "VOUCHER_QUERY",
        "SHOP_LOOKUP", "SHOP_RECOMMENDATION", "HOT_CONTENT", "EXTERNAL_INFO",
        "ORDER_CANCEL", "REFUND_REQUEST", "HUMAN_HANDOFF",
    }
