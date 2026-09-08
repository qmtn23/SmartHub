"""Workflow selection eval by default. --chat-url evaluates the deployed Java/RAG path.

The bundled cases are synthetic. Never label workflow-only scores as Milvus recall.
"""
import argparse
import asyncio
import json
import os
import uuid
from pathlib import Path

import httpx
from app.config import get_settings
from app.faq.product import ProductFaqWorkflow, applicable
from app.models.provider import build_chat_model


class FixtureKnowledge:
    def __init__(self, entries): self.entries = entries
    async def ainvoke(self, _): return {"success": True, "entries": self.entries}


async def evaluate(args):
    dataset = json.loads(args.dataset.read_text(encoding="utf-8"))
    if args.chat_url and dataset["datasetVersion"].startswith("synthetic"):
        raise ValueError("Live evaluation requires a separately labelled dataset with real published FAQ/shop/voucher IDs")
    if args.chat_url and (not args.im_chat_id or not args.chat_id or not os.getenv("CUSTOMER_SESSION_TOKEN")):
        raise ValueError("Live evaluation requires chat IDs and CUSTOMER_SESSION_TOKEN for a dedicated test account")
    settings = get_settings()
    model = None if args.chat_url else build_chat_model(settings, router=True, max_retries=0)
    gold = hits = status_ok = rules_ok = unsafe_advice = foreign_evidence = changed_facts = 0
    failed = []
    async with httpx.AsyncClient(timeout=70) as client:
        for case in dataset["cases"]:
            fixtures = dataset.get("fixtures", {})
            if args.chat_url:
                response = await client.post(args.chat_url,
                    headers={"Authorization": os.environ["CUSTOMER_SESSION_TOKEN"]}, json={
                        "imChatId": args.im_chat_id, "chatId": args.chat_id,
                        "clientMessageId": f"faq-eval-{uuid.uuid4().hex}",
                        "message": case["question"], "consultationContext": case["context"]})
                response.raise_for_status()
                body = response.json()
                body = body.get("data", body)
                result = (body.get("structuredContent") or {}).get("productFaq", {}) if isinstance(body, dict) else {}
            else:
                workflow = ProductFaqWorkflow(model=model, settings=settings,
                    knowledge_tool=FixtureKnowledge([fixtures[k] for k in case["entryIds"]]))
                result = (await workflow.ainvoke({"question":case["question"], "context":case["context"]}))["result"]
            expected = set(case["expectedFaqIds"])
            selected = {e["faqId"] for e in result.get("faqMatches", [])[:4]}
            actual_rules = {r["ruleId"] for r in result.get("shoppingAdvice", [])}
            gold += len(expected); hits += len(expected & selected)
            status_ok += int(result.get("status") == case["expectedStatus"])
            rules_ok += int(actual_rules == set(case["expectedRules"]))
            unsafe_advice += int(not expected and bool(actual_rules))
            for entry in result.get("faqMatches", []):
                foreign_evidence += int(not applicable(entry, case["context"]))
                source = fixtures.get(entry["faqId"])
                changed_facts += int(source is not None and source["answer"] != entry.get("answer"))
            if expected != selected or result.get("status") != case["expectedStatus"]:
                failed.append(case["id"])
    total = len(dataset["cases"])
    metrics = {"mode":"live_java_rag" if args.chat_url else "workflow_only_synthetic_evidence",
        "cases":total, "goldFaqCount":gold, "statusAccuracy":status_ok/max(total,1),
        "faqRecallAt4" if args.chat_url else "evidenceSelectionRecallAt4":hits/max(gold,1),
        "ruleAccuracy":rules_ok/max(total,1), "unsupportedAdviceCount":unsafe_advice,
        "crossScopeEvidenceCount":foreign_evidence, "changedCanonicalAnswerCount":changed_facts,
        "failedCaseIds":failed}
    print(json.dumps(metrics,ensure_ascii=False,indent=2))
    return hits/max(gold,1)>=.9 and rules_ok/max(total,1)>=.95 and not (unsafe_advice or foreign_evidence or changed_facts)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dataset",type=Path,default=Path("evals/product_faq_cases.json"))
    parser.add_argument("--chat-url",help="Explicit live Java send-message URL; writes messages to a dedicated test conversation")
    parser.add_argument("--im-chat-id",type=int)
    parser.add_argument("--chat-id",type=int)
    args=parser.parse_args()
    if not asyncio.run(evaluate(args)): raise SystemExit(1)


if __name__ == "__main__": main()
