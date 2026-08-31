import asyncio
import json
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
        self.embeddings = OpenAIEmbeddings(
            model=settings.dashscope_embedding_model,
            api_key=settings.dashscope_api_key,
            base_url=settings.dashscope_base_url,
            dimensions=settings.embedding_dimension,
            max_retries=1,
        )

    def collection_exists(self) -> bool:
        return self.client.has_collection(self.settings.milvus_collection)

    async def asearch(self, query: str, categories: list[str] | None = None) -> list[dict[str, Any]]:
        return await asyncio.to_thread(self.search, query, categories)

    def search(self, query: str, categories: list[str] | None = None) -> list[dict[str, Any]]:
        if not self.collection_exists():
            return []
        vector = self.embeddings.embed_query(query)
        allowed = {"complaint-handling", "platform-faq", "refund-process", "shop-recommendation", "voucher-guide"}
        selected = sorted(set(categories or []) & allowed)
        filter_expression = None
        if selected:
            filter_expression = "category in " + json.dumps(selected, ensure_ascii=False)
        search_kwargs: dict[str, Any] = {
            "collection_name": self.settings.milvus_collection,
            "data": [vector],
            "limit": self.settings.retrieval_top_k,
            "output_fields": ["content", "source", "category", "version"],
            "search_params": {"metric_type": "COSINE", "params": {}},
        }
        if filter_expression:
            search_kwargs["filter"] = filter_expression
        rows = self.client.search(
            **search_kwargs,
        )
        matches: list[dict[str, Any]] = []
        for row in rows[0] if rows else []:
            score = float(row.get("distance", 0))
            if score < self.settings.retrieval_min_score:
                continue
            entity = row.get("entity", {})
            matches.append(
                {
                    "content": entity.get("content"),
                    "source": entity.get("source"),
                    "category": entity.get("category"),
                    "version": entity.get("version"),
                    "score": score,
                }
            )
        return matches
