package com.kaliscriber.app.importing

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import com.kaliscriber.app.diagnostics.AppLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Copies just the sound of a video into an audio file, without re-encoding, so a shared screen
 * recording or video call doesn't fill the phone with video the app never uses.
 */
object AudioTrackExtractor {

    /**
     * Writes the audio track of the video at [uri] to a file in [dir] and returns it. Null when
     * [uri] has no video track, its audio can't be copied as is (only AAC, Opus and Vorbis can), or
     * anything goes wrong: then the whole file is imported instead.
     */
    fun extract(context: Context, uri: Uri, dir: File): File? {
        val extractor = MediaExtractor()
        var out: File? = null
        try {
            extractor.setDataSource(context, uri, null)
            val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
            if (mimes.none { it.startsWith("video/") }) return null
            val track = mimes.indexOfFirst { it.startsWith("audio/") }.takeIf { it >= 0 } ?: return null
            val format = extractor.getTrackFormat(track)
            val (container, extension) = when (mimes[track]) {
                MediaFormat.MIMETYPE_AUDIO_AAC -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "m4a"
                MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
                else -> return null
            }
            val file = File(dir, "imported.$extension").also { out = it }
            val muxer = MediaMuxer(file.absolutePath, container)
            try {
                val target = muxer.addTrack(format)
                muxer.start()
                extractor.selectTrack(track)
                val capacity = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
                val buffer = ByteBuffer.allocate(capacity.coerceAtLeast(DEFAULT_BUFFER))
                val info = MediaCodec.BufferInfo()
                while (true) {
                    buffer.clear()
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) break
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    info.set(0, size, extractor.sampleTime, flags)
                    muxer.writeSampleData(target, buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
            return file.takeIf { it.length() > 0 }
        } catch (e: Exception) {
            AppLog.w("KaliScriber", "Couldn't take the audio out of the video, importing it whole: ${e.message}")
            out?.delete()
            return null
        } finally {
            extractor.release()
        }
    }

    private const val DEFAULT_BUFFER = 1 shl 20
}
