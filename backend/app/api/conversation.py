"""Real-time conversation over WebSocket.

Per turn the endpoint:
1. Receives a ``MessageRequest`` (audio base64 or direct text).
2. Transcribes audio when needed (emitting LISTENING).
3. Emits THINKING and runs the message through Claude (with tools).
4. Synthesizes the reply with TTS (base64 MP3 in ``tts_url``).
5. Persists both the user and assistant messages to memory.
6. Emits the final CyberbotResponse.

State updates are streamed as JSON messages so the holographic display can
react in real time.
"""

import base64

from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from loguru import logger

from app.core import claude_client, stt, tts
from app.models.conversation import MessageRequest
from app.models.response import CyberbotResponse, CyberbotState
from app.tools import registry

router = APIRouter(tags=["conversation"])


async def _resolve_user_text(request: MessageRequest, websocket: WebSocket) -> str:
    """Return the user's text, transcribing audio if necessary."""
    if request.text and request.text.strip():
        return request.text.strip()

    if request.audio_base64:
        await websocket.send_json({"state": CyberbotState.LISTENING.value})
        audio_bytes = base64.b64decode(request.audio_base64)
        return await stt.transcribe_audio(
            audio_bytes, language=request.language or "en"
        )

    return ""


@router.websocket("/ws/conversation/{session_id}")
async def conversation_ws(websocket: WebSocket, session_id: str) -> None:
    """Handle a full-duplex conversation session."""
    await websocket.accept()
    logger.info("WebSocket connected: session={}", session_id)

    try:
        while True:
            data = await websocket.receive_json()
            # Ensure the session id from the path is authoritative.
            data["session_id"] = session_id

            # Camera frames are logged only for now (not yet processed) and must
            # not interrupt the conversation.
            if data.get("type") == "camera_frame":
                frame_b64 = data.get("data", "") or ""
                logger.info(
                    "Received camera frame ({} base64 chars) for session {}",
                    len(frame_b64),
                    session_id,
                )
                await websocket.send_json({"state": CyberbotState.STANDBY.value})
                continue

            request = MessageRequest(**data)

            try:
                user_text = await _resolve_user_text(request, websocket)
                if not user_text:
                    await websocket.send_json(
                        {
                            "state": CyberbotState.ERROR.value,
                            "reply": "No input received (empty text and audio).",
                        }
                    )
                    continue

                # Thinking phase.
                await websocket.send_json({"state": CyberbotState.THINKING.value})

                # Stream intermediate states (e.g. EXECUTING while a tool runs).
                async def emit_state(state: CyberbotState) -> None:
                    await websocket.send_json({"state": state.value})

                response: CyberbotResponse = await claude_client.process_message(
                    session_id=session_id,
                    user_message=user_text,
                    tools=registry.get_tools(),
                    on_state=emit_state,
                )

                # Note: the turn is persisted inside claude_client.process_message
                # (RAG), so we do not save it again here to avoid duplicates.

                # Send the final response first (no tts_url) so the client can
                # set up streaming playback; the audio follows as PCM chunks.
                await websocket.send_json(response.model_dump())

                # Stream the speech as raw PCM chunks for instant interruption.
                if response.reply and response.state != CyberbotState.ERROR:
                    async def _send_chunk(b64: str, index: int) -> None:
                        await websocket.send_json(
                            {"type": "tts_chunk", "data": b64, "index": index}
                        )

                    try:
                        provider = await tts.synthesize_speech_streaming(
                            response.reply,
                            language=response.language,
                            chunk_callback=_send_chunk,
                        )
                        logger.info("TTS streamed via {}", provider)
                    except Exception as exc:  # noqa: BLE001
                        logger.error("TTS streaming failed: {}", exc)
                    await websocket.send_json({"type": "tts_end"})

            except Exception as exc:  # noqa: BLE001 - per-turn isolation
                logger.error("Error processing turn: {}", exc)
                await websocket.send_json(
                    {
                        "state": CyberbotState.ERROR.value,
                        "reply": f"Sorry, something went wrong: {exc}",
                    }
                )
    except WebSocketDisconnect:
        logger.info("WebSocket disconnected: session={}", session_id)
    except Exception as exc:  # noqa: BLE001
        logger.error("WebSocket fatal error (session={}): {}", session_id, exc)
        try:
            await websocket.close()
        except Exception:  # noqa: BLE001
            pass
