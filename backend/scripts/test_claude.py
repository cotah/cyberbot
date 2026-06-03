"""Manual test: Claude conversation.

Sends a single message to claude_client and prints the structured response.
Run from the backend directory: uv run python scripts/test_claude.py
"""

import asyncio
import sys
from pathlib import Path

# Make the backend root importable when run as "python scripts/test_claude.py".
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core import claude_client


async def main() -> None:
    response = await claude_client.process_message(
        session_id="test_session",
        user_message="Hello CyberBot, introduce yourself in one sentence.",
        tools=[],
    )
    print("=" * 50)
    print("reply:    ", response.reply)
    print("state:    ", response.state)
    print("emotion:  ", response.emotion)
    print("language: ", response.language)
    print("tool_used:", response.tool_used)
    print("=" * 50)


if __name__ == "__main__":
    asyncio.run(main())
