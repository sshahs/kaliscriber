package com.kaliscriber.app.data

import com.kaliscriber.app.storage.StorageManager
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Deletes meetings, notes and recordings a few seconds after asked, so the snackbar can offer
 * Undo instead of a confirmation. Until then they are hidden from lists. The wait runs in the
 * app's scope, so leaving the screen doesn't cancel it; if the app is closed first, nothing is lost.
 */
class Deletions(
    private val scope: CoroutineScope,
    private val meetings: MeetingRepository,
    private val notes: NoteRepository,
    private val storage: StorageManager,
) {
    enum class Kind { MEETING, NOTE, RECORDING }

    data class Pending(val id: String, val kind: Kind, val label: String) {
        val key: String get() = "$kind:$id"
    }

    private val _pending = MutableStateFlow<List<Pending>>(emptyList())
    val pending: StateFlow<List<Pending>> = _pending.asStateFlow()
    private val jobs = HashMap<String, Job>()

    /** Meetings and notes waiting to be deleted, to leave out of lists. */
    val hidden: Flow<Set<String>> = pending.map { list -> list.filter { it.kind != Kind.RECORDING }.map { it.id }.toSet() }

    fun isPending(id: String, kind: Kind): Boolean = _pending.value.any { it.id == id && it.kind == kind }

    fun delete(id: String, kind: Kind, label: String) {
        val item = Pending(id, kind, label)
        synchronized(jobs) {
            jobs.remove(item.key)?.cancel()
            _pending.update { list -> list.filterNot { it.key == item.key } + item }
            jobs[item.key] = scope.launch {
                delay(UNDO_MS)
                when (kind) {
                    Kind.MEETING -> meetings.delete(id)
                    Kind.NOTE -> notes.delete(id)
                    Kind.RECORDING -> storage.deleteRecording(id)
                }
                synchronized(jobs) {
                    jobs.remove(item.key)
                    _pending.update { list -> list.filterNot { it.key == item.key } }
                }
            }
        }
    }

    fun undo(item: Pending) = synchronized(jobs) {
        jobs.remove(item.key)?.cancel()
        _pending.update { list -> list.filterNot { it.key == item.key } }
    }

    companion object {
        /** Longer than the snackbar offering Undo is shown. */
        const val UNDO_MS = 6_000L
    }
}
