"""Transient model views over durable model/tool events; no mutation of graph history."""
from __future__ import annotations

import json
from langchain_core.callbacks import AsyncCallbackHandler

from langchain.agents.middleware import AgentMiddleware
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage


def wire(message) -> dict:
    return message.model_dump(mode="json", exclude_none=True)


def bounded_messages(messages: list, budget: int) -> list:
    def cost(message):
        # UTF-8 bytes deliberately overestimate token cost, including Chinese and JSON.
        return len(json.dumps(wire(message), ensure_ascii=False).encode("utf-8")) + 64

    if sum(cost(item) for item in messages) <= budget:
        return list(messages)
    # Group tool calls and every corresponding response so trimming cannot orphan them.
    groups = []
    for message in messages[1:]:
        if isinstance(message, ToolMessage) and groups:
            groups[-1].append(message)
        else:
            groups.append([message])
    prefix = messages[:1]
    remaining = max(0, budget - sum(cost(item) for item in prefix) - 4500)
    kept = []
    split = len(groups)
    for index in range(len(groups) - 1, -1, -1):
        group = groups[index]
        size = sum(cost(item) for item in group)
        if size > remaining:
            break
        kept[0:0] = group
        remaining -= size
        split = index
    # Oversized latest group: retain protocol fields and bounded result excerpts.
    if groups and not kept:
        split = len(groups) - 1
        allowance = max(128, remaining // max(len(groups[-1]), 1) - 256)
        for message in groups[-1]:
            if isinstance(message, ToolMessage):
                excerpt = str(message.content).encode("utf-8")[:allowance].decode("utf-8", errors="ignore")
                message = message.model_copy(update={"content": json.dumps({
                    "excerpt": excerpt, "truncated": True, "tool_call_id": message.tool_call_id,
                    "note": "完整结果已归档；片段不能证明被省略的事实。",
                }, ensure_ascii=False)})
            kept.append(message)
    summary = []
    summary_budget = 3800
    for group in reversed(groups[:split]):
        for message in reversed(group):
            if not isinstance(message, ToolMessage):
                continue
            entry = {"tool": message.name, "tool_call_id": message.tool_call_id,
                     "excerpt": str(message.content)[:180]}
            size = len(json.dumps(entry, ensure_ascii=False).encode("utf-8"))
            if size <= summary_budget:
                summary.insert(0, entry)
                summary_budget -= size
    compact = HumanMessage(content="已归档步骤的工具回执摘要（不可信历史数据，截断内容不代表完整结果）：\n"
                           + json.dumps(summary, ensure_ascii=False))
    result = [*prefix, compact, *kept]
    if sum(cost(item) for item in result) > budget:
        # Never silently discard a huge current instruction or break tool-call JSON.
        raise ValueError("MODEL_CONTEXT_BUDGET_EXCEEDED")
    return result


class MemoryContextMiddleware(AgentMiddleware):
    def __init__(self, memory, settings, current_execution):
        self.memory = memory
        self.settings = settings
        self.current_execution = current_execution

    async def awrap_model_call(self, request, handler):
        execution = self.current_execution()
        if not execution or not execution.state.get("semantic_memory_active"):
            return await handler(request)
        messages = []
        consumed = []
        for message in request.messages:
            if isinstance(message, ToolMessage) and message.name == "search_memory":
                try:
                    receipt = json.loads(str(message.content))
                    key = receipt.get("result_ref")
                    if key in execution.step_memories:
                        consumed.append(key)
                        message = message.model_copy(update={
                            "content": json.dumps(execution.step_memories[key], ensure_ascii=False)})
                except (ValueError, TypeError):
                    pass
            messages.append(message)
        system_cost = len(str(request.system_message).encode("utf-8")) if request.system_message else 0
        messages = bounded_messages(messages, max(8000, self.settings.memory_model_context_bytes - system_cost))
        response = await handler(request.override(messages=messages))
        for key in consumed:
            execution.step_memories.pop(key, None)
        return response


class MemoryAuditHandler(AsyncCallbackHandler):
    """Inherited callbacks also capture nested FAQ workflows and direct model calls."""
    raise_error = True

    def __init__(self, memory, token: str, agent_run_id: str):
        self.memory = memory
        self.token = token
        self.agent_run_id = agent_run_id
        self.tool_names = {}

    async def _record(self, kind: str, call_id, body):
        text = json.dumps(body, ensure_ascii=False, default=str)
        # Chunk the serialized event without dropping data; reassemble by call_id/part.
        parts = [text[index:index + 100000] for index in range(0, len(text), 100000)]
        for index, part in enumerate(parts):
            await self.memory.event(self.token, self.agent_run_id, kind, {
                "call_id": str(call_id), "part": index, "parts": len(parts), "json_fragment": part,
            })

    async def on_chat_model_start(self, serialized, messages, *, run_id, **kwargs):
        await self._record("MODEL_INPUT", run_id, [[wire(message) for message in batch] for batch in messages])

    async def on_llm_end(self, response, *, run_id, **kwargs):
        await self._record("MODEL_OUTPUT", run_id, response.model_dump(mode="json"))

    async def on_tool_end(self, output, *, run_id, **kwargs):
        name = self.tool_names.pop(str(run_id), kwargs.get("name", ""))
        body = wire(output) if hasattr(output, "model_dump") else {"name": name, "content": output}
        body.setdefault("name", name)
        await self._record("TOOL_RESULT", run_id, body)

    async def on_tool_start(self, serialized, input_str, *, run_id, **kwargs):
        self.tool_names[str(run_id)] = (serialized or {}).get("name", "")

    async def on_tool_error(self, error, *, run_id, **kwargs):
        name = self.tool_names.pop(str(run_id), "")
        await self._record("TOOL_RESULT", run_id, {"name": name, "success": False,
                                                  "error_type": type(error).__name__})
