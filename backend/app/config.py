"""Application configuration.

All configuration is loaded from environment variables (or a local ``.env``
file) using ``pydantic-settings``. Secrets default to empty strings so the
application can boot locally for development and health checks without real
credentials. Each integration validates its own required secret at call time.
"""

from functools import lru_cache

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Strongly typed application settings loaded from the environment."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=True,
        extra="ignore",
    )

    # --- AI / LLM ---
    ANTHROPIC_API_KEY: str = ""
    OPENAI_API_KEY: str = ""
    PERPLEXITY_API_KEY: str = ""

    # --- Speech to text ---
    DEEPGRAM_API_KEY: str = ""

    # --- Text to speech ---
    ELEVENLABS_API_KEY: str = ""
    ELEVENLABS_VOICE_ID: str = ""

    # --- Database / memory ---
    SUPABASE_URL: str = ""
    SUPABASE_KEY: str = ""

    # --- Cache / state ---
    REDIS_URL: str = "redis://localhost:6379/0"

    # --- Monitoring ---
    SENTRY_DSN: str = ""

    # --- App ---
    ENVIRONMENT: str = "development"
    APP_NAME: str = "CyberBot AI"
    VERSION: str = "1.0.0"

    @property
    def is_production(self) -> bool:
        """Return True when running in the production environment."""
        return self.ENVIRONMENT.lower() == "production"


@lru_cache
def get_settings() -> Settings:
    """Return a cached singleton ``Settings`` instance."""
    return Settings()


# Convenient module-level singleton.
settings: Settings = get_settings()
