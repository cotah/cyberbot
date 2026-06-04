package com.cyberbot.ai.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline "Hey CyberBot" wake-word detector built on Vosk.
 *
 * On first run the small English model is downloaded and unzipped into
 * ``filesDir/vosk-model``. Then Vosk listens continuously in the background and
 * fires [onWakeWordDetected] as soon as it hears "cyberbot" / "cyber bot".
 *
 * Note: Vosk opens its own microphone (AudioSource.VOICE_RECOGNITION). Call
 * [stop] before starting [AudioCaptureManager] so the two never fight over the
 * mic, and [start] again afterwards to resume wake-word listening.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var model: Model? = null
    @Volatile private var speechService: SpeechService? = null
    @Volatile private var triggered = false

    /** Load the model if needed and begin (or resume) wake-word listening. */
    fun start() {
        triggered = false
        if (speechService != null) {
            Log.d(TAG, "Wake word already listening")
            return
        }
        scope.launch {
            try {
                if (model == null) {
                    val modelDir = ensureModel()
                    model = Model(modelDir.absolutePath)
                    Log.i(TAG, "Vosk model loaded from ${modelDir.absolutePath}")
                }
                val loaded = model ?: return@launch
                withContext(Dispatchers.Main) { beginListening(loaded) }
            } catch (e: Exception) {
                Log.e(TAG, "WakeWordDetector start failed", e)
            }
        }
    }

    /** Stop listening and release the microphone (model stays loaded). */
    fun stop() {
        speechService?.let { service ->
            try {
                service.stop()
            } catch (e: Exception) {
                Log.e(TAG, "SpeechService stop failed", e)
            }
            try {
                service.shutdown()
            } catch (e: Exception) {
                Log.e(TAG, "SpeechService shutdown failed", e)
            }
        }
        speechService = null
        Log.i(TAG, "Wake word listening stopped")
    }

    /** Fully release the detector (model included). */
    fun shutdown() {
        stop()
        try {
            model?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Model close failed", e)
        }
        model = null
        scope.cancel()
        Log.i(TAG, "WakeWordDetector shut down")
    }

    private fun beginListening(loadedModel: Model) {
        try {
            val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
            val service = SpeechService(recognizer, SAMPLE_RATE)
            speechService = service
            service.startListening(listener)
            Log.i(TAG, "Wake word listening started (say 'hey cyberbot')")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to begin wake-word listening", e)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = checkHypothesis(hypothesis)
        override fun onResult(hypothesis: String?) = checkHypothesis(hypothesis)
        override fun onFinalResult(hypothesis: String?) = checkHypothesis(hypothesis)
        override fun onError(exception: Exception?) {
            Log.e(TAG, "Vosk error: ${exception?.message}", exception)
        }
        override fun onTimeout() {
            Log.d(TAG, "Vosk timeout")
        }
    }

    private fun checkHypothesis(hypothesis: String?) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            val json = JSONObject(hypothesis)
            json.optString("partial", json.optString("text", ""))
        } catch (e: Exception) {
            hypothesis
        }.lowercase()

        if (text.isBlank()) return
        if (text.contains("cyberbot") || text.contains("cyber bot")) {
            if (!triggered) {
                triggered = true
                Log.i(TAG, "Wake word detected in: \"$text\"")
                onWakeWordDetected()
            }
        }
    }

    /** Ensure the Vosk model exists on disk, downloading + unzipping if needed. */
    private fun ensureModel(): File {
        val modelDir = File(context.filesDir, "vosk-model")
        val readyMarker = File(modelDir, ".ready")
        if (readyMarker.exists()) {
            return modelRoot(modelDir)
        }

        Log.i(TAG, "Downloading Vosk model from $MODEL_URL ...")
        modelDir.mkdirs()
        val zipFile = File(context.cacheDir, "vosk-model.zip")
        URL(MODEL_URL).openStream().use { input ->
            FileOutputStream(zipFile).use { output -> input.copyTo(output) }
        }

        Log.i(TAG, "Unzipping Vosk model (${zipFile.length()} bytes) ...")
        unzip(zipFile, modelDir)
        zipFile.delete()
        readyMarker.createNewFile()
        Log.i(TAG, "Vosk model ready")
        return modelRoot(modelDir)
    }

    /** The zip extracts into a nested folder; return the dir that holds the model. */
    private fun modelRoot(modelDir: File): File {
        val nested = modelDir.listFiles()
            ?.firstOrNull { it.isDirectory && File(it, "conf").exists() }
        return nested ?: modelDir
    }

    private fun unzip(zip: File, targetDir: File) {
        ZipInputStream(BufferedInputStream(FileInputStream(zip))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val SAMPLE_RATE = 16000.0f
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
