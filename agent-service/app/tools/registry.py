from __future__ import annotations

import json
import hashlib
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any

import httpx
from langchain_core.tools import BaseTool, tool
from opentelemetry import trace

from app.config import Settings
from app.observability import tool_calls, tool_failures
from app.rag.retriever import KnowledgeRetriever
from app.schemas import HandoffRequest
from app.tools.smarthub_client import SmartHubToolClient


tracer = trace.get_tracer("smarthub.agent_service.tools")


@dataclass
class RunToolContext:
    active_agent: str
    token: str | None
    max_calls: int
    request_id: str = ""
    result_cache: Any | None = None
    result_ttl_seconds: int = 86400
    graph_version: str = "v4"
    call_count: int = 0
    business_refs: list[dict[str, Any]] = field(default_factory=list)
    handoff_request: dict[str, Any] | None = None

    def consume(self, tool_name: str) -> None:
        self.call_count += 1
        tool_calls.add(1, {"tool": tool_name, "agent": self.active_agent})
        if self.call_count > self.max_calls:
            raise RuntimeError("本轮工具调用次数已达上限")


_run_context: ContextVar[RunToolContext | None] = ContextVar("run_tool_context", default=None)


def set_run_tool_context(context: RunToolContext):
    return _run_context.set(context)


def reset_run_tool_context(token) -> None:
    _run_context.reset(token)


def current_run_tool_context(tool_name: str) -> RunToolContext:
    context = _run_context.get()
    if context is None:
        raise RuntimeError("工具调用缺少运行上下文")
    context.consume(tool_name)
    return context


