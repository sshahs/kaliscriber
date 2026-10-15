package com.kaliscriber.core

import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.series.MeetingSeries
import com.kaliscriber.core.series.TalkStats
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SeriesTest {
    private fun meeting(id: String, title: String, at: Long, auto: Boolean = false, uid: String? = null, insights: MeetingInsights? = null) =
        Meeting(
            id, title, at, autoTitle = auto, status = MeetingStatus.READY, insights = insights,
            calendarEvent = uid?.let { CalendarEvent(it, "Event $it", at, at + 1) },
        )

    @Test
    fun `meetings with the same title or calendar event form a series`() {
        val first = meeting("a", "Weekly sync 12 Oct", 1, insights = MeetingInsights(
            decisions = listOf("Ship on Friday"),
            actionItems = listOf(ActionItem("x", "Send the deck", owner = "Sam"), ActionItem("y", "Book room", done = true)),
        ))
        val second = meeting("b", "Weekly Sync – 19 October", 2)
        val third = meeting("c", "weekly sync", 3)
        val other = meeting("d", "Budget review", 2)
        val untitled = meeting("e", "Meeting 14:00", 2, auto = true)
        val all = listOf(first, second, third, other, untitled)

        assertEquals(listOf("b", "a"), MeetingSeries.history(third, all).map { it.id })
        val since = MeetingSeries.sinceLast(second, all)!!
        assertEquals("a", since.previous.id)
        assertEquals(listOf("Ship on Friday"), since.decisions)
        assertEquals(listOf("Send the deck"), since.stillOpen.map { it.task })
        assertEquals(listOf("Book room"), since.done.map { it.task })
        assertNull(MeetingSeries.sinceLast(first, all), "nothing before the first")
        assertNull(MeetingSeries.sinceLast(untitled, all), "titles the app made up don't link meetings")

        // Occurrences of one calendar event match whatever they are called.
        val x = meeting("x", "Kickoff", 1, uid = "evt-1")
        val y = meeting("y", "Renamed later", 2, uid = "evt-1")
        assertEquals("x", MeetingSeries.sinceLast(y, listOf(x, y))!!.previous.id)
    }

    @Test
    fun `talk time counts speaking, turns, questions and the longest stretch`() {
        fun seg(i: Int, from: Long, to: Long, who: String, text: String) = TranscriptSegment("s$i", from, to, text, who)
        val m = Meeting(
            "m", "1:1", 0, status = MeetingStatus.READY,
            speakers = listOf(Speaker("p", "Priya", isPlaceholder = false), Speaker("s", "Sam", isPlaceholder = false)),
            segments = listOf(
                seg(0, 0, 10_000, "p", "How did the launch go? Any surprises?"),
                seg(1, 10_000, 40_000, "s", "Well."),
                seg(2, 40_000, 70_000, "s", "Mostly fine."),
                seg(3, 70_000, 80_000, "p", "Great."),
            ),
        )
        val stats = TalkStats.of(m)!!
        assertEquals(listOf("Sam", "Priya"), stats.people.map { it.name })
        val sam = stats.people[0]
        val priya = stats.people[1]
        assertEquals(60_000L, sam.speakingMs)
        assertEquals(0.75f, sam.share)
        assertEquals(1, sam.turns)
        assertEquals(60_000L, sam.longestMs)
        assertEquals(2, priya.turns)
        assertEquals(2, priya.questions)
        assertEquals(3, stats.turns)
        assertNull(TalkStats.of(m.copy(segments = emptyList())))
    }
}

class InitialsTest {
    @Test
    fun `initials come from the first and last word`() {
        assertEquals("PS", com.kaliscriber.core.library.Initials.of("Priya Shah"))
        assertEquals("AL", com.kaliscriber.core.library.Initials.of("Ana de la Luz"))
        assertEquals("S3", com.kaliscriber.core.library.Initials.of("Speaker 3"))
        assertEquals("É", com.kaliscriber.core.library.Initials.of("élodie"))
        assertEquals("?", com.kaliscriber.core.library.Initials.of("  "))
    }
}
