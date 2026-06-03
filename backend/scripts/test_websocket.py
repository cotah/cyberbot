"""Manual test: full conversation flow over WebSocket.

Connects to the running backend, sends a text message that should trigger the
weather tool, and prints every event received in order.

Start the server first (in another terminal):
    uvicorn app.main:app --reload --port 8000
Then run: uv run python scripts/test_websocket.py
"""

import asyncio
import json

import websockets

WS_URL = "ws://localhost:8000/ws/conversation/test_session"
MESSAGE = {"text": "What is the weather like in Dublin today?", "language": "en"}


async def main() -> None:
    print(f"Connecting to {WS_URL} ...")
    async with websockets.connect(WS_URL) as ws:
        print("Connected. Sending message ...")
        await ws.send(json.dumps(MESSAGE))

        event_index = 0
        while True:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=90.0)
            except asyncio.TimeoutError:
                print("Timeout waiting for more events.")
                break

            event_index += 1
            data = json.loads(raw)
            print("-" * 50)
            print(f"EVENT #{event_index}")

            # Intermediate state update.
            if "reply" not in data:
                print("  state:", data.get("state"))
                continue

            # Final response.
            print("  state:    ", data.get("state"))
            print("  tool_used:", data.get("tool_used"))
            print("  language: ", data.get("language"))
            print("  reply:    ", data.get("reply"))
            tts_url = data.get("tts_url")
            if tts_url:
                print(f"  tts_url:   {tts_url[:60]}... ({len(tts_url)} chars total)")
            else:
                print("  tts_url:   None")
            tool_result = data.get("tool_result")
            if tool_result:
                print("  tool_result:", tool_result)
            print("=" * 50)
            print("FINAL RESPONSE RECEIVED")
            break


if __name__ == "__main__":
    asyncio.run(main())
