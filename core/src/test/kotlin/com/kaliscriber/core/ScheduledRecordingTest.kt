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

    @Test
    fun `chosen days, every day, paused schedules, reminders and labels`() {
        // Wednesday 14 Oct 2026, 14:00–15:00, on Mondays and Thursdays.
        val base = ScheduledRecording("x", "Review", at("2026-10-14T14:00"), at("2026-10-14T15:00"))
        val custom = base.copy(repeat = Repeat.CUSTOM, days = listOf(4, 1), remindMinutes = 10)
        val days = ScheduledRecordings.occurrences(listOf(custom), at("2026-10-14T00:00"), at("2026-10-21T00:00"), zone)
            .map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().dayOfWeek.value }
        assertEquals(listOf(4, 1), days, "Thursday 15th then Monday 19th")
        assertEquals("Mon and Thu", ScheduledRecordings.repeatLabel(custom, zone, java.util.Locale.UK))
        assertEquals("Every Wednesday", ScheduledRecordings.repeatLabel(base.copy(repeat = Repeat.WEEKLY), zone, java.util.Locale.UK))
        assertEquals(7, ScheduledRecordings.occurrences(listOf(base.copy(repeat = Repeat.DAILY)), at("2026-10-14T00:00"), at("2026-10-21T00:00"), zone).size)

        val first = ScheduledRecordings.occurrences(listOf(custom), at("2026-10-14T00:00"), at("2026-10-21T00:00"), zone).first()
        assertEquals(at("2026-10-15T13:50"), ScheduledRecordings.reminderAt(first, listOf(custom)))
        assertNull(ScheduledRecordings.reminderAt(first, listOf(custom.copy(remindMinutes = 0))))

        // Paused: no occurrences, but its next time is still known for showing.
        val paused = custom.copy(enabled = false)
        assertTrue(ScheduledRecordings.occurrences(listOf(paused), at("2026-10-14T00:00"), at("2026-10-21T00:00"), zone).isEmpty())
        assertEquals(at("2026-10-15T14:00"), ScheduledRecordings.next(paused, at("2026-10-14T16:00"), zone)?.start)

        val mine = listOf(com.kaliscriber.core.ai.CustomFormat("f", "Ticket review"))
        assertEquals("Ticket review", ScheduledRecordings.formatLabel(base.copy(formatId = "f"), mine))
        assertEquals("1:1", ScheduledRecordings.formatLabel(base.copy(type = com.kaliscriber.core.ai.MeetingType.ONE_ON_ONE), mine))
        assertNull(ScheduledRecordings.formatLabel(base, mine))
    }

    @Test
    fun `repeats can end, recording can be paused for a while and schedules duplicated`() {
        // Every weekday from Wednesday 14 Oct 2026 until Friday 16 Oct.
        val s = ScheduledRecording("w", "Stand-up", at("2026-10-14T09:00"), at("2026-10-14T09:15"), Repeat.WEEKDAYS, until = "2026-10-16")
        val days = ScheduledRecordings.occurrences(listOf(s), at("2026-10-14T00:00"), at("2026-10-24T00:00"), zone)
            .map { java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString() }
        assertEquals(listOf("2026-10-14", "2026-10-15", "2026-10-16"), days)
        // Kept until its last day is over, then dropped.
        assertEquals(1, ScheduledRecordings.pruned(listOf(s), at("2026-10-16T20:00"), zone).size)
        assertEquals(0, ScheduledRecordings.pruned(listOf(s), at("2026-10-18T09:00"), zone).size)

        val thursday = ScheduledRecordings.occurrences(listOf(s), at("2026-10-15T00:00"), at("2026-10-16T00:00"), zone).single()
        assertTrue(ScheduledRecordings.paused(thursday, "2026-10-15", zone), "paused to the end of the day")
        assertTrue(!ScheduledRecordings.paused(thursday, "2026-10-14", zone))
        assertTrue(!ScheduledRecordings.paused(thursday, null, zone))

        val copy = ScheduledRecordings.duplicate(s, "w2")
        assertEquals("w2", copy.id)
        assertEquals("Stand-up (copy)", copy.title)
        assertEquals(s.copy(id = "w2", title = "Stand-up (copy)"), copy)
    }
}
