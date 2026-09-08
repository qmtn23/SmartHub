# SmartHub Agent Service

LangGraph + LangChain customer-service orchestration service. Version 5 uses a conservative keyword-weight router before the Qwen structured-output fallback router, then gives the permitted `PRE_SALES`/`AFTER_SALES` scene set to one Customer Service Master. The Master mixes ordinary query tools with bounded sub-Agents for product FAQ, recommendation, after-sales analysis, and complaints. Agent tools remain read-only; Spring Boot alone executes confirmed order mutations.

FAQ and policy answers use only the versioned SmartHub knowledge base in Milvus. The service does not expose internet or external-web search tools.

`answer_product_faq` exposes a bounded product FAQ Agent-as-Tool. The Agent can combine a deterministic `ProductFaqWorkflow` evidence tool with context-bound current-voucher/current-shop lookups and platform voucher guidance. Product identity is injected from trusted consultation context, and the Agent must return a structured, evidence-linked result. No valid evidence means clarification/no evidence, never a model-knowledge answer. See [Product FAQ deployment and API guide](docs/product-faq.md).

The FAQ ingestion format is `markdown-v2`; run the knowledge indexer once after deployment so existing documents are re-chunked along Markdown heading boundaries.

The execution hierarchy is:

```text
Keyword Router -> low-confidence Qwen Router
  -> Customer Service Master
       -> Product FAQ / Recommendation / After-sales Advisor / Complaint Agent-as-Tool
            -> Product FAQ deterministic evidence workflow
       -> ordinary business and knowledge tools
       -> terminal Reply Generator tool
  -> Human handoff guard
```

There are no pre-sales, after-sales, or cross-scene intermediate Masters. Scene routing is an authorization boundary; one top-level Master decomposes both single- and cross-scene requests and invokes domain Agents as tools. Explicit human handoff is exclusive and bypasses all business Agents. Ordinary Java/RAG tool calls share one per-run budget whether invoked by the Master directly or from a child Agent.

Spring Boot contains no LangChain4j model client. Session summarization and long-term-memory merging are model capabilities of this Python service, exposed only through service-key-protected internal endpoints.

## Local startup

1. Copy `.env.example` to `.env` and set non-empty service/model keys.
2. Start Redis Stack and Milvus: `docker compose -f compose.agent.yml up -d agent-redis milvus-standalone` from the repository root.
3. Index knowledge: `docker compose -f compose.agent.yml --profile ingest run --rm knowledge-indexer`.
4. Apply the additive `src/main/resources/db/product-faq.sql` migration once, then initialize the merchant collection: `docker compose -f compose.agent.yml --profile merchant-faq run --rm merchant-faq-indexer python -m app.rag.merchant_worker --setup-only`. Start the service: `docker compose -f compose.agent.yml up -d agent-service`.
5. Configure Spring with the same `AGENT_SERVICE_API_KEY` and a separate `AGENT_TOOL_JWT_SECRET` of at least 32 bytes.
6. Apply `src/main/resources/db/phase2-multi-agent.sql`, `phase3-composite-supervisor.sql`, `phase4-safe-actions.sql`, and then `phase5-cascade-router.sql` when upgrading. Enable each action separately only after shadow evaluation.

The Java business API remains on the host by default. The Compose setup uses a dedicated Redis Stack instance because the LangGraph Redis checkpointer requires RedisJSON and RediSearch; the Java application can continue using its existing Redis instance.

V5 uses `customer_service_v5`, `agent:v5:run:*`, and run-scoped checkpoint thread IDs (`chatId:runId`). This allows an interrupted action confirmation to coexist with ordinary questions in the same chat. The service accepts v2-v5-shaped requests during rolling replacement but always executes v5. Action confirmation expires after ten minutes and resumes through `POST /v1/customer-service/runs/{runId}/resume` after Java has executed the idempotent transaction.

Keyword rules live in `app/routing/router_rules.yaml`. A rule result is accepted only when it reaches the configured threshold and margin; negative phrases, short contextual follow-ups, and unresolved scene conflicts fall through to the LLM. The response audit identifies `routeSource=RULE|LLM`, the rule version, scores, and matched rule IDs.

Java action rollout flags default to disabled:

```text
CUSTOMER_ACTION_CANCEL_ENABLED=false
CUSTOMER_ACTION_REFUND_ENABLED=false
CUSTOMER_ACTION_AUTO_HANDOFF_ENABLED=false
CUSTOMER_ACTION_CONFIRMATION_TTL_SECONDS=600
```

Natural-language confirmation uses an exact server-side phrase allowlist. The LLM never decides whether a confirmation message authorizes execution and never receives a write-scoped credential.

## Router and Master evaluation

The annotated cases in `evals/router_cases.jsonl` cover scene routing, ordinary-tool selection, Agent-tool selection, action and handoff intent, ambiguity, multi-scene requests, and prompt injection. With model credentials configured, run:

```shell
python -m app.evals.router_eval --dataset evals/router_cases.jsonl
python -m app.evals.supervisor_eval --dataset evals/router_cases.jsonl
```

The Router evaluation requires at least 92% scene accuracy, 99% explicit-human recall, and 97% after-sales recall. The Master evaluation requires at least 85% first-tool accuracy and a 100% legal tool-call rate.


## Task memory and explicit user profiles

Task memory is stored in MySQL per user and platform scope, with asynchronous incremental extraction, exact message receipts and version CAS. Run-scoped Redis checkpoints remain separate. Apply `src/main/resources/db/task-memory.sql` before enabling Java's `CUSTOMER_MEMORY_ENABLED` flag.

The optional user-profile layer consumes transactional task-memory events and records only preferences explicitly stated by the user. It supports evidence-backed field patches, expiry and clear barriers, and sends scoped `userProfile` context to the Master and domain Agents. Apply `src/main/resources/db/user-profile.sql` and enable `CUSTOMER_PROFILE_ENABLED` after the Python profile endpoint is deployed. Both Java flags default to false. The dedicated model is configurable through `DASHSCOPE_PROFILE_MODEL`.

See [用户画像部署与实现说明](docs/user-profile.md) for activation, data flow, update semantics and retry operations.

The complete three-level design is documented in [三级记忆架构](docs/memory-architecture.md): Redis ZSet working memory with asynchronous writes and rolling windows, MySQL task-oriented episodic memory and explicit user profiles, plus a dedicated thread pool for synchronous Milvus operations.
