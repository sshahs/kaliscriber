package com.kaliscriber.app.storage

import android.content.Context
import com.kaliscriber.app.diagnostics.AppLog
import com.kaliscriber.app.processing.MeetingJobs
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.RecordingRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/** What the app keeps on the phone, and deleting old recordings to make room. */
class StorageManager(
    private val context: Context,
    private val meetings: MeetingRepository,
    private val settings: () -> AppSettings,
    private val jobs: MeetingJobs,
) {
    /** Bytes used, by kind. */
    data class Usage(val recordings: Long, val meetings: Long, val models: Long, val searchIndex: Long, val cache: Long) {
        val total: Long get() = recordings + meetings + models + searchIndex + cache
    }

    private val lock = Mutex()

    suspend fun usage(): Usage = withContext(Dispatchers.IO) {
        val files = context.filesDir
        val data = listOf("meetings", "transcripts", "notes").sumOf { size(File(files, it)) } +
            files.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().sumOf { it.length() }
        val db = context.getDatabasePath("search.db")
        Usage(
            recordings = size(File(files, "audio")),
            meetings = data,
            models = size(File(files, "models")),
            searchIndex = listOf("", "-wal", "-shm", "-journal").sumOf { File(db.path + it).length() },
            cache = size(context.cacheDir),
        )
    }

    /** Recordings that keeping them for [days] would delete now, and their total size. */
    fun due(days: Int): List<Meeting> =
        RecordingRetention.due(meetings.all(), days, System.currentTimeMillis()).filter { it.id !in jobs.active.value }

    fun dueBytes(due: List<Meeting>): Long = due.sumOf { m -> m.audioPath?.let { File(it).length() } ?: 0L }

    /** Deletes recordings older than the "Keep recordings" setting allows. Returns how many and the bytes freed. */
    suspend fun applyRetention(): Pair<Int, Long> = lock.withLock {
        meetings.load()
        val due = due(settings().keepRecordingsDays)
        var freed = 0L
        for (m in due) freed += meetings.deleteRecording(m.id)
        if (due.isNotEmpty()) AppLog.i("KaliScriber", "Deleted ${due.size} old recording(s), ${formatBytes(freed)}")
        due.size to freed
    }

    /** Deletes one meeting's recording, keeping its transcript. Returns the bytes freed. */
    suspend fun deleteRecording(meetingId: String): Long = lock.withLock { meetings.deleteRecording(meetingId) }

    private fun size(file: File): Long = if (file.isFile) file.length() else file.listFiles().orEmpty().sumOf(::size)
}

/** A size such as "850 KB", "12 MB" or "1.4 GB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 10L shl 20 -> "${bytes shr 20} MB"
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> "${bytes shr 10} KB"
    else -> "$bytes bytes"
}
