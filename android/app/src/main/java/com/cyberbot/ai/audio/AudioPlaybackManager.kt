package com.cyberbot.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import java.io.File

/**
 * Plays MP3 audio returned by the backend, either from a URL or from raw bytes
 * (e.g. a base64 `data:` URI decoded by the caller). Invokes `onComplete` when
 * playback finishes or fails, so the UI can return to listening.
 */
class AudioPlaybackManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    fun playFromUrl(url: String, onComplete: () -> Unit) {
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
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "stop failed: ${e.message}")
            }
            player.release()
        }
        mediaPlayer = null
    }

    private fun newPlayer(onComplete: () -> Unit): MediaPlayer =
        MediaPlayer().apply {
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
                Log.i(TAG, "Playback complete")
                onComplete()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "Playback error: what=$what extra=$extra")
                onComplete()
                true
            }
        }

    companion object {
        private const val TAG = "AudioPlayback"
    }
}
