# SmartHub Agent Service

LangGraph + LangChain customer-service orchestration service. SmartHub remains the source of truth for authentication, conversations, tools, and business transactions.

## Local startup

1. Copy `.env.example` to `.env` and set non-empty service/model keys.
2. Start Redis Stack and Milvus: `docker compose -f compose.agent.yml up -d agent-redis milvus-standalone` from the repository root.
3. Index knowledge: `docker compose -f compose.agent.yml --profile ingest run --rm knowledge-indexer`.
4. Start the service: `docker compose -f compose.agent.yml up -d agent-service`.
5. Configure Spring with the same `AGENT_SERVICE_API_KEY` and a separate `AGENT_TOOL_JWT_SECRET` of at least 32 bytes.

The Java business API remains on the host by default. The Compose setup uses a dedicated Redis Stack instance because the LangGraph Redis checkpointer requires RedisJSON and RediSearch; the Java application can continue using its existing Redis instance.
