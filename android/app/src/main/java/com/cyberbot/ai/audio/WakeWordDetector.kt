package com.cyberbot.ai.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.cyberbot.ai.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.math.abs

/**
 * Offline "Hey CyberBot" wake-word detector built on Vosk.
 *
 * Unlike Vosk's bundled SpeechService (which opens its own microphone with
 * AudioSource.VOICE_RECOGNITION — silent on the YF-088D), this feeds the Vosk
 * [Recognizer] directly from our own [AudioRecord] using
 * AudioSource.VOICE_COMMUNICATION, the source that actually captures audio on
 * this device.
 *
 * On first run the small English model is downloaded and unzipped into
 * ``filesDir/vosk-model``. Then the recognizer listens continuously and fires
 * [onWakeWordDetected] as soon as it hears "cyberbot" / "cyber bot".
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var model: Model? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var listening = false
    @Volatile private var triggered = false
    private var captureJob: Job? = null

    /** Load the model if needed and begin (or resume) wake-word listening. */
    fun start() {
        if (listening) {
            Log.d(TAG, "Wake word already listening")
            return
        }
        triggered = false
        listening = true
        captureJob = scope.launch {
            try {
                if (model == null) {
                    val modelDir = ensureModel()
                    model = Model(modelDir.absolutePath)
                    Log.i(TAG, "Vosk model loaded from ${modelDir.absolutePath}")
                }
                val loaded = model
                if (loaded == null) {
                    Log.e(TAG, "Model unavailable; cannot start wake-word")
                    listening = false
                    return@launch
                }
                runRecognitionLoop(loaded)
            } catch (e: Exception) {
                Log.e(TAG, "WakeWordDetector start failed", e)
                listening = false
            }
        }
    }

    /**
     * Re-arm detection after an ignored trigger (e.g. during the speech grace
     * period) without restarting the audio loop. Lets the detector fire again.
     */
    fun rearm() {
        triggered = false
    }

    /** Stop listening and release the microphone (model stays loaded). */
    fun stop() {
        // The capture loop checks this flag every chunk (~256 ms) and then
        // releases the AudioRecord/Recognizer in its finally block.
        listening = false
        Log.i(TAG, "Wake word stop requested")
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

    @SuppressLint("MissingPermission") // RECORD_AUDIO is verified by the caller.
    private fun runRecognitionLoop(loadedModel: Model) {
        // Grammar mode constrains the recognizer to the wake phrase plus "[unk]".
        // Everything that is NOT "cyber bot" is mapped to [unk] and ignored, so
        // ordinary speech (and the bot's own voice) can no longer trip the wake
        // word. Generic fillers like "hey"/"computer"/"robot" were removed
        // because they fired on any conversation.
        val grammar = """["cyber bot", "[unk]"]"""
        val recognizer = Recognizer(loadedModel, SAMPLE_RATE_F, grammar)
        val minBuffer = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT,
        )
        val bufferSizeBytes = maxOf(minBuffer, CHUNK_SAMPLES * 2)

        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            Constants.SAMPLE_RATE,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT,
            bufferSizeBytes,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Wake-word AudioRecord failed to initialize")
            record.release()
            recognizer.close()
            listening = false
            return
        }
        audioRecord = record

        val buffer = ShortArray(CHUNK_SAMPLES)
        try {
            record.startRecording()
            Log.i(TAG, "Wake word capture started (VOICE_COMMUNICATION)")

            while (listening) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Log amplitude to confirm the mic is actually capturing.
                var sum = 0L
                for (i in 0 until read) sum += abs(buffer[i].toInt())
                val avgAmplitude = (sum / read).toInt()
                Log.d(TAG, "Wake chunk amplitude: $avgAmplitude")

                val isFinal = recognizer.acceptWaveForm(buffer, read)
                val hypothesis =
                    if (isFinal) recognizer.result else recognizer.partialResult
                checkHypothesis(hypothesis, isFinal)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Wake word capture loop error", e)
        } finally {
            try {
                record.stop()
            } catch (e: Exception) {
                Log.e(TAG, "AudioRecord stop failed", e)
            }
            record.release()
            audioRecord = null
            try {
                recognizer.close()
            } catch (e: Exception) {
                Log.e(TAG, "Recognizer close failed", e)
            }
            Log.i(TAG, "Wake word capture stopped")
        }
    }

    private fun checkHypothesis(hypothesis: String?, isFinal: Boolean) {
        if (hypothesis.isNullOrBlank()) return
        val text = try {
            val json = JSONObject(hypothesis)
            json.optString("partial", json.optString("text", ""))
        } catch (e: Exception) {
            hypothesis
        }.lowercase()

        if (text.isBlank()) return

        // Always log what Vosk heard, to help tune detection on-device.
        if (isFinal) {
            Log.d(TAG, "Vosk result: $text")
        } else {
            Log.d(TAG, "Vosk partial: $text")
        }

        if (WAKE_WORDS.any { text.contains(it) }) {
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
        private const val SAMPLE_RATE_F = 16000.0f
        private const val CHUNK_SAMPLES = 1024

        // Only the full wake phrase triggers activation. Single generic words
        // ("cyber", "computer", "robot", "hey") were removed: matched as a
        // substring they fired on ordinary speech, so the bot listened/answered
        // without a real wake word.
        private val WAKE_WORDS = listOf(
            "cyber bot", "cyberbot",
        )
        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"
    }
}
