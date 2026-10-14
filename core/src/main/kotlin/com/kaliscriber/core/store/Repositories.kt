package com.kaliscriber.core.store

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeetingRepository(
    private val store: JsonDocumentStore<Meeting>,
    /** Root folder holding one audio folder per meeting. */
    private val audioRoot: File,
    private val clock: Clock = Clock.System,
) {
    val loaded = store.loaded

    val meetings: Flow<List<Meeting>> = store.items.map { items -> items.values.sortedByDescending { it.createdAt } }

    suspend fun load() = store.load()

    fun get(id: String): Meeting? = store.get(id)

    fun observe(id: String): Flow<Meeting?> = store.items.map { it[id] }.distinctUntilChanged()

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

    suspend fun update(id: String, transform: (Meeting) -> Meeting): Meeting? =
        store.update(id) { transform(it).copy(updatedAt = clock.now()) }

    suspend fun delete(id: String) {
        store.delete(id)
        withContext(Dispatchers.IO) { File(audioRoot, id).deleteRecursively() }
    }

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
        return m.copy(
            speakers = m.speakers.filter { it.id != fromId },
            segments = m.segments.map { if (it.speakerId == fromId) it.copy(speakerId = intoId) else it },
        )
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
        m.copy(segments = m.segments.map { if (it.id == segmentId) it.copy(text = text) else it })
    }

    suspend fun deleteSegment(meetingId: String, segmentId: String) = update(meetingId) { m ->
        m.copy(segments = m.segments.filter { it.id != segmentId }).withoutUnusedPlaceholders()
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

    private fun Meeting.withoutUnusedPlaceholders(): Meeting {
        val used = segments.mapNotNull { it.speakerId }.toSet()
        return copy(speakers = speakers.filter { !it.isPlaceholder || it.id in used })
    }

    companion object {
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
}
