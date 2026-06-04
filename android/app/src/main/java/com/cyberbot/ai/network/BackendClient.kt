package com.cyberbot.ai.network

import android.util.Base64
import android.util.Log
import com.cyberbot.ai.network.models.CyberbotResponse
import com.cyberbot.ai.network.models.CyberbotState
import com.cyberbot.ai.network.models.StateUpdate
import com.cyberbot.ai.util.Constants
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the CyberBot FastAPI backend.
 *
 * Connects to [Constants.BACKEND_WS_URL]/{sessionId}, parses streamed state
 * updates and full responses, and reconnects INDEFINITELY with a capped
 * exponential backoff (2s, 4s, 8s, 16s, then 30s). On a successful reconnect it
 * notifies the UI to return to STANDBY. The connection is only abandoned when
 * [disconnect] is called explicitly.
 */
class BackendClient(
    private val onStateUpdate: (CyberbotState) -> Unit,
    private val onResponse: (CyberbotResponse) -> Unit,
    private val onError: (String) -> Unit,
) {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // keep the socket open indefinitely
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var webSocket: WebSocket? = null
    private var sessionId: String = ""
    private var reconnectAttempts = 0
    @Volatile private var manuallyClosed = false

    fun connect(sessionId: String) {
        this.sessionId = sessionId
        manuallyClosed = false
        reconnectAttempts = 0
        openSocket()
    }

    fun disconnect() {
        manuallyClosed = true
        webSocket?.close(NORMAL_CLOSURE, "Client disconnect")
        webSocket = null
        Log.i(TAG, "Disconnected by client")
    }

    fun sendText(text: String) {
        val payload = mapOf(
            "session_id" to sessionId,
            "text" to text,
            "language" to "en",
        )
        val sent = webSocket?.send(gson.toJson(payload)) ?: false
        Log.i(TAG, "sendText (${text.length} chars) -> sent=$sent")
    }

    fun sendAudio(audioBytes: ByteArray) {
        val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        val payload = mapOf(
            "session_id" to sessionId,
            "audio_base64" to b64,
            "language" to "en",
        )
        val sent = webSocket?.send(gson.toJson(payload)) ?: false
        Log.i(TAG, "sendAudio (${audioBytes.size} bytes) -> sent=$sent")
    }

    fun sendCameraFrame(jpegBytes: ByteArray) {
        val b64 = Base64.encodeToString(jpegBytes, Base64.NO_WRAP)
        val payload = mapOf(
            "type" to "camera_frame",
            "data" to b64,
        )
        val sent = webSocket?.send(gson.toJson(payload)) ?: false
        Log.i(TAG, "sendCameraFrame (${jpegBytes.size} bytes) -> sent=$sent")
    }

    private fun openSocket() {
        val url = "${Constants.BACKEND_WS_URL}/$sessionId"
        Log.i(TAG, "Connecting to $url (attempt ${reconnectAttempts + 1})")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val wasReconnect = reconnectAttempts > 0
            Log.i(
                TAG,
                "WebSocket connected to $sessionId (HTTP ${response.code})" +
                    if (wasReconnect) " [recovered after $reconnectAttempts attempt(s)]" else "",
            )
            reconnectAttempts = 0
            // After a recovered connection, return the assistant to STANDBY.
            if (wasReconnect) {
                onStateUpdate(CyberbotState.STANDBY)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            Log.d(TAG, "Message: $text")
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closing: $code $reason")
            webSocket.close(NORMAL_CLOSURE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.w(TAG, "WebSocket closed: $code $reason")
            if (!manuallyClosed) scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val httpInfo =
                if (response != null) "HTTP ${response.code} ${response.message}"
                else "no HTTP response (transport-level failure)"
            Log.e(
                TAG,
                "WebSocket failure [$httpInfo]: ${t.javaClass.simpleName}: ${t.message}",
                t,
            )
            // Do NOT surface ERROR on a single failure. Retry first; onError is
            // only emitted once all reconnect attempts are exhausted.
            if (!manuallyClosed) scheduleReconnect()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            when {
                // A full response always carries a "reply" field.
                json.has("reply") -> {
                    val response = gson.fromJson(text, CyberbotResponse::class.java)
                    onStateUpdate(response.state)
                    onResponse(response)
                }
                // Otherwise it is an intermediate state update.
                json.has("state") -> {
                    val update = gson.fromJson(text, StateUpdate::class.java)
                    onStateUpdate(update.state)
                }
                else -> Log.w(TAG, "Unrecognized message: $text")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message: ${e.message}")
            onError("Parse error: ${e.message}")
        }
    }

    private fun scheduleReconnect() {
        if (manuallyClosed) return

        // Capped exponential backoff: 2s, 4s, 8s, 16s, then 30s forever.
        val exponent = reconnectAttempts.coerceAtMost(MAX_BACKOFF_EXPONENT)
        val delayMs = minOf(BASE_BACKOFF_MS shl exponent, MAX_BACKOFF_MS)
        reconnectAttempts++
        Log.w(TAG, "Reconnect attempt #$reconnectAttempts scheduled in ${delayMs}ms")
        scheduler.schedule(
            { if (!manuallyClosed) openSocket() },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    companion object {
        private const val TAG = "BackendClient"
        private const val NORMAL_CLOSURE = 1000
        private const val BASE_BACKOFF_MS = 2000L
        private const val MAX_BACKOFF_MS = 30000L
        private const val MAX_BACKOFF_EXPONENT = 4 // 2000 << 4 = 32000, capped to 30000
    }
}
