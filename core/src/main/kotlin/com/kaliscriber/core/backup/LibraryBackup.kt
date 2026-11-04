package com.kaliscriber.core.backup

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.usage.UsageRecord
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.NoteRepository
import com.kaliscriber.core.store.StoreJson
import com.kaliscriber.core.util.Clock
import kotlinx.coroutines.flow.first
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Backs up meetings (with transcripts, summaries and chats), notes, saved voices, settings, the
 * Ask-all conversation and AI usage, optionally with the recordings, into one encrypted file
 * ([Backup]); and restores such a file by adding what the phone doesn't have yet. Nothing on the
 * phone is overwritten. API keys and passwords are never included.
 */
class LibraryBackup(
    private val meetings: MeetingRepository,
    private val notes: NoteRepository,
    private val settings: JsonValueStore<AppSettings>,
    private val voices: JsonValueStore<List<VoiceProfile>>,
    private val askAll: JsonValueStore<List<AskMessage>>,
    private val usage: JsonValueStore<List<UsageRecord>>,
    private val appVersion: String = "",
    private val clock: Clock = Clock.System,
) {
    @Serializable
    data class Manifest(
        val format: Int = FORMAT,
        val createdAt: Long = 0,
        val appVersion: String = "",
        val meetings: Int = 0,
        val notes: Int = 0,
        val recordings: Boolean = false,
    )

    data class Restored(
        val meetingsAdded: Int,
        /** Meetings left alone because the phone already has them. */
        val meetingsKept: Int,
        val notesAdded: Int,
        val voicesAdded: Int,
        val recordings: Int,
        val settingsRestored: Boolean,
    )

    /**
     * Writes a backup to [out]. [skip] leaves out meetings that are still being recorded.
     * [onProgress] gets a fraction from 0 to 1.
     */
    suspend fun write(
        out: OutputStream,
        password: CharArray,
        withRecordings: Boolean,
        skip: (Meeting) -> Boolean = { it.status == MeetingStatus.RECORDING },
        onProgress: (Float) -> Unit = {},
    ) {
        meetings.load()
        notes.load()
        val included = meetings.all().filterNot(skip)
        val noteList = notes.notes.first()
        val recordings = if (withRecordings) included.flatMap { m -> recordingsOf(m).map { m.id to it } } else emptyList()
        val manifest = Manifest(FORMAT, clock.now(), appVersion, included.size, noteList.size, withRecordings)

        val entries = sequence {
            yield(json(MANIFEST, Manifest.serializer(), manifest))
            yield(json(SETTINGS, AppSettings.serializer(), settings.value.value))
            yield(json(VOICES, ListSerializer(VoiceProfile.serializer()), voices.value.value))
            yield(json(ASK_ALL, ListSerializer(AskMessage.serializer()), askAll.value.value))
            yield(json(USAGE, ListSerializer(UsageRecord.serializer()), usage.value.value))
            for (note in noteList) yield(json("$NOTES/${note.id}.json", Note.serializer(), note))
            for (m in included) {
                // Read when written, so only one transcript is in memory at a time.
                yield(
                    Backup.Entry("$MEETINGS/${m.id}.json", size = m.stats.lines * LINE_BYTES, time = m.createdAt) {
                        val full = meetings.peek(m.id) ?: throw java.io.FileNotFoundException(m.id)
                        StoreJson.encodeToString(Meeting.serializer(), full).toByteArray().inputStream()
                    },
                )
            }
            for ((id, file) in recordings) yield(Backup.Entry.file("$AUDIO/$id/${file.name}", file, compress = false))
        }
        val total = included.sumOf { it.stats.lines * LINE_BYTES } + recordings.sumOf { it.second.length() } + 64 * 1024
        Backup.write(out, password, entries, total) { done, all -> onProgress((done.toFloat() / all).coerceIn(0f, 1f)) }
    }

    /**
     * Adds what [input] holds and the phone doesn't: meetings and notes it lacks (by id), saved
     * voices it lacks, Ask-all history and usage records. Settings are taken from the backup only
     * when [replaceSettings] is set. [onProgress] gets what is being restored.
     */
    suspend fun restore(
        input: InputStream,
        password: CharArray,
        replaceSettings: Boolean,
        onProgress: (String) -> Unit = {},
    ): Restored {
        meetings.load()
        notes.load()
        var manifest: Manifest? = null
        val added = mutableListOf<Meeting>()
        var kept = 0
        var notesAdded = 0
        var voicesAdded = 0
        var recordings = 0
        var settingsRestored = false
        Backup.read(input, password) { path, data ->
            if (manifest == null && path != MANIFEST) throw BackupException("This backup is damaged.")
            when {
                path == MANIFEST -> {
                    val m = decode(data, Manifest.serializer())
                    if (m.format > FORMAT) throw BackupException("This backup was made by a newer version of KaliScriber.")
                    manifest = m
                    onProgress("Restoring a backup of ${m.meetings} meeting${if (m.meetings == 1) "" else "s"}…")
                }
                path == SETTINGS -> if (replaceSettings) {
                    val backup = decode(data, AppSettings.serializer())
                    settings.update { backup }
                    settingsRestored = true
                } else {
                    // Restored meetings keep their projects even when the settings stay as they are.
                    val backup = decode(data, AppSettings.serializer())
                    settings.update { local -> local.copy(projects = local.projects + backup.projects.filter { b -> local.projects.none { it.id == b.id } }) }
                }
                path == VOICES -> {
                    val backup = decode(data, ListSerializer(VoiceProfile.serializer()))
                    voices.update { local ->
                        val missing = backup.filter { b -> local.none { it.id == b.id } }
                        voicesAdded = missing.size
                        local + missing
                    }
                }
                path == ASK_ALL -> {
                    val backup = decode(data, ListSerializer(AskMessage.serializer()))
                    askAll.update { local -> if (local.isEmpty()) backup else local }
                }
                path == USAGE -> {
                    val backup = decode(data, ListSerializer(UsageRecord.serializer()))
                    usage.update { local ->
                        val seen = local.toHashSet()
                        (local + backup.filter { it !in seen }).sortedBy { it.at }
                    }
                }
                path.startsWith("$NOTES/") -> {
                    if (notes.restore(decode(data, Note.serializer()))) notesAdded++
                }
                path.startsWith("$MEETINGS/") -> {
                    val meeting = settled(decode(data, Meeting.serializer()))
                    if (meetings.restore(meeting)) {
                        added += meeting
                        if (added.size % 10 == 0) onProgress("Restored ${added.size} meetings…")
                    } else {
                        kept++
                    }
                }
                path.startsWith("$AUDIO/") -> {
                    val parts = path.split('/')
                    val id = parts.getOrNull(1)
                    val name = parts.getOrNull(2)
                    if (parts.size == 3 && id != null && name != null && safeName(id) && safeName(name) && added.any { it.id == id }) {
                        val target = File(meetings.audioDir(id), name)
                        if (!target.exists()) {
                            onProgress("Restoring recordings…")
                            val tmp = File(target.path + ".restoring")
                            tmp.outputStream().use { data.copyTo(it) }
                            if (tmp.renameTo(target)) recordings++ else tmp.delete()
                        }
                    }
                }
            }
        }
        if (manifest == null) throw BackupException("This backup is empty.")
        // Point restored meetings at their recordings here, or at none if they weren't included.
        for (m in added) {
            if (m.audioPath == null && m.briefing?.audioPath == null) continue
            meetings.update(m.id) { current ->
                current.copy(
                    audioPath = relocate(m.id, current.audioPath),
                    briefing = current.briefing?.copy(audioPath = relocate(m.id, current.briefing.audioPath)),
                )
            }
        }
        return Restored(added.size, kept, notesAdded, voicesAdded, recordings, settingsRestored)
    }

    /** The meeting's audio files: its recording and audio briefing (not leftover recording chunks). */
    private fun recordingsOf(m: Meeting): List<File> =
        listOfNotNull(m.audioPath, m.briefing?.audioPath).map(::File).filter { it.isFile && safeName(it.name) }.distinctBy { it.name }

    /** Where a recording from the backup ends up on this phone, if it was restored. */
    private fun relocate(id: String, path: String?): String? {
        val name = path?.let { File(it).name }?.takeIf(::safeName) ?: return null
        return File(meetings.audioDir(id), name).takeIf { it.isFile }?.absolutePath
    }

    /** A meeting backed up while being processed is restored as finished, as after a restart. */
    private fun settled(m: Meeting): Meeting = when (m.status) {
        MeetingStatus.RECORDING, MeetingStatus.TRANSCRIBING, MeetingStatus.ANALYZING -> m.copy(
            status = if (m.segments.isEmpty()) MeetingStatus.ERROR else MeetingStatus.READY,
            pendingChunks = emptyList(),
            processingStep = null,
            processingProgress = null,
        )
        else -> m.copy(pendingChunks = emptyList())
    }

    private fun <T> json(path: String, serializer: KSerializer<T>, value: T) =
        Backup.Entry.bytes(path, StoreJson.encodeToString(serializer, value).toByteArray(), clock.now())

    private fun <T> decode(data: InputStream, serializer: KSerializer<T>): T = try {
        StoreJson.decodeFromString(serializer, data.readBytes().decodeToString())
    } catch (e: IllegalArgumentException) {
        throw BackupException("This backup is damaged.", e)
    }

    private fun safeName(name: String) = name.isNotEmpty() && name != "." && name != ".." && name.all { it.isLetterOrDigit() || it in "._-" }

    companion object {
        const val FORMAT = 1
        private const val MANIFEST = "backup.json"
        private const val SETTINGS = "settings.json"
        private const val VOICES = "voices.json"
        private const val ASK_ALL = "ask-all.json"
        private const val USAGE = "usage.json"
        private const val NOTES = "notes"
        private const val MEETINGS = "meetings"
        private const val AUDIO = "audio"
        /** A rough size of one transcript line in a backup, for progress. */
        private const val LINE_BYTES = 200L
    }
}
