package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.DefaultAiServices
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.RedactingLlm
import com.kaliscriber.core.ai.Redaction
import com.kaliscriber.core.ai.RedactionSettings
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.ask.AskScope
import com.kaliscriber.core.diagnostics.DiagnosticLog
import com.kaliscriber.core.diagnostics.ProblemReport
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.ForMe
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.projects.Project
import com.kaliscriber.core.projects.Projects
import com.kaliscriber.core.sync.MarkdownSync
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PrivacyAndProjectsTest {
    private val on = RedactionSettings(enabled = true, names = "Priya Sharma, Priya\nAcme Corp")

    @Test
    fun `emails, phones, cards and listed names are masked and come back`() {
        val r = Redaction(on)
        val text = "[00:12] Priya Sharma: mail priya@acme.com or call +44 7700 900123. Card 4111 1111 1111 1111. " +
            "Priya said Acme Corp pays on 2026-11-04 at 10:30, budget 250000."
        val masked = r.redact(text)
        assertFalse(masked.contains("priya", ignoreCase = true), masked)
        assertFalse(masked.contains("acme", ignoreCase = true), masked)
        assertFalse(masked.contains("7700"), masked)
        assertFalse(masked.contains("4111"), masked)
        // Timestamps, dates, times and ordinary numbers stay.
        assertTrue(masked.contains("[00:12]") && masked.contains("2026-11-04") && masked.contains("10:30") && masked.contains("250000"), masked)
        // Both variants of a name share one placeholder.
        assertEquals(2, Regex("\\[NAME_1]").findAll(masked).count(), masked)
        assertTrue(masked.contains("[EMAIL_1]") && masked.contains("[PHONE_1]") && masked.contains("[CARD_1]") && masked.contains("[NAME_2]"), masked)

        val answer = "[NAME_1] will email [EMAIL_1]; NAME_2 owes the invoice."
        assertEquals("Priya Sharma will email priya@acme.com; Acme Corp owes the invoice.", r.restore(answer))
        // A placeholder cut off mid-stream waits for the rest.
        assertEquals("Ask ", r.restorePartial("Ask [NAM"))
    }

    @Test
    fun `card-like numbers that fail the checksum are not cards, and off means untouched`() {
        val r = Redaction(on.copy(phones = false))
        assertEquals("Order 1234 5678 9012 3456", r.redact("Order 1234 5678 9012 3456"))
        val text = "Priya at priya@acme.com"
        assertEquals(text, Redaction(on.copy(enabled = false)).redact(text))
    }

    @Test
    fun `requests are masked the same way across system, prefix and messages, and answers restored`() = runTest {
        val llm = FakeLlm { "[NAME_1] owns it. Reach them at [EMAIL_1]." }
        val client = RedactingLlm(llm, on)
        val prefix = "Meeting with Priya Sharma (priya@acme.com).\n"
        val answer = client.complete(
            LlmRequest(system = prefix + "Task", messages = listOf(LlmMessage(LlmRole.USER, "What did Priya ask?")), cachePrefix = prefix),
        )
        assertEquals("Priya Sharma owns it. Reach them at priya@acme.com.", answer)
        val sent = llm.requests.single()
        assertTrue(sent.system.startsWith(sent.cachePrefix!!), "prefix must still match for caching")
        assertTrue(sent.system.contains("placeholders"), "the model is told about placeholders")
        assertEquals("What did [NAME_1] ask?", sent.messages.single().content)
        assertFalse((sent.system + sent.messages).contains("Priya"))

        // Streaming shows restored text as it arrives.
        val streamed = mutableListOf<String>()
        val streaming = object : com.kaliscriber.core.ai.llm.LlmClient by llm {
            override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String {
                listOf("Ask [NA", "Ask [NAME_1] now").forEach(onText)
                return "Ask [NAME_1] now"
            }
        }
        val out = RedactingLlm(streaming, on).stream(LlmRequest("Priya", emptyList())) { streamed += it }
        assertEquals(listOf("Ask ", "Ask Priya Sharma now"), streamed)
        assertEquals("Ask Priya Sharma now", out)
    }

    @Test
    fun `the AI services wrap chat only when redaction is on`() {
        var settings = AppSettings(chatProvider = ProviderId.OPENAI)
        val ai = DefaultAiServices({ settings }, { "key" })
        assertFalse(ai.chat() is RedactingLlm)
        settings = settings.copy(redaction = on)
        assertTrue(ai.chat() is RedactingLlm)
        settings = settings.copy(redaction = RedactionSettings(enabled = true, emails = false, phones = false, cards = false))
        assertFalse(ai.chat() is RedactingLlm, "nothing to mask")
    }

    @Test
    fun `the notes say what the user was asked, promised and left unanswered`() = runTest {
        val meeting = Meeting("m", "Sync", 0, segments = listOf(TranscriptSegment("s", 0, 1000, "Sunny, can you send the deck?")))
        val llm = FakeLlm {
            """{"title":"Sync","summary":"Deck.","forMe":{"asked":["Priya asked you for the deck [00:01]"],"promised":["Send the deck by Friday [00:02]"],"unanswered":[]}}"""
        }
        val stt = FakeStt(false) { SttResult(emptyList(), false) }
        val ai = MeetingAi(FakeAi(llm, stt), { AppSettings(yourName = "Sunny, Sunil Shah") })
        val insights = ai.insights(meeting)
        assertEquals(ForMe("Sunny", listOf("Priya asked you for the deck [00:01]"), listOf("Send the deck by Friday [00:02]")), insights.forMe)
        val system = llm.requests.single().system
        assertTrue(system.contains("The user who recorded the meeting is Sunny (also called Sunil Shah)"), system)
        val md = TranscriptFormatter.markdown(meeting.copy(insights = insights))
        assertTrue(md.contains("## For Sunny\n- Asked of you: Priya asked you for the deck [00:01]\n- You promised: Send the deck by Friday [00:02]"), md)

        // Without a name nothing is asked for.
        val plain = MeetingAi(FakeAi(llm, stt), { AppSettings() }).insights(meeting)
        assertNull(plain.forMe)
        assertFalse(llm.requests.last().system.contains("forMe"))
    }

    private fun meeting(id: String, title: String, at: Long, project: String? = null, insights: MeetingInsights? = null) =
        Meeting(id, title, at, projectId = project, insights = insights, speakers = listOf(Speaker("a", "Priya", isPlaceholder = false)))

    @Test
    fun `meetings join projects by choice or by title words`() {
        val apollo = Project("p1", "Apollo", titleWords = listOf("apollo", "launch sync"))
        val zeus = Project("p2", "Zeus", titleWords = listOf("zeus"))
        val projects = listOf(apollo, zeus)
        assertSame(apollo, Projects.of(meeting("a", "Apollo weekly", 0), projects))
        assertSame(apollo, Projects.of(meeting("b", "Launch sync #4", 0), projects))
        assertNull(Projects.of(meeting("c", "Apollonia lunch", 0), projects), "whole words only")
        assertSame(zeus, Projects.of(meeting("d", "Apollo weekly", 0, project = "p2"), projects), "the user's choice wins")
        assertNull(Projects.of(meeting("e", "Apollo weekly", 0, project = Projects.NONE), projects))
        assertNull(Projects.of(meeting("f", "Apollo weekly", 0), listOf(apollo.copy(archived = true))))
        assertEquals(listOf("apollo", "Launch sync"), Projects.words("apollo, Launch sync,, APOLLO"))
    }

    @Test
    fun `a project page gathers decisions, open actions and people`() {
        val apollo = Project("p1", "Apollo", titleWords = listOf("apollo"))
        val old = meeting(
            "a", "Apollo kickoff", 1_000,
            insights = MeetingInsights(
                decisions = listOf("Ship in May"), risks = listOf("Old risk"),
                actionItems = listOf(ActionItem("1", "Hire designer"), ActionItem("2", "Book venue", done = true)),
            ),
        )
        val new = meeting(
            "b", "Apollo review", 2_000,
            insights = MeetingInsights(decisions = listOf("Ship in June"), nextSteps = listOf("Tell sales"), actionItems = listOf(ActionItem("3", "Update plan"))),
        )
        val other = meeting("c", "Budget", 3_000, insights = MeetingInsights(decisions = listOf("Unrelated")))
        val o = Projects.overview(apollo, listOf(old, other, new), listOf(apollo))
        assertEquals(listOf("b", "a"), o.meetings.map { it.id })
        assertEquals(listOf("Ship in June", "Ship in May"), o.decisions.map { it.text })
        assertEquals(listOf("Hire designer", "Update plan"), o.openActions.map { it.item.task })
        assertEquals(1, o.doneActions)
        assertEquals(listOf("Tell sales"), o.nextSteps.map { it.text })
        assertEquals(listOf("Priya"), o.people)
        assertEquals(1_000L, o.firstAt)

        val scope = AskScope(project = apollo)
        assertTrue(scope.matches(new, 0, projects = listOf(apollo)))
        assertFalse(scope.matches(other, 0, projects = listOf(apollo)))
        assertEquals("the Apollo project", scope.describe())

        val note = MarkdownSync.note(new, transcript = false, zone = ZoneOffset.UTC, projects = listOf(apollo))
        assertTrue(note.contains("project: Apollo"), note)
    }

    @Test
    fun `the diagnostic log takes turns between two files and keeps secrets out`() {
        val dir = Files.createTempDirectory("diag").toFile()
        var now = 0L
        val log = DiagnosticLog(dir, maxBytes = 200, clock = { now }, zone = ZoneOffset.UTC)
        log.add(DiagnosticLog.Level.INFO, "Rec", "Recording started")
        log.add(DiagnosticLog.Level.WARN, "AI", "Failed with key sk-proj-abcdefghijklmnop1234 for bob@example.com", IllegalStateException("boom"))
        val text = log.text()
        assertTrue(text.startsWith("1970-01-01 00:00:00.000 I Rec: Recording started"), text)
        assertTrue(text.contains("IllegalStateException: boom"), text)
        assertFalse(text.contains("sk-proj") || text.contains("bob@"), text)
        repeat(30) { now += 1000; log.add(DiagnosticLog.Level.INFO, "Tick", "line $it") }
        assertTrue(log.sizeBytes() < 700, "old lines are dropped: ${log.sizeBytes()}")
        assertTrue(log.text().contains("line 29"))
        log.clear()
        assertEquals("", log.text())

        assertEquals("password=[hidden] Bearer [hidden] https://[hidden]@dav.example.org", ProblemReport.scrub("password=hunter2 Bearer abcdefghijklmnopqrstu https://me:pw@dav.example.org"))
        assertEquals("a basic setting, token count 5", ProblemReport.scrub("a basic setting, token count 5"))
        val report = ProblemReport.build("KaliScriber problem report", listOf("App" to "0.15.0"), "")
        assertTrue(report.contains("App: 0.15.0") && report.contains("(nothing logged yet)"), report)
    }
}
