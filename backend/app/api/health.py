"""Health check endpoint.

Reports overall status plus the connectivity of each dependent service. The
overall status is ``ok`` when every checked service is up, otherwise
``degraded`` (the process itself is still serving requests).
"""

from typing import Any

from fastapi import APIRouter
from loguru import logger

from app.config import settings
from app.core import memory, redis_client

router = APIRouter(prefix="/api", tags=["health"])


@router.get("/health")
async def health_check() -> dict[str, Any]:
    """Return backend, Redis and Supabase status."""
    services: dict[str, str] = {"backend": "ok"}

    try:
        services["redis"] = "ok" if await redis_client.ping() else "down"
    except Exception as exc:  # noqa: BLE001
        logger.warning("Redis health check failed: {}", exc)
        services["redis"] = "down"

    try:
        services["supabase"] = "ok" if memory.is_connected() else "down"
    except Exception as exc:  # noqa: BLE001
        logger.warning("Supabase health check failed: {}", exc)
        services["supabase"] = "down"

    overall = "ok" if all(status == "ok" for status in services.values()) else "degraded"

    return {
        "status": overall,
        "version": settings.VERSION,
        "services": services,
        "environment": settings.ENVIRONMENT,
    }
