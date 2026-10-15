package com.kaliscriber.core.store

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.model.TranscriptStats
import com.kaliscriber.core.util.Clock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** A meeting's transcript as stored on disk, next to the meeting file. */
@Serializable
internal class TranscriptFile(val segments: List<TranscriptSegment> = emptyList())

/**
 * Meetings on disk as two files each: the meeting itself (title, summary, speakers, chat…) in
 * [dir] and its transcript in [transcriptDir]. Only the meetings are held in memory ([items], with
 * empty segments and a [TranscriptStats] summary instead), so a long history of meetings stays
 * light. A transcript is read when its meeting is opened or processed; the few in use stay cached,
 * and meetings someone is observing are never dropped from the cache.
 *
 * Meeting files written by earlier versions, with the transcript inside, are split when loaded.
 */
class MeetingStore(
    private val dir: File,
    private val transcriptDir: File,
    /** The part of a meeting worth saving; changes to the rest (progress) stay in memory. */
    private val persisted: (Meeting) -> Meeting = { it },
    private val clock: Clock = Clock.System,
    private val cacheSize: Int = 4,
) {
    sealed interface Change {
        val id: String

        /** The transcript of meeting [id] changed and was saved. */
        data class Transcript(override val id: String) : Change

        data class Deleted(override val id: String) : Change
    }

    private val mutex = Mutex()
    private val _items = MutableStateFlow<Map<String, Meeting>>(emptyMap())
    private val _loaded = MutableStateFlow(false)
    private val _changes = MutableSharedFlow<Change>(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every meeting without its transcript (segments are empty; see [Meeting.stats]). */
    val items: StateFlow<Map<String, Meeting>> = _items.asStateFlow()
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    val changes: SharedFlow<Change> = _changes.asSharedFlow()

    // Meetings with their transcripts, least recently used first. Guarded by cacheLock, as are
    // pins and every change to _items, so the two never disagree.
    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, Meeting>(16, 0.75f, true)
    private val pins = HashMap<String, Int>()
    private val _full = MutableStateFlow<Map<String, Meeting>>(emptyMap())

    suspend fun load() = mutex.withLock { loadLocked() }

    private suspend fun loadLocked() {
        if (_loaded.value) return
        val meetings = withContext(Dispatchers.IO) {
            dir.mkdirs()
            transcriptDir.mkdirs()
            val files = dir.listFiles { f -> f.isFile && f.extension == "json" }.orEmpty().toList()
            coroutineScope {
                files.map { file -> async(LOAD_DISPATCHER) { runCatching { readMeeting(file) }.getOrNull() } }.awaitAll().filterNotNull()
            }
        }
        synchronized(cacheLock) { _items.value = meetings.associateBy { it.id } }
        _loaded.value = true
    }

    /** Reads a meeting file, moving a transcript stored inside it (by earlier versions) to its own file. */
    private fun readMeeting(file: File): Meeting {
        val meeting = StoreJson.decodeFromString(Meeting.serializer(), file.readText())
        if (meeting.segments.isEmpty()) return meeting
        val light = meeting.copy(segments = emptyList(), stats = TranscriptStats.of(meeting.segments, meeting.updatedAt))
        // The transcript is written first: if the app stops in between, the next start does this again.
        writeTranscript(meeting.id, meeting.segments)
        file.writeAtomically(StoreJson.encodeToString(Meeting.serializer(), persisted(light)))
        return light
    }

    fun light(id: String): Meeting? = _items.value[id]

    /** The meeting with its transcript, read from disk unless cached. Null if there is no such meeting. */
    fun get(id: String): Meeting? = synchronized(cacheLock) {
        cache[id]?.let { return it }
        val light = _items.value[id] ?: return null
        val full = light.copy(segments = readTranscript(id))
        cache[id] = full
        trim()
        publish()
        full
    }

    /** Like [get] but leaves the cache alone, for reading many meetings once (e.g. to index them). */
    fun peek(id: String): Meeting? {
        synchronized(cacheLock) { cache[id]?.let { return it } }
        val light = _items.value[id] ?: return null
        return light.copy(segments = readTranscript(id))
    }

    /** The meeting with its transcript as it changes; it stays cached while collected. */
    fun observe(id: String): Flow<Meeting?> = flow {
        synchronized(cacheLock) { pins[id] = (pins[id] ?: 0) + 1 }
        try {
            withContext(Dispatchers.IO) { get(id) }
            emitAll(_full.map { it[id] }.distinctUntilChanged())
        } finally {
            synchronized(cacheLock) {
                val left = (pins[id] ?: 1) - 1
                if (left <= 0) pins.remove(id) else pins[id] = left
                trim()
                publish()
            }
        }
    }

    suspend fun put(meeting: Meeting) = mutex.withLock {
        loadLocked()
        val full = meeting.copy(stats = TranscriptStats.of(meeting.segments, clock.now()))
        withContext(Dispatchers.IO) {
            if (full.segments.isNotEmpty() || transcriptFile(full.id).exists()) writeTranscript(full.id, full.segments)
            writeMeta(full)
        }
        remember(full)
        if (full.segments.isNotEmpty()) _changes.tryEmit(Change.Transcript(full.id))
    }

    /** Adds [meeting] unless one with its id is already stored; true if it was added. */
    suspend fun putIfAbsent(meeting: Meeting): Boolean = mutex.withLock {
        loadLocked()
        if (meeting.id in _items.value) return@withLock false
        val full = meeting.copy(stats = TranscriptStats.of(meeting.segments, clock.now()))
        withContext(Dispatchers.IO) {
            if (full.segments.isNotEmpty()) writeTranscript(full.id, full.segments)
            writeMeta(full)
        }
        remember(full)
        if (full.segments.isNotEmpty()) _changes.tryEmit(Change.Transcript(full.id))
        true
    }

    /** Atomically transforms the meeting with [id] (with its transcript); returns the result, or null if missing. */
    suspend fun update(id: String, transform: (Meeting) -> Meeting): Meeting? = mutex.withLock {
        loadLocked()
        val current = withContext(Dispatchers.IO) { get(id) } ?: return@withLock null
        var next = transform(current)
        if (next == current) return@withLock current
        val transcriptChanged = next.segments != current.segments
        next = next.copy(
            stats = if (transcriptChanged) {
                TranscriptStats.of(next.segments, maxOf(clock.now(), current.stats.version + 1))
            } else {
                current.stats
            },
        )
        withContext(Dispatchers.IO) {
            if (transcriptChanged) writeTranscript(id, next.segments)
            if (persisted(lightOf(next)) != persisted(lightOf(current))) writeMeta(next)
        }
        remember(next)
        if (transcriptChanged) _changes.tryEmit(Change.Transcript(id))
        next
    }

    suspend fun delete(id: String) = mutex.withLock {
        loadLocked()
        withContext(Dispatchers.IO) {
            metaFile(id).delete()
            transcriptFile(id).delete()
        }
        synchronized(cacheLock) {
            cache.remove(id)
            _items.value = _items.value - id
            publish()
        }
        _changes.tryEmit(Change.Deleted(id))
    }

    private fun remember(full: Meeting) = synchronized(cacheLock) {
        cache[full.id] = full
        _items.value = _items.value + (full.id to lightOf(full))
        trim()
        publish()
    }

    /** Drops the least recently used transcripts beyond [cacheSize], keeping observed meetings. */
    private fun trim() {
        if (cache.size <= cacheSize) return
        val drop = cache.keys.filter { it !in pins }.take(cache.size - cacheSize)
        drop.forEach { cache.remove(it) }
    }

    private fun publish() {
        _full.value = HashMap(cache)
    }

    private fun lightOf(full: Meeting) = if (full.segments.isEmpty()) full else full.copy(segments = emptyList())

    private fun writeMeta(full: Meeting) =
        metaFile(full.id).writeAtomically(StoreJson.encodeToString(Meeting.serializer(), persisted(lightOf(full))))

    private fun writeTranscript(id: String, segments: List<TranscriptSegment>) =
        transcriptFile(id).writeAtomically(StoreJson.encodeToString(TranscriptFile.serializer(), TranscriptFile(segments)))

    private fun readTranscript(id: String): List<TranscriptSegment> {
        val file = transcriptFile(id)
        if (!file.exists()) return emptyList()
        return runCatching { StoreJson.decodeFromString(TranscriptFile.serializer(), file.readText()).segments }.getOrDefault(emptyList())
    }

    private fun safe(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun metaFile(id: String) = File(dir, "${safe(id)}.json")
    fun transcriptFile(id: String) = File(transcriptDir, "${safe(id)}.json")

    private companion object {
        @OptIn(ExperimentalCoroutinesApi::class)
        val LOAD_DISPATCHER = Dispatchers.IO.limitedParallelism(4)
    }
}
