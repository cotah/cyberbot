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
 * updates and full responses, and automatically reconnects up to
 * [MAX_RECONNECT_ATTEMPTS] times with an exponential backoff (2s, 4s, 8s).
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

    private fun openSocket() {
        val url = "${Constants.BACKEND_WS_URL}/$sessionId"
        Log.i(TAG, "Connecting to $url")
        val request = Request.Builder().url(url).build()
        webSocket = client.newWebSocket(request, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "WebSocket open")
            reconnectAttempts = 0
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
            Log.e(TAG, "WebSocket failure: ${t.message}")
            onError(t.message ?: "WebSocket failure")
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
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnect attempts reached")
            onError("Connection lost. Max reconnect attempts reached.")
            return
        }
        val delayMs = 2000L * (1 shl reconnectAttempts) // 2s, 4s, 8s
        reconnectAttempts++
        Log.w(TAG, "Reconnecting in ${delayMs}ms (attempt $reconnectAttempts/$MAX_RECONNECT_ATTEMPTS)")
        scheduler.schedule(
            { if (!manuallyClosed) openSocket() },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    companion object {
        private const val TAG = "BackendClient"
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private const val NORMAL_CLOSURE = 1000
    }
}
