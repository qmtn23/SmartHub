import asyncio
import hmac
import json
import uuid
import time

from fastapi import APIRouter, Header, HTTPException, Request, Response, status
from opentelemetry import trace

from app.config import get_settings
from app.graph.builder import RouterInvalidResponseError
from app.observability import errors, run_latency
from app.schemas import AgentRunRequest, AgentRunResponse

router = APIRouter()
tracer = trace.get_tracer("smarthub.agent_service")


def _require_service_key(value: str | None) -> None:
    expected = get_settings().agent_service_api_key
    if not expected or value is None or not hmac.compare_digest(value, expected):
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid service key")


@router.get("/health/live")
async def liveness() -> dict[str, str]:
    return {"status": "UP"}


@router.get("/health/ready")
async def readiness(request: Request) -> dict[str, str]:
    settings = get_settings()
    failures: list[str] = []
    if not settings.agent_service_api_key:
        failures.append("AGENT_SERVICE_API_KEY")
    if not settings.dashscope_api_key:
        failures.append("DASHSCOPE_API_KEY")
    if not settings.tavily_api_key:
        failures.append("TAVILY_API_KEY")
    try:
        await request.app.state.runtime.redis.ping()
    except Exception:
        failures.append("redis")
    try:
        if not await asyncio.to_thread(request.app.state.runtime.retriever.collection_exists):
            failures.append("milvus_collection")
    except Exception:
        failures.append("milvus")
    if failures:
        raise HTTPException(status_code=503, detail={"status": "DOWN", "failures": failures})
    return {"status": "UP"}


@router.post("/v1/customer-service/runs", response_model=AgentRunResponse)
async def run_customer_service(
    payload: AgentRunRequest,
    request: Request,
    x_agent_service_key: str | None = Header(default=None),
    idempotency_key: str | None = Header(default=None),
) -> AgentRunResponse:
    _require_service_key(x_agent_service_key)
    if idempotency_key != payload.request_id or payload.request_id != str(payload.user_message_id):
        raise HTTPException(status_code=422, detail="idempotency key must equal userMessageId")

    runtime = request.app.state.runtime
    settings = get_settings()
    result_key = f"agent:v2:run:{payload.request_id}:result"
    lock_key = f"agent:v2:run:{payload.request_id}:lock"
    cached = await runtime.redis.get(result_key)
    if cached:
        return AgentRunResponse.model_validate_json(cached)
    acquired = await runtime.redis.set(lock_key, "1", ex=settings.run_timeout_seconds + 5, nx=True)
    if not acquired:
        raise HTTPException(status_code=409, detail="RUN_IN_PROGRESS")

    run_id = uuid.uuid4().hex
    started_at = time.perf_counter()
    latency_attributes = {"outcome": "error", "mode": "unknown"}
    with tracer.start_as_current_span("customer_service.run") as span:
        span.set_attribute("agent.request_id", payload.request_id)
        span.set_attribute("agent.thread_id", payload.thread_id)
        span_context = span.get_span_context()
        trace_id = f"{span_context.trace_id:032x}" if span_context.is_valid else uuid.uuid4().hex
        graph_input = payload.model_dump(
            mode="python", exclude={"tool_access_token", "tool_access_tokens"}
        ) | {
            "run_id": run_id,
            "trace_id": trace_id,
        }
        scoped_tokens = payload.tool_access_tokens.model_dump(mode="python") if payload.tool_access_tokens else {
            "transaction_agent_token": payload.tool_access_token,
            "discovery_agent_token": payload.tool_access_token,
        }
        tool_tokens = {
            "transaction_agent": scoped_tokens["transaction_agent_token"],
            "discovery_agent": scoped_tokens["discovery_agent_token"],
        }
        graph_config = {
            "configurable": {
                "thread_id": payload.thread_id,
                "checkpoint_ns": "customer_service_v2",
            },
            "recursion_limit": settings.graph_recursion_limit,
        }
        try:
            result = None
            invoke_input = graph_input
            if hasattr(runtime.graph, "aget_state"):
                snapshot = await runtime.graph.aget_state(graph_config)
                snapshot_values = getattr(snapshot, "values", {}) or {}
                if snapshot_values.get("request_id") == payload.request_id:
                    if getattr(snapshot, "next", ()):
                        # Resume only the failed/pending node; completed Router, Agent and tool nodes stay checkpointed.
                        invoke_input = None
                    elif snapshot_values.get("final_response"):
                        result = snapshot_values
            if result is None:
                result = await asyncio.wait_for(
                    runtime.graph.ainvoke(
                        invoke_input,
                        config=graph_config,
                        context={
                            "tool_access_tokens": tool_tokens,
                            "request_id": payload.request_id,
                            "result_cache": runtime.redis,
                            "result_ttl_seconds": settings.result_ttl_seconds,
                        },
                    ),
                    timeout=settings.run_timeout_seconds,
                )
            response = AgentRunResponse(
                run_id=result.get("run_id", run_id),
                reply=result["final_response"],
                intent=result.get("primary_intent", "GENERAL"),
                active_agent=result.get("active_agent", "general_support_agent"),
                business_refs=result.get("business_refs", []),
                trace_id=result.get("trace_id", trace_id),
                graph_version="v2",
                route_history=result.get("route_history", []),
                handoff_count=result.get("handoff_count", 0),
                model_call_count=result.get("model_call_count", 0),
                tool_call_count=result.get("tool_call_count", 0),
                usage={
                    "prompt_tokens": result.get("prompt_tokens", 0),
                    "completion_tokens": result.get("completion_tokens", 0),
                },
            )
            await runtime.redis.set(
                result_key,
                response.model_dump_json(by_alias=True),
                ex=settings.result_ttl_seconds,
            )
            latency_attributes = {
                "outcome": "success",
                "mode": "multi_intent" if response.handoff_count else "single_intent",
            }
            return response
        except asyncio.TimeoutError as exc:
            latency_attributes["error_code"] = "AGENT_TIMEOUT"
            errors.add(1, {"code": "AGENT_TIMEOUT"})
            raise HTTPException(status_code=504, detail="AGENT_TIMEOUT") from exc
        except RouterInvalidResponseError as exc:
            latency_attributes["error_code"] = exc.code
            errors.add(1, {"code": exc.code})
            raise HTTPException(status_code=502, detail=exc.code) from exc
        except Exception:
            latency_attributes["error_code"] = "AGENT_INTERNAL_ERROR"
            errors.add(1, {"code": "AGENT_INTERNAL_ERROR"})
            raise
        finally:
            run_latency.record(time.perf_counter() - started_at, latency_attributes)
            await runtime.redis.delete(lock_key)


@router.delete("/v1/customer-service/threads/{thread_id}", status_code=204)
async def delete_thread(
    thread_id: str,
    request: Request,
    x_agent_service_key: str | None = Header(default=None),
) -> Response:
    _require_service_key(x_agent_service_key)
    await request.app.state.runtime.checkpointer.adelete_thread(thread_id)
    return Response(status_code=204)
