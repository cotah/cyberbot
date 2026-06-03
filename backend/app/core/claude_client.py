"""Claude (Anthropic) integration.

``process_message`` runs a full turn against Claude Sonnet, including native
tool use: if the model decides to call a tool, the tool is executed via the
registry and the result is fed back to Claude until it produces a final text
reply. Conversation history is loaded from memory before processing. The
returned :class:`CyberbotResponse` carries the assistant state derived from the
turn (informative -> SPEAKING, executing a tool -> EXECUTING, error -> ERROR).
"""

import json
from typing import Any, Optional

from loguru import logger

from app.config import settings
from app.core import memory
from app.models.response import CyberbotResponse, CyberbotState

MODEL: str = "claude-sonnet-4-5"
MAX_TOKENS: int = 1024

SYSTEM_PROMPT: str = (
    "You are CyberBot, a cyberpunk AI assistant created by Henrique Pasquetto.\n"
    "Default language: English.\n"
    "Always detect the user language and respond in the same language.\n"
    "Supported languages: English, Portuguese (BR), Spanish.\n"
    "Never mix languages in a single response.\n"
    "Be concise, helpful, and slightly futuristic in personality.\n"
    "Current emotional state and context will be provided."
)


def _detect_language(text: str) -> str:
    """Detect response language, reusing the TTS heuristic (lazy import)."""
    from app.core.tts import detect_language

    return detect_language(text)


def _error_response(session_id: str, message: str) -> CyberbotResponse:
    """Build a uniform error response."""
    return CyberbotResponse(
        reply=message,
        state=CyberbotState.ERROR,
        emotion="error",
        tts_url=None,
        tool_used=None,
        tool_result=None,
        language="en",
        session_id=session_id,
    )


async def process_message(
    session_id: str,
    user_message: str,
    tools: Optional[list[dict[str, Any]]] = None,
) -> CyberbotResponse:
    """Process a single user message and return a CyberbotResponse.

    Args:
        session_id: Conversation session identifier (used to load history).
        user_message: The user's text for this turn.
        tools: Anthropic tool definitions to expose to the model. Defaults to
            no tools.

    Returns:
        A fully populated :class:`CyberbotResponse`. Never raises; failures are
        returned as an ERROR-state response.
    """
    if not settings.ANTHROPIC_API_KEY:
        logger.error("ANTHROPIC_API_KEY is not configured")
        return _error_response(
            session_id, "AI service is not configured. Please set ANTHROPIC_API_KEY."
        )

    tools = tools or []

    try:
        from anthropic import AsyncAnthropic

        client = AsyncAnthropic(api_key=settings.ANTHROPIC_API_KEY)

        # Load recent history so the model has conversational context.
        history = await memory.get_history(session_id)
        messages: list[dict[str, Any]] = [
            {"role": item["role"], "content": item["content"]} for item in history
        ]
        messages.append({"role": "user", "content": user_message})

        tool_used: Optional[str] = None
        tool_result_data: Optional[dict[str, Any]] = None

        def _create_kwargs() -> dict[str, Any]:
            kwargs: dict[str, Any] = {
                "model": MODEL,
                "max_tokens": MAX_TOKENS,
                "system": SYSTEM_PROMPT,
                "messages": messages,
            }
            if tools:
                kwargs["tools"] = tools
            return kwargs

        response = await client.messages.create(**_create_kwargs())

        # Native tool-use loop: keep going while Claude asks for tools.
        from app.tools import registry

        while response.stop_reason == "tool_use":
            messages.append({"role": "assistant", "content": response.content})
            tool_result_blocks: list[dict[str, Any]] = []
            for block in response.content:
                if getattr(block, "type", None) != "tool_use":
                    continue
                tool_used = block.name
                tool_result_data = await registry.execute_tool(block.name, block.input)
                tool_result_blocks.append(
                    {
                        "type": "tool_result",
                        "tool_use_id": block.id,
                        "content": json.dumps(tool_result_data),
                    }
                )
            messages.append({"role": "user", "content": tool_result_blocks})
            response = await client.messages.create(**_create_kwargs())

        reply = "".join(
            block.text
            for block in response.content
            if getattr(block, "type", None) == "text"
        ).strip()

        language = _detect_language(reply or user_message)
        if tool_used:
            state = CyberbotState.EXECUTING
            emotion = "executing"
        else:
            state = CyberbotState.SPEAKING
            emotion = "informative"

        logger.info(
            "Claude turn complete (state={}, tool={}, lang={})",
            state.value,
            tool_used,
            language,
        )
        return CyberbotResponse(
            reply=reply,
            state=state,
            emotion=emotion,
            tts_url=None,
            tool_used=tool_used,
            tool_result=tool_result_data,
            language=language,
            session_id=session_id,
        )
    except Exception as exc:  # noqa: BLE001
        logger.error("Claude processing failed: {}", exc)
        return _error_response(session_id, f"AI processing failed: {exc}")
