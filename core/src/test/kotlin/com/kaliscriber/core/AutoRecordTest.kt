package com.kaliscriber.core

import com.kaliscriber.core.calendar.AutoRecord
import com.kaliscriber.core.calendar.AutoRecord.Decision
import com.kaliscriber.core.calendar.AutoRecord.Recorder
import com.kaliscriber.core.calendar.AutoRecord.Which
import com.kaliscriber.core.calendar.CalendarEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AutoRecordTest {
    private val min = 60_000L
    private fun event(uid: String, start: Long, end: Long, people: Int = 0, allDay: Boolean = false) =
        CalendarEvent(uid, "Event $uid", start * min, end * min, attendees = List(people) { "P$it" }, allDay = allDay)

    private val sync = event("sync", 60, 90, people = 3)
    private val focus = event("focus", 120, 180)
    private val lunch = event("lunch", 200, 202) // a reminder, too short
    private val holiday = event("holiday", 0, 1440, allDay = true)
    private val events = listOf(sync, focus, lunch, holiday)
    private val none = AutoRecord.State()

    @Test
    fun `events are recorded from their start to their end`() {
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 59 * min, Which.ALL, none, Recorder.IDLE))
        assertEquals(60 * min, AutoRecord.nextWake(events, 59 * min, Which.ALL, none, Recorder.IDLE))
        assertEquals(Decision.Start(sync), AutoRecord.decide(events, 60 * min, Which.ALL, none, Recorder.IDLE))
        // Joined late, it still starts; too close to the end, it doesn't.
        assertEquals(Decision.Start(sync), AutoRecord.decide(events, 80 * min, Which.ALL, none, Recorder.IDLE))
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 89 * min, Which.ALL, none, Recorder.IDLE))

        val recording = none.copy(
            active = AutoRecord.Active(AutoRecord.key(sync), "m1", sync.title, sync.end),
            handled = mapOf(AutoRecord.key(sync) to sync.end),
        )
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 70 * min, Which.ALL, recording, Recorder.AUTO))
        assertEquals(90 * min, AutoRecord.nextWake(events, 70 * min, Which.ALL, recording, Recorder.AUTO))
        assertEquals(Decision.Stop, AutoRecord.decide(events, 90 * min, Which.ALL, recording, Recorder.AUTO))
        // Kept going by the user: no stop, and the next wake is the next event.
        val kept = recording.copy(active = recording.active!!.copy(stopAtEnd = false))
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 95 * min, Which.ALL, kept, Recorder.AUTO))
        assertEquals(120 * min, AutoRecord.nextWake(events, 95 * min, Which.ALL, kept, Recorder.AUTO))
        // Stopped by hand mid-meeting: it isn't started again.
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 75 * min, Which.ALL, recording.copy(active = null), Recorder.IDLE))
    }

    @Test
    fun `reminders, all-day events, skipped ones and other people's recordings are left alone`() {
        assertNull(AutoRecord.nextWake(events, 190 * min, Which.ALL, none, Recorder.IDLE), "lunch is too short to be a meeting")
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 600 * min, Which.ALL, none, Recorder.IDLE), "all-day events aren't recorded")
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 130 * min, Which.WITH_OTHERS, none, Recorder.IDLE), "focus time has nobody invited")
        val skipped = none.copy(skipped = mapOf(AutoRecord.key(sync) to sync.end))
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 60 * min, Which.ALL, skipped, Recorder.IDLE))
        assertEquals(focus, AutoRecord.upcoming(events, 0, Which.ALL, skipped))
        assertEquals(Decision.Nothing, AutoRecord.decide(events, 60 * min, Which.ALL, none, Recorder.MANUAL), "a recording the user started is theirs")
    }

    @Test
    fun `back-to-back and overlapping meetings`() {
        val a = event("a", 60, 120)
        val b = event("b", 120, 180)
        val c = event("c", 150, 200)
        val list = listOf(a, b, c)
        val onA = none.copy(active = AutoRecord.Active(AutoRecord.key(a), "m", a.title, a.end), handled = mapOf(AutoRecord.key(a) to a.end))
        // At 2:00 a ends; once stopped, b starts.
        assertEquals(Decision.Stop, AutoRecord.decide(list, 120 * min, Which.ALL, onA, Recorder.AUTO))
        assertEquals(Decision.Start(b), AutoRecord.decide(list, 120 * min, Which.ALL, onA.copy(active = null), Recorder.IDLE))
        // c starts while b is recorded: b carries on, then c is picked up when b ends.
        val onB = none.copy(
            active = AutoRecord.Active(AutoRecord.key(b), "m2", b.title, b.end),
            handled = mapOf(AutoRecord.key(a) to a.end, AutoRecord.key(b) to b.end),
        )
        assertEquals(Decision.Nothing, AutoRecord.decide(list, 150 * min, Which.ALL, onB, Recorder.AUTO))
        assertEquals(Decision.Start(c), AutoRecord.decide(list, 180 * min, Which.ALL, onB.copy(active = null), Recorder.IDLE))
        // Old occurrences are forgotten after a day.
        assertEquals(emptyMap(), onB.pruned(3 * 24 * 60 * min).handled)
    }
}
