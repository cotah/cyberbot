"""Manual test: Deepgram speech-to-text.

Downloads a short public English audio sample and transcribes it via stt.py.
Run from the backend directory: uv run python scripts/test_stt.py
"""

import asyncio
import sys
from pathlib import Path

import httpx

# Make the backend root importable when run as "python scripts/test_stt.py".
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core import stt

# Public Deepgram sample (English speech, WAV).
AUDIO_URL = "https://static.deepgram.com/examples/Bueller-Life-moves-pretty-fast.wav"


async def main() -> None:
    print(f"Downloading test audio from {AUDIO_URL} ...")
    async with httpx.AsyncClient(timeout=30.0) as client:
        response = await client.get(AUDIO_URL)
        response.raise_for_status()
        audio_bytes = response.content
    print(f"Downloaded {len(audio_bytes)} bytes")

    print("Transcribing via Deepgram ...")
    transcript = await stt.transcribe_audio(audio_bytes, language="en")
    print("=" * 50)
    print("TRANSCRIPT:", transcript)
    print("=" * 50)


if __name__ == "__main__":
    asyncio.run(main())
