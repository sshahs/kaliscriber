package com.kaliscriber.app.tasks

import android.content.Context
import com.kaliscriber.app.diagnostics.AppLog
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.export.MeetingExporter
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.tasks.TaskSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/**
 * Keeps exported action items and their CalDAV tasks in step both ways ([TaskSync] decides what
 * goes where): shortly after an item is ticked or edited here, and every half hour for changes
 * made in a calendar or tasks app.
 */
class TaskSyncer(
    private val context: Context,
    private val meetings: MeetingRepository,
    private val exporter: MeetingExporter,
    private val settings: () -> AppSettings,
) {
    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data class Done(val at: Long, val pushed: Int, val pulled: Int) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()

    /** After exporting [meetingId]'s tasks: it is kept in step from now on, starting in step. */
    suspend fun markExported(meetingId: String) {
        val client = exporter.calDavTasks() ?: return
        if (!settings().export.caldavTasks) return
        val now = System.currentTimeMillis()
        meetings.update(meetingId) { m ->
            m.copy(
                tasksExportedTo = client.tasksCollection,
                insights = m.insights?.let { i -> i.copy(actionItems = i.actionItems.map { it.copy(syncedAt = now) }) },
            )
        }
    }

    /** Brings exported tasks and action items in step; returns how many changed. */
    suspend fun sync(): Int = mutex.withLock {
        if (!settings().export.caldavTaskSync) return@withLock 0
        val client = exporter.calDavTasks() ?: return@withLock 0
        val exported = meetings.all().filter { it.tasksExportedTo != null && !it.insights?.actionItems.isNullOrEmpty() }
        if (exported.isEmpty()) return@withLock 0
        _status.value = Status.Working
        try {
            val remote = exporter.remoteTasks()
            val changes = TaskSync.plan(exported, client.tasksCollection, remote)
            if (changes.isEmpty()) {
                _status.value = Status.Done(System.currentTimeMillis(), 0, 0)
                return@withLock 0
            }
            val now = System.currentTimeMillis()
            var pushed = 0
            var pulled = 0
            for ((meetingId, forMeeting) in changes.groupBy { it.meetingId }) {
                val meeting = exported.first { it.id == meetingId }
                val done = mutableListOf<TaskSync.Change>()
                for (change in forMeeting) {
                    if (change is TaskSync.Change.Push) {
                        val item = meeting.insights?.actionItems?.firstOrNull { it.id == change.itemId } ?: continue
                        try {
                            client.putTask(meeting, item, now)
                            pushed++
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Couldn't update the task for \"${item.task}\"", e)
                            continue
                        }
                    } else if (change is TaskSync.Change.Pull) {
                        pulled++
                    }
                    done += change
                }
                if (done.isEmpty()) continue
                val byItem = done.associateBy { it.itemId }
                meetings.update(meetingId) { m ->
                    val i = m.insights ?: return@update m
                    m.copy(insights = i.copy(actionItems = i.actionItems.map { item -> byItem[item.id]?.let { TaskSync.apply(item, it, now) } ?: item }))
                }
            }
            _status.value = Status.Done(now, pushed, pulled)
            pushed + pulled
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Task sync failed", e)
            _status.value = Status.Failed(e.message ?: "Couldn't reach the calendar")
            0
        }
    }

    /** Syncs shortly after exported action items change here, and turns the periodic check on or off. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            meetings.meetings
                .map { list ->
                    list.filter { it.tasksExportedTo != null }.map { m ->
                        m.id to m.insights?.actionItems.orEmpty().map { listOf(it.id, it.done, it.task, it.dueDateIso, it.updatedAt) }
                    }
                }
                .distinctUntilChanged()
                .drop(1)
                .debounce(LOCAL_SETTLE_MS)
                .collect { runCatching { sync() } }
        }
        applySettings()
    }

    fun applySettings() {
        val work = WorkManager.getInstance(context)
        if (settings().export.caldavTaskSync && settings().export.caldavUrl.isNotBlank()) {
            work.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TaskSyncWorker>(30, TimeUnit.MINUTES)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        } else {
            work.cancelUniqueWork(WORK)
        }
    }

    private companion object {
        const val TAG = "TaskSync"
        const val WORK = "caldav-task-sync"
        const val LOCAL_SETTLE_MS = 15_000L
    }
}

/** Picks up ticks and edits made in a calendar or tasks app. */
class TaskSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KaliScriberApp).container
        container.meetings.load()
        container.taskSync.sync()
        return Result.success()
    }
}
