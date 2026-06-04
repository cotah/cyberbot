"""Claude (Anthropic) integration.

``process_message`` runs a full turn against Claude Sonnet, including native
tool use: if the model decides to call a tool, the tool is executed via the
registry and the result is fed back to Claude until it produces a final text
reply. Conversation history is loaded from memory before processing. The
final returned :class:`CyberbotResponse` state is SPEAKING for a successful
turn (or ERROR on failure); EXECUTING is emitted only as an intermediate state
via the optional ``on_state`` callback while a tool is running.
"""

import json
from typing import Any, Awaitable, Callable, Optional

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


# Phrases that suggest the user is stating a durable fact about themselves.
_FACT_MARKERS: tuple[str, ...] = (
    "my name is", "i am ", "i'm ", "i like", "i love", "i prefer", "i live",
    "i work", "my favorite", "remember that",
    "meu nome", "me chamo", "eu moro", "eu gosto", "eu trabalho", "eu prefiro",
    "meu projeto", "minha preferência",
    "mi nombre", "me llamo", "me gusta", "vivo en", "trabajo en", "prefiero",
)


def _build_system_prompt(memories: list[dict[str, Any]]) -> str:
    """Append relevant long-term memories to the base system prompt."""
    lines = [f"- {m['content']}" for m in memories if m.get("content")]
    if not lines:
        return SYSTEM_PROMPT
    return (
        SYSTEM_PROMPT
        + "\n\nRelevant memories about the user:\n"
        + "\n".join(lines)
    )


def _extract_user_fact(user_message: str) -> Optional[str]:
    """Return the user message if it appears to state a durable personal fact."""
    lowered = user_message.lower()
    if any(marker in lowered for marker in _FACT_MARKERS):
        return user_message.strip()
    return None


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
    on_state: Optional[Callable[[CyberbotState], Awaitable[None]]] = None,
) -> CyberbotResponse:
    """Process a single user message and return a CyberbotResponse.

    Args:
        session_id: Conversation session identifier (used to load history).
        user_message: The user's text for this turn.
        tools: Anthropic tool definitions to expose to the model. Defaults to
            no tools.
        on_state: Optional async callback invoked with intermediate states
            (currently EXECUTING, emitted while a tool is running) so callers
            can stream progress. The final returned state is always SPEAKING.

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

        # RAG: load recent history (as message turns) and relevant long-term
        # memories (injected into the system prompt).
        history = await memory.get_history(session_id)
        memories = await memory.search_memories(user_message)
        system_prompt = _build_system_prompt(memories)
        logger.info(
            "RAG context: {} history msgs, {} relevant memories",
            len(history),
            len(memories),
        )

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
                "system": system_prompt,
                "messages": messages,
            }
            if tools:
                kwargs["tools"] = tools
            return kwargs

        response = await client.messages.create(**_create_kwargs())

        # Native tool-use loop: keep going while Claude asks for tools.
        from app.tools import registry

        while response.stop_reason == "tool_use":
            # Surface the intermediate EXECUTING state while the tool runs.
            if on_state is not None:
                await on_state(CyberbotState.EXECUTING)
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
        # The final reply is always spoken. EXECUTING is only an intermediate
        # state, emitted via on_state while a tool runs (see loop above).
        state = CyberbotState.SPEAKING
        emotion = "informative"

        # Persist the turn for future RAG context.
        await memory.save_message(session_id, "user", user_message)
        if reply:
            await memory.save_message(session_id, "assistant", reply)

        # If the user stated a durable personal fact, store it as a memory.
        fact = _extract_user_fact(user_message)
        if fact:
            await memory.save_memory(fact, category="user_fact")

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
