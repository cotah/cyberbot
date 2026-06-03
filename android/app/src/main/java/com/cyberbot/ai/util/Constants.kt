package com.cyberbot.ai.util

import android.media.AudioFormat

/**
 * Central app constants.
 *
 * Note: [CHANNEL_CONFIG] and [AUDIO_FORMAT] are declared as `val` (not
 * `const val`) because Kotlin `const val` only accepts compile-time literals,
 * and the AudioFormat.* values are Java static fields, not Kotlin constants.
 */
object Constants {
    // Backend (10.0.2.2 is the host loopback as seen from the Android emulator)
    const val BACKEND_WS_URL = "ws://10.0.2.2:8000/ws/conversation"
    const val BACKEND_HTTP_URL = "http://10.0.2.2:8000"

    // Audio capture
    const val SAMPLE_RATE = 16000
    val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val SILENCE_THRESHOLD = 500
    const val SILENCE_DURATION_MS = 1500L

    // Session
    const val SESSION_ID_KEY = "cyberbot_session_id"
}
