"""Request and conversation models."""

from datetime import datetime
from typing import Optional

from pydantic import BaseModel, Field


class MessageRequest(BaseModel):
    """Incoming user turn: either transcribed audio or direct text."""

    session_id: str = Field(..., description="Conversation session identifier")
    audio_base64: Optional[str] = Field(
        default=None, description="Base64-encoded audio payload to transcribe"
    )
    text: Optional[str] = Field(
        default=None, description="Direct text input (skips transcription)"
    )
    language: Optional[str] = Field(
        default="en", description="Preferred language: en, pt or es"
    )


class ConversationMessage(BaseModel):
    """A single stored conversation message."""

    role: str = Field(..., description="Either 'user' or 'assistant'")
    content: str = Field(..., description="Message text")
    timestamp: datetime = Field(..., description="When the message was created")
