package com.kaliscriber.app.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.util.Log
import com.kaliscriber.core.audio.Wav
import com.kaliscriber.core.model.AudioChunk
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.sqrt

/**
 * Captures 16 kHz mono PCM from the microphone on a background thread and, at the same time:
 * - encodes the whole meeting to AAC in an `.m4a` file (small enough to upload for a final
 *   diarization pass and to play back), and
 * - cuts the PCM into WAV chunks of [chunkMillis] that are handed to [Listener.onChunk] for live
 *   transcription.
 */
class AudioCaptureEngine(
    private val outputFile: File,
    private val chunkDir: File,
    private val chunkMillis: Long,
    private val listener: Listener,
) {
    interface Listener {
        fun onChunk(chunk: AudioChunk)
        fun onProgress(capturedMs: Long, level: Float)
        fun onError(error: Throwable)
    }

    @Volatile private var running = false
    @Volatile var paused = false
    @Volatile var capturedMs = 0L
        private set

    private var thread: Thread? = null
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // Checked by the UI before recording starts.
    fun start() {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw IOException("The microphone is not available")
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer * 4, BYTES_PER_SECOND / 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("Could not open the microphone. Is another app using it?")
        }
        try {
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            throw IOException("Could not start recording: ${e.message}", e)
        }
        chunkDir.mkdirs()
        record = rec
        running = true
        thread = Thread({ captureLoop(rec) }, "kaliscriber-audio").apply { start() }
    }

    /** Stops capture, flushes the last chunk and finalizes the m4a. Blocks until done. */
    fun stop(): Long {
        running = false
        thread?.join(10_000)
        thread = null
        record?.let {
            runCatching { it.stop() }
            it.release()
        }
        record = null
        return capturedMs
    }

    private fun captureLoop(rec: AudioRecord) {
        val encoder = runCatching { AacFileEncoder(outputFile, SAMPLE_RATE, BIT_RATE) }
            .onFailure { Log.w(TAG, "AAC encoder unavailable; only live chunks will be kept", it) }
            .getOrNull()
        val readBuffer = ByteArray(BYTES_PER_SECOND / 10) // 100 ms
        val chunkTarget = (BYTES_PER_SECOND * chunkMillis / 1000).toInt()
        val chunk = ByteArrayOutputStream(chunkTarget + readBuffer.size)
        var chunkIndex = 0
        var capturedBytes = 0L
        var chunkStartBytes = 0L
        var encoderOk = encoder != null

        fun emitChunk() {
            val startMs = Wav.durationMs(chunkStartBytes, SAMPLE_RATE)
            val endMs = Wav.durationMs(capturedBytes, SAMPLE_RATE)
            val file = File(chunkDir, RecordingManager.chunkFileName(chunkIndex, startMs, endMs))
            Wav.write(file, chunk.toByteArray(), chunk.size(), SAMPLE_RATE)
            listener.onChunk(AudioChunk(chunkIndex, file.absolutePath, startMs, endMs))
            chunkIndex++
            chunk.reset()
            chunkStartBytes = capturedBytes
        }

        try {
            while (running) {
                val n = rec.read(readBuffer, 0, readBuffer.size)
                if (n < 0) throw IOException("Microphone read failed ($n)")
                if (n == 0) continue
                if (paused) {
                    listener.onProgress(capturedMs, 0f)
                    continue
                }
                if (encoderOk) {
                    encoderOk = runCatching { encoder!!.encode(readBuffer, n) }
                        .onFailure { Log.w(TAG, "AAC encoding failed", it) }
                        .isSuccess
                }
                chunk.write(readBuffer, 0, n)
                capturedBytes += n
                capturedMs = Wav.durationMs(capturedBytes, SAMPLE_RATE)
                listener.onProgress(capturedMs, level(readBuffer, n))
                if (chunk.size() >= chunkTarget) emitChunk()
            }
            // Keep a trailing chunk if it holds at least a second of audio.
            if (chunk.size() >= BYTES_PER_SECOND) emitChunk()
        } catch (t: Throwable) {
            Log.e(TAG, "Capture failed", t)
            runCatching { if (chunk.size() >= BYTES_PER_SECOND) emitChunk() }
            listener.onError(t)
        } finally {
            val ok = encoder != null && runCatching { encoder.finish() }.getOrDefault(false) && encoderOk
            if (!ok) outputFile.delete()
        }
    }

    /** Perceived loudness 0..1 from RMS of 16-bit little-endian samples. */
    private fun level(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var i = 0
        while (i + 1 < length) {
            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
            count++
            i += 2
        }
        if (count == 0) return 0f
        val rms = sqrt(sum / count) / Short.MAX_VALUE
        return sqrt(rms).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val BIT_RATE = 32_000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val TAG = "AudioCapture"
    }
}

/** Encodes PCM to AAC-LC and muxes it into an MPEG-4 (.m4a) file. */
private class AacFileEncoder(file: File, private val sampleRate: Int, bitRate: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var samplesQueued = 0L
    private var wroteSamples = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            muxer.release()
            throw e
        }
    }

    fun encode(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index < 0) {
                drain(endOfStream = false)
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: continue
            buffer.clear()
            val n = minOf(buffer.remaining(), length - offset)
            buffer.put(pcm, offset, n)
            codec.queueInputBuffer(index, 0, n, presentationTimeUs(), 0)
            samplesQueued += n / 2
            offset += n
            drain(endOfStream = false)
        }
    }

    /** Returns true if a playable file was written. */
    fun finish(): Boolean {
        try {
            var index = -1
            repeat(50) {
                if (index < 0) index = codec.dequeueInputBuffer(10_000)
            }
            if (index >= 0) {
                codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(endOfStream = true)
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            val stopped = muxerStarted && runCatching { muxer.stop() }.isSuccess
            runCatching { muxer.release() }
            if (!stopped) wroteSamples = false
        }
        return wroteSamples
    }

    private fun drain(endOfStream: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idle > 200) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted && out != null) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                        wroteSamples = true
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun presentationTimeUs(): Long = samplesQueued * 1_000_000L / sampleRate
}
