"""Text-to-speech synthesis.

Provider selection follows the environment:
- development -> OpenAI TTS (cheap, fast to iterate on)
- production  -> ElevenLabs (higher quality, configurable voice)

Both providers return MP3 audio bytes. SDKs are imported lazily so missing
dependencies never block startup. ``detect_language`` provides a dependency
free best-effort guess between English, Portuguese and Spanish.
"""

import asyncio

from loguru import logger

from app.config import settings

# Lightweight, dependency-free language hints. Not perfect, but good enough to
# pick a voice/model when the caller did not specify a language explicitly.
_PT_MARKERS: tuple[str, ...] = (
    "ã", "õ", "ç", "você", "não", "obrigado", "olá", "está", "também",
    "porque", "bom dia", "tudo bem",
)
_ES_MARKERS: tuple[str, ...] = (
    "ñ", "¿", "¡", "hola", "gracias", "qué", "cómo", "está", "porque",
    "usted", "buenos días", "por favor",
)


def detect_language(text: str) -> str:
    """Best-effort detection between ``en``, ``pt`` and ``es``.

    Defaults to English when no strong signal is found.
    """
    lowered = f" {text.lower()} "
    pt_score = sum(1 for marker in _PT_MARKERS if marker in lowered)
    es_score = sum(1 for marker in _ES_MARKERS if marker in lowered)

    if pt_score == 0 and es_score == 0:
        return "en"
    return "pt" if pt_score >= es_score else "es"


async def _synthesize_openai(text: str) -> bytes:
    """Synthesize speech with OpenAI TTS (used in development)."""
    if not settings.OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY is not configured")

    from openai import OpenAI

    client = OpenAI(api_key=settings.OPENAI_API_KEY)

    def _op() -> bytes:
        response = client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input=text,
            response_format="mp3",
        )
        return response.content

    return await asyncio.to_thread(_op)


async def _synthesize_elevenlabs(text: str) -> bytes:
    """Synthesize speech with ElevenLabs (used in production)."""
    if not settings.ELEVENLABS_API_KEY or not settings.ELEVENLABS_VOICE_ID:
        raise RuntimeError(
            "ELEVENLABS_API_KEY and ELEVENLABS_VOICE_ID must be configured"
        )

    from elevenlabs.client import ElevenLabs

    client = ElevenLabs(api_key=settings.ELEVENLABS_API_KEY)

    def _op() -> bytes:
        audio_stream = client.text_to_speech.convert(
            voice_id=settings.ELEVENLABS_VOICE_ID,
            model_id="eleven_multilingual_v2",
            text=text,
            output_format="mp3_44100_128",
        )
        return b"".join(audio_stream)

    return await asyncio.to_thread(_op)


async def synthesize_speech(text: str, language: str = "en") -> bytes:
    """Convert text into MP3 audio bytes.

    The provider is chosen from ``ENVIRONMENT``: OpenAI in development,
    ElevenLabs in production.

    Args:
        text: The text to speak.
        language: One of ``en``, ``pt`` or ``es`` (kept for future per-language
            voice selection; current providers are multilingual).

    Raises:
        RuntimeError: If the selected provider is not configured or fails.
    """
    if not text.strip():
        raise RuntimeError("Cannot synthesize empty text")

    try:
        if settings.is_production:
            audio = await _synthesize_elevenlabs(text)
            provider = "elevenlabs"
        else:
            audio = await _synthesize_openai(text)
            provider = "openai"
        logger.info(
            "Synthesized {} bytes via {} (lang={})", len(audio), provider, language
        )
        return audio
    except Exception as exc:  # noqa: BLE001
        logger.error("Speech synthesis failed: {}", exc)
        raise RuntimeError(f"Speech synthesis failed: {exc}") from exc
