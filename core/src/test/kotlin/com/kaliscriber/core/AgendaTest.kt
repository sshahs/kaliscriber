package com.kaliscriber.core

import com.kaliscriber.core.ai.Agenda
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AgendaItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgendaTest {
    @Test
    fun `agenda lines lose their bullets and numbering`() {
        assertEquals(
            listOf("Budget", "Hiring plan", "Launch date", "Office move"),
            Agenda.items("1. Budget\n- Hiring plan\n\n  * Launch date\n[ ] Office move\n"),
        )
    }

    @Test
    fun `the notes say which agenda items were covered`() = runTest {
        val meeting = Meeting(
            "m", "Planning", 0, autoTitle = false, agenda = "1. Budget\n2. Launch date",
            segments = listOf(TranscriptSegment("s", 0, 1000, "The budget is approved.")),
        )
        val llm = FakeLlm {
            """{"title":"Planning","summary":"Budget approved.",
               "agenda":[{"item":"Budget","covered":true,"notes":"Approved"},{"item":"Launch date","covered":false,"notes":"Not discussed"}]}"""
        }
        val ai = MeetingAi(FakeAi(llm, FakeStt(false) { SttResult(emptyList(), false) }), { AppSettings() })
        val insights = ai.insights(meeting)
        val system = llm.requests.single().system
        assertTrue(system.contains("<agenda>") && system.contains("- Launch date") && system.contains("\"covered\""), system)
        assertEquals(listOf(AgendaItem("Budget", true, "Approved"), AgendaItem("Launch date", false, "Not discussed")), insights.agenda)
        val md = TranscriptFormatter.markdown(meeting.copy(insights = insights))
        assertTrue(md.contains("## Agenda\n- [x] Budget — Approved\n- [ ] Launch date (not covered) — Not discussed"), md)

        // Without an agenda nothing about it is asked for.
        ai.insights(meeting.copy(agenda = ""))
        assertFalse(llm.requests.last().system.contains("<agenda>"))
    }
}
