"""Text-to-speech synthesis.

Provider preference follows the environment:
- production  -> ElevenLabs first, OpenAI as fallback
- development -> OpenAI first, ElevenLabs as fallback

The fallback guarantees a turn still gets audio when the preferred provider is
not configured or fails (e.g. ElevenLabs credentials missing in production).
Both providers return MP3 audio bytes. SDKs are imported lazily so missing
dependencies never block startup. ``detect_language`` provides a dependency
free best-effort guess between English, Portuguese and Spanish.
"""

import asyncio
import base64
from typing import Awaitable, Callable, Optional

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
    """Synthesize speech with OpenAI TTS."""
    if not settings.OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY is not configured")

    from openai import OpenAI

    logger.debug("OpenAI TTS: requesting model=tts-1 voice=alloy")
    client = OpenAI(api_key=settings.OPENAI_API_KEY)

    def _op() -> bytes:
        response = client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input=text,
            response_format="mp3",
        )
        return response.content

    audio = await asyncio.to_thread(_op)
    logger.debug("OpenAI TTS: received {} bytes", len(audio))
    return audio


async def _synthesize_elevenlabs(text: str) -> bytes:
    """Synthesize speech with ElevenLabs."""
    if not settings.ELEVENLABS_API_KEY or not settings.ELEVENLABS_VOICE_ID:
        raise RuntimeError(
            "ELEVENLABS_API_KEY and ELEVENLABS_VOICE_ID must be configured"
        )

    from elevenlabs.client import ElevenLabs

    logger.debug(
        "ElevenLabs TTS: requesting voice_id={} model=eleven_multilingual_v2",
        settings.ELEVENLABS_VOICE_ID,
    )
    client = ElevenLabs(api_key=settings.ELEVENLABS_API_KEY)

    def _op() -> bytes:
        audio_stream = client.text_to_speech.convert(
            voice_id=settings.ELEVENLABS_VOICE_ID,
            model_id="eleven_multilingual_v2",
            text=text,
            output_format="mp3_44100_128",
        )
        return b"".join(audio_stream)

    audio = await asyncio.to_thread(_op)
    logger.debug("ElevenLabs TTS: received {} bytes", len(audio))
    return audio


# Provider registry: name -> async synthesizer.
_Synthesizer = Callable[[str], Awaitable[bytes]]
_PROVIDERS: dict[str, _Synthesizer] = {
    "elevenlabs": _synthesize_elevenlabs,
    "openai": _synthesize_openai,
}


def _provider_order() -> list[str]:
    """Return the ordered list of providers to try for the current env."""
    if settings.is_production:
        return ["elevenlabs", "openai"]
    return ["openai", "elevenlabs"]


async def synthesize_speech(text: str, language: str = "en") -> bytes:
    """Convert text into MP3 audio bytes, trying providers in preference order.

    Args:
        text: The text to speak.
        language: One of ``en``, ``pt`` or ``es`` (kept for future per-language
            voice selection; current providers are multilingual).

    Returns:
        MP3 audio bytes from the first provider that succeeds.

    Raises:
        RuntimeError: If the text is empty or every provider fails.
    """
    if not text.strip():
        raise RuntimeError("Cannot synthesize empty text")

    order = _provider_order()
    logger.info(
        "TTS request: {} chars, lang={}, env={}, provider order={}",
        len(text),
        language,
        settings.ENVIRONMENT,
        order,
    )

    errors: list[str] = []
    for name in order:
        synthesizer = _PROVIDERS[name]
        try:
            logger.info("TTS: attempting provider '{}'", name)
            audio = await synthesizer(text)
            if not audio:
                raise RuntimeError("provider returned empty audio")
            logger.info("TTS: provider '{}' succeeded ({} bytes)", name, len(audio))
            return audio
        except Exception as exc:  # noqa: BLE001 - try the next provider
            logger.warning("TTS: provider '{}' failed: {}", name, exc)
            errors.append(f"{name}: {exc}")

    detail = "; ".join(errors)
    logger.error("TTS: all providers failed -> {}", detail)
    raise RuntimeError(f"Speech synthesis failed (all providers): {detail}")


