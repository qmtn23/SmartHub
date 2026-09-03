# SmartHub Agent Service

LangGraph + LangChain customer-service orchestration service. Version 4 keeps the v3 Router and Composite Supervisor, then adds a structured Resolution Planner for safe action proposals and strictly guarded human handoff. Domain Agent tools remain read-only; Spring Boot is the only component allowed to execute order mutations.

## Local startup

1. Copy `.env.example` to `.env` and set non-empty service/model keys.
2. Start Redis Stack and Milvus: `docker compose -f compose.agent.yml up -d agent-redis milvus-standalone` from the repository root.
3. Index knowledge: `docker compose -f compose.agent.yml --profile ingest run --rm knowledge-indexer`.
4. Start the service: `docker compose -f compose.agent.yml up -d agent-service`.
5. Configure Spring with the same `AGENT_SERVICE_API_KEY` and a separate `AGENT_TOOL_JWT_SECRET` of at least 32 bytes.
6. Apply `src/main/resources/db/phase2-multi-agent.sql`, `phase3-composite-supervisor.sql`, and then `phase4-safe-actions.sql` when upgrading. Enable each action separately only after shadow evaluation.

The Java business API remains on the host by default. The Compose setup uses a dedicated Redis Stack instance because the LangGraph Redis checkpointer requires RedisJSON and RediSearch; the Java application can continue using its existing Redis instance.

V4 uses `customer_service_v4`, `agent:v4:run:*`, and run-scoped checkpoint thread IDs (`chatId:runId`). This allows an interrupted action confirmation to coexist with ordinary questions in the same chat. The service accepts v2/v3-shaped requests during rolling replacement but always executes v4. Action confirmation expires after ten minutes and resumes through `POST /v1/customer-service/runs/{runId}/resume` after Java has executed the idempotent transaction.

Java action rollout flags default to disabled:

```text
CUSTOMER_ACTION_CANCEL_ENABLED=false
CUSTOMER_ACTION_REFUND_ENABLED=false
CUSTOMER_ACTION_AUTO_HANDOFF_ENABLED=false
CUSTOMER_ACTION_CONFIRMATION_TTL_SECONDS=600
```

Natural-language confirmation uses an exact server-side phrase allowlist. The LLM never decides whether a confirmation message authorizes execution and never receives a write-scoped credential.

## Router evaluation

The 360 annotated cases in `evals/router_cases.jsonl` cover single and triple intent, parallel and dependent tasks, action and handoff intent, action negation, follow-ups, ambiguity, partial failure, same-domain merging, and prompt injection. With model credentials configured, run:

```shell
python -m app.evals.router_eval --dataset evals/router_cases.jsonl
python -m app.evals.supervisor_eval --dataset evals/router_cases.jsonl
```

The router evaluation requires at least 92% overall accuracy, 90% complexity F1, 97% transaction recall, and less than 2% false transaction routing. The Supervisor evaluation requires at least 90% target accuracy and a 100% legal dependency-graph rate.
