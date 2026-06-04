"""Manual test: streaming TTS (raw PCM chunks).

Run from the backend directory: uv run python scripts/test_tts.py
"""

import asyncio
import base64
import sys
from pathlib import Path

# Make the backend root importable when run as "python scripts/test_tts.py".
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.core import tts


async def main() -> None:
    text = "Hello operator, this is a streaming test of the CyberBot voice."
    chunks: list[bytes] = []

    async def on_chunk(b64: str, index: int) -> None:
        chunks.append(base64.b64decode(b64))

    print("Streaming TTS ...")
    provider = await tts.synthesize_speech_streaming(text, "en", on_chunk)

    total = sum(len(c) for c in chunks)
    out = Path(__file__).parent / "test_output.pcm"
    out.write_bytes(b"".join(chunks))
    print("=" * 50)
    print(f"Provider:    {provider}")
    print(f"Chunks:      {len(chunks)}")
    print(f"Total PCM:   {total} bytes (24kHz mono 16-bit)")
    print(f"Saved:       {out}")
    print("=" * 50)


if __name__ == "__main__":
    asyncio.run(main())