# ---------------------------------------------------------------------------
# Streaming synthesis (raw 24 kHz mono 16-bit PCM, base64 per chunk).
#
# We stream raw PCM (not MP3) so each chunk is independently usable by an
# AudioTrack on the client without decoding -- MP3 frames cross chunk
# boundaries and cannot be decoded per-chunk.
# ---------------------------------------------------------------------------

# callback(base64_chunk: str, index: int) -> awaitable
ChunkCallback = Callable[[str, int], Awaitable[None]]

PCM_CHUNK_BYTES = 4096


async def _stream_elevenlabs(text: str, chunk_callback: "ChunkCallback") -> None:
    """Stream raw PCM (pcm_24000) from ElevenLabs, base64 per chunk."""
    import httpx

    url = (
        "https://api.elevenlabs.io/v1/text-to-speech/"
        f"{settings.ELEVENLABS_VOICE_ID}/stream"
    )
    params = {"output_format": "pcm_24000"}
    headers = {
        "xi-api-key": settings.ELEVENLABS_API_KEY,
        "Content-Type": "application/json",
    }
    payload = {"text": text, "model_id": "eleven_monolingual_v1"}

    index = 0
    async with httpx.AsyncClient(timeout=60.0) as client:
        async with client.stream(
            "POST", url, params=params, headers=headers, json=payload
        ) as response:
            response.raise_for_status()
            async for chunk in response.aiter_bytes(PCM_CHUNK_BYTES):
                if chunk:
                    await chunk_callback(base64.b64encode(chunk).decode("utf-8"), index)
                    index += 1
    logger.info("ElevenLabs streamed {} PCM chunks", index)


async def _stream_openai(text: str, chunk_callback: "ChunkCallback") -> None:
    """Stream raw 24 kHz mono PCM from OpenAI TTS in real time.

    Uses the async streaming response so each PCM chunk is forwarded as soon
    as OpenAI produces it, instead of waiting for the full synthesis.
    """
    if not settings.OPENAI_API_KEY:
        raise RuntimeError("OPENAI_API_KEY is not configured")

    from openai import AsyncOpenAI

    client = AsyncOpenAI(api_key=settings.OPENAI_API_KEY)

    index = 0
    async with client.audio.speech.with_streaming_response.create(
        model="tts-1",
        voice="alloy",
        input=text,
        response_format="pcm",  # 24 kHz, mono, 16-bit little-endian
    ) as response:
        async for chunk in response.iter_bytes(PCM_CHUNK_BYTES):
            if chunk:
                await chunk_callback(base64.b64encode(chunk).decode("utf-8"), index)
                index += 1
    logger.info("OpenAI streamed {} PCM chunks (real-time)", index)


async def synthesize_speech_streaming(
    text: str,
    language: str = "en",
    chunk_callback: Optional["ChunkCallback"] = None,
) -> str:
    """Stream speech as raw 24 kHz mono PCM, delivering base64 chunks.

    Prefers ElevenLabs (true streaming) when configured, otherwise OpenAI
    (full synth then chunked). Returns the provider name used.
    """
    if not text.strip():
        raise RuntimeError("Cannot synthesize empty text")
    if chunk_callback is None:
        raise RuntimeError("chunk_callback is required for streaming synthesis")

    if settings.ELEVENLABS_API_KEY and settings.ELEVENLABS_VOICE_ID:
        try:
            await _stream_elevenlabs(text, chunk_callback)
            return "elevenlabs"
        except Exception as exc:  # noqa: BLE001
            logger.warning(
                "ElevenLabs streaming failed; falling back to OpenAI: {}", exc
            )

    await _stream_openai(text, chunk_callback)
    return "openai"
