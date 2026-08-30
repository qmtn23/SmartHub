from langchain_openai import ChatOpenAI

from app.config import Settings


def build_chat_model(settings: Settings) -> ChatOpenAI:
    return ChatOpenAI(
        model=settings.dashscope_chat_model,
        api_key=settings.dashscope_api_key,
        base_url=settings.dashscope_base_url,
        temperature=0.2,
        timeout=20,
        max_retries=1,
    )
