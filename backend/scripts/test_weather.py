"""Manual test: weather tool (Open-Meteo).

Calls the weather tool directly for Dublin and prints the result.
Run from the backend directory: uv run python scripts/test_weather.py
"""

import asyncio
import sys
from pathlib import Path

# Make the backend root importable when run as "python scripts/test_weather.py".
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.tools.weather_tool import get_weather


async def main() -> None:
    result = await get_weather(city="Dublin")
    print("=" * 50)
    print("WEATHER (Dublin):", result)
    print("=" * 50)


if __name__ == "__main__":
    asyncio.run(main())
