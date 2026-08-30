from dataclasses import dataclass

from langgraph.graph.state import CompiledStateGraph
from redis.asyncio import Redis

from app.rag.retriever import KnowledgeRetriever


@dataclass
class AppRuntime:
    redis: Redis
    checkpointer: object
    graph: CompiledStateGraph
    retriever: KnowledgeRetriever
