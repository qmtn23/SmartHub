from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    app_name: str = "smarthub-agent-service"
    agent_service_api_key: str = ""
    smarthub_internal_base_url: str = "http://host.docker.internal:8081"

    dashscope_api_key: str = ""
    dashscope_base_url: str = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    dashscope_chat_model: str = "qwen-plus"
    dashscope_router_model: str = "qwen-plus"
    dashscope_embedding_model: str = "text-embedding-v3"
    embedding_dimension: int = 1024
    tavily_api_key: str = ""

    redis_url: str = "redis://host.docker.internal:6379/0"
    checkpoint_ttl_seconds: int = 86400
    result_ttl_seconds: int = 86400

    milvus_uri: str = "http://milvus-standalone:19530"
    milvus_token: str = ""
    milvus_collection: str = "smarthub_customer_knowledge"
    knowledge_dir: Path = Field(default=Path("/knowledge/customer-service"))
    retrieval_top_k: int = 4
    retrieval_min_score: float = 0.55

    run_timeout_seconds: int = 60
    action_confirmation_ttl_seconds: int = 600
    tool_connect_timeout_seconds: float = 1.0
    tool_read_timeout_seconds: float = 5.0
    max_agent_steps: int = 4
    graph_recursion_limit: int = 48
    max_tool_calls: int = 8
    max_tool_calls_per_task: int = 4
    max_agent_activations: int = 3
    max_parallel_agents: int = 2
    max_execution_waves: int = 3
    max_supervisor_replans: int = 1
    max_handoffs: int = 1
    router_confidence_threshold: float = 0.70


@lru_cache
def get_settings() -> Settings:
    return Settings()
