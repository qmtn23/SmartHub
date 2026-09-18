"""User-scoped historical retrieval. MySQL is authoritative; Milvus is an index only."""
from __future__ import annotations

import asyncio
import hashlib
import json
import logging
import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

from app.rag.retriever import rerank_by_lexical_overlap

logger = logging.getLogger(__name__)


class MemoryIndexRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    memory_id: str = Field(pattern=r"^[a-f0-9]{32}$")
    version: int = Field(ge=1)
    user_id: str = Field(pattern=r"^[0-9]{1,20}$")
    operation: Literal["UPSERT", "DELETE"]
    content: str = Field(default="", max_length=30000)


class SemanticMemory:
    def __init__(self, retriever, client, settings):
        self.retriever = retriever
        self.client = client
        self.settings = settings
        if settings.memory_collection in {settings.milvus_collection, settings.merchant_faq_collection}:
            raise ValueError("user memory must use a separate Milvus collection")

    def setup(self) -> None:
        from pymilvus import DataType

        client = self.retriever.client
        name = self.settings.memory_collection
        if client.has_collection(name, timeout=30):
            return
        schema = client.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("id", DataType.VARCHAR, is_primary=True, max_length=64)
        schema.add_field("memory_id", DataType.VARCHAR, max_length=32)
        schema.add_field("user_id", DataType.VARCHAR, max_length=20)
        schema.add_field("version", DataType.INT64)
        schema.add_field("embedding_model", DataType.VARCHAR, max_length=128)
        schema.add_field("vector", DataType.FLOAT_VECTOR, dim=self.settings.embedding_dimension)
        indexes = client.prepare_index_params()
        indexes.add_index(field_name="vector", index_type="AUTOINDEX", metric_type="COSINE")
        client.create_collection(collection_name=name, schema=schema, index_params=indexes,
                                 consistency_level="Strong", timeout=30)

    def index(self, request: MemoryIndexRequest) -> None:
        name = self.settings.memory_collection
        if not self.retriever.client.has_collection(name, timeout=30):
            raise ValueError("memory collection missing; run memory index setup")
        # Versioned primary keys prevent delayed upserts overwriting current entries.
        self.retriever.client.delete(collection_name=name, filter=(
            f'memory_id == "{request.memory_id}" and user_id == "{request.user_id}" '
            f'and version {"<" if request.operation == "UPSERT" else "<="} {request.version}'
        ), timeout=30)
        if request.operation == "DELETE":
            return
        if not request.content.strip():
            raise ValueError("cannot index empty memory")
        vector = self.retriever.embeddings.embed_query(request.content, timeout=30)
        self.retriever.client.upsert(collection_name=name, data=[{
            "id": f"{request.memory_id}:{request.version}", "memory_id": request.memory_id,
            "user_id": request.user_id, "version": request.version,
            "embedding_model": self.settings.dashscope_embedding_model, "vector": vector,
        }], timeout=30)

    def _search(self, user_id: str, query: str) -> list[dict]:
        vector = self.retriever.embeddings.embed_query(query, timeout=self.settings.memory_retrieval_timeout_seconds)
        result = self.retriever.client.search(
            collection_name=self.settings.memory_collection, data=[vector], limit=30,
            filter=f"user_id == {json.dumps(user_id)} and embedding_model == "
                   + json.dumps(self.settings.dashscope_embedding_model),
            output_fields=["memory_id", "version"],
            search_params={"metric_type": "COSINE", "params": {}},
            timeout=self.settings.memory_retrieval_timeout_seconds,
        )
        return [{**row["entity"], "score": float(row["distance"])} for row in (result[0] if result else [])]

    async def bootstrap(self, token: str | None, query: str) -> dict:
        if not self.settings.semantic_memory_enabled or not token:
            return {"enabled": False, "items": []}
        try:
            return await asyncio.wait_for(self._retrieve(token, query, 5),
                                          timeout=self.settings.memory_retrieval_timeout_seconds)
        except Exception as error:
            logger.warning("Memory bootstrap unavailable: %s", type(error).__name__)
            return {"enabled": False, "items": [], "status": "UNAVAILABLE"}

    async def search(self, token: str | None, query: str, top_k: int = 5) -> dict:
        if not self.settings.semantic_memory_enabled or not token:
            return {"items": [], "status": "DISABLED"}
        try:
            return await asyncio.wait_for(self._retrieve(token, query[:2000], max(1, min(top_k, 5))),
                                          timeout=self.settings.memory_retrieval_timeout_seconds)
        except Exception as error:
            logger.warning("Memory retrieval unavailable: %s", type(error).__name__)
            return {"items": [], "status": "UNAVAILABLE"}

    async def _retrieve(self, token: str, query: str, limit: int) -> dict:
        # Authentication and user ID resolution happen in Java, never in the model.
        entity_ids = list(dict.fromkeys(re.findall(r"(?<![0-9])[0-9]{1,20}(?![0-9])", query)))[:10]
        base = await self.client.call("/internal/agent-tools/memory/bootstrap", token, {"entityIds": entity_ids})
        if not base.get("enabled"):
            return {"enabled": False, "items": []}
        candidates = {item["memory_id"]: item for item in base.get("items", [])}
        exact = {item["memory_id"]: item for item in base.get("entityItems", [])}
        candidates.update(exact)
        scores = {key: 1.0 for key in exact}
        status = "OK"
        try:
            hits = await self.retriever.run_sync(self._search, str(base["user_id"]), query[:2000])
            ids = list(dict.fromkeys(hit["memory_id"] for hit in hits))
            if ids:
                canonical = await self.client.call("/internal/agent-tools/memory/lookup", token, {"ids": ids})
                by_id = {item["memory_id"]: item for item in canonical.get("items", [])}
                for hit in hits:
                    item = by_id.get(hit["memory_id"])
                    if item and int(item["version"]) == int(hit["version"]):
                        candidates[item["memory_id"]] = item
                        scores[item["memory_id"]] = max(scores.get(item["memory_id"], 0), hit["score"])
        except Exception as error:
            logger.warning("Memory vector search degraded to lexical: %s", type(error).__name__)
            status = "LEXICAL_FALLBACK"
        ranked = rerank_by_lexical_overlap(query, [
            {**item, "content": json.dumps(item["content"], ensure_ascii=False),
             "score": scores.get(key, 0.0)} for key, item in candidates.items()
        ], limit)
        selected = []
        budget = self.settings.memory_context_bytes
        for match in ranked:
            if match["score"] < self.settings.memory_min_score:
                continue
            item = dict(candidates[match["memory_id"]])
            # Keep provenance references without duplicating all field evidence in prompts.
            sources = [source for field in item.pop("source_refs", {}).values() for source in field.get("sources", [])]
            refs = {source["messageId"] for source in sources if "messageId" in source}
            item["source_message_ids"] = sorted(refs)
            item["source_event_ids"] = sorted({source["eventId"] for source in sources if "eventId" in source})
            cost = len(json.dumps(item, ensure_ascii=False).encode("utf-8"))
            if cost <= budget:
                selected.append(item)
                budget -= cost
        return {"enabled": True, "profile": base.get("profile", {}), "items": selected, "status": status}

    async def event(self, token: str | None, run_id: str, event_type: str, payload: dict) -> None:
        if not self.settings.semantic_memory_enabled or not token:
            return
        serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True, default=str)
        event_id = hashlib.sha256(f"{run_id}:{event_type}:{serialized}".encode()).hexdigest()
        result = await self.client.call("/internal/agent-tools/memory/events", token, {
            "runId": run_id, "eventId": event_id, "type": event_type, "payload": payload,
        })
        if not result.get("persisted"):
            raise RuntimeError("memory event persistence is not enabled on Java")


async def _setup():
    from app.config import get_settings
    from app.rag.retriever import KnowledgeRetriever
    settings = get_settings()
    retriever = KnowledgeRetriever(settings)
    try:
        await retriever.run_sync(SemanticMemory(retriever, None, settings).setup)
    finally:
        retriever.close()


if __name__ == "__main__":
    asyncio.run(_setup())
