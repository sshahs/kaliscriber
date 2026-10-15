package com.kaliscriber.app.speech

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.floor

/**
 * Decodes audio files (our WAV chunks, the m4a recording, or imported files) into 16 kHz mono float
 * samples in [-1, 1], streaming blocks to a callback so long files never sit in memory twice.
 */
object AudioDecoder {
    const val SAMPLE_RATE = 16_000

    /** Calls [onSamples] with consecutive blocks of 16 kHz mono samples. */
    fun decode(file: File, onSamples: (FloatArray) -> Unit) {
        if (isPcmWav(file)) decodeWav(file, onSamples) else decodeCompressed(file, onSamples)
    }

    /** Decodes the whole file into one array (needed by speaker diarization). */
    fun decodeAll(file: File, maxSamples: Long = Long.MAX_VALUE): FloatArray {
        val expected = estimateSamples(file)
        if (expected != null && expected > maxSamples) throw TooLongException(expected / SAMPLE_RATE)
        var buffer = FloatArray(((expected ?: (SAMPLE_RATE * 60L)) + SAMPLE_RATE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        var size = 0
        decode(file) { block ->
            if (size + block.size > maxSamples) throw TooLongException((size + block.size).toLong() / SAMPLE_RATE)
            if (size + block.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 3 / 2, size + block.size))
            System.arraycopy(block, 0, buffer, size, block.size)
            size += block.size
        }
        return if (size == buffer.size) buffer else buffer.copyOf(size)
    }

    /**
     * Decodes only [fromMs] to [toMs] of the file as 16 kHz mono samples, seeking rather than
     * decoding everything before it, for clips of long recordings.
     */
    fun decodeRange(file: File, fromMs: Long, toMs: Long): FloatArray {
        val wanted = ((toMs - fromMs).coerceAtLeast(0) * SAMPLE_RATE / 1000).toInt()
        val out = FloatArray(wanted)
        var size = 0
        // Samples still to drop before the clip starts, known once the first decoded block's time is.
        var skip = 0L
        val collect: (FloatArray) -> Unit = { block ->
            var from = 0
            if (skip > 0) {
                val drop = minOf(skip, block.size.toLong()).toInt()
                skip -= drop
                from = drop
            }
            val n = minOf(block.size - from, wanted - size)
            if (n > 0) {
                System.arraycopy(block, from, out, size, n)
                size += n
            }
        }
        if (isPcmWav(file)) {
            val info = readWavInfo(file)
            decodeWav(file, collect, done = { size >= wanted }, startFrame = fromMs * info.sampleRate / 1000)
        } else {
            decodeCompressed(
                file, collect,
                startUs = fromMs * 1000,
                firstTimeUs = { t -> skip = ((fromMs * 1000 - t).coerceAtLeast(0) * SAMPLE_RATE / 1_000_000) },
                done = { size >= wanted },
            )
        }
        return if (size == wanted) out else out.copyOf(size)
    }

    class TooLongException(val seconds: Long) : IOException("Audio is too long to process in one go (${seconds / 60} min)")

    /** Number of 16 kHz samples the file will decode to, from its header; null if unknown. */
    fun estimateSamples(file: File): Long? = runCatching {
        if (isPcmWav(file)) {
            val info = readWavInfo(file)
            info.dataBytes / (info.channels * 2) * SAMPLE_RATE / info.sampleRate
        } else {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.absolutePath)
                val format = (0 until extractor.trackCount).map(extractor::getTrackFormat)
                    .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                format?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)?.let { it * SAMPLE_RATE / 1_000_000 }
            } finally {
                extractor.release()
            }
        }
    }.getOrNull()

    // --- WAV ------------------------------------------------------------------------------------

    private class WavInfo(val sampleRate: Int, val channels: Int, val dataOffset: Long, val dataBytes: Long)

    private fun isPcmWav(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(12)
            raf.readFully(header)
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE"
        } && readWavInfo(file).let { true }
    }.getOrDefault(false)

    private fun readWavInfo(file: File): WavInfo {
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(12)
            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0
            val chunkHeader = ByteArray(8)
            while (raf.filePointer + 8 <= raf.length()) {
                raf.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt())
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = b.getShort(0).toInt()
                        channels = b.getShort(2).toInt()
                        sampleRate = b.getInt(4)
                        bits = b.getShort(14).toInt()
                    }
                    "data" -> {
                        if (format != 1 || bits != 16 || channels <= 0 || sampleRate <= 0) throw IOException("Unsupported WAV format")
                        val available = raf.length() - raf.filePointer
                        return WavInfo(sampleRate, channels, raf.filePointer, minOf(size, available))
                    }
                    else -> raf.seek(raf.filePointer + size + (size and 1))
                }
            }
        }
        throw IOException("WAV file has no data")
    }

    private fun decodeWav(file: File, onSamples: (FloatArray) -> Unit, done: () -> Boolean = { false }, startFrame: Long = 0) {
        val info = readWavInfo(file)
        val resampler = Resampler(info.sampleRate, SAMPLE_RATE)
        RandomAccessFile(file, "r").use { raf ->
            val frameBytes = info.channels * 2
            val startByte = (startFrame * frameBytes).coerceIn(0, info.dataBytes / frameBytes * frameBytes)
            raf.seek(info.dataOffset + startByte)
            val buffer = ByteArray(frameBytes * 8192)
            var remaining = info.dataBytes - startByte
            while (remaining > 0 && !done()) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt() / frameBytes * frameBytes)
                if (n <= 0) break
                remaining -= n
                val mono = pcm16ToMono(ByteBuffer.wrap(buffer, 0, n).order(ByteOrder.LITTLE_ENDIAN), n, info.channels)
                resampler.process(mono)?.let(onSamples)
            }
        }
    }

    // --- Compressed (m4a, mp3, ogg, ...) via MediaCodec -------------------------------------------

    private fun decodeCompressed(
        file: File,
        onSamples: (FloatArray) -> Unit,
        startUs: Long = 0,
        firstTimeUs: ((Long) -> Unit)? = null,
        done: () -> Boolean = { false },
    ) {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run {
            extractor.release()
            throw IOException("No audio track in ${file.name}")
        }
        extractor.selectTrack(track)
        if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        var timed = firstTimeUs == null
        val inputFormat = extractor.getTrackFormat(track)
        val codec = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        try {
            codec.configure(inputFormat, null, null, 0)
            codec.start()
            var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var resampler = Resampler(inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE), SAMPLE_RATE)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (!done()) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = codec.outputFormat
                        channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        resampler = Resampler(out.getInteger(MediaFormat.KEY_SAMPLE_RATE), SAMPLE_RATE)
                        floatPcm = out.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            out.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val out = codec.getOutputBuffer(outIndex)
                        val mono = if (out != null && info.size > 0) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            val pcm = out.slice().order(ByteOrder.nativeOrder())
                            if (floatPcm) floatToMono(pcm, info.size, channels) else pcm16ToMono(pcm, info.size, channels)
                        } else {
                            null
                        }
                        if (mono != null && !timed) {
                            timed = true
                            firstTimeUs?.invoke(info.presentationTimeUs)
                        }
                        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        // Hand the buffer back before the callback, which may run for minutes (speaker detection).
                        codec.releaseOutputBuffer(outIndex, false)
                        mono?.let { resampler.process(it)?.let(onSamples) }
                        if (endOfStream) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    private fun pcm16ToMono(buffer: ByteBuffer, bytes: Int, channels: Int): FloatArray {
        val shorts = buffer.asShortBuffer()
        val frames = bytes / 2 / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += shorts.get(f * channels + c) / 32768f
            sum / channels
        }
    }

    private fun floatToMono(buffer: ByteBuffer, bytes: Int, channels: Int): FloatArray {
        val floats = buffer.asFloatBuffer()
        val frames = bytes / 4 / channels
        return FloatArray(frames) { f ->
            var sum = 0f
            for (c in 0 until channels) sum += floats.get(f * channels + c)
            sum / channels
        }
    }

    /** Streaming linear resampler; good enough for speech recognition. */
    private class Resampler(private val inRate: Int, private val outRate: Int) {
        private var position = 0.0
        private var last = 0f

        fun process(input: FloatArray): FloatArray? {
            if (input.isEmpty()) return null
            if (inRate == outRate) return input
            val step = inRate.toDouble() / outRate
            val out = FloatArray(((input.size - position) / step).toInt().coerceAtLeast(0) + 2)
            var count = 0
            // position is relative to the start of input; index -1 is the previous block's last sample.
            while (position < input.size - 1 && count < out.size) {
                val i = floor(position).toInt()
                val frac = (position - i).toFloat()
                val a = if (i < 0) last else input[i]
                val b = input[i + 1]
                out[count++] = a + (b - a) * frac
                position += step
            }
            position -= input.size
            last = input.last()
            return if (count == 0) null else out.copyOf(count)
        }
    }
}
