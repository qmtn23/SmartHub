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
class SharedToolBudget:
    max_calls: int
    call_count: int = 0

    def consume(self) -> None:
        self.call_count += 1
        if self.call_count > self.max_calls:
            raise RuntimeError("本轮工具调用次数已达上限")


@dataclass
class RunToolContext:
    active_agent: str
    max_calls: int
    token: str | None = None
    tokens: dict[str, str | None] = field(default_factory=dict)
    shared_budget: SharedToolBudget | None = None
    request_id: str = ""
    result_cache: Any | None = None
    result_ttl_seconds: int = 86400
    graph_version: str = "v5"
    consultation_context: dict[str, Any] = field(default_factory=dict)
    call_count: int = 0
    business_refs: list[dict[str, Any]] = field(default_factory=list)
    handoff_request: dict[str, Any] | None = None
    allowed_scenes: set[str] = field(default_factory=set)

    def consume(self, tool_name: str) -> None:
        self.call_count += 1
        tool_calls.add(1, {"tool": tool_name, "agent": self.active_agent})
        if self.call_count > self.max_calls:
            raise RuntimeError("本轮工具调用次数已达上限")
        if self.shared_budget is not None:
            self.shared_budget.consume()

    def token_for(self, tool_name: str) -> str | None:
        if tool_name == "query_current_user_orders":
            preferred = "refund_agent" if self.active_agent == "after_sales_advisor_agent" else "order_agent"
            return self.tokens.get(preferred) or self.tokens.get("transaction_agent") or self.token
        if tool_name in {"query_vouchers_by_shop_id", "query_current_voucher"}:
            return self.tokens.get("voucher_agent") or self.tokens.get("transaction_agent") or self.token
        if tool_name in {"search_shops_by_name", "recommend_shops_by_type"}:
            return self.tokens.get("shop_agent") or self.tokens.get("discovery_agent") or self.token
        if tool_name == "query_hot_blogs":
            return self.tokens.get("content_agent") or self.tokens.get("discovery_agent") or self.token
        if tool_name in {"query_shop_by_id", "query_current_shop"}:
            return (
                self.tokens.get("shop_agent") or self.tokens.get("voucher_agent")
                or self.tokens.get("order_agent") or self.tokens.get("discovery_agent")
                or self.tokens.get("transaction_agent") or self.token
            )
        return self.token


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


def peek_run_tool_context() -> RunToolContext:
    """Read trusted runtime context without charging a second nested-tool call."""
    context = _run_context.get()
    if context is None:
        raise RuntimeError("工具调用缺少运行上下文")
    return context


def build_agent_tools(
    settings: Settings,
    client: SmartHubToolClient,
    retriever: KnowledgeRetriever,
    merchant_index=None,
) -> dict[str, list[BaseTool]]:
    async def java_tool(tool_name: str, path: str, payload: dict[str, Any] | None = None) -> str:
        context = current_run_tool_context(tool_name)
        required_scene = {
            "search_shops_by_name": "PRE_SALES",
            "recommend_shops_by_type": "PRE_SALES",
            "query_vouchers_by_shop_id": "PRE_SALES",
            "query_current_voucher": "PRE_SALES",
            "query_current_shop": "PRE_SALES",
            "query_hot_blogs": "PRE_SALES",
            "query_current_user_orders": "AFTER_SALES",
        }.get(tool_name)
        if required_scene and context.allowed_scenes and required_scene not in context.allowed_scenes:
            raise PermissionError(f"{tool_name} is not available in the routed scene")
        token = context.token_for(tool_name)
        if not token:
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
                result = await client.call(path, token, payload)
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
        if context.active_agent not in {
            "general_support_agent", "customer_service_master_agent",
            "after_sales_advisor_agent", "complaint_agent", "product_faq_agent",
        }:
            raise PermissionError("当前Agent无权检索平台知识")
        allowed_categories = {
            "complaint-handling", "platform-faq", "refund-process", "shop-recommendation", "voucher-guide"
        }
        if category and category not in allowed_categories:
            tool_failures.add(1, {"tool": "search_platform_knowledge", "agent": context.active_agent})
            return json.dumps({"success": False, "code": "INVALID_KNOWLEDGE_CATEGORY"}, ensure_ascii=False)
        limit = settings.retrieval_top_k
        matches = await retriever.asearch(query, [category] if category else None, limit=limit)
        return json.dumps(matches, ensure_ascii=False)

    @tool
    async def search_merchant_faq(query: str, validate_ids: list[dict[str, Any]] | None = None) -> str:
        """内部商品FAQ检索/版本校验，只允许固定工作流调用；身份和商品由运行时提供。"""
        context = current_run_tool_context("search_merchant_faq")
        if context.active_agent != "product_faq_agent":
            raise PermissionError("Only the product FAQ Agent may read merchant knowledge")
        token = context.tokens.get("faq_knowledge")
        if not token:
            raise PermissionError("Missing scoped FAQ credential")
        if not context.consultation_context.get("shopId"):
            return json.dumps({"success": True, "entries": []})
        if validate_ids is None:
            if merchant_index is None:
                raise RuntimeError("Merchant FAQ index not configured")
            candidates = await merchant_index.search(query, context.consultation_context)
        else:
            candidates = validate_ids
        if len(candidates) > 50:
            raise ValueError("Too many FAQ candidates")
        result = await client.call("/internal/agent-tools/faq/validate", token,
            {"candidates": [{"faqId": x["faqId"], "revision": x["revision"]} for x in candidates]})
        # Compare server-authorized context before returning any canonical content.
        trusted = result.get("context", {})
        if any(trusted.get(k) != context.consultation_context.get(k) for k in ("shopId", "voucherId", "categoryId")):
            raise PermissionError("FAQ context mismatch")
        result.pop("context", None)
        retrieval_metadata = {
            (str(item["faqId"]), int(item["revision"])): item
            for item in candidates
            if item.get("faqId") is not None and item.get("revision") is not None
        }
        for entry in result.get("entries", []):
            metadata = retrieval_metadata.get((str(entry.get("faqId")), int(entry.get("revision", -1))))
            if metadata:
                entry["retrievalScore"] = metadata.get("score")
                entry["vectorScore"] = metadata.get("vectorScore")
                entry["lexicalScore"] = metadata.get("lexicalScore")
                entry["retrievalChannels"] = metadata.get("retrievalChannels", [])
        if context.consultation_context.get("voucherId") is not None:
            ref = {"bizType": "VOUCHER", "bizId": context.consultation_context["voucherId"]}
            if ref not in context.business_refs:
                context.business_refs.append(ref)
        return json.dumps(result, ensure_ascii=False)

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
    async def query_current_voucher() -> str:
        """按可信消息上下文查询当前优惠券；模型不传商品或店铺ID。"""
        return await java_tool("query_current_voucher", "/internal/agent-tools/context/current-voucher")

    @tool
    async def query_current_shop() -> str:
        """按可信消息上下文查询当前店铺；模型不传店铺ID。"""
        return await java_tool("query_current_shop", "/internal/agent-tools/context/current-shop")

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
        "general_support_agent": [search_platform_knowledge, request_handoff],
        "product_faq_agent": [
            search_merchant_faq, query_current_voucher,
            query_current_shop, search_platform_knowledge,
        ],
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
