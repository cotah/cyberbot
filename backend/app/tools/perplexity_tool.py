"""Real-time web search tool backed by the Perplexity API.

Perplexity answers tend to be long and citation-heavy, which is expensive and
awkward for text-to-speech. We constrain it to a short, citation-free summary
and, if the answer is still too long, compress it once more.
"""

from typing import Any

import httpx
from loguru import logger

from app.config import settings

PERPLEXITY_URL = "https://api.perplexity.ai/chat/completions"
MODEL = "sonar"
MAX_CHARS = 500

_SYSTEM_PROMPT = (
    "You are a research assistant. Return ONLY a concise summary of the most "
    "important facts. Maximum 3 sentences. No sources, no citations, no URLs. "
    "Respond in the same language as the query. Focus on the key information "
    "the user needs."
)

_LANGUAGE_NAME = {"en": "English", "pt": "Portuguese", "es": "Spanish"}


async def _call_perplexity(system: str, user: str) -> str:
    """Single chat completion call to Perplexity; returns the message content."""
    headers = {
        "Authorization": f"Bearer {settings.PERPLEXITY_API_KEY}",
        "Content-Type": "application/json",
    }
    payload = {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ],
    }
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.post(PERPLEXITY_URL, headers=headers, json=payload)
        response.raise_for_status()
        data = response.json()
    return (data["choices"][0]["message"]["content"] or "").strip()


async def search_web(query: str, language: str = "en") -> str:
    """Search the web via Perplexity and return a short, TTS-friendly summary.

    Args:
        query: The search query.
        language: One of ``en``, ``pt`` or ``es``.

    Returns:
        A concise summary string (<= ~500 chars). On failure, a short plain
        message explaining the problem (never raises).
    """
    if not settings.PERPLEXITY_API_KEY:
        return "Web search is not configured."

    try:
        result = await _call_perplexity(_SYSTEM_PROMPT, query)

        # If the answer is still too long for speech, compress it once more.
        if len(result) > MAX_CHARS:
            logger.info("Perplexity result is {} chars; compressing", len(result))
            lang = _LANGUAGE_NAME.get(language.lower(), "the same language")
            try:
                compressed = await _call_perplexity(
                    "Summarize the following text in at most 2 short sentences, "
                    f"in {lang}. No citations, no URLs.",
                    result,
                )
                if compressed:
                    result = compressed
            except Exception as exc:  # noqa: BLE001
                logger.warning("Perplexity compression failed: {}", exc)

            # Hard cap as a last resort (clean word boundary).
            if len(result) > MAX_CHARS:
                result = result[:MAX_CHARS].rsplit(" ", 1)[0].rstrip() + "..."

        logger.info("search_web('{}') -> {} chars", query, len(result))
        return result
    except Exception as exc:  # noqa: BLE001
        logger.error("Perplexity search failed: {}", exc)
        return f"Could not complete the web search: {exc}"


# Anthropic tool definition consumed by the Claude client.
SEARCH_WEB_TOOL: dict[str, Any] = {
    "name": "search_web",
    "description": (
        "Search the internet for current information, news, prices, events, or "
        "any real-time data. Use when the user asks about something that may "
        "have changed recently."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "query": {
                "type": "string",
                "description": "search query",
            },
            "language": {
                "type": "string",
                "description": "en, pt or es",
            },
        },
        "required": ["query"],
    },
}
