"""Response models returned by CyberBot."""

from enum import Enum
from typing import Any, Optional

from pydantic import BaseModel, Field


class CyberbotState(str, Enum):
    """Lifecycle state of the assistant, mirrored on the holographic display."""

    STANDBY = "STANDBY"
    LISTENING = "LISTENING"
    THINKING = "THINKING"
    SPEAKING = "SPEAKING"
    EXECUTING = "EXECUTING"
    ERROR = "ERROR"


class CyberbotResponse(BaseModel):
    """The full response produced for a single user turn."""

    reply: str = Field(..., description="Assistant reply text in the user's language")
    state: CyberbotState = Field(..., description="Current assistant state")
    emotion: str = Field(..., description="Emotional tag, e.g. informative/executing/error")
    tts_url: Optional[str] = Field(
        default=None, description="Synthesized speech as base64 MP3 (or a URL)"
    )
    tool_used: Optional[str] = Field(
        default=None, description="Name of the tool invoked, if any"
    )
    tool_result: Optional[dict[str, Any]] = Field(
        default=None, description="Raw result returned by the invoked tool"
    )
    language: str = Field(default="en", description="Response language: en, pt or es")
    session_id: str = Field(..., description="Conversation session identifier")
