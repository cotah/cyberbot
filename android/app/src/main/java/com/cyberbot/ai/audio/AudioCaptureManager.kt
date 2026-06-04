package com.cyberbot.ai.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.cyberbot.ai.util.Constants
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Captures raw PCM audio from the microphone (16 kHz, mono, 16-bit).
 *
 * Only chunks whose AVERAGE amplitude exceeds [Constants.SILENCE_THRESHOLD] are
 * accumulated, so leading/background silence is never sent to the backend. The
 * utterance is delivered via `onAudioReady` only after at least
 * [MIN_SPEECH_MS] of real speech has been captured AND the user has gone silent
 * for [Constants.SILENCE_DURATION_MS].
 */
class AudioCaptureManager(private val context: Context) {

    @Volatile private var isCapturing = false
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Permission is verified above before recording.
    fun startCapture(onAudioReady: (ByteArray) -> Unit) {
        if (!hasPermission()) {
            Log.e(TAG, "RECORD_AUDIO permission not granted; cannot start capture")
            return
        }
        if (isCapturing) {
            Log.w(TAG, "Capture already running")
            return
        }

        val minBuffer = AudioRecord.getMinBufferSize(
            Constants.SAMPLE_RATE,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT,
        )
        val bufferSize = if (minBuffer > 0) minBuffer else Constants.SAMPLE_RATE * 2

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            Constants.SAMPLE_RATE,
            Constants.CHANNEL_CONFIG,
            Constants.AUDIO_FORMAT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize")
            record.release()
            return
        }

        audioRecord = record
        isCapturing = true
        record.startRecording()
        Log.i(TAG, "Capture started (buffer=$bufferSize)")

        captureThread = thread(start = true, name = "AudioCapture") {
            val buffer = ShortArray(bufferSize)
            val output = ByteArrayOutputStream()
            var lastVoiceTime = System.currentTimeMillis()
            var hasSpoken = false
            var voicedDurationMs = 0L

            while (isCapturing) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                // Average amplitude of this chunk.
                var sum = 0L
                for (i in 0 until read) {
                    sum += abs(buffer[i].toInt())
                }
                val avgAmplitude = (sum / read).toInt()
                val chunkDurationMs = read.toLong() * 1000L / Constants.SAMPLE_RATE
                val now = System.currentTimeMillis()

                if (avgAmplitude > Constants.SILENCE_THRESHOLD) {
                    // Voiced chunk: accumulate it.
                    Log.d(TAG, "Audio chunk amplitude: $avgAmplitude — sending")
                    for (i in 0 until read) {
                        val sample = buffer[i].toInt()
                        output.write(sample and 0xFF)
                        output.write((sample shr 8) and 0xFF)
                    }
                    lastVoiceTime = now
                    hasSpoken = true
                    voicedDurationMs += chunkDurationMs
                } else {
                    // Silence chunk: skip (do not accumulate).
                    Log.d(TAG, "Audio chunk amplitude: $avgAmplitude — skipping")
                    val enoughSpeech = voicedDurationMs >= MIN_SPEECH_MS
                    val silentLongEnough = now - lastVoiceTime >= Constants.SILENCE_DURATION_MS
                    if (hasSpoken && enoughSpeech && silentLongEnough) {
                        val audio = output.toByteArray()
                        Log.i(
                            TAG,
                            "End of speech: ${voicedDurationMs}ms voiced; delivering ${audio.size} bytes",
                        )
                        onAudioReady(audio)
                        break
                    }
                }
            }
            stopInternal()
        }
    }

    fun stopCapture() {
        Log.i(TAG, "stopCapture requested")
        isCapturing = false
        captureThread = null
    }

    private fun stopInternal() {
        isCapturing = false
        try {
            audioRecord?.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "AudioRecord stop failed: ${e.message}")
        }
        audioRecord?.release()
        audioRecord = null
        Log.i(TAG, "Capture stopped")
    }

    companion object {
        private const val TAG = "AudioCapture"

        // Minimum amount of real (voiced) audio required before delivering.
        private const val MIN_SPEECH_MS = 1000L
    }
}
