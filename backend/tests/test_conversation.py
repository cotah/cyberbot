"""Tests for the conversation WebSocket endpoint.

Claude and TTS are mocked so the tests run offline and deterministically.
"""

from typing import Any

import pytest
from fastapi.testclient import TestClient

from app.core import claude_client, tts
from app.main import app
from app.models.response import CyberbotResponse, CyberbotState


@pytest.fixture(autouse=True)
def _mock_externals(monkeypatch: pytest.MonkeyPatch) -> None:
    """Replace Claude and TTS with fast, offline fakes."""

    async def fake_process_message(
        session_id: str, user_message: str, tools: Any = None
    ) -> CyberbotResponse:
        return CyberbotResponse(
            reply="Hello, operator.",
            state=CyberbotState.SPEAKING,
            emotion="informative",
            tts_url=None,
            tool_used=None,
            tool_result=None,
            language="en",
            session_id=session_id,
        )

    async def fake_synthesize(text: str, language: str = "en") -> bytes:
        return b"fake-audio-bytes"

    monkeypatch.setattr(claude_client, "process_message", fake_process_message)
    monkeypatch.setattr(tts, "synthesize_speech", fake_synthesize)


def test_websocket_connects() -> None:
    """The WebSocket route should accept a connection."""
    with TestClient(app) as client:
        with client.websocket_connect("/ws/conversation/test-session") as ws:
            assert ws is not None


def test_text_message_returns_valid_response() -> None:
    """Sending text should yield a valid CyberbotResponse payload."""
    with TestClient(app) as client:
        with client.websocket_connect("/ws/conversation/test-session") as ws:
            ws.send_json({"text": "hello", "language": "en"})

            # First the THINKING state, then the final response.
            first = ws.receive_json()
            assert first["state"] == CyberbotState.THINKING.value

            final = ws.receive_json()

    assert final["reply"] == "Hello, operator."
    assert final["state"] == CyberbotState.SPEAKING.value
    assert final["session_id"] == "test-session"
    assert final["language"] == "en"
    # TTS output should be embedded as a base64 data URI.
    assert final["tts_url"] is not None
    assert final["tts_url"].startswith("data:audio/mp3;base64,")
