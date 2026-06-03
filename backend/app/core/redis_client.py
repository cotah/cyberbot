"""Redis client for device state and generic caching.

State is stored per ``device_id`` with a one hour TTL. The module exposes a
small set of async helper functions and manages a single shared connection
created during application startup. All operations fail soft: if Redis is not
available they log a warning and return safe defaults instead of raising, so a
missing cache never takes the whole API down.
"""

import json
from typing import Any, Optional

import redis.asyncio as aioredis
from loguru import logger

from app.config import settings

# Device state lives for one hour before it is considered stale.
STATE_TTL_SECONDS: int = 3600

_client: Optional["aioredis.Redis"] = None


async def connect() -> None:
    """Create the shared Redis connection. Never raises on failure."""
    global _client
    try:
        _client = aioredis.from_url(
            settings.REDIS_URL,
            encoding="utf-8",
            decode_responses=True,
        )
        await _client.ping()
        logger.info("Redis connected at {}", settings.REDIS_URL)
    except Exception as exc:  # noqa: BLE001 - we want to degrade gracefully
        _client = None
        logger.warning("Redis connection failed: {}", exc)


async def disconnect() -> None:
    """Close the shared Redis connection if it exists."""
    global _client
    if _client is not None:
        try:
            await _client.aclose()
            logger.info("Redis disconnected")
        except Exception as exc:  # noqa: BLE001
            logger.warning("Error while closing Redis: {}", exc)
        finally:
            _client = None


async def ping() -> bool:
    """Return True when Redis answers a ping, False otherwise."""
    if _client is None:
        return False
    try:
        return bool(await _client.ping())
    except Exception as exc:  # noqa: BLE001
        logger.warning("Redis ping failed: {}", exc)
        return False


def is_connected() -> bool:
    """Return True if a Redis client has been initialized."""
    return _client is not None


def _state_key(device_id: str) -> str:
    return f"device:state:{device_id}"


async def get_state(device_id: str) -> Optional[dict[str, Any]]:
    """Return the current state for a device, or None if unavailable."""
    if _client is None:
        return None
    try:
        raw = await _client.get(_state_key(device_id))
        return json.loads(raw) if raw else None
    except Exception as exc:  # noqa: BLE001
        logger.warning("get_state failed for {}: {}", device_id, exc)
        return None


async def set_state(device_id: str, state: dict[str, Any]) -> None:
    """Persist a device state with a one hour TTL."""
    if _client is None:
        return
    try:
        await _client.set(
            _state_key(device_id),
            json.dumps(state),
            ex=STATE_TTL_SECONDS,
        )
    except Exception as exc:  # noqa: BLE001
        logger.warning("set_state failed for {}: {}", device_id, exc)


async def get_cache(key: str) -> Optional[Any]:
    """Return a cached JSON value, or None if missing/unavailable."""
    if _client is None:
        return None
    try:
        raw = await _client.get(f"cache:{key}")
        return json.loads(raw) if raw else None
    except Exception as exc:  # noqa: BLE001
        logger.warning("get_cache failed for {}: {}", key, exc)
        return None


async def set_cache(key: str, value: Any, ttl_seconds: int) -> None:
    """Cache a JSON-serializable value with an explicit TTL in seconds."""
    if _client is None:
        return
    try:
        await _client.set(f"cache:{key}", json.dumps(value), ex=ttl_seconds)
    except Exception as exc:  # noqa: BLE001
        logger.warning("set_cache failed for {}: {}", key, exc)


async def delete_cache(key: str) -> None:
    """Delete a cached value. No-op if Redis is unavailable."""
    if _client is None:
        return
    try:
        await _client.delete(f"cache:{key}")
    except Exception as exc:  # noqa: BLE001
        logger.warning("delete_cache failed for {}: {}", key, exc)
