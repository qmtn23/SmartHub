# SmartHub Agent Service

LangGraph + LangChain customer-service orchestration service. Version 2 uses a structured Qwen router and bounded sequential handoff across `general_support_agent`, `transaction_agent`, and `discovery_agent`. SmartHub remains the source of truth for authentication, conversations, tools, and business transactions.

## Local startup

1. Copy `.env.example` to `.env` and set non-empty service/model keys.
2. Start Redis Stack and Milvus: `docker compose -f compose.agent.yml up -d agent-redis milvus-standalone` from the repository root.
3. Index knowledge: `docker compose -f compose.agent.yml --profile ingest run --rm knowledge-indexer`.
4. Start the service: `docker compose -f compose.agent.yml up -d agent-service`.
5. Configure Spring with the same `AGENT_SERVICE_API_KEY` and a separate `AGENT_TOOL_JWT_SECRET` of at least 32 bytes.
6. Before deploying v2, apply `src/main/resources/db/phase2-multi-agent.sql`; it is idempotent and only adds backward-compatible columns.

The Java business API remains on the host by default. The Compose setup uses a dedicated Redis Stack instance because the LangGraph Redis checkpointer requires RedisJSON and RediSearch; the Java application can continue using its existing Redis instance.

V2 uses the `customer_service_v2` checkpoint namespace and `agent:v2:run:*` result keys. Scoped JWT values only travel in LangGraph runtime context. Successful Java tool results are cached by request and normalized tool arguments so a checkpoint retry does not repeat a completed business query.

## Router evaluation

The annotated set in `evals/router_cases.jsonl` covers single intent, follow-up, ambiguous, multi-intent, and prompt-injection requests. With model credentials configured, run:

```shell
python -m app.evals.router_eval --dataset evals/router_cases.jsonl
```

The command fails unless overall accuracy is at least 92%, transaction recall is at least 97%, and non-transaction requests route to the transaction agent less than 2% of the time.
