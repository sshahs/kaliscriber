package com.kaliscriber.core

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.sync.MarkdownSync
import com.kaliscriber.core.sync.MarkdownSync.Action
import com.kaliscriber.core.sync.MarkdownSync.Entry
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarkdownSyncTest {
    private val utc = ZoneOffset.UTC
    private val day = 1_791_936_000_000L // 2026-10-14 00:00 UTC

    private fun meeting(id: String, title: String, at: Long = day, updated: Long = at, status: MeetingStatus = MeetingStatus.READY) =
        Meeting(id, title, at, status = status, updatedAt = updated)

    @Test
    fun `new meetings are written, changed ones rewritten, renamed and deleted ones tidied`() {
        val a = meeting("a", "Weekly sync: Q4 / planning")
        val b = meeting("b", "Weekly sync: Q4 / planning", at = day + 3_600_000)
        val c = meeting("c", "Recording", status = MeetingStatus.RECORDING)
        assertEquals("2026-10-14 Weekly sync Q4 planning", MarkdownSync.baseName(a, utc))

        val first = MarkdownSync.plan(listOf(a, b, c), emptyMap(), transcript = true, zone = utc)
        assertEquals(
            listOf(Action.Write("a", "2026-10-14 Weekly sync Q4 planning.md"), Action.Write("b", "2026-10-14 Weekly sync Q4 planning (2).md")),
            first,
            "same day and title get distinct names; meetings in progress wait",
        )
        val state = mapOf(
            "a" to Entry("2026-10-14 Weekly sync Q4 planning.md", a.updatedAt, true),
            "b" to Entry("2026-10-14 Weekly sync Q4 planning (2).md", b.updatedAt, true),
            "gone" to Entry("2026-10-01 Old.md", 1, true),
        )
        // Nothing changed except a deleted meeting.
        assertEquals(listOf(Action.Delete("2026-10-01 Old.md")), MarkdownSync.plan(listOf(a, b), state, true, utc))
        // b changed and a was renamed to what b is called: a's old file goes first, then both are written.
        val renamed = a.copy(title = "Budget", updatedAt = a.updatedAt + 1)
        val changed = b.copy(updatedAt = b.updatedAt + 5)
        assertEquals(
            listOf(
                Action.Delete("2026-10-01 Old.md"),
                Action.Delete("2026-10-14 Weekly sync Q4 planning.md"),
                Action.Write("a", "2026-10-14 Budget.md"),
                Action.Write("b", "2026-10-14 Weekly sync Q4 planning (2).md"),
            ),
            MarkdownSync.plan(listOf(renamed, changed), state, true, utc),
        )
        // Leaving the transcript out rewrites everything.
        assertEquals(2, MarkdownSync.plan(listOf(a, b), state - "gone", transcript = false, zone = utc).size)
    }

    @Test
    fun `the note has properties Obsidian reads and the notes`() {
        val m = meeting("m1", "Kick-off: \"Atlas\"").copy(
            durationMs = 3_725_000,
            speakers = listOf(Speaker("p", "Priya", isPlaceholder = false), Speaker("x", "Amber Falcon")),
            segments = listOf(TranscriptSegment("s", 0, 1000, "Hello there.", "p")),
            insights = MeetingInsights(summary = "We started.", actionItems = listOf(ActionItem("t", "Send plan"))),
        )
        val note = MarkdownSync.note(m, transcript = true, zone = utc)
        assertTrue(note.startsWith("---\ntitle: \"Kick-off: \\\"Atlas\\\"\"\ndate: 2026-10-14T00:00:00Z\nduration: \"1:02:05\"\npeople:\n  - Priya\nformat: General\nopen_actions: 1\ntags:\n  - meeting\nkaliscriber_id: m1\n---\n\n# Kick-off"), note)
        assertTrue(note.contains("## Summary\nWe started.") && note.contains("- [ ] Send plan") && note.contains("## Transcript"), note)
        assertFalse(MarkdownSync.note(m, transcript = false, zone = utc).contains("## Transcript"))
    }
}
