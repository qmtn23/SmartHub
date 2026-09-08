from types import SimpleNamespace
import pytest
from app.rag.merchant import MerchantFaqIndex
from test_product_faq import ENTRY


class Vectors:
    def __init__(self, fail=False): self.calls=0; self.fail=fail
    def embed_query(self, content):
        self.calls += 1
        if self.fail: raise RuntimeError("embedding failure")
        return [0.1, 0.2]


class Store:
    def __init__(self): self.rows={}; self.deleted=[]; self.upserts=0
    def get(self, collection, ids, **kwargs): return [self.rows[i] for i in ids if i in self.rows]
    def upsert(self, collection, rows):
        self.upserts += 1
        for row in rows: self.rows[row["id"]]=row
    def flush(self, collection): pass
    def delete(self, collection, filter): self.deleted.append(filter)


def index_for(store=None, vectors=None):
    return MerchantFaqIndex(SimpleNamespace(client=store or Store(), embeddings=vectors or Vectors(),
        settings=SimpleNamespace(merchant_faq_collection="test_faq")))


def test_index_update_is_idempotent_and_keeps_old_revision_until_new_visible():
    index=index_for()
    index.upsert(ENTRY,"checksum-v1")
    index.upsert(ENTRY,"checksum-v1")
    assert index.embeddings.calls==1 and index.client.upserts==1
    index.upsert(ENTRY | {"revision":2,"answer":"更新的预约说明。"},"checksum-v2")
    assert len(index.client.rows)==2 and not index.client.deleted


def test_embedding_failure_does_not_delete_active_version():
    store=Store(); index=index_for(store)
    index.upsert(ENTRY,"checksum-v1")
    index.embeddings=Vectors(fail=True)
    with pytest.raises(RuntimeError): index.upsert(ENTRY | {"revision":2},"checksum-v2")
    assert f"{ENTRY['faqId']}:1" in store.rows
    assert not store.deleted and store.upserts==1


def test_cleanup_cannot_delete_active_pending_or_future_revisions():
    index=index_for()
    index.prune_older([{"faq_id":ENTRY["faqId"],"active_revision":2,"pending_revision":3,"enabled":True}])
    assert index.client.deleted==[f'faq_id == "{ENTRY["faqId"]}" and revision < 2']
    index.prune_older([{"faq_id":ENTRY["faqId"],"active_revision":2,"pending_revision":None,"enabled":False}])
    assert len(index.client.deleted)==1


def test_validity_timestamps_use_china_timezone_without_os_timezone_database():
    index=index_for()
    index.upsert(ENTRY | {"validFrom":"1970-01-01T08:00:00"},"checksum")
    assert next(iter(index.client.rows.values()))["valid_from"]==0
