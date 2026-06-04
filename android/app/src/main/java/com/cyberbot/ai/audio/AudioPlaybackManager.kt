package com.cyberbot.ai.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import kotlin.concurrent.thread

/**
 * Plays MP3 audio with **instant interruption**.
 *
 * Instead of MediaPlayer (which buffers the whole clip and has a slow stop),
 * the MP3 is decoded to raw PCM with MediaCodec and streamed to an AudioTrack
 * in small chunks. Between every chunk the loop checks [manuallyStopped], so a
 * call to [stop] silences playback within one chunk (~a few ms).
 *
 * The AudioTrack is configured from the PCM's actual sample rate and channel
 * count (TTS providers differ: OpenAI is 24 kHz mono, ElevenLabs 44.1 kHz).
 */
class AudioPlaybackManager(private val context: Context) {

    @Volatile private var manuallyStopped = false
    @Volatile private var audioTrack: AudioTrack? = null
    private var playbackThread: Thread? = null

    fun playFromUrl(url: String, onComplete: () -> Unit) {
        if (url.startsWith("data:")) {
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

        // The backend sends data URIs, but support plain URLs defensively.
        thread(name = "AudioDownload") {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                playFromBytes(bytes, onComplete)
            } catch (e: Exception) {
                Log.e(TAG, "playFromUrl failed: ${e.message}")
                onComplete()
            }
        }
    }

    fun playFromBytes(audioBytes: ByteArray, onComplete: () -> Unit) {
        stop() // halt any current playback first
        manuallyStopped = false

        playbackThread = thread(name = "AudioPlayback") {
            var completedNaturally = false
            try {
                val pcm = decodeMp3ToPcm(audioBytes)
                if (pcm != null && !manuallyStopped) {
                    streamPcm(pcm)
                    completedNaturally = !manuallyStopped
                }
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed: ${e.message}", e)
            } finally {
                releaseTrack()
            }

            if (completedNaturally) {
                Log.i(TAG, "Playback complete")
                onComplete()
            }
        }
    }

    fun stop() {
        manuallyStopped = true
        // pause()+flush() silences and discards buffered audio immediately.
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pause/flush failed: ${e.message}")
            }
        }
        releaseTrack()
        Log.i(TAG, "Playback stopped manually")
    }

    private fun streamPcm(pcm: Pcm) {
        val channelConfig =
            if (pcm.channels >= 2) AudioFormat.CHANNEL_OUT_STEREO
            else AudioFormat.CHANNEL_OUT_MONO

        val minBuffer = AudioTrack.getMinBufferSize(
            pcm.sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = if (minBuffer > 0) maxOf(minBuffer, CHUNK_BYTES) else CHUNK_BYTES * 2

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(pcm.sampleRate)
                .setChannelMask(channelConfig)
                .build(),
            bufferSize,
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE,
        )
        audioTrack = track
        track.play()
        Log.i(
            TAG,
            "Playback started (sr=${pcm.sampleRate}, ch=${pcm.channels}, ${pcm.data.size} PCM bytes)",
        )

        // Write PCM in small chunks, bailing out instantly if stopped.
        val data = pcm.data
        var offset = 0
        while (offset < data.size && !manuallyStopped) {
            val length = minOf(CHUNK_BYTES, data.size - offset)
            val written = track.write(data, offset, length)
            if (written <= 0) {
                Log.e(TAG, "AudioTrack write returned $written; aborting")
                break
            }
            offset += written
        }

        // Let the already-queued audio drain so the tail isn't clipped.
        if (!manuallyStopped) {
            val bytesPerFrame = 2 * pcm.channels
            val totalFrames = if (bytesPerFrame > 0) data.size / bytesPerFrame else 0
            while (!manuallyStopped && track.playbackHeadPosition < totalFrames) {
                Thread.sleep(20)
            }
        }
    }

    private fun releaseTrack() {
        audioTrack?.let { track ->
            try {
                if (track.state == AudioTrack.STATE_INITIALIZED) {
                    track.stop()
                }
            } catch (e: IllegalStateException) {
                Log.w(TAG, "AudioTrack stop failed: ${e.message}")
            }
            try {
                track.release()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack release failed: ${e.message}")
            }
        }
        audioTrack = null
    }

    /** Decode an MP3 byte array to 16-bit PCM using MediaCodec. */
    private fun decodeMp3ToPcm(mp3Bytes: ByteArray): Pcm? {
        val tempFile = File.createTempFile("cyberbot_tts", ".mp3", context.cacheDir)
        try {
            tempFile.writeBytes(mp3Bytes)

            val extractor = MediaExtractor()
            extractor.setDataSource(tempFile.absolutePath)

            var trackIndex = -1
            var inputFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    trackIndex = i
                    inputFormat = format
                    break
                }
            }
            if (trackIndex < 0 || inputFormat == null) {
                Log.e(TAG, "No audio track found in MP3")
                extractor.release()
                return null
            }
            extractor.selectTrack(trackIndex)

            val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!
            var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(inputFormat, null, null, 0)
            codec.start()

            val output = ByteArrayOutputStream()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS && !manuallyStopped) {
                if (!sawInputEOS) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEOS = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = codec.outputFormat
                        sampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* no output yet */ }
                    else -> if (outIndex >= 0) {
                        val outputBuffer = codec.getOutputBuffer(outIndex)!!
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        outputBuffer.clear()
                        output.write(chunk)
                        codec.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEOS = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()
            extractor.release()
            return Pcm(output.toByteArray(), sampleRate, channels)
        } catch (e: Exception) {
            Log.e(TAG, "decodeMp3ToPcm failed: ${e.message}", e)
            return null
        } finally {
            tempFile.delete()
        }
    }

    private data class Pcm(val data: ByteArray, val sampleRate: Int, val channels: Int)

    companion object {
        private const val TAG = "AudioPlayback"
        private const val CHUNK_BYTES = 4096
        private const val TIMEOUT_US = 10000L
    }
}
