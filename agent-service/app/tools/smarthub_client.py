import asyncio
from typing import Any

import httpx

from app.config import Settings


class SmartHubToolClient:
    def __init__(self, settings: Settings):
        timeout = httpx.Timeout(
            connect=settings.tool_connect_timeout_seconds,
            read=settings.tool_read_timeout_seconds,
            write=settings.tool_read_timeout_seconds,
            pool=settings.tool_connect_timeout_seconds,
        )
        self._client = httpx.AsyncClient(
            base_url=settings.smarthub_internal_base_url.rstrip("/"),
            timeout=timeout,
        )

    async def call(self, path: str, token: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
        headers = {"Authorization": f"Bearer {token}"}
        last_error: Exception | None = None
        for attempt in range(2):
            try:
                response = await self._client.post(path, headers=headers, json=payload)
                if response.status_code == 429 or response.status_code >= 500:
                    if attempt == 0:
                        await asyncio.sleep(0.1)
                        continue
                response.raise_for_status()
                body = response.json()
                if not isinstance(body, dict):
                    raise ValueError("SmartHub tool returned a non-object response")
                return body
            except (httpx.TimeoutException, httpx.NetworkError) as exc:
                last_error = exc
                if attempt == 0:
                    await asyncio.sleep(0.1)
                    continue
                raise
        assert last_error is not None
        raise last_error

    async def aclose(self) -> None:
        await self._client.aclose()
