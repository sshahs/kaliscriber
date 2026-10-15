package com.kaliscriber.core.store

import com.kaliscriber.core.model.Bookmark
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The user's meetings. Lists ([meetings], [all]) hold meetings without their transcripts, which
 * stay on disk ([Meeting.stats] summarises them); [get], [observe] and [update] work with the
 * whole meeting, transcript included.
 */
class MeetingRepository(
    private val store: MeetingStore,
    /** Root folder holding one audio folder per meeting. */
    private val audioRoot: File,
    private val clock: Clock = Clock.System,
) {
    val loaded = store.loaded

    /** Every meeting, newest first, without transcripts (segments are empty). */
    val meetings: Flow<List<Meeting>> = store.items.map { items -> items.values.sortedByDescending { it.createdAt } }

    /** Transcript changes and deletions, e.g. to keep a search index up to date. */
    val changes: SharedFlow<MeetingStore.Change> = store.changes

    suspend fun load() = store.load()

    /** The whole meeting, transcript included (read from disk if it is not cached). */
    fun get(id: String): Meeting? = store.get(id)

    /** The whole meeting without keeping it cached, for one-off reads of many meetings. */
    fun peek(id: String): Meeting? = store.peek(id)

    /** The meeting without its transcript (cheap; nothing is read from disk). */
    fun withoutTranscript(id: String): Meeting? = store.light(id)

    /** Every meeting, newest first, without transcripts (segments are empty). */
    fun all(): List<Meeting> = store.items.value.values.sortedByDescending { it.createdAt }

    /** The whole meeting as it changes. */
    fun observe(id: String): Flow<Meeting?> = store.observe(id)

    fun audioDir(id: String): File = File(audioRoot, id).apply { mkdirs() }

    suspend fun create(title: String? = null, status: MeetingStatus = MeetingStatus.RECORDING): Meeting {
        val now = clock.now()
        val meeting = Meeting(
            id = Ids.new(),
            title = title?.takeIf { it.isNotBlank() } ?: defaultTitle(now),
            autoTitle = title.isNullOrBlank(),
            createdAt = now,
            status = status,
        )
        store.put(meeting)
        return meeting
    }

    /** Atomically transforms the whole meeting, transcript included. */
    suspend fun update(id: String, transform: (Meeting) -> Meeting): Meeting? =
        store.update(id) { current ->
            val next = transform(current)
            // Progress-only changes stay in memory, so they don't count as an edit.
            if (PERSISTED(next) == PERSISTED(current)) next else next.copy(updatedAt = clock.now())
        }

    suspend fun delete(id: String) {
        store.delete(id)
        withContext(Dispatchers.IO) { File(audioRoot, id).deleteRecursively() }
    }

    /**
     * Deletes the meeting's recording and any leftover recording chunks to save space, keeping the
     * transcript, summary and everything else. Returns the bytes freed.
     */
    suspend fun deleteRecording(id: String): Long {
        val meeting = withoutTranscript(id) ?: return 0
        val dir = File(audioRoot, id)
        val freed = withContext(Dispatchers.IO) {
            val chunks = File(dir, "chunks")
            // Only files in the app's own audio folder are ever deleted.
            val files = listOfNotNull(meeting.audioPath?.let(::File)?.takeIf { it.isInside(audioRoot) }) + chunks.listFiles().orEmpty()
            files.filter { it.isFile }.sumOf { f -> f.length().let { size -> if (f.delete()) size else 0L } }
                .also { chunks.delete() }
        }
        update(id) { it.copy(audioPath = null, pendingChunks = emptyList(), audioDeletedAt = clock.now()) }
        return freed
    }

    private fun File.isInside(dir: File): Boolean =
        runCatching { canonicalPath.startsWith(dir.canonicalPath + File.separator) }.getOrDefault(false)

    /** Adds a meeting from a backup unless the phone already has it; true if it was added. */
    suspend fun restore(meeting: Meeting): Boolean = store.putIfAbsent(meeting)

    suspend fun rename(id: String, title: String) = update(id) {
        if (title.isBlank()) it else it.copy(title = title.trim(), autoTitle = false)
    }

    suspend fun renameSpeaker(meetingId: String, speakerId: String, name: String) = update(meetingId) { m ->
        val clean = name.trim()
        if (clean.isEmpty()) return@update m
        // Renaming to an existing speaker's name means they are the same person: merge.
        val duplicate = m.speakers.firstOrNull { it.id != speakerId && it.name.equals(clean, ignoreCase = true) }
        if (duplicate != null) return@update merge(m, fromId = speakerId, intoId = duplicate.id)
        m.copy(speakers = m.speakers.map {
            if (it.id == speakerId) it.copy(name = clean, isPlaceholder = false, suggestedName = null, suggestionReason = null) else it
        })
    }

    suspend fun dismissSuggestion(meetingId: String, speakerId: String) = update(meetingId) { m ->
        m.copy(speakers = m.speakers.map {
            if (it.id == speakerId) it.copy(suggestedName = null, suggestionReason = null) else it
        })
    }

    suspend fun mergeSpeakers(meetingId: String, fromId: String, intoId: String) = update(meetingId) { merge(it, fromId, intoId) }

    private fun merge(m: Meeting, fromId: String, intoId: String): Meeting {
        if (fromId == intoId || m.speaker(intoId) == null) return m
        val from = m.speaker(fromId)
        val labels = listOfNotNull(from?.label) + from?.aliases.orEmpty()
        return m.copy(
            speakers = m.speakers.filter { it.id != fromId }.map {
                if (it.id == intoId && labels.isNotEmpty()) it.copy(aliases = (it.aliases + labels).distinct() - setOfNotNull(it.label)) else it
            },
            segments = m.segments.map { if (it.speakerId == fromId) it.copy(speakerId = intoId) else it },
        )
    }

    suspend fun addBookmark(meetingId: String, atMs: Long, note: String = ""): Bookmark {
        val bookmark = Bookmark(Ids.new(), atMs.coerceAtLeast(0), note.trim())
        update(meetingId) { m -> m.copy(bookmarks = (m.bookmarks + bookmark).sortedBy { it.atMs }) }
        return bookmark
    }

    suspend fun editBookmark(meetingId: String, id: String, note: String) = update(meetingId) { m ->
        m.copy(bookmarks = m.bookmarks.map { if (it.id == id) it.copy(note = note.trim()) else it })
    }

    suspend fun deleteBookmark(meetingId: String, id: String) = update(meetingId) { m ->
        m.copy(bookmarks = m.bookmarks.filterNot { it.id == id })
    }

    suspend fun reassignSegments(meetingId: String, segmentIds: Set<String>, speakerId: String) = update(meetingId) { m ->
        if (m.speaker(speakerId) == null) return@update m
        m.copy(segments = m.segments.map { if (it.id in segmentIds) it.copy(speakerId = speakerId) else it })
            .withoutUnusedPlaceholders()
    }

    /** Moves the segments to a brand new speaker, named [name] or given a random placeholder name. */
    suspend fun assignToNewSpeaker(meetingId: String, segmentIds: Set<String>, name: String?): Speaker? {
        var created: Speaker? = null
        update(meetingId) { m ->
            val speaker = if (name.isNullOrBlank()) {
                SpeakerAssigner.newPlaceholder(m.speakers, label = null)
            } else {
                Speaker(id = Ids.new(), name = name.trim(), isPlaceholder = false, colorIndex = SpeakerAssigner.nextColor(m.speakers))
            }
            created = speaker
            m.copy(
                speakers = m.speakers + speaker,
                segments = m.segments.map { if (it.id in segmentIds) it.copy(speakerId = speaker.id) else it },
            ).withoutUnusedPlaceholders()
        }
        return created
    }

    suspend fun editSegment(meetingId: String, segmentId: String, text: String) = update(meetingId) { m ->
        m.copy(segments = m.segments.map { if (it.id == segmentId) it.copy(text = text, words = null) else it })
    }

    suspend fun deleteSegment(meetingId: String, segmentId: String) = update(meetingId) { m ->
        m.copy(segments = m.segments.filter { it.id != segmentId }).withoutUnusedPlaceholders()
    }

    /** Changes one action item; marks it as edited so a regenerated summary keeps the change. */
    suspend fun editActionItem(meetingId: String, itemId: String, transform: (ActionItem) -> ActionItem) = update(meetingId) { m ->
        val insights = m.insights ?: return@update m
        m.copy(insights = insights.copy(actionItems = insights.actionItems.map { if (it.id == itemId) transform(it).copy(edited = true) else it }))
    }

    suspend fun setActionItemDone(meetingId: String, itemId: String, done: Boolean) = update(meetingId) { m ->
        val insights = m.insights ?: return@update m
        m.copy(insights = insights.copy(actionItems = insights.actionItems.map { if (it.id == itemId) it.copy(done = done) else it }))
    }

    suspend fun answerQuestion(meetingId: String, questionId: String, answer: String) = update(meetingId) { m ->
        val gaps = m.gaps ?: return@update m
        m.copy(gaps = gaps.copy(questions = gaps.questions.map {
            if (it.id == questionId) it.copy(answer = answer.trim().ifEmpty { null }) else it
        }))
    }

    suspend fun clearChat(meetingId: String) = update(meetingId) { it.copy(chat = emptyList()) }

    suspend fun removeChatMessages(meetingId: String, ids: Set<String>) =
        update(meetingId) { m -> m.copy(chat = m.chat.filterNot { it.id in ids }) }

    private fun Meeting.withoutUnusedPlaceholders(): Meeting {
        val used = segments.mapNotNull { it.speakerId }.toSet()
        return copy(speakers = speakers.filter { !it.isPlaceholder || it.id in used })
    }

    companion object {
        /** Processing progress lives in memory only, so progress updates don't rewrite the meeting file. */
        val PERSISTED: (Meeting) -> Meeting = { it.copy(processingStep = null, processingProgress = null) }

        /** Meetings in [root]/meetings with transcripts in [root]/transcripts and audio in [audioRoot]. */
        fun inDir(root: File, audioRoot: File = File(root, "audio"), clock: Clock = Clock.System) = MeetingRepository(
            MeetingStore(File(root, "meetings"), File(root, "transcripts"), PERSISTED, clock),
            audioRoot,
            clock,
        )

        fun defaultTitle(millis: Long): String =
            "Meeting " + SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(millis))
    }
}

class NoteRepository(
    private val store: JsonDocumentStore<Note>,
    private val clock: Clock = Clock.System,
) {
    val notes: Flow<List<Note>> = store.items.map { items -> items.values.sortedByDescending { it.updatedAt } }

    suspend fun load() = store.load()

    fun get(id: String): Note? = store.get(id)

    fun observe(id: String): Flow<Note?> = store.items.map { it[id] }.distinctUntilChanged()

    suspend fun create(title: String = "", content: String = "", meetingId: String? = null): Note {
        val now = clock.now()
        val note = Note(id = Ids.new(), title = title, content = content, createdAt = now, meetingId = meetingId)
        store.put(note)
        return note
    }

    suspend fun update(id: String, transform: (Note) -> Note): Note? =
        store.update(id) { old -> transform(old).let { if (it == old) it else it.copy(updatedAt = clock.now()) } }

    suspend fun delete(id: String) = store.delete(id)

    /** Adds a note from a backup unless the phone already has it; true if it was added. */
    suspend fun restore(note: Note): Boolean {
        store.load()
        if (store.get(note.id) != null) return false
        store.put(note)
        return true
    }
}
