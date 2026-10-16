package com.kaliscriber.core

import com.kaliscriber.core.calendar.AutoRecord
import com.kaliscriber.core.calendar.ScheduledRecording
import com.kaliscriber.core.calendar.ScheduledRecording.Repeat
import com.kaliscriber.core.calendar.ScheduledRecordings
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScheduledRecordingTest {
    private val zone = ZoneId.of("Europe/London")
    private fun at(text: String) = LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `one-off and repeating schedules become occurrences`() {
        // Wednesday 14 Oct 2026, 09:30–10:00.
        val once = ScheduledRecording("a", "Interview", at("2026-10-14T09:30"), at("2026-10-14T10:00"))
        val daily = once.copy(id = "b", title = "Stand-up", repeat = Repeat.WEEKDAYS)
        val weekly = once.copy(id = "c", title = "", repeat = Repeat.WEEKLY)

        val week = ScheduledRecordings.occurrences(listOf(once, daily, weekly), at("2026-10-14T00:00"), at("2026-10-22T00:00"), zone)
        assertEquals(1, week.count { it.uid == "app:a" })
        // Wed, Thu, Fri, Mon, Tue, Wed: no weekend.
        assertEquals(
            listOf("2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-20", "2026-10-21"),
            week.filter { it.uid == "app:b" }.map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString() },
        )
        assertEquals(listOf(at("2026-10-14T09:30"), at("2026-10-21T09:30")), week.filter { it.uid == "app:c" }.map { it.start })
        assertEquals("Scheduled recording", week.first { it.uid == "app:c" }.title)

        // Clocks go back on 25 Oct 2026: the weekly one stays at 09:30 local time, 30 minutes long.
        val after = ScheduledRecordings.next(weekly, at("2026-10-27T00:00"), zone)!!
        assertEquals(at("2026-10-28T09:30"), after.start)
        assertEquals(30 * 60_000L, after.end - after.start)

        // A one-off is over once it has ended, and pruned a day later.
        assertNull(ScheduledRecordings.next(once, at("2026-10-14T10:01"), zone))
        assertEquals(listOf("b", "c"), ScheduledRecordings.pruned(listOf(once, daily, weekly), at("2026-10-16T00:00")).map { it.id })
    }

    @Test
    fun `scheduled recordings are recorded whatever the calendar filters say`() {
        val short = ScheduledRecording("s", "Quick call", at("2026-10-14T09:30"), at("2026-10-14T09:33"))
        val event = ScheduledRecordings.occurrences(listOf(short), at("2026-10-14T00:00"), at("2026-10-15T00:00"), zone).single()
        val none = AutoRecord.State()
        // Three minutes and nobody invited: a calendar event like this would be skipped.
        assertTrue(AutoRecord.wanted(event, AutoRecord.Which.WITH_OTHERS, none))
        assertEquals(AutoRecord.Decision.Start(event), AutoRecord.decide(listOf(event), at("2026-10-14T09:30"), AutoRecord.Which.WITH_OTHERS, none, AutoRecord.Recorder.IDLE))
        val skipped = none.copy(skipped = mapOf(AutoRecord.key(event) to event.end))
        assertEquals(AutoRecord.Decision.Nothing, AutoRecord.decide(listOf(event), at("2026-10-14T09:30"), AutoRecord.Which.ALL, skipped, AutoRecord.Recorder.IDLE))
    }
}
