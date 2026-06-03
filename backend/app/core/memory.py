"""Conversation history and long-term memory backed by Supabase.

Tables used (PostgreSQL + pgvector):
- ``conversations`` : (session_id, role, content, created_at)
- ``memories``      : (content, embedding vector, created_at)
- ``device_config`` : (key, value)

Vector similarity search relies on a Postgres RPC function ``match_memories``
(``query_embedding`` vector, ``match_count`` int) returning the closest rows.

The Supabase Python client is synchronous, so blocking calls are off-loaded to
a worker thread to avoid stalling the async event loop. Every function fails
soft so a missing database degrades features instead of crashing the API.
"""

import asyncio
from typing import Any, Optional

from loguru import logger

from app.config import settings

# The Supabase client type is only needed for annotations; import lazily so a
# missing/broken dependency never blocks application startup.
_client: Optional[Any] = None


def connect() -> None:
    """Create the shared Supabase client. Never raises on failure."""
    global _client
    if not settings.SUPABASE_URL or not settings.SUPABASE_KEY:
        logger.warning("Supabase credentials missing; memory features disabled")
        _client = None
        return
    try:
        from supabase import create_client

        _client = create_client(settings.SUPABASE_URL, settings.SUPABASE_KEY)
        logger.info("Supabase connected")
    except Exception as exc:  # noqa: BLE001
        _client = None
        logger.warning("Supabase connection failed: {}", exc)


def disconnect() -> None:
    """Drop the shared Supabase client reference."""
    global _client
    _client = None
    logger.info("Supabase client released")


def is_connected() -> bool:
    """Return True if a Supabase client has been initialized."""
    return _client is not None


async def save_message(session_id: str, role: str, content: str) -> None:
    """Persist a single conversation message."""
    if _client is None:
        return

    def _op() -> None:
        _client.table("conversations").insert(
            {"session_id": session_id, "role": role, "content": content}
        ).execute()

    try:
        await asyncio.to_thread(_op)
    except Exception as exc:  # noqa: BLE001
        logger.warning("save_message failed: {}", exc)


async def get_history(session_id: str, limit: int = 20) -> list[dict[str, str]]:
    """Return the most recent messages for a session in chronological order."""
    if _client is None:
        return []

    def _op() -> list[dict[str, str]]:
        response = (
            _client.table("conversations")
            .select("role, content, created_at")
            .eq("session_id", session_id)
            .order("created_at", desc=True)
            .limit(limit)
            .execute()
        )
        rows = response.data or []
        # Reverse so the oldest message comes first (chronological order).
        rows.reverse()
        return [{"role": row["role"], "content": row["content"]} for row in rows]

    try:
        return await asyncio.to_thread(_op)
    except Exception as exc:  # noqa: BLE001
        logger.warning("get_history failed: {}", exc)
        return []


async def save_memory(content: str, embedding: list[float]) -> None:
    """Persist a long-term memory together with its embedding vector."""
    if _client is None:
        return

    def _op() -> None:
        _client.table("memories").insert(
            {"content": content, "embedding": embedding}
        ).execute()

    try:
        await asyncio.to_thread(_op)
    except Exception as exc:  # noqa: BLE001
        logger.warning("save_memory failed: {}", exc)


async def search_memories(
    embedding: list[float], limit: int = 5
) -> list[dict[str, Any]]:
    """Return the most similar memories via pgvector cosine distance."""
    if _client is None:
        return []

    def _op() -> list[dict[str, Any]]:
        response = _client.rpc(
            "match_memories",
            {"query_embedding": embedding, "match_count": limit},
        ).execute()
        return response.data or []

    try:
        return await asyncio.to_thread(_op)
    except Exception as exc:  # noqa: BLE001
        logger.warning("search_memories failed: {}", exc)
        return []


async def get_config(key: str) -> Optional[Any]:
    """Return a value from the ``device_config`` table, or None if absent."""
    if _client is None:
        return None

    def _op() -> Optional[Any]:
        response = (
            _client.table("device_config")
            .select("value")
            .eq("key", key)
            .limit(1)
            .execute()
        )
        rows = response.data or []
        return rows[0]["value"] if rows else None

    try:
        return await asyncio.to_thread(_op)
    except Exception as exc:  # noqa: BLE001
        logger.warning("get_config failed for {}: {}", key, exc)
        return None
