from app.rag.retriever import rerank_by_lexical_overlap


def test_reranker_promotes_exact_business_terms_over_dense_only_match():
    matches = [
        {"content": "平台其他常见问题", "score": 0.72, "chunk_id": "dense"},
        {"content": "登录验证码有效期为2分钟", "score": 0.62, "chunk_id": "exact"},
    ]

    ranked = rerank_by_lexical_overlap("登录验证码有效期", matches, 2)

    assert ranked[0]["chunk_id"] == "exact"
    assert ranked[0]["lexical_score"] > ranked[1]["lexical_score"]
