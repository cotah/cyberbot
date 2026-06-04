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


async def generate_embedding(text: str) -> Optional[list[float]]:
    """Generate a 1536-dim embedding for ``text`` via OpenAI.

    Uses ``text-embedding-3-small``. Returns None on failure or when OpenAI is
    not configured, so callers can degrade gracefully.
    """
    if not settings.OPENAI_API_KEY:
        logger.warning("OPENAI_API_KEY missing; cannot generate embedding")
        return None

    try:
        from openai import OpenAI

        client = OpenAI(api_key=settings.OPENAI_API_KEY)

        def _op() -> list[float]:
            response = client.embeddings.create(
                model="text-embedding-3-small",
                input=text,
            )
            return response.data[0].embedding

        return await asyncio.to_thread(_op)
    except Exception as exc:  # noqa: BLE001
        logger.warning("generate_embedding failed: {}", exc)
        return None


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


async def save_memory(content: str, category: str = "general") -> None:
    """Generate an embedding for ``content`` and persist it as a memory."""
    if _client is None:
        return

    embedding = await generate_embedding(content)
    if embedding is None:
        logger.warning("save_memory skipped (embedding unavailable)")
        return

    def _op() -> None:
        _client.table("memories").insert(
            {"content": content, "embedding": embedding, "category": category}
        ).execute()

    try:
        await asyncio.to_thread(_op)
        logger.info("Saved memory ({} chars, category={})", len(content), category)
    except Exception as exc:  # noqa: BLE001
        logger.warning("save_memory failed: {}", exc)


async def search_memories(
    query_text: str, limit: int = 5, match_threshold: float = 0.3
) -> list[dict[str, Any]]:
    """Return memories most similar to ``query_text`` via pgvector.

    Generates the query embedding, then calls the ``match_memories`` RPC. Returns
    rows ordered by similarity (highest first); empty list on any failure.

    ``match_threshold`` defaults to 0.3 because text-embedding-3-small similarity
    scores for related sentences typically sit in the 0.4-0.6 range; the RPC's
    own 0.7 default is too strict and filters out useful matches.
    """
    if _client is None:
        return []

    embedding = await generate_embedding(query_text)
    if embedding is None:
        return []

    # The RPC takes a float8[] (cast to vector internally), so the plain list
    # serializes cleanly as a JSON number array via PostgREST.
    def _op() -> list[dict[str, Any]]:
        response = _client.rpc(
            "match_memories",
            {
                "query_embedding": embedding,
                "match_threshold": match_threshold,
                "match_count": limit,
            },
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
