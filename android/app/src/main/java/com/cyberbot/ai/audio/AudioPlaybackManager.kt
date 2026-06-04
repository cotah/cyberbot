package com.cyberbot.ai.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import java.io.File

/**
 * Simple MP3 playback via MediaPlayer with an instant stop (pause + stop).
 * Manual stops suppress the onComplete callback so the caller does not
 * double-trigger the next listening cycle (used by barge-in).
 */
class AudioPlaybackManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    private var manuallyStopped = false

    fun playFromBytes(audioBytes: ByteArray, onComplete: () -> Unit) {
        manuallyStopped = false
        mediaPlayer?.release()

        val tempFile = File.createTempFile("tts", ".mp3", context.cacheDir)
        tempFile.writeBytes(audioBytes)

        mediaPlayer = MediaPlayer().apply {
            setDataSource(tempFile.absolutePath)
            setOnCompletionListener {
                tempFile.delete()
                if (!manuallyStopped) onComplete()
            }
            setOnErrorListener { _, _, _ ->
                tempFile.delete()
                if (!manuallyStopped) onComplete()
                true
            }
            prepare()
            start()
        }
        Log.i(TAG, "Playback started (${audioBytes.size} bytes)")
    }

    fun playFromUrl(url: String, onComplete: () -> Unit) {
        if (url.startsWith("data:")) {
            val base64 = url.substringAfter("base64,", "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            playFromBytes(bytes, onComplete)
            return
        }

        // Plain URL (not used by the backend, which sends data URIs).
        manuallyStopped = false
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(url)
            setOnCompletionListener { if (!manuallyStopped) onComplete() }
            setOnErrorListener { _, _, _ ->
                if (!manuallyStopped) onComplete()
                true
            }
            setOnPreparedListener { start() }
            prepareAsync()
        }
    }

    fun stop() {
        manuallyStopped = true
        mediaPlayer?.let {
            try {
                if (it.isPlaying) {
                    it.pause()
                    it.stop()
                }
                it.release()
            } catch (e: Exception) {
                Log.e(TAG, "Stop error", e)
            }
        }
        mediaPlayer = null
        Log.i(TAG, "Playback stopped manually")
    }

    fun release() {
        stop()
    }

    companion object {
        private const val TAG = "AudioPlayback"
    }
}
