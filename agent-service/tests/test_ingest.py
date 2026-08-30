from app.rag.ingest import split_text, stable_id


def test_split_text_uses_configured_overlap():
    chunks = split_text("a" * 1000, size=500, overlap=80)
    assert [len(chunk) for chunk in chunks] == [500, 500, 160]


def test_stable_id_is_deterministic():
    assert stable_id("faq.md", 0, "content") == stable_id("faq.md", 0, "content")
    assert stable_id("faq.md", 0, "content") != stable_id("faq.md", 1, "content")
