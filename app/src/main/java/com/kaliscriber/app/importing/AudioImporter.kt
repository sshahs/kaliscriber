package com.kaliscriber.app.importing

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.kaliscriber.app.processing.MeetingJobs
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Turns an audio or video file (picked in the app, shared to it or opened with it) into a meeting:
 * copies it into app storage and transcribes it in the background under the processing service.
 */
class AudioImporter(
    private val context: Context,
    private val meetings: MeetingRepository,
    private val pipeline: MeetingPipeline,
    private val jobs: MeetingJobs,
    private val scope: CoroutineScope,
) {
    /** Copies [uri] and starts transcribing it; returns the new meeting's id once the copy is done. */
    suspend fun import(uri: Uri): String {
        val resolver = context.contentResolver
        val name = withContext(Dispatchers.IO) {
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Imported audio"
        val meeting = meetings.create(name.substringBeforeLast('.').ifBlank { "Imported audio" }, status = MeetingStatus.TRANSCRIBING)
        val extension = name.substringAfterLast('.', "").lowercase().take(5).ifBlank { extensionFor(resolver.getType(uri)) }
        val dir = meetings.audioDir(meeting.id)
        var target = File(dir, "imported.$extension")
        val durationMs = try {
            withContext(Dispatchers.IO) {
                // From a video only the sound is kept; anything else is copied as it is.
                val audioOnly = if (isVideo(resolver.getType(uri), extension)) AudioTrackExtractor.extract(context, uri, dir) else null
                if (audioOnly != null) {
                    target = audioOnly
                } else {
                    resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                        ?: error("Could not read the file")
                }
                runCatching {
                    MediaMetadataRetriever().run {
                        try {
                            setDataSource(target.absolutePath)
                            extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        } finally {
                            release()
                        }
                    }
                }.getOrNull()
            }
        } catch (e: Exception) {
            meetings.delete(meeting.id)
            throw e
        }
        if (durationMs != null) meetings.update(meeting.id) { it.copy(durationMs = durationMs) }
        val audio = target
        scope.launch {
            try {
                jobs.run(meeting.id) { pipeline.processImportedAudio(meeting.id, audio, durationMs) }
            } catch (e: Exception) {
                Log.w("KaliScriber", "Import of ${meeting.id} failed: ${e.message}")
            }
        }
        return meeting.id
    }

    private fun isVideo(mime: String?, extension: String) =
        mime?.startsWith("video/") == true || extension in setOf("mp4", "mov", "m4v", "webm", "mkv", "3gp")

    private fun extensionFor(mime: String?): String = when {
        mime == null -> "m4a"
        mime.contains("mpeg") -> "mp3"
        mime.contains("ogg") || mime.contains("opus") -> "ogg"
        mime.contains("wav") -> "wav"
        mime.contains("flac") -> "flac"
        mime.contains("webm") -> "webm"
        mime.startsWith("video/") -> "mp4"
        else -> "m4a"
    }
}
