# SmartHub Agent Service

LangGraph + LangChain customer-service orchestration service. Version 3 keeps a Router fast path for simple requests and uses a Composite Supervisor for dependent or parallel work across `general_support_agent`, `transaction_agent`, and `discovery_agent`. SmartHub remains the source of truth for authentication, conversations, tools, and business transactions.

## Local startup

1. Copy `.env.example` to `.env` and set non-empty service/model keys.
2. Start Redis Stack and Milvus: `docker compose -f compose.agent.yml up -d agent-redis milvus-standalone` from the repository root.
3. Index knowledge: `docker compose -f compose.agent.yml --profile ingest run --rm knowledge-indexer`.
4. Start the service: `docker compose -f compose.agent.yml up -d agent-service`.
5. Configure Spring with the same `AGENT_SERVICE_API_KEY` and a separate `AGENT_TOOL_JWT_SECRET` of at least 32 bytes.
6. Apply `src/main/resources/db/phase2-multi-agent.sql` when upgrading from v1, then apply `src/main/resources/db/phase3-composite-supervisor.sql`. Both migrations are idempotent and only add backward-compatible columns.

The Java business API remains on the host by default. The Compose setup uses a dedicated Redis Stack instance because the LangGraph Redis checkpointer requires RedisJSON and RediSearch; the Java application can continue using its existing Redis instance.

V3 uses the `customer_service_v3` checkpoint namespace and `agent:v3:run:*` result/tool keys. It accepts a v2-shaped request during rolling replacement but always executes the v3 graph. Scoped JWT values only travel in LangGraph runtime context. Complex requests execute at most three domain Agents, with a maximum concurrency of two and at most one Supervisor replan. Successful Java tool results are cached by request and normalized tool arguments so a checkpoint retry does not repeat a completed business query.

## Router evaluation

The 240+ annotated cases in `evals/router_cases.jsonl` cover single and triple intent, parallel and dependent tasks, follow-ups, ambiguity, partial failure, same-domain merging, and prompt injection. With model credentials configured, run:

```shell
python -m app.evals.router_eval --dataset evals/router_cases.jsonl
python -m app.evals.supervisor_eval --dataset evals/router_cases.jsonl
```

The router evaluation requires at least 92% overall accuracy, 90% complexity F1, 97% transaction recall, and less than 2% false transaction routing. The Supervisor evaluation requires at least 90% target accuracy and a 100% legal dependency-graph rate.
