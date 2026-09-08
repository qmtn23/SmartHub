from app.rag.ingest import split_markdown, split_text, stable_id


def test_split_text_uses_configured_overlap():
    chunks = split_text("a" * 1000, size=500, overlap=80)
    assert [len(chunk) for chunk in chunks] == [500, 500, 160]


def test_stable_id_is_deterministic():
    assert stable_id("faq.md", 0, "content") == stable_id("faq.md", 0, "content")
    assert stable_id("faq.md", 0, "content") != stable_id("faq.md", 1, "content")


def test_split_markdown_preserves_heading_path_in_each_chunk():
    chunks = split_markdown("# 平台FAQ\n## 登录\n" + "验证码规则" * 100, size=120, overlap=20)
    assert len(chunks) > 1
    assert all(chunk.startswith("平台FAQ > 登录\n") for chunk in chunks)
