package com.cyberbot.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Streams raw PCM (24 kHz mono 16-bit) chunks to an AudioTrack as they arrive
 * over the network, for instant interruption.
 *
 * Flow: [startStream] (begins the playback thread) -> [addChunk] per received
 * chunk -> [signalEnd] when the backend sent tts_end. [stop] silences and
 * clears everything within one chunk.
 */
class AudioPlaybackManager(private val context: Context) {

    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile private var manuallyStopped = false
    @Volatile private var endSignaled = false
    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null
    private var onComplete: (() -> Unit)? = null

    /** Begin a new streaming playback session. */
    fun startStream(onComplete: () -> Unit) {
        stop() // tear down any previous session
        manuallyStopped = false
        endSignaled = false
        queue.clear()
        this.onComplete = onComplete
        playbackThread = thread(name = "TtsStream") { runPlayback() }
        Log.i(TAG, "TTS stream started")
    }

    /** Enqueue a raw PCM chunk for playback. */
    fun addChunk(pcm: ByteArray) {
        if (!manuallyStopped) queue.offer(pcm)
    }

    /** Signal that no more chunks will arrive; playback finishes when drained. */
    fun signalEnd() {
        endSignaled = true
    }

    fun stop() {
        manuallyStopped = true
        queue.clear()
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pause/flush failed: ${e.message}")
            }
        }
        releaseTrack()
    }

    fun release() {
        stop()
    }

    private fun runPlayback() {
        // Run at audio priority so the OS scheduler keeps this thread ahead of
        // ordinary work -- avoids jitter/delay feeding the AudioTrack.
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        // Use the device's true minimum buffer for 24 kHz mono 16-bit PCM so
        // the first chunk reaches the speaker with the least latency possible.
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuffer > 0) minBuffer else 4096

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack = track
        // Start the track BEFORE the chunk loop so it plays the first chunk the
        // instant it is written -- no waiting to accumulate chunks.
        track.play()

        try {
            while (!manuallyStopped) {
                val chunk = queue.poll(50, TimeUnit.MILLISECONDS)
                if (chunk != null) {
                    track.write(chunk, 0, chunk.size)
                } else if (endSignaled) {
                    break // end signaled and nothing left to play
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Stream playback error: ${e.message}", e)
        }

        if (!manuallyStopped) {
            try {
                track.stop() // drain remaining buffered audio
            } catch (e: IllegalStateException) {
                Log.w(TAG, "track stop failed: ${e.message}")
            }
        }
        releaseTrack()

        if (!manuallyStopped) {
            Log.i(TAG, "TTS stream complete")
            onComplete?.invoke()
        }
    }

    private fun releaseTrack() {
        audioTrack?.let { track ->
            try {
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack release failed: ${e.message}")
            }
        }
        audioTrack = null
    }

    companion object {
        private const val TAG = "AudioPlayback"
        private const val SAMPLE_RATE = 24000
    }
}
