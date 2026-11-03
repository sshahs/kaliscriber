package com.kaliscriber.core

import com.kaliscriber.core.calendar.CalDavCalendar
import com.kaliscriber.core.calendar.IcsTask
import com.kaliscriber.core.export.ICalendar
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.tasks.TaskSync
import com.kaliscriber.core.tasks.TaskSync.Change
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskSyncTest {
    private val url = "https://dav.example/dav.php/calendars/sam/tasks/"

    private fun meeting(vararg items: ActionItem, exported: String? = url) =
        Meeting("m", "Planning", 0, insights = MeetingInsights(actionItems = items.toList()), tasksExportedTo = exported)

    private fun remote(m: Meeting, item: ActionItem, done: Boolean = item.done, summary: String = item.task, due: String? = item.dueDateIso, at: Long) =
        IcsTask(ICalendar.taskUid(m, item), summary, done, due, at)

    @Test
    fun `changes flow the way they were made, the later one winning`() {
        // In step at 1000.
        val ticked = ActionItem("a", "Send deck", done = true, updatedAt = 2000, syncedAt = 1000)
        val untouched = ActionItem("b", "Book room", syncedAt = 1000)
        val both = ActionItem("c", "Call Sam", done = true, updatedAt = 3000, syncedAt = 1000)
        val fresh = ActionItem("d", "New since export")
        val deletedThere = ActionItem("e", "Gone in calendar", syncedAt = 1000)
        val m = meeting(ticked, untouched, both, fresh, deletedThere)
        val remote = listOf(
            remote(m, ticked, done = false, at = 1000),             // only changed here: push
            remote(m, untouched, done = true, at = 2500),           // only changed there: pull
            remote(m, both, done = false, summary = "Call Sam today", at = 4000), // both, there later: pull
        )
        val changes = TaskSync.plan(listOf(m), url.trimEnd('/'), remote)
        assertEquals(
            listOf(
                Change.Push("m", "a"),
                Change.Pull("m", "b", true, "Book room", null),
                Change.Pull("m", "c", false, "Call Sam today", null),
                Change.Push("m", "d"),
            ),
            changes,
        )
        // Not exported, or exported elsewhere: left alone.
        assertTrue(TaskSync.plan(listOf(meeting(fresh, exported = null)), url, emptyList()).isEmpty())
        assertTrue(TaskSync.plan(listOf(meeting(fresh, exported = "https://other/")), url, emptyList()).isEmpty())
    }

    @Test
    fun `items exported before changes were tracked keep what's done and edited`() {
        val doneHere = ActionItem("a", "Send deck", done = true)
        val doneThere = ActionItem("b", "Book room")
        val editedHere = ActionItem("c", "Call Sam on Monday", edited = true)
        val m = meeting(doneHere, doneThere, editedHere)
        val changes = TaskSync.plan(
            listOf(m), url,
            listOf(
                remote(m, doneHere, done = false, at = 500),
                remote(m, doneThere, done = true, at = 500),
                remote(m, editedHere, summary = "Call Sam", at = 500),
            ),
        )
        assertEquals(listOf(Change.Push("m", "a"), Change.Pull("m", "b", true, "Book room", null), Change.Push("m", "c")), changes)

        // Applying a pull records the sync and keeps a moved due date readable.
        val pulled = TaskSync.apply(doneThere, Change.Pull("m", "b", true, "Book room", "2026-11-20"), now = 9000)
        assertEquals(doneThere.copy(done = true, dueDateIso = "2026-11-20", dueDate = "2026-11-20", syncedAt = 9000), pulled)
        // Once in step, nothing more to do.
        val inStep = ActionItem("x", "Same", syncedAt = 9000)
        val mm = meeting(inStep)
        assertTrue(TaskSync.plan(listOf(mm), url, listOf(remote(mm, inStep, at = 9000))).isEmpty())
    }

    @Test
    fun `tasks are read from a CalDAV reply`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav"><d:response><d:propstat><d:prop>
            <cal:calendar-data>BEGIN:VCALENDAR&#13;
            BEGIN:VTODO&#13;
            UID:kaliscriber-task-m-a&#13;
            SUMMARY:Send the deck\, then call&#13;
            STATUS:COMPLETED&#13;
            DUE;VALUE=DATE:20261120&#13;
            LAST-MODIFIED:20261103T101500Z&#13;
            BEGIN:VALARM&#13;
            SUMMARY:ignored&#13;
            END:VALARM&#13;
            END:VTODO&#13;
            END:VCALENDAR</cal:calendar-data></d:prop></d:propstat></d:response></d:multistatus>
        """.trimIndent()
        val task = CalDavCalendar.parseTasks(xml).single()
        assertEquals(IcsTask("kaliscriber-task-m-a", "Send the deck, then call", true, "2026-11-20", 1_793_700_900_000L), task)
    }
}
