package com.cyberbot.ai.network.models

/** Lifecycle state of the assistant, mirrored on the holographic display. */
enum class CyberbotState {
    STANDBY, LISTENING, THINKING, SPEAKING, EXECUTING, ERROR
}

/** Full response produced by the backend for a single turn. */
data class CyberbotResponse(
    val reply: String,
    val state: CyberbotState,
    val emotion: String,
    val tts_url: String?,
    val tool_used: String?,
    val tool_result: Map<String, Any>?,
    val language: String,
    val session_id: String,
)

/** Lightweight intermediate state update streamed during processing. */
data class StateUpdate(
    val state: CyberbotState,
)
