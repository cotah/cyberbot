"""Speech-to-text using Deepgram.

Supported languages: English (``en``), Portuguese (``pt``) and Spanish
(``es``). The Deepgram SDK is imported lazily so a missing dependency does not
block application startup.
"""

import asyncio

from loguru import logger

from app.config import settings

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
        audio_bytes: The raw audio payload (e.g. WAV/MP3/Opus bytes).
        language: One of ``en``, ``pt`` or ``es``.

    Returns:
        The transcribed text (empty string if nothing was recognized).

    Raises:
        RuntimeError: If Deepgram is not configured or transcription fails.
    """
    if not settings.DEEPGRAM_API_KEY:
        raise RuntimeError("DEEPGRAM_API_KEY is not configured")

    try:
        from deepgram import DeepgramClient, PrerecordedOptions

        client = DeepgramClient(settings.DEEPGRAM_API_KEY)
        options = PrerecordedOptions(
            model="nova-2",
            language=_deepgram_language(language),
            smart_format=True,
        )
        payload = {"buffer": audio_bytes}

        def _op() -> str:
            response = client.listen.rest.v("1").transcribe_file(payload, options)
            return (
                response.results.channels[0].alternatives[0].transcript or ""
            ).strip()

        transcript = await asyncio.to_thread(_op)
        logger.info("Transcribed {} chars (lang={})", len(transcript), language)
        return transcript
    except Exception as exc:  # noqa: BLE001
        logger.error("Deepgram transcription failed: {}", exc)
        raise RuntimeError(f"Transcription failed: {exc}") from exc
