"""Manual test: Perplexity web search tool.

Run from the backend directory: uv run python scripts/test_perplexity.py
"""

import asyncio
import sys
from pathlib import Path

# Make the backend root importable when run as "python scripts/test_perplexity.py".
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.tools.perplexity_tool import search_web


async def main() -> None:
    query = "latest news in Dublin today"
    print(f"Query: {query}")
    result = await search_web(query, language="en")
    print("=" * 60)
    print(f"RESULT ({len(result)} chars):")
    print(result)
    print("=" * 60)


if __name__ == "__main__":
    asyncio.run(main())
