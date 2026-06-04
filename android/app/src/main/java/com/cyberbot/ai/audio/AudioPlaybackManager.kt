package com.cyberbot.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Plays MP3 audio returned by the backend, either from a URL or from raw bytes
 * (e.g. a base64 `data:` URI decoded by the caller). Invokes `onComplete` when
 * playback finishes or fails, so the UI can return to listening.
 */
class AudioPlaybackManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    // When stopped manually (e.g. barge-in), the onComplete callback is skipped
    // so the caller does not double-trigger the next listening cycle.
    @Volatile private var manuallyStopped = false

    fun playFromUrl(url: String, onComplete: () -> Unit) {
        // The backend delivers TTS as a base64 "data:" URI, which MediaPlayer
        // cannot stream directly. Decode it and play from bytes instead.
        if (url.startsWith("data:")) {
            Log.i(TAG, "Playback from data URI")
            val base64 = url.substringAfter("base64,", "")
            if (base64.isEmpty()) {
                Log.e(TAG, "data URI has no base64 payload")
                onComplete()
                return
            }
            val bytes = try {
                Base64.decode(base64, Base64.DEFAULT)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode data URI base64: ${e.message}")
                onComplete()
                return
            }
            playFromBytes(bytes, onComplete)
            return
        }

        // Regular http(s) URL.
        Log.i(TAG, "Playback from URL")
        stop()
        val player = newPlayer(onComplete)
        try {
            player.setDataSource(url)
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "playFromUrl failed: ${e.message}")
            player.release()
            onComplete()
        }
    }

    fun playFromBytes(audioBytes: ByteArray, onComplete: () -> Unit) {
        Log.i(TAG, "Playback from ${audioBytes.size} bytes")
        stop()
        val tempFile = try {
            File.createTempFile("cyberbot_tts", ".mp3", context.cacheDir).apply {
                writeBytes(audioBytes)
                deleteOnExit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to buffer audio bytes: ${e.message}")
            onComplete()
            return
        }

        val player = newPlayer {
            tempFile.delete()
            onComplete()
        }
        try {
            player.setDataSource(tempFile.absolutePath)
            player.prepareAsync()
            mediaPlayer = player
        } catch (e: Exception) {
            Log.e(TAG, "playFromBytes failed: ${e.message}")
            player.release()
            tempFile.delete()
            onComplete()
        }
    }

    fun stop() {
        manuallyStopped = true
        mediaPlayer?.let { player ->
            // pause() halts audio output instantly; stop()/release() then tear
            // down without the audible tail that a bare stop() can leave.
            try {
                if (player.isPlaying) {
                    player.pause()
                    player.seekTo(0)
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pause/seek failed: ${e.message}")
            }
            try {
                player.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
            player.release()
        }
        mediaPlayer = null
        Log.i(TAG, "Playback stopped manually")
    }

    private fun newPlayer(onComplete: () -> Unit): MediaPlayer {
        // A fresh playback is starting, so it was not manually stopped (yet).
        manuallyStopped = false
        return MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnPreparedListener {
                Log.i(TAG, "Playback started")
                it.start()
            }
            setOnCompletionListener {
                if (manuallyStopped) return@setOnCompletionListener
                Log.i(TAG, "Playback complete")
                onComplete()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                if (!manuallyStopped) onComplete()
                true
            }
        }
    }

    companion object {
        private const val TAG = "AudioPlayback"
    }
}
