package com.kaliscriber.app.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import androidx.core.content.FileProvider
import com.kaliscriber.app.speech.AudioDecoder
import com.kaliscriber.core.audio.Wav
import com.kaliscriber.core.clip.Clip
import com.kaliscriber.core.clip.Clips
import com.kaliscriber.core.model.Meeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cuts a stretch of a meeting's recording into a small audio file and shares it with what was said. */
object ClipSharer {
    private const val TAG = "ClipSharer"
    private const val BIT_RATE = 48_000

    /** Makes the clip's audio file; null when the meeting has no recording to cut it from. */
    suspend fun make(context: Context, meeting: Meeting, clip: Clip): File? = withContext(Dispatchers.IO) {
        val source = meeting.audioPath?.let(::File)?.takeIf { it.exists() && it.length() > 0 } ?: return@withContext null
        val samples = AudioDecoder.decodeRange(source, clip.fromMs, clip.toMs)
        if (samples.isEmpty()) return@withContext null
        val dir = File(context.cacheDir, "exports/clips").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val m4a = File(dir, Clips.fileName(meeting, clip, "m4a"))
        try {
            encodeAac(samples, AudioDecoder.SAMPLE_RATE, m4a)
            m4a
        } catch (e: Exception) {
            // Not every phone has a usable AAC encoder; a WAV file plays anywhere.
            Log.w(TAG, "AAC encoding failed, sharing WAV", e)
            m4a.delete()
            val wav = File(dir, Clips.fileName(meeting, clip, "wav"))
            val pcm = toPcm16(samples)
            Wav.write(wav, pcm, pcm.size, AudioDecoder.SAMPLE_RATE)
            wav
        }
    }

    /** Opens the share sheet with the clip's audio (if any) and its transcript. */
    fun share(context: Context, meeting: Meeting, clip: Clip, audio: File?) {
        val text = Clips.text(meeting, clip)
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, "Clip from ${meeting.title}")
            putExtra(Intent.EXTRA_TEXT, text)
            if (audio != null) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", audio)
                type = if (audio.extension == "m4a") "audio/mp4" else "audio/wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri(audio.name, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                type = "text/plain"
            }
        }
        context.startActivity(
            Intent.createChooser(send, "Share clip")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun toPcm16(samples: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { buffer.putShort((it.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()) }
        return buffer.array()
    }

    /** Encodes mono [samples] at [sampleRate] as AAC in an MP4 container. */
    private fun encodeAac(samples: FloatArray, sampleRate: Int, out: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        var stopped = false
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var offset = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.clear()
                        val n = minOf(buffer.remaining() / 2, samples.size - offset)
                        val timeUs = offset * 1_000_000L / sampleRate
                        if (n <= 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            for (i in 0 until n) buffer.putShort((samples[offset + i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort())
                            codec.queueInputBuffer(inIndex, 0, n * 2, timeUs, 0)
                            offset += n
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        track = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        started = true
                    }
                    outIndex >= 0 -> {
                        val encoded = codec.getOutputBuffer(outIndex)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (encoded != null && info.size > 0 && !config && started) {
                            encoded.position(info.offset)
                            encoded.limit(info.offset + info.size)
                            muxer.writeSampleData(track, encoded, info)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            check(started) { "The encoder produced no audio" }
            muxer.stop()
            stopped = true
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (started && !stopped) runCatching { muxer.stop() }
            muxer.release()
        }
    }
}
