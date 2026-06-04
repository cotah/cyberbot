"""Central registry of tools available to Claude.

``get_tools`` returns the Anthropic-format tool definitions; ``execute_tool``
dispatches a tool call to its implementation. For now only the weather tool is
registered, but adding a new tool is a matter of importing its definition and
adding one entry to ``_EXECUTORS``.
"""

from typing import Any, Awaitable, Callable

from loguru import logger

from app.tools.perplexity_tool import SEARCH_WEB_TOOL, search_web
from app.tools.weather_tool import WEATHER_TOOL, get_weather


async def _run_search_web(tool_input: dict[str, Any]) -> dict[str, Any]:
    """Wrap the string result of search_web in a dict for the tool contract."""
    summary = await search_web(**tool_input)
    return {"summary": summary}


# All tool definitions exposed to the model (Anthropic format).
_TOOL_DEFINITIONS: list[dict[str, Any]] = [WEATHER_TOOL, SEARCH_WEB_TOOL]

# Map of tool name -> async executor.
_EXECUTORS: dict[str, Callable[[dict[str, Any]], Awaitable[dict[str, Any]]]] = {
    "get_weather": lambda tool_input: get_weather(**tool_input),
    "search_web": _run_search_web,
}


def get_tools() -> list[dict[str, Any]]:
    """Return all available tool definitions in Anthropic format."""
    return _TOOL_DEFINITIONS


async def execute_tool(tool_name: str, tool_input: dict[str, Any]) -> dict[str, Any]:
    """Execute a tool by name and return its result.

    Returns a dict with an ``error`` key when the tool is unknown or raises, so
    the result is always JSON-serializable and safe to feed back to the model.
    """
    executor = _EXECUTORS.get(tool_name)
    if executor is None:
        logger.warning("Unknown tool requested: {}", tool_name)
        return {"error": f"Unknown tool: {tool_name}"}

    try:
        logger.info("Executing tool {} with input {}", tool_name, tool_input)
        return await executor(tool_input)
    except Exception as exc:  # noqa: BLE001
        logger.error("Tool {} failed: {}", tool_name, exc)
        return {"error": f"Tool '{tool_name}' failed: {exc}"}
