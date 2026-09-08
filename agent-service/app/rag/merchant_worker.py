"""python -m app.rag.merchant_worker [--setup-only | --once]; no MySQL credentials."""
import argparse
import asyncio
import logging

import httpx
from app.config import get_settings
from app.rag.retriever import KnowledgeRetriever
from app.rag.merchant import MerchantFaqIndex

logger = logging.getLogger("merchant_faq_index")


async def run_once(client, index):
    response = await client.post("/internal/faq-index/claim")
    response.raise_for_status()
    for job in response.json()["jobs"]:
        success = False
        try:
            await index.run_sync(index.upsert, job["entry"], job["checksum"])
            success = True
        except Exception:
            logger.warning("FAQ index write failed", extra={"event_id": job["eventId"]})
        completed = await client.post("/internal/faq-index/complete", json={
            "eventId": job["eventId"], "leaseId": job["leaseId"], "success": success})
        completed.raise_for_status()
    cursor = ""
    while True:
        response = await client.get("/internal/faq-index/versions", params={"afterId": cursor})
        response.raise_for_status()
        rows = response.json()["entries"]
        if not rows:
            break
        await index.run_sync(index.prune_older, rows)
        cursor = rows[-1]["faq_id"]


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--once", action="store_true")
    parser.add_argument("--setup-only", action="store_true")
    args = parser.parse_args()
    settings = get_settings()
    retriever = KnowledgeRetriever(settings)
    index = MerchantFaqIndex(retriever)
    try:
        await retriever.run_sync(index.setup)
        if args.setup_only:
            return
        if len(settings.faq_index_service_key) < 32:
            raise ValueError("FAQ_INDEX_SERVICE_KEY must contain at least 32 characters")
        async with httpx.AsyncClient(base_url=settings.smarthub_internal_base_url.rstrip("/"),
                headers={"X-Faq-Index-Key": settings.faq_index_service_key}, timeout=10) as client:
            while True:
                try:
                    await run_once(client, index)
                except Exception:
                    if args.once:
                        raise
                    logger.warning("FAQ index poll failed; will retry (no credentials or content logged)")
                if args.once:
                    return
                await asyncio.sleep(5)
    finally:
        retriever.close()


if __name__ == "__main__":
    asyncio.run(main())
