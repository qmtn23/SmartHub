"""Merchant-scoped disposable index; Java validates every returned revision."""
import json
import time
from typing import Any

from app.rag.retriever import KnowledgeRetriever, rerank_by_lexical_overlap


class MerchantFaqIndex:
    def __init__(self, retriever: KnowledgeRetriever):
        self.client = retriever.client
        self.embeddings = retriever.embeddings
        self.settings = retriever.settings
        self.run_sync = retriever.run_sync
        self.collection = self.settings.merchant_faq_collection

    def exists(self):
        return self.client.has_collection(self.collection)

    def setup(self):
        from pymilvus import DataType, MilvusClient
        if self.exists():
            return
        schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
        schema.add_field("id", DataType.VARCHAR, max_length=80, is_primary=True)
        schema.add_field("vector", DataType.FLOAT_VECTOR, dim=self.settings.embedding_dimension)
        for name in ("shop_id", "voucher_id", "category_id", "revision", "valid_from", "valid_until"):
            schema.add_field(name, DataType.INT64)
        for name, size in (("scope", 16), ("faq_id", 32), ("checksum", 64), ("content", 16000), ("payload", 32000)):
            schema.add_field(name, DataType.VARCHAR, max_length=size)
        indexes = self.client.prepare_index_params()
        indexes.add_index("vector", index_type="AUTOINDEX", metric_type="COSINE")
        self.client.create_collection(self.collection, schema=schema, index_params=indexes,
                                      consistency_level="Strong")

    @staticmethod
    def scope_filter(context: dict) -> str:
        shop_id = int(context["shopId"])
        if shop_id <= 0:
            raise ValueError("Invalid shop")
        scopes = ['scope == "SHOP"']
        if context.get("voucherId") is not None:
            scopes.append(f'(scope == "PRODUCT" and voucher_id == {int(context["voucherId"])})')
        if context.get("categoryId") is not None:
            scopes.append(f'(scope == "CATEGORY" and category_id == {int(context["categoryId"])})')
        now = int(time.time())
        published = []
        for row in context.get("knowledgeVersion", []):
            identifier = str(row.get("faq_id", ""))
            if not row.get("enabled") or row.get("active_revision") is None:
                continue
            if len(identifier) != 32 or not all(c in "0123456789abcdef" for c in identifier):
                raise ValueError("Invalid published FAQ identity")
            published.append(f'(faq_id == "{identifier}" and revision == {int(row["active_revision"])})')
        publication_filter = " or ".join(published) or 'faq_id == "__no_published_faq__"'
        return (f'shop_id == {shop_id} and ({" or ".join(scopes)}) and ({publication_filter}) '
                f'and valid_from <= {now} and valid_until > {now}')

    async def search(self, query: str, context: dict) -> list[dict]:
        return await self.run_sync(self._search, query, context)

    def _search(self, query, context):
        if not self.exists():
            raise RuntimeError("Merchant FAQ collection is missing")
        expression = self.scope_filter(context)
        fields = ["id", "faq_id", "revision", "payload", "content"]
        rows = self.client.search(collection_name=self.collection,
            data=[self.embeddings.embed_query(query)], filter=expression,
            limit=12, output_fields=fields,
            search_params={"metric_type": "COSINE", "params": {}}, consistency_level="Strong")
        merged = {}
        for hit in rows[0] if rows else []:
            value = dict(hit["entity"])
            value["score"] = float(hit["distance"])
            merged[str(hit["id"])] = value
        # Bounded lexical fallback, deliberately NOT advertised as native Milvus BM25.
        lexical = self.client.query(collection_name=self.collection, filter=expression,
            limit=min(500, self.settings.retrieval_lexical_scan_limit), output_fields=fields,
            consistency_level="Strong")
        for row in lexical:
            merged.setdefault(str(row["id"]), dict(row, score=0.0))
        ranked = rerank_by_lexical_overlap(query, list(merged.values()), 24)
        return [{
            "faqId": x["faq_id"],
            "revision": x["revision"],
            "score": x["score"],
            "vectorScore": x.get("vector_score", 0.0),
            "lexicalScore": x.get("lexical_score", 0.0),
            "retrievalChannels": [
                channel for channel, present in (
                    ("DENSE", float(x.get("vector_score", 0.0) or 0.0) > 0),
                    ("LEXICAL", float(x.get("lexical_score", 0.0) or 0.0) > 0),
                ) if present
            ],
        } for x in ranked if x["score"] >= self.settings.retrieval_min_score]

    def upsert(self, entry: dict, checksum: str):
        from datetime import datetime, timezone, timedelta
        def epoch(value, default):
            if not value:
                return default
            return int(datetime.fromisoformat(value).replace(tzinfo=timezone(timedelta(hours=8))).timestamp())
        identifier = f"{entry['faqId']}:{entry['revision']}"
        existing = self.client.get(self.collection, ids=[identifier], output_fields=["checksum"], consistency_level="Strong")
        if existing and existing[0].get("checksum") == checksum:
            return
        content = "\n".join([entry["question"], *entry.get("aliases", []), entry["answer"]])
        row = {
            "id": identifier, "faq_id": entry["faqId"], "revision": entry["revision"],
            "shop_id": entry["shopId"], "voucher_id": entry.get("voucherId") or 0,
            "category_id": entry.get("categoryId") or 0, "scope": entry["scope"],
            "valid_from": epoch(entry.get("validFrom"), 0), "valid_until": epoch(entry.get("validUntil"), 253402185600),
            "content": content, "payload": json.dumps(entry, ensure_ascii=False), "checksum": checksum,
            "vector": self.embeddings.embed_query(content),
        }
        # Never delete the previous revision before embeddings and write succeed.
        self.client.upsert(self.collection, [row])
        self.client.flush(self.collection)
        verified = self.client.get(self.collection, ids=[identifier], output_fields=["checksum"], consistency_level="Strong")
        if not verified or verified[0].get("checksum") != checksum:
            raise RuntimeError("Index visibility verification failed")

    def prune_older(self, versions: list[dict]):
        for row in versions:
            # Delete only versions strictly older than BOTH active and pending versions.
            # Retain latest disabled entries; Java rejects them immediately. This avoids racing a publication.
            keep = [int(row[k]) for k in ("active_revision", "pending_revision") if row.get(k) is not None]
            if keep and row.get("enabled"):
                faq_id = str(row["faq_id"])
                if len(faq_id) != 32 or not all(c in "0123456789abcdef" for c in faq_id):
                    raise ValueError("Invalid FAQ identity")
                self.client.delete(self.collection, filter=f'faq_id == "{faq_id}" and revision < {min(keep)}')
