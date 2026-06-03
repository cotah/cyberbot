"""Audio endpoints: speech-to-text and text-to-speech."""

import base64
from typing import Optional

from fastapi import APIRouter, HTTPException
from loguru import logger
from pydantic import BaseModel, Field

from app.core import stt, tts

router = APIRouter(prefix="/api/audio", tags=["audio"])


class TranscribeRequest(BaseModel):
    """Request body for transcription."""

    audio_base64: str = Field(..., description="Base64-encoded audio payload")
    language: str = Field(default="en", description="Language hint: en, pt or es")


class TranscribeResponse(BaseModel):
    """Transcription result."""

    text: str
    language: str


class SynthesizeRequest(BaseModel):
    """Request body for speech synthesis."""

    text: str = Field(..., description="Text to convert to speech")
    language: str = Field(default="en", description="Language hint: en, pt or es")


class SynthesizeResponse(BaseModel):
    """Synthesis result with base64-encoded MP3 audio."""

    audio_base64: str
    language: str


@router.post("/transcribe", response_model=TranscribeResponse)
async def transcribe(request: TranscribeRequest) -> TranscribeResponse:
    """Transcribe base64 audio and return the text plus detected language."""
    try:
        audio_bytes = base64.b64decode(request.audio_base64)
    except Exception as exc:  # noqa: BLE001
        logger.warning("Invalid base64 audio payload: {}", exc)
        raise HTTPException(status_code=400, detail="Invalid base64 audio") from exc

    try:
        text = await stt.transcribe_audio(audio_bytes, language=request.language)
    except RuntimeError as exc:
        logger.error("Transcription error: {}", exc)
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    detected = tts.detect_language(text) if text else request.language
    return TranscribeResponse(text=text, language=detected)


@router.post("/synthesize", response_model=SynthesizeResponse)
async def synthesize(request: SynthesizeRequest) -> SynthesizeResponse:
    """Synthesize speech from text and return base64-encoded MP3 audio."""
    if not request.text.strip():
        raise HTTPException(status_code=400, detail="Text must not be empty")

    try:
        audio_bytes = await tts.synthesize_speech(
            request.text, language=request.language
        )
    except RuntimeError as exc:
        logger.error("Synthesis error: {}", exc)
        raise HTTPException(status_code=502, detail=str(exc)) from exc

    audio_base64 = base64.b64encode(audio_bytes).decode("utf-8")
    return SynthesizeResponse(audio_base64=audio_base64, language=request.language)
