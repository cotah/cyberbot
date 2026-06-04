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

    // Delays for releasing the microphone between consumers (wake word vs capture).
    private val micHandler = Handler(Looper.getMainLooper())

    // When the current TTS playback started (for the barge-in grace period).
    @Volatile private var speakingStartTime = 0L

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
        backendClient.onTtsChunk = { b64 ->
            runOnUiThread { audioPlayback.addChunk(Base64.decode(b64, Base64.NO_WRAP)) }
        }
        backendClient.onTtsEnd = {
            runOnUiThread { audioPlayback.signalEnd() }
        }

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

    /**
     * Go idle: stop everything, wait for the mic to be released, then restart
     * the wake-word detector and show STANDBY.
     */
    private fun returnToStandby() {
        audioCapture.stopCapture()
        cancelConversationTimer()
        micHandler.postDelayed({
            wakeWordDetector.start()
            service.setStandby()
        }, MIC_RELEASE_DELAY_MS)
    }

    /**
     * Wake word heard. If the assistant is currently speaking, this is a
     * barge-in: stop the TTS and start listening immediately. Otherwise it is a
     * normal activation from STANDBY.
     */
    private fun onWakeWord() {
        val current = service.state.value
        Log.i(TAG, "Wake word detected (state=$current)")
        if (current == CyberbotState.SPEAKING) {
            val elapsed = SystemClock.elapsedRealtime() - speakingStartTime
            if (elapsed < SPEAKING_GRACE_MS) {
                // Likely the assistant's own voice early in playback; ignore and
                // re-arm so a real interruption after the grace period still works.
                Log.i(TAG, "Wake word ignored: grace period (${elapsed}ms < ${SPEAKING_GRACE_MS}ms)")
                wakeWordDetector.rearm()
                return
            }
            Log.i(TAG, "Barge-in: interrupting speech to listen")
            audioPlayback.stop() // stop TTS now; onComplete is suppressed
        }
        // startContinuousConversation stops the wake word + any capture, waits
        // for the mic, then begins a fresh listening cycle with the 60s timer.
        startContinuousConversation()
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
     *
     * Stops every mic consumer first and waits [MIC_RELEASE_DELAY_MS] so the
     * microphone is fully released before opening a new capture (avoids the
     * AudioRecord contention that previously broke the second turn).
     */
    private fun startContinuousConversation() {
        wakeWordDetector.stop()
        audioCapture.stopCapture()
        micHandler.postDelayed({
            startListeningCycle()
            resetConversationTimer()
        }, MIC_RELEASE_DELAY_MS)
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
        returnToStandby()
    }

    private fun handleResponse(response: CyberbotResponse) {
        if (response.state == CyberbotState.ERROR) {
            service.setError()
            // Auto-recover: after 3s, drop back to STANDBY and re-arm wake word
            // instead of leaving a permanent red X.
            micHandler.postDelayed({ returnToStandby() }, ERROR_RECOVERY_DELAY_MS)
            return
        }
        service.setSpeaking()

        // Keep the wake word active during playback so the user can interrupt
        // ("barge-in") by saying "cyberbot". The mic is free here (capture has
        // already finished), so there is no AudioRecord contention with TTS.
        if (Constants.ALLOW_INTERRUPT_DURING_SPEECH) {
            speakingStartTime = SystemClock.elapsedRealtime()
            wakeWordDetector.start()
        }

        // Audio arrives as a stream of PCM chunks (onTtsChunk). Start streaming
        // playback now; when it finishes, stay in continuous conversation.
        audioPlayback.startStream {
            runOnUiThread { startContinuousConversation() }
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
        micHandler.removeCallbacksAndMessages(null)
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
        private const val MIC_RELEASE_DELAY_MS = 500L
        private const val ERROR_RECOVERY_DELAY_MS = 3000L
        private const val SPEAKING_GRACE_MS = 1500L
    }
}
