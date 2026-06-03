"""Manual test: OpenAI text-to-speech.

Gets a fresh reply from Claude, synthesizes it via tts.py, saves the MP3 and
prints its size. Run from the backend directory:
    uv run python scripts/test_tts.py
"""

import asyncio
import sys
from pathlib import Path

# Make the backend root importable when run as "python scripts/test_tts.py".
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core import claude_client, tts


async def main() -> None:
    print("Getting text from Claude ...")
    response = await claude_client.process_message(
        session_id="test_session",
        user_message="Hello CyberBot, introduce yourself in one sentence.",
        tools=[],
    )
    text = response.reply
    print("Text to synthesize:", text)

    print("Synthesizing via TTS ...")
    audio_bytes = await tts.synthesize_speech(text, language=response.language)

    output_path = Path(__file__).parent / "test_output.mp3"
    output_path.write_bytes(audio_bytes)
    print("=" * 50)
    print(f"Saved: {output_path}")
    print(f"Size:  {len(audio_bytes)} bytes")
    print("=" * 50)


if __name__ == "__main__":
    asyncio.run(main())
