import hashlib
from pathlib import Path

from langchain_openai import OpenAIEmbeddings

from app.config import get_settings


def split_text(text: str, size: int = 500, overlap: int = 80) -> list[str]:
    normalized = text.strip()
    if not normalized:
        return []
    chunks: list[str] = []
    start = 0
    while start < len(normalized):
        end = min(start + size, len(normalized))
        chunks.append(normalized[start:end])
        if end == len(normalized):
            break
        start = end - overlap
    return chunks


def stable_id(source: str, index: int, content: str) -> int:
    digest = hashlib.sha256(f"{source}:{index}:{content}".encode("utf-8")).hexdigest()
    return int(digest[:15], 16)


def ensure_collection(client, name: str, dimension: int) -> None:
    from pymilvus import DataType, MilvusClient

    if client.has_collection(name):
        return
    schema = MilvusClient.create_schema(auto_id=False, enable_dynamic_field=False)
    schema.add_field("id", DataType.INT64, is_primary=True)
    schema.add_field("vector", DataType.FLOAT_VECTOR, dim=dimension)
    schema.add_field("document_id", DataType.VARCHAR, max_length=64)
    schema.add_field("source", DataType.VARCHAR, max_length=255)
    schema.add_field("category", DataType.VARCHAR, max_length=64)
    schema.add_field("version", DataType.VARCHAR, max_length=64)
    schema.add_field("checksum", DataType.VARCHAR, max_length=64)
    schema.add_field("content", DataType.VARCHAR, max_length=4096)
    index_params = client.prepare_index_params()
    index_params.add_index("vector", index_type="AUTOINDEX", metric_type="COSINE")
    client.create_collection(name, schema=schema, index_params=index_params)


def ingest() -> None:
    from pymilvus import MilvusClient

    settings = get_settings()
    client_kwargs = {"uri": settings.milvus_uri}
    if settings.milvus_token:
        client_kwargs["token"] = settings.milvus_token
    client = MilvusClient(**client_kwargs)
    ensure_collection(client, settings.milvus_collection, settings.embedding_dimension)
    embeddings = OpenAIEmbeddings(
        model=settings.dashscope_embedding_model,
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        dimensions=settings.embedding_dimension,
        max_retries=1,
    )

    files = sorted(Path(settings.knowledge_dir).glob("*.md"))
    current_sources = {path.name for path in files}
    existing = client.query(
        settings.milvus_collection,
        filter="id >= 0",
        output_fields=["source", "checksum"],
        limit=10000,
    )
    existing_by_source = {row["source"]: row["checksum"] for row in existing}
    for stale_source in set(existing_by_source) - current_sources:
        client.delete(settings.milvus_collection, filter=f'source == "{stale_source}"')

    for path in files:
        content = path.read_text(encoding="utf-8")
        checksum = hashlib.sha256(content.encode("utf-8")).hexdigest()
        if existing_by_source.get(path.name) == checksum:
            continue
        client.delete(settings.milvus_collection, filter=f'source == "{path.name}"')
        chunks = split_text(content)
        vectors = embeddings.embed_documents(chunks)
        rows = [
            {
                "id": stable_id(path.name, index, chunk),
                "vector": vector,
                "document_id": hashlib.sha256(path.name.encode("utf-8")).hexdigest()[:32],
                "source": path.name,
                "category": path.stem,
                "version": "1",
                "checksum": checksum,
                "content": chunk,
            }
            for index, (chunk, vector) in enumerate(zip(chunks, vectors))
        ]
        if rows:
            client.upsert(settings.milvus_collection, rows)
        print(f"indexed {path.name}: {len(rows)} chunks")


if __name__ == "__main__":
    ingest()
