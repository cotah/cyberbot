package com.cyberbot.ai.service

import android.util.Log
import com.cyberbot.ai.network.models.CyberbotState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Central state holder for CyberBot. Exposes the current [CyberbotState] as a
 * [StateFlow] so the UI can observe it reactively. Every transition is logged.
 */
class CyberbotService {

    private val _state = MutableStateFlow(CyberbotState.STANDBY)
    val state: StateFlow<CyberbotState> = _state.asStateFlow()

    // Last emotion returned by the backend; drives the SPEAKING animation choice.
    private val _emotion = MutableStateFlow("")
    val emotion: StateFlow<String> = _emotion.asStateFlow()

    fun setEmotion(emotion: String) {
        _emotion.value = emotion
    }

    private fun transition(next: CyberbotState) {
        val previous = _state.value
        if (previous != next) {
            Log.i(TAG, "State: $previous -> $next")
        }
        _state.value = next
    }

    fun startListening() = transition(CyberbotState.LISTENING)

    fun stopListening() = transition(CyberbotState.STANDBY)

    fun setThinking() = transition(CyberbotState.THINKING)

    fun setSpeaking() = transition(CyberbotState.SPEAKING)

    fun setStandby() = transition(CyberbotState.STANDBY)

    fun setError() = transition(CyberbotState.ERROR)

    /** Apply an arbitrary state (used for updates streamed from the backend). */
    fun setState(state: CyberbotState) = transition(state)

    companion object {
        private const val TAG = "CyberbotService"
    }
}
