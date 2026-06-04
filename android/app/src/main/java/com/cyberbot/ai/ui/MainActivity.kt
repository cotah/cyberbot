package com.cyberbot.ai.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cyberbot.ai.audio.AudioCaptureManager
import com.cyberbot.ai.audio.AudioPlaybackManager
import com.cyberbot.ai.audio.WakeWordDetector
import com.cyberbot.ai.camera.CameraManager
import com.cyberbot.ai.hologram.HologramRenderer
import com.cyberbot.ai.kiosk.KioskManager
import com.cyberbot.ai.network.BackendClient
import com.cyberbot.ai.network.models.CyberbotResponse
import com.cyberbot.ai.network.models.CyberbotState
import com.cyberbot.ai.service.CyberbotService
import com.cyberbot.ai.ui.theme.CyberBotTheme
import com.cyberbot.ai.util.Constants
import java.util.UUID

/**
 * Single fullscreen activity. Wires together state, networking, audio capture
 * and playback, and renders the holographic display.
 *
 * Flow: STANDBY -> LISTENING (capture) -> THINKING (send) -> SPEAKING (play) ->
 * back to LISTENING when playback completes.
 */
class MainActivity : ComponentActivity() {

    private lateinit var service: CyberbotService
    private lateinit var kioskManager: KioskManager
    private lateinit var audioCapture: AudioCaptureManager
    private lateinit var audioPlayback: AudioPlaybackManager
    private lateinit var wakeWordDetector: WakeWordDetector
    private lateinit var cameraManager: CameraManager
    private lateinit var backendClient: BackendClient
    private lateinit var sessionId: String

    @Volatile private var previewView: PreviewView? = null

