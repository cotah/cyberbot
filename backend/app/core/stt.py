"""Speech-to-text using Deepgram's prerecorded REST API.

We call the stable HTTP endpoint directly with httpx instead of the Deepgram
SDK: the SDK's public API changes substantially between major versions, while
the REST contract is stable. Supported languages: English (``en``),
Portuguese (``pt``) and Spanish (``es``).
"""

import httpx
from loguru import logger

from app.config import settings

DEEPGRAM_URL = "https://api.deepgram.com/v1/listen"

# Map our short language codes to Deepgram language codes.
_LANGUAGE_MAP: dict[str, str] = {
    "en": "en",
    "pt": "pt-BR",
    "es": "es",
}


def _deepgram_language(language: str) -> str:
    """Return a Deepgram-compatible language code, defaulting to English."""
    return _LANGUAGE_MAP.get(language.lower(), "en")


async def transcribe_audio(audio_bytes: bytes, language: str = "en") -> str:
    """Transcribe raw audio bytes into text.

    Args:
        audio_bytes: The raw audio payload (WAV/MP3/Opus/etc.). Deepgram
            auto-detects the container.
        language: One of ``en``, ``pt`` or ``es``.

    Returns:
        The transcribed text (empty string if nothing was recognized).

    Raises:
        RuntimeError: If Deepgram is not configured or transcription fails.
    """
    if not settings.DEEPGRAM_API_KEY:
        raise RuntimeError("DEEPGRAM_API_KEY is not configured")

    params = {
        "model": "nova-2",
        "language": _deepgram_language(language),
        "smart_format": "true",
    }
    headers = {
        "Authorization": f"Token {settings.DEEPGRAM_API_KEY}",
        "Content-Type": "application/octet-stream",
    }

    try:
        async with httpx.AsyncClient(timeout=60.0) as client:
            response = await client.post(
                DEEPGRAM_URL,
                params=params,
                headers=headers,
                content=audio_bytes,
            )
            response.raise_for_status()
            data = response.json()

        transcript = (
            data["results"]["channels"][0]["alternatives"][0]["transcript"] or ""
        ).strip()
        logger.info("Transcribed {} chars (lang={})", len(transcript), language)
        return transcript
    except Exception as exc:  # noqa: BLE001
        logger.error("Deepgram transcription failed: {}", exc)
        raise RuntimeError(f"Transcription failed: {exc}") from exc
