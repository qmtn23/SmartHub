from __future__ import annotations

import json
from contextvars import ContextVar
from dataclasses import dataclass, field
from typing import Any

import httpx
from langchain_core.tools import BaseTool, tool

from app.config import Settings
from app.rag.retriever import KnowledgeRetriever
from app.tools.smarthub_client import SmartHubToolClient


@dataclass
class RunToolContext:
    token: str
    max_calls: int
    call_count: int = 0
    business_refs: list[dict[str, Any]] = field(default_factory=list)

    def consume(self) -> None:
        self.call_count += 1
        if self.call_count > self.max_calls:
            raise RuntimeError("本轮工具调用次数已达上限")


_run_context: ContextVar[RunToolContext | None] = ContextVar("run_tool_context", default=None)


def set_run_tool_context(context: RunToolContext):
    return _run_context.set(context)


def reset_run_tool_context(token) -> None:
    _run_context.reset(token)


def current_run_tool_context() -> RunToolContext:
    context = _run_context.get()
    if context is None:
        raise RuntimeError("工具调用缺少运行上下文")
    context.consume()
    return context


def build_tools(
    settings: Settings,
    client: SmartHubToolClient,
    retriever: KnowledgeRetriever,
) -> list[BaseTool]:
    async def java_tool(path: str, payload: dict[str, Any] | None = None) -> str:
        context = current_run_tool_context()
        try:
            result = await client.call(path, context.token, payload)
        except (httpx.HTTPError, ValueError) as exc:
            return json.dumps(
                {"success": False, "code": "TOOL_UNAVAILABLE", "message": "业务服务暂时不可用", "retryable": True},
                ensure_ascii=False,
            )
        refs = result.get("bizRefs") or []
        for ref in refs:
            if ref not in context.business_refs:
                context.business_refs.append(ref)
        return json.dumps(result, ensure_ascii=False, default=str)

    @tool
    async def search_platform_knowledge(query: str) -> str:
        """检索平台FAQ、规则、操作指南和客服SOP；不得用于查询实时业务状态。"""
        current_run_tool_context()
        matches = await retriever.asearch(query)
        return json.dumps(matches, ensure_ascii=False)

    @tool
    async def query_shop_by_id(shop_id: int) -> str:
        """根据店铺ID查询实时店铺详情。"""
        return await java_tool("/internal/agent-tools/shops/get", {"id": shop_id})

    @tool
    async def search_shops_by_name(keyword: str) -> str:
        """根据名称关键词搜索实时店铺，最多返回5条。"""
        return await java_tool("/internal/agent-tools/shops/search", {"keyword": keyword})

    @tool
    async def query_vouchers_by_shop_id(shop_id: int) -> str:
        """查询指定店铺当前优惠券、库存和有效期。"""
        return await java_tool("/internal/agent-tools/vouchers/by-shop", {"id": shop_id})

    @tool
    async def query_current_user_orders() -> str:
        """查询当前已认证用户最近10条优惠券订单；不要询问或传入用户ID。"""
        return await java_tool("/internal/agent-tools/orders/current")

    @tool
    async def recommend_shops_by_type(type_id: int) -> str:
        """按类型推荐评分最高的店铺；类型ID为1到10。"""
        return await java_tool("/internal/agent-tools/shops/recommend", {"typeId": type_id})

    @tool
    async def query_hot_blogs() -> str:
        """查询当前最热门的探店笔记，最多返回5条。"""
        return await java_tool("/internal/agent-tools/blogs/hot")

    @tool
    async def search_external_web(query: str) -> str:
        """仅在问题超出平台数据范围且确需最新互联网信息时搜索网页。"""
        current_run_tool_context()
        if not settings.tavily_api_key:
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
            return json.dumps({"success": False, "message": "外部搜索暂时不可用"}, ensure_ascii=False)
        safe_results = [
            {"title": item.get("title"), "url": item.get("url"), "content": item.get("content")}
            for item in body.get("results", [])[:5]
        ]
        return json.dumps({"untrustedWebResults": safe_results}, ensure_ascii=False)

    return [
        search_platform_knowledge,
        query_shop_by_id,
        search_shops_by_name,
        query_vouchers_by_shop_id,
        query_current_user_orders,
        recommend_shops_by_type,
        query_hot_blogs,
        search_external_web,
    ]