    // Continuous-conversation inactivity timer.
    private val conversationHandler = Handler(Looper.getMainLooper())
    @Volatile private var conversationDeadline = 0L
    private val conversationTick = object : Runnable {
        override fun run() {
            val remaining = conversationDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                Log.i(TAG, "Conversation timeout: returning to standby")
                endConversation()
            } else {
                Log.i(TAG, "Conversation active: ${remaining}ms remaining")
                conversationHandler.postDelayed(this, CONVERSATION_TICK_MS)
            }
        }
    }

    private val frameHandler = Handler(Looper.getMainLooper())
    private val frameRunnable = object : Runnable {
        override fun run() {
            cameraManager.captureFrame { bytes ->
                Log.i(TAG, "Camera frame captured: ${bytes.size} bytes")
                backendClient.sendCameraFrame(bytes)
            }
            frameHandler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            Log.i(TAG, "Permission results: $result")
            if (result[Manifest.permission.RECORD_AUDIO] == true) {
                onPermissionsReady()
            } else {
                Log.e(TAG, "RECORD_AUDIO denied; cannot operate")
                service.setError()
            }
            if (result[Manifest.permission.CAMERA] == true) {
                startCamera()
            } else {
                Log.w(TAG, "CAMERA denied; camera frame capture disabled")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        service = CyberbotService()
        kioskManager = KioskManager(this)
        audioCapture = AudioCaptureManager(this)
        audioPlayback = AudioPlaybackManager(this)
        wakeWordDetector = WakeWordDetector(applicationContext) {
            runOnUiThread { onWakeWord() }
        }
        cameraManager = CameraManager(this, this)
        sessionId = getOrCreateSessionId()

        backendClient = BackendClient(
            onStateUpdate = { state -> runOnUiThread { service.setState(state) } },
            onResponse = { response -> runOnUiThread { handleResponse(response) } },
            onError = { error ->
                Log.e(TAG, "Backend error: $error")
                runOnUiThread { service.setError() }
            },
        )

        setContent {
            CyberBotTheme {
                val state by service.state.collectAsState()
                Box(modifier = Modifier.fillMaxSize()) {
                    HologramRenderer(state = state, modifier = Modifier.fillMaxSize())
                    // Tiny, effectively-hidden preview that keeps the camera
                    // stream active so frames can be captured on demand.
                    AndroidView(
                        factory = { ctx -> PreviewView(ctx).also { previewView = it } },
                        modifier = Modifier.size(1.dp),
                    )
                }
            }
        }

        requestPermissions()
    }

    override fun onResume() {
        super.onResume()

        try {
            kioskManager.enableKioskMode(this)
        } catch (e: Exception) {
            Log.e(TAG, "enableKioskMode call failed", e)
        }

        // Auto-dismiss the "App is pinned" system dialog
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val dismissIntent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                sendBroadcast(dismissIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to dismiss pin dialog", e)
            }
        }, 500)
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA),
        )
    }

    private fun onPermissionsReady() {
        backendClient.connect(sessionId)
        // Idle in STANDBY, listening for the "hey cyberbot" wake word.
        returnToStandby()
    }

    /** Initialize the front camera and begin periodic frame capture. */
    private fun startCamera() {
        cameraManager.initialize(previewView?.surfaceProvider)
        frameHandler.removeCallbacks(frameRunnable)
        frameHandler.postDelayed(frameRunnable, FRAME_INTERVAL_MS)
        Log.i(TAG, "Camera started; capturing a frame every ${FRAME_INTERVAL_MS}ms")
    }

    /** Go idle: STANDBY state + wake-word listening. */
    private fun returnToStandby() {
        service.setStandby()
        wakeWordDetector.start()
    }

    /** Wake word heard: stop wake-word mic and start capturing the request. */
    private fun onWakeWord() {
        Log.i(TAG, "Wake word detected; switching to active listening")
        wakeWordDetector.stop() // release the mic before AudioCaptureManager opens it
        startListeningCycle()
    }

    private fun startListeningCycle() {
        service.startListening()
        audioCapture.startCapture { audio ->
            runOnUiThread {
                // The user spoke: pause the inactivity timer while we process.
                cancelConversationTimer()
                service.setThinking()
            }
            backendClient.sendAudio(audio)
        }
    }

    /**
     * Continuous conversation: keep listening for the next question without a
     * wake word, and arm a 60s inactivity timer that falls back to STANDBY.
     */
    private fun startContinuousConversation() {
        resetConversationTimer()
        startListeningCycle()
    }

    private fun resetConversationTimer() {
        conversationHandler.removeCallbacks(conversationTick)
        conversationDeadline =
            SystemClock.elapsedRealtime() + Constants.CONVERSATION_TIMEOUT_MS
        conversationHandler.postDelayed(conversationTick, CONVERSATION_TICK_MS)
    }

    private fun cancelConversationTimer() {
        conversationHandler.removeCallbacks(conversationTick)
    }

    /** Inactivity timed out: stop listening and require the wake word again. */
    private fun endConversation() {
        cancelConversationTimer()
        audioCapture.stopCapture()
        returnToStandby()
    }

    private fun handleResponse(response: CyberbotResponse) {
        if (response.state == CyberbotState.ERROR) {
            service.setError()
            return
        }
        service.setSpeaking()

        // After speaking, stay in continuous conversation for 60s (no wake word).
        val onComplete: () -> Unit = { runOnUiThread { startContinuousConversation() } }
        val url = response.tts_url

        when {
            url != null && url.startsWith("data:") -> {
                val base64 = url.substringAfter("base64,", "")
                if (base64.isNotEmpty()) {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    audioPlayback.playFromBytes(bytes, onComplete)
                } else {
                    onComplete()
                }
            }
            url != null -> audioPlayback.playFromUrl(url, onComplete)
            else -> onComplete()
        }
    }

    private fun getOrCreateSessionId(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        var id = prefs.getString(Constants.SESSION_ID_KEY, null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString(Constants.SESSION_ID_KEY, id).apply()
        }
        return id
    }

    override fun onDestroy() {
        super.onDestroy()
        conversationHandler.removeCallbacks(conversationTick)
        frameHandler.removeCallbacks(frameRunnable)
        cameraManager.release()
        audioCapture.stopCapture()
        audioPlayback.stop()
        wakeWordDetector.shutdown()
        backendClient.disconnect()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "cyberbot"
        private const val FRAME_INTERVAL_MS = 30000L
        private const val CONVERSATION_TICK_MS = 10000L
    }
}
