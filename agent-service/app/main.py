from contextlib import asynccontextmanager

from fastapi import FastAPI
from langgraph.checkpoint.redis.aio import AsyncRedisSaver
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry import metrics
from redis.asyncio import Redis

from app.api import router
from app.config import get_settings
from app.graph.builder import build_customer_service_graph
from app.models.provider import build_chat_model
from app.rag.retriever import KnowledgeRetriever
from app.runtime import AppRuntime
from app.tools.registry import build_agent_tools
from app.tools.smarthub_client import SmartHubToolClient


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    redis = Redis.from_url(settings.redis_url, decode_responses=True)
    checkpointer_cm = AsyncRedisSaver.from_conn_string(
        settings.redis_url,
        ttl={
            "default_ttl": settings.checkpoint_ttl_seconds / 60,
            "refresh_on_read": True,
        },
    )
    checkpointer = await checkpointer_cm.__aenter__()
    await checkpointer.asetup()

    retriever = KnowledgeRetriever(settings)
    tool_client = SmartHubToolClient(settings)
    tools_by_agent = build_agent_tools(settings, tool_client, retriever)
    graph = build_customer_service_graph(
        model=build_chat_model(settings),
        router_model=build_chat_model(settings, router=True),
        supervisor_model=build_chat_model(settings, router=True),
        tools_by_agent=tools_by_agent,
        checkpointer=checkpointer,
        settings=settings,
    )
    app.state.runtime = AppRuntime(redis, checkpointer, graph, retriever)
    try:
        yield
    finally:
        await tool_client.aclose()
        await redis.aclose()
        await checkpointer_cm.__aexit__(None, None, None)


trace.set_tracer_provider(TracerProvider())
metrics.set_meter_provider(MeterProvider())

app = FastAPI(title="SmartHub Agent Service", version="0.3.0", lifespan=lifespan)
app.include_router(router)
