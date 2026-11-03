package com.kaliscriber.core.tasks

import com.kaliscriber.core.calendar.IcsTask
import com.kaliscriber.core.export.ICalendar
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting

/**
 * Keeps action items and their CalDAV tasks in step, both ways, for meetings whose tasks were
 * exported to [collection]: ticking or editing an item here updates its task, and ticking or
 * editing the task in a calendar or tasks app updates the item. When both changed since they were
 * last in step, the later change wins.
 */
object TaskSync {
    sealed interface Change {
        val meetingId: String
        val itemId: String

        /** Write the item to its task. */
        data class Push(override val meetingId: String, override val itemId: String) : Change

        /** Take the task's state into the item. */
        data class Pull(
            override val meetingId: String,
            override val itemId: String,
            val done: Boolean,
            val task: String,
            val dueIso: String?,
        ) : Change

        /** Already the same: just note that they are in step. */
        data class Mark(override val meetingId: String, override val itemId: String) : Change
    }

    fun plan(meetings: List<Meeting>, collection: String, remote: List<IcsTask>): List<Change> {
        val byUid = remote.associateBy { it.uid }
        val changes = mutableListOf<Change>()
        for (meeting in meetings) {
            if (meeting.tasksExportedTo == null || !sameCollection(meeting.tasksExportedTo, collection)) continue
            for (item in meeting.insights?.actionItems.orEmpty()) {
                changes += change(meeting, item, byUid[ICalendar.taskUid(meeting, item)]) ?: continue
            }
        }
        return changes
    }

    private fun change(meeting: Meeting, item: ActionItem, task: IcsTask?): Change? {
        val m = meeting.id
        val i = item.id
        if (task == null) {
            // Never written (new since the export): create it. Written before but gone: it was
            // deleted in the calendar on purpose, so leave it deleted.
            return if (item.syncedAt == 0L) Change.Push(m, i) else null
        }
        val same = task.done == item.done && task.summary.trim() == item.task.trim() && task.dueIso == item.dueDateIso
        if (same) return if (item.syncedAt < maxOf(item.updatedAt, task.lastModified)) Change.Mark(m, i) else null
        val pull = Change.Pull(m, i, task.done, task.summary.trim().ifEmpty { item.task }, task.dueIso)

        // Exported before changes were tracked: nothing says which side is newer. Keep what's done
        // done, and the user's own wording if they edited it here.
        if (item.syncedAt == 0L && item.updatedAt == 0L) {
            return when {
                item.done && !task.done -> Change.Push(m, i)
                !item.done && task.done -> pull
                item.edited -> Change.Push(m, i)
                else -> pull
            }
        }
        val changedHere = item.updatedAt > item.syncedAt
        val changedThere = task.lastModified > item.syncedAt
        return when {
            changedHere && !changedThere -> Change.Push(m, i)
            changedThere && !changedHere -> pull
            changedHere && changedThere -> if (item.updatedAt >= task.lastModified) Change.Push(m, i) else pull
            else -> Change.Push(m, i)
        }
    }

    /** The item after [change] is applied at [now]. */
    fun apply(item: ActionItem, change: Change, now: Long): ActionItem = when (change) {
        is Change.Pull -> item.copy(
            done = change.done,
            task = change.task,
            dueDateIso = change.dueIso,
            // Keep a human due date in step when the calendar moved it.
            dueDate = if (change.dueIso != item.dueDateIso) change.dueIso else item.dueDate,
            edited = item.edited || change.task != item.task,
            syncedAt = now,
        )
        is Change.Push, is Change.Mark -> item.copy(syncedAt = now)
    }

    private fun sameCollection(a: String, b: String) = a.trimEnd('/') == b.trimEnd('/')
}
