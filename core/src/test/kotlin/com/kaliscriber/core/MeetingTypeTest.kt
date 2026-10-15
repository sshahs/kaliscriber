package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MeetingTypeTest {
    private fun meeting(title: String, autoTitle: Boolean = false, event: String? = null) = Meeting(
        "m", title, 0, autoTitle = autoTitle,
        calendarEvent = event?.let { CalendarEvent("e", it, 0, 1) },
        segments = listOf(TranscriptSegment("s", 0, 1000, "Yesterday I fixed the login bug, today the export. Blocked on review.")),
    )

    @Test
    fun `the type is guessed from the meeting or calendar title`() {
        assertEquals(MeetingType.STANDUP, MeetingType.guess(meeting("Daily stand-up")))
        assertEquals(MeetingType.ONE_ON_ONE, MeetingType.guess(meeting("Priya / Sam 1:1")))
        assertEquals(MeetingType.SALES_CALL, MeetingType.guess(meeting("Meeting 14 Oct", autoTitle = true, event = "Acme discovery call")))
        assertEquals(MeetingType.INTERVIEW, MeetingType.guess(meeting("Interview: backend engineer")))
        assertEquals(MeetingType.LECTURE, MeetingType.guess(meeting("Linear algebra lecture 4")))
        assertEquals(MeetingType.GENERAL, MeetingType.guess(meeting("Budget review")))
        // A title the AI made up doesn't decide the format.
        assertEquals(MeetingType.GENERAL, MeetingType.guess(meeting("Daily stand-up", autoTitle = true)))
        assertEquals(MeetingType.LECTURE, MeetingType.of(meeting("Daily stand-up").copy(type = MeetingType.LECTURE)))
    }

    @Test
    fun `a stand-up summary asks for and keeps its own sections`() = runTest {
        val llm = FakeLlm {
            """{"title":"Stand-up","summary":"Short.","actionItems":[],
               "sections":[{"title":"By person","items":["Sam: done login bug; next export; blockers review"]},{"title":"Blockers","items":[]}]}"""
        }
        val ai = MeetingAi(FakeAi(llm, FakeStt(false) { SttResult(emptyList(), false) }), { AppSettings() })
        val insights = ai.insights(meeting("Daily stand-up"))
        val system = llm.requests.single().system
        assertTrue(system.contains(Prompts.TASK_INSIGHTS) && system.contains("\"By person\"") && system.contains("\"Blockers\""), system)
        assertEquals(MeetingType.STANDUP, insights.type)
        assertEquals(listOf("By person"), insights.sections.map { it.title }, "empty sections are dropped")
        val md = TranscriptFormatter.markdown(meeting("Daily stand-up").copy(insights = insights))
        assertTrue(md.contains("## By person\n- Sam: done login bug"), md)

        ai.insights(meeting("Budget review"))
        assertFalse(llm.requests.last().system.contains("\"sections\""), "general meetings keep the usual format")
    }
}
