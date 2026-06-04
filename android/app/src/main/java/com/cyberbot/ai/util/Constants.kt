package com.cyberbot.ai.util

object Constants {
    const val BACKEND_WS_URL = "wss://cyberbot-production.up.railway.app/ws/conversation"
    const val BACKEND_HTTP_URL = "https://cyberbot-production.up.railway.app"

    const val SAMPLE_RATE = 16000
    val CHANNEL_CONFIG = android.media.AudioFormat.CHANNEL_IN_MONO
    val AUDIO_FORMAT = android.media.AudioFormat.ENCODING_PCM_16BIT
    const val SILENCE_THRESHOLD = 50
    const val SILENCE_DURATION_MS = 1500L

    const val SESSION_ID_KEY = "cyberbot_session_id"
}
