package com.kaliscriber.core.search

import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.MeetingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps a [TranscriptIndex] in step with the meetings. At start it indexes every meeting whose
 * transcript changed since it was last indexed (including all of them the first time); after that
 * it reindexes a meeting a moment after its transcript changes, or at most once a minute while the
 * meeting is being recorded.
 */
class SearchIndexer(
    private val repo: MeetingRepository,
    private val index: TranscriptIndex,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val delayMs: Long = 2_000,
    private val recordingDelayMs: Long = 60_000,
) {
    private val pending = ConcurrentHashMap<String, Job>()
    private val writes = Mutex()

    fun start(): Job = scope.launch(Dispatchers.IO) {
        repo.loaded.first { it }
        // Listen first, so a change made while catching up is not missed.
        launch { repo.changes.collect(::onChange) }
        try {
            catchUp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Search index: catching up failed: ${e.message}")
        }
    }

    /** Catches up again, e.g. once a search model has been downloaded and passages can be embedded. */
    fun refresh(): Job = scope.launch(Dispatchers.IO) {
        try {
            catchUp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Search index: catching up failed: ${e.message}")
        }
    }

    /**
     * Indexes meetings whose transcript changed since they were indexed, or whose passages were
     * embedded with another model (or none), and forgets deleted meetings.
     */
    suspend fun catchUp() {
        val indexed = index.states()
        val model = index.embedderId()
        val meetings = repo.all()
        val ids = meetings.mapTo(HashSet()) { it.id }
        for (gone in indexed.keys - ids) write { index.remove(gone) }
        var count = 0
        for (m in meetings) {
            val state = indexed[m.id]
            when {
                !m.hasTranscript -> if (state != null) write { index.remove(m.id) }
                state == null || state.version != m.stats.version || state.embeddedWith != model -> {
                    val full = repo.peek(m.id) ?: continue
                    write { index.reindex(full) }
                    count++
                }
            }
        }
        if (count > 0) log("Search index: indexed $count meeting(s)")
    }

    private fun onChange(change: MeetingStore.Change) {
        when (change) {
            is MeetingStore.Change.Deleted -> {
                pending.remove(change.id)?.cancel()
                scope.launch(Dispatchers.IO) { write { index.remove(change.id) } }
            }
            // A pending reindex reads the latest transcript when it runs, so one is enough.
            is MeetingStore.Change.Transcript -> if (pending[change.id]?.isActive != true) {
                pending[change.id] = scope.launch(Dispatchers.IO) {
                    val recording = repo.withoutTranscript(change.id)?.status == MeetingStatus.RECORDING
                    delay(if (recording) recordingDelayMs else delayMs)
                    pending.remove(change.id)
                    val meeting = repo.peek(change.id) ?: return@launch
                    try {
                        write { if (meeting.hasTranscript) index.reindex(meeting) else index.remove(meeting.id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        log("Search index: couldn't index ${meeting.id}: ${e.message}")
                    }
                }
            }
        }
    }

    private suspend fun <T> write(block: () -> T): T = writes.withLock { block() }
}