def build_agent_tools(
    settings: Settings,
    client: SmartHubToolClient,
    retriever: KnowledgeRetriever,
) -> dict[str, list[BaseTool]]:
    async def java_tool(tool_name: str, path: str, payload: dict[str, Any] | None = None) -> str:
        context = current_run_tool_context(tool_name)
        if not context.token:
            raise PermissionError(f"{context.active_agent}没有Java业务工具凭证")
        cache_key = None
        if context.result_cache is not None and context.request_id:
            signature = json.dumps({"tool": tool_name, "payload": payload}, sort_keys=True, ensure_ascii=False)
            digest = hashlib.sha256(signature.encode("utf-8")).hexdigest()
            cache_key = f"agent:{context.graph_version}:run:{context.request_id}:tool:{digest}"
            cached = await context.result_cache.get(cache_key)
            if cached:
                cached_result = json.loads(cached)
                for ref in cached_result.get("bizRefs") or []:
                    if ref not in context.business_refs:
                        context.business_refs.append(ref)
                return cached
        with tracer.start_as_current_span(f"tool.{tool_name}") as span:
            span.set_attribute("agent.name", context.active_agent)
            try:
                result = await client.call(path, context.token, payload)
            except (httpx.HTTPError, ValueError):
                span.set_attribute("tool.success", False)
                tool_failures.add(1, {"tool": tool_name, "agent": context.active_agent})
                return json.dumps(
                    {"success": False, "code": "TOOL_UNAVAILABLE", "message": "业务服务暂时不可用", "retryable": True},
                    ensure_ascii=False,
                )
            span.set_attribute("tool.success", bool(result.get("success", True)))
        for ref in result.get("bizRefs") or []:
            if ref not in context.business_refs:
                context.business_refs.append(ref)
        encoded = json.dumps(result, ensure_ascii=False, default=str)
        if cache_key and result.get("success", True):
            await context.result_cache.set(cache_key, encoded, ex=context.result_ttl_seconds)
        return encoded

    @tool
    async def search_platform_knowledge(query: str, category: str | None = None) -> str:
        """检索平台FAQ、规则、操作指南和客服SOP。category可传平台知识分类，不得查询实时数据。"""
        context = current_run_tool_context("search_platform_knowledge")
        if context.active_agent != "general_support_agent":
            raise PermissionError("仅通用客服Agent可以检索平台知识")
        allowed_categories = {
            "complaint-handling", "platform-faq", "refund-process", "shop-recommendation", "voucher-guide"
        }
        if category and category not in allowed_categories:
            tool_failures.add(1, {"tool": "search_platform_knowledge", "agent": context.active_agent})
            return json.dumps({"success": False, "code": "INVALID_KNOWLEDGE_CATEGORY"}, ensure_ascii=False)
        matches = await retriever.asearch(query, [category] if category else None)
        return json.dumps(matches, ensure_ascii=False)

    @tool
    async def query_shop_by_id(shop_id: int) -> str:
        """根据店铺ID查询实时店铺详情。"""
        return await java_tool("query_shop_by_id", "/internal/agent-tools/shops/get", {"id": shop_id})

    @tool
    async def search_shops_by_name(keyword: str) -> str:
        """根据名称关键词搜索实时店铺，最多返回5条。"""
        return await java_tool("search_shops_by_name", "/internal/agent-tools/shops/search", {"keyword": keyword})

    @tool
    async def query_vouchers_by_shop_id(shop_id: int) -> str:
        """查询指定店铺当前优惠券、库存和有效期。"""
        return await java_tool("query_vouchers_by_shop_id", "/internal/agent-tools/vouchers/by-shop", {"id": shop_id})

    @tool
    async def query_current_user_orders() -> str:
        """查询当前已认证用户最近10条优惠券订单；不要询问或传入用户ID。"""
        return await java_tool("query_current_user_orders", "/internal/agent-tools/orders/current")

    @tool
    async def recommend_shops_by_type(type_id: int) -> str:
        """按类型推荐评分最高的店铺；类型ID为1到10。"""
        return await java_tool("recommend_shops_by_type", "/internal/agent-tools/shops/recommend", {"typeId": type_id})

    @tool
    async def query_hot_blogs() -> str:
        """查询当前最热门的探店笔记，最多返回5条。"""
        return await java_tool("query_hot_blogs", "/internal/agent-tools/blogs/hot")

    @tool
    async def search_external_web(query: str) -> str:
        """仅在问题超出平台数据范围且确需最新互联网信息时搜索网页。"""
        context = current_run_tool_context("search_external_web")
        if context.active_agent != "general_support_agent":
            raise PermissionError("仅通用客服Agent可以搜索外部网页")
        if not settings.tavily_api_key:
            tool_failures.add(1, {"tool": "search_external_web", "agent": context.active_agent})
            return json.dumps({"success": False, "message": "外部搜索暂未配置"}, ensure_ascii=False)
        try:
            async with httpx.AsyncClient(timeout=8) as web_client:
                response = await web_client.post(
                    "https://api.tavily.com/search",
                    json={"api_key": settings.tavily_api_key, "query": query, "max_results": 5},
                )
                response.raise_for_status()
                body = response.json()
        except (httpx.HTTPError, ValueError):
            tool_failures.add(1, {"tool": "search_external_web", "agent": context.active_agent})
            return json.dumps({"success": False, "message": "外部搜索暂时不可用"}, ensure_ascii=False)
        safe_results = [
            {"title": item.get("title"), "url": item.get("url"), "content": item.get("content")}
            for item in body.get("results", [])[:5]
        ]
        return json.dumps({"untrustedWebResults": safe_results}, ensure_ascii=False)

    @tool
    async def request_handoff(
        target_agent: str,
        target_intent: str,
        context_summary: str,
        reason_code: str,
    ) -> str:
        """仅当当前任务确实需要另一领域时，请求顺序移交给另一个Agent；请求仍会被系统权限守卫校验。"""
        context = current_run_tool_context("request_handoff")
        try:
            request = HandoffRequest.model_validate(
                {
                    "targetAgent": target_agent,
                    "targetIntent": target_intent,
                    "contextSummary": context_summary,
                    "reasonCode": reason_code,
                }
            )
        except ValueError:
            return json.dumps({"accepted": False, "reason": "INVALID_HANDOFF_REQUEST"}, ensure_ascii=False)
        context.handoff_request = request.model_dump(mode="python")
        return json.dumps({"accepted": True, "message": "移交请求已提交守卫校验"}, ensure_ascii=False)

    return {
        "general_support_agent": [search_platform_knowledge, search_external_web, request_handoff],
        "transaction_agent": [query_current_user_orders, query_vouchers_by_shop_id, query_shop_by_id, request_handoff],
        "discovery_agent": [search_shops_by_name, recommend_shops_by_type, query_shop_by_id, query_hot_blogs, request_handoff],
    }


def build_tools(settings: Settings, client: SmartHubToolClient, retriever: KnowledgeRetriever) -> list[BaseTool]:
    """Compatibility helper for phase-one callers; new code should use build_agent_tools."""
    tools = build_agent_tools(settings, client, retriever)
    unique: dict[str, BaseTool] = {}
    for agent_tools in tools.values():
        for item in agent_tools:
            unique[item.name] = item
    return list(unique.values())
