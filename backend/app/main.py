"""CyberBot AI - FastAPI application entrypoint."""

from contextlib import asynccontextmanager
from typing import AsyncIterator

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from loguru import logger

from app.api import audio, conversation, health
from app.config import settings
from app.core import memory, redis_client


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    """Connect external services on startup, disconnect on shutdown."""
    logger.info("Starting {} v{} ({})", settings.APP_NAME, settings.VERSION, settings.ENVIRONMENT)

    # Connect dependencies. Both connect functions fail soft.
    await redis_client.connect()
    memory.connect()

    yield

    # Cleanly release resources.
    await redis_client.disconnect()
    memory.disconnect()
    logger.info("{} shut down cleanly", settings.APP_NAME)


def _configure_sentry() -> None:
    """Initialize Sentry only in production with a configured DSN."""
    if not (settings.is_production and settings.SENTRY_DSN):
        return
    try:
        import sentry_sdk

        sentry_sdk.init(
            dsn=settings.SENTRY_DSN,
            environment=settings.ENVIRONMENT,
            traces_sample_rate=0.2,
        )
        logger.info("Sentry initialized")
    except Exception as exc:  # noqa: BLE001
        logger.warning("Sentry initialization failed: {}", exc)


_configure_sentry()

app = FastAPI(
    title=settings.APP_NAME,
    version=settings.VERSION,
    description="Backend for CyberBot AI - a holographic Android AI assistant.",
    lifespan=lifespan,
)

# Open CORS for development. Tighten allowed origins before production.
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Register routers.
app.include_router(health.router)
app.include_router(audio.router)
app.include_router(conversation.router)


@app.get("/")
async def root() -> dict[str, str]:
    """Simple root endpoint with basic service info."""
    return {
        "name": settings.APP_NAME,
        "version": settings.VERSION,
        "status": "online",
    }
