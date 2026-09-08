import asyncio
import functools
import json
import math
import re
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from typing import Any, TYPE_CHECKING

from langchain_openai import OpenAIEmbeddings

from app.config import Settings

if TYPE_CHECKING:
    from pymilvus import MilvusClient


class KnowledgeRetriever:
    def __init__(self, settings: Settings):
        from pymilvus import MilvusClient

        self.settings = settings
        kwargs: dict[str, Any] = {"uri": settings.milvus_uri}
        if settings.milvus_token:
            kwargs["token"] = settings.milvus_token
        self.client: MilvusClient = MilvusClient(**kwargs)
        self.vector_executor = ThreadPoolExecutor(
            max_workers=settings.vector_thread_pool_workers,
            thread_name_prefix="milvus-io",
        )
        self.embeddings = OpenAIEmbeddings(
            model=settings.dashscope_embedding_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.dashscope_base_url,
            dimensions=settings.embedding_dimension,
            max_retries=1,
        )

    def collection_exists(self) -> bool:
        return self.client.has_collection(self.settings.milvus_collection)

    async def asearch(
        self, query: str, categories: list[str] | None = None, limit: int | None = None
    ) -> list[dict[str, Any]]:
        return await self.run_sync(self.search, query, categories, limit)

    async def run_sync(self, function, *args):
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(
            self.vector_executor, functools.partial(function, *args)
        )

    def close(self) -> None:
        self.vector_executor.shutdown(wait=False, cancel_futures=True)

    def search(
        self, query: str, categories: list[str] | None = None, limit: int | None = None
    ) -> list[dict[str, Any]]:
        if not self.collection_exists():
            return []
        vector = self.embeddings.embed_query(query)
        requested_limit = max(1, min(limit or self.settings.retrieval_top_k, 50))
        allowed = {"complaint-handling", "platform-faq", "refund-process", "shop-recommendation", "voucher-guide"}
        selected = sorted(set(categories or []) & allowed)
        filter_expression = None
        if selected:
            filter_expression = "category in " + json.dumps(selected, ensure_ascii=False)
        search_kwargs: dict[str, Any] = {
            "collection_name": self.settings.milvus_collection,
            "data": [vector],
            "limit": requested_limit,
            "output_fields": ["document_id", "content", "source", "category", "version", "checksum"],
            "search_params": {"metric_type": "COSINE", "params": {}},
        }
        if filter_expression:
            search_kwargs["filter"] = filter_expression
        rows = self.client.search(
            **search_kwargs,
        )
        dense_matches: list[dict[str, Any]] = []
        for row in rows[0] if rows else []:
            score = float(row.get("distance", 0))
            entity = row.get("entity", {})
            dense_matches.append(
                {
                    "chunk_id": str(row.get("id", "")),
                    "document_id": entity.get("document_id"),
                    "content": entity.get("content"),
                    "source": entity.get("source"),
                    "category": entity.get("category"),
                    "version": entity.get("version"),
                    "checksum": entity.get("checksum"),
                    "score": score,
                }
            )
        lexical_matches: list[dict[str, Any]] = []
        try:
            lexical_rows = self.client.query(
                collection_name=self.settings.milvus_collection,
                filter=filter_expression or "id >= 0",
                output_fields=["id", "document_id", "content", "source", "category", "version", "checksum"],
                limit=self.settings.retrieval_lexical_scan_limit,
            )
            lexical_matches = [
                {
                    "chunk_id": str(row.get("id", "")),
                    "document_id": row.get("document_id"),
                    "content": row.get("content"),
                    "source": row.get("source"),
                    "category": row.get("category"),
                    "version": row.get("version"),
                    "checksum": row.get("checksum"),
                    "score": 0.0,
                }
                for row in lexical_rows
            ]
        except Exception:
            # Dense retrieval remains available if a legacy Milvus deployment cannot perform the scalar scan.
            lexical_matches = []
        merged: dict[str, dict[str, Any]] = {}
        for value in [*dense_matches, *lexical_matches]:
            key = str(value.get("chunk_id") or value.get("content") or "")
            if key not in merged or float(value.get("score", 0.0)) > float(merged[key].get("score", 0.0)):
                merged[key] = value
        reranked = rerank_by_lexical_overlap(query, list(merged.values()), requested_limit)
        return [item for item in reranked if float(item["score"]) >= self.settings.retrieval_min_score]


def _terms(value: str) -> list[str]:
    normalized = re.sub(r"\s+", "", value.lower())
    latin = re.findall(r"[a-z0-9_]+", normalized)
    chinese = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
    grams = [chinese[index:index + 2] for index in range(max(0, len(chinese) - 1))]
    return [*latin, *grams]


def rerank_by_lexical_overlap(
    query: str, matches: list[dict[str, Any]], limit: int
) -> list[dict[str, Any]]:
    """Deterministically blend dense similarity with exact business-term overlap."""
    query_terms = set(_terms(query))
    document_terms = [_terms(str(match.get("content") or "")) for match in matches]
    document_frequencies = Counter(
        term for terms in document_terms for term in set(terms) if term in query_terms
    )
    average_length = sum(len(terms) for terms in document_terms) / max(len(document_terms), 1)
    raw_bm25: list[float] = []
    for terms in document_terms:
        frequencies = Counter(terms)
        score = 0.0
        for term in query_terms:
            frequency = frequencies.get(term, 0)
            if not frequency:
                continue
            document_frequency = document_frequencies.get(term, 0)
            inverse_frequency = math.log(1 + (len(matches) - document_frequency + 0.5) /
                                         (document_frequency + 0.5))
            denominator = frequency + 1.5 * (1 - 0.75 + 0.75 * len(terms) / max(average_length, 1))
            score += inverse_frequency * frequency * 2.5 / denominator
        raw_bm25.append(score)
    max_bm25 = max(raw_bm25, default=0.0)
    reranked: list[dict[str, Any]] = []
    for match, terms, lexical_score in zip(matches, document_terms, raw_bm25):
        value = dict(match)
        coverage = len(query_terms & set(terms)) / max(len(query_terms), 1)
        lexical = (lexical_score / max_bm25) * coverage if max_bm25 > 0 else 0.0
        vector_score = max(0.0, float(value.get("score", 0.0) or 0.0))
        value["vector_score"] = vector_score
        value["lexical_score"] = lexical
        value["score"] = min(1.0, max(vector_score * 0.8 + lexical * 0.2, lexical * 0.85))
        reranked.append(value)
    reranked.sort(key=lambda item: float(item["score"]), reverse=True)
    return reranked[:limit]
