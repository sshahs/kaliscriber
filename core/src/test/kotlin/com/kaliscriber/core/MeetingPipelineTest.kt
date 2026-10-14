package com.kaliscriber.core

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MeetingPipelineTest {
    private val root: File = Files.createTempDirectory("kali").toFile()
    private val repo = MeetingRepository(JsonDocumentStore(File(root, "meetings"), Meeting.serializer()) { it.id }, File(root, "audio"))
    private var settings = AppSettings()

    private val insightsJson = """```json
        {"title":"Q3 launch sync","summary":"The team agreed the launch date.","keyPoints":["Launch on 3 Nov"],
         "highlights":[{"text":"We ship on the third","speaker":"Priya","timestamp":"00:05","why":"Sets the date"}],
         "actionItems":[{"task":"Email the press list","owner":"Sam","dueDate":"Friday","priority":"High"}, "Book the venue"],
         "decisions":["Launch on 3 Nov"],"topics":[{"name":"Launch","summary":"Date agreed"}],
         "risks":[],"nextSteps":["Draft announcement"],"sentiment":"Positive"}
        ```"""
    private val gapsJson = """{"gaps":[{"title":"Budget","detail":"No budget was agreed","suggestion":"Confirm with finance"}],
        "questions":[{"question":"What is the launch budget?","context":"[00:10] budget mentioned"}]}"""

    private fun llm() = FakeLlm { req: LlmRequest ->
        when {
            req.system.startsWith("You are KaliScriber, an expert meeting analyst") -> insightsJson
            req.system.startsWith("You review meeting transcripts") -> gapsJson
            req.system == Prompts.SPEAKER_NAMES_SYSTEM -> {
                val placeholder = Regex("Placeholder speakers to identify: ([^,\\n]+)").find(req.messages.last().content)!!.groupValues[1]
                """{"speakers":[{"placeholder":"$placeholder","name":"Priya","confidence":0.9,"reason":"Introduced herself at 00:00"}]}"""
            }
            req.system == Prompts.SEPARATE_SPEAKERS_SYSTEM ->
                """{"turns":[{"i":0,"s":"S1","text":"Hi, I'm Priya."},{"i":0,"s":"S2","text":"Hello Priya!"},{"i":1,"s":"S1","text":"We ship on the third."}]}"""
            else -> "The launch is on 3 Nov [00:05]."
        }
    }

    private fun pipeline(ai: FakeAi, fetchModels: suspend (suspend (Float) -> Unit) -> Unit = {}) = MeetingPipeline(
        repo, ai, MeetingAi(ai, { settings }), { settings }, random = Random(1), fetchDiarizationModels = fetchModels,
    )

    private fun chunkFile(name: String) = File(root, name).apply { writeBytes(ByteArray(10)) }

    @Test
    fun `live chunks are offset and unknown speakers get a random placeholder name`() = runTest {
        val stt = FakeStt(supportsDiarization = false) {
            SttResult(listOf(RawSegment(1_000, 4_000, "Hi, I'm Priya. Hello Priya!")), diarized = false)
        }
        val p = pipeline(FakeAi(llm(), stt))
        val meeting = repo.create()

        assertTrue(p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 30_000)))
        assertTrue(p.transcribeChunk(meeting.id, AudioChunk(1, chunkFile("c1.wav").path, 30_000, 60_000)))

        val m = repo.get(meeting.id)!!
        assertEquals(listOf(1_000L, 31_000L), m.segments.map { it.startMs })
        assertEquals(1, m.speakers.size)
        val speaker = m.speakers.single()
        assertTrue(speaker.isPlaceholder)
        assertTrue(speaker.name.matches(Regex("[A-Z][a-z]+ [A-Z][a-z]+")), speaker.name)
        assertFalse(File(root, "c0.wav").exists(), "transcribed chunks are deleted")
    }

    @Test
    fun `failed chunks are queued and retried when recording finishes`() = runTest {
        var fail = true
        val stt = FakeStt(supportsDiarization = false) {
            if (fail) error("offline") else SttResult(listOf(RawSegment(0, 2_000, "Back online")), diarized = false)
        }
        settings = settings.copy(autoAnalyze = false, llmSpeakerSeparation = false, suggestSpeakerNames = false)
        val p = pipeline(FakeAi(llm(), stt))
        val meeting = repo.create()
        val chunk = AudioChunk(0, chunkFile("c0.wav").path, 0, 30_000)

        assertFalse(p.transcribeChunk(meeting.id, chunk))
        assertEquals(listOf(chunk), repo.get(meeting.id)!!.pendingChunks)
        assertEquals("offline", repo.get(meeting.id)!!.lastError)

        fail = false
        p.finishRecording(meeting.id, audioFile = null, durationMs = 30_000)
        val m = repo.get(meeting.id)!!
        assertEquals(MeetingStatus.READY, m.status)
        assertTrue(m.pendingChunks.isEmpty())
        assertEquals("Back online", m.segments.single().text)
        assertNull(m.lastError)
    }

    @Test
    fun `final diarization pass replaces live transcript and analysis runs`() = runTest {
        val stt = FakeStt(supportsDiarization = true) { file ->
            if (file.name == "full.m4a") {
                SttResult(
                    listOf(
                        RawSegment(0, 2_000, "Hi, I'm Priya.", "A"),
                        RawSegment(2_000, 4_000, "Hello Priya!", "B"),
                        RawSegment(5_000, 8_000, "We ship on the third.", "A"),
                    ),
                    diarized = true,
                )
            } else {
                SttResult(listOf(RawSegment(0, 8_000, "live text", "A")), diarized = true)
            }
        }
        val ai = FakeAi(llm(), stt)
        val p = pipeline(ai)
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 8_000))

        p.finishRecording(meeting.id, chunkFile("full.m4a"), 8_000)

        val m = repo.get(meeting.id)!!
        assertEquals(MeetingStatus.READY, m.status, m.lastError)
        assertTrue(m.speakersFinal)
        assertEquals(3, m.segments.size)
        assertEquals(2, m.speakers.size)
        assertEquals(m.segments[0].speakerId, m.segments[2].speakerId)
        // No LLM speaker separation needed because the provider diarized.
        assertTrue((ai.llm as FakeLlm).requests.none { it.system == Prompts.SEPARATE_SPEAKERS_SYSTEM })
        // Names are suggested, not applied.
        val suggested = m.speakers.single { it.suggestedName != null }
        assertEquals("Priya", suggested.suggestedName)
        assertTrue(suggested.isPlaceholder)

        val insights = assertNotNull(m.insights)
        assertEquals("Q3 launch sync", m.title, "auto title is replaced by the AI title")
        assertEquals(2, insights.actionItems.size)
        assertEquals("Sam", insights.actionItems[0].owner)
        assertEquals("high", insights.actionItems[0].priority)
        assertEquals("Book the venue", insights.actionItems[1].task)
        assertEquals("What is the launch budget?", m.gaps!!.questions.single().question)
    }

    @Test
    fun `llm separates speakers when the provider cannot`() = runTest {
        val stt = FakeStt(supportsDiarization = false) {
            SttResult(
                listOf(RawSegment(0, 4_000, "Hi, I'm Priya. Hello Priya!"), RawSegment(4_000, 6_000, "We ship on the third.")),
                diarized = false,
            )
        }
        settings = settings.copy(autoAnalyze = false, suggestSpeakerNames = false)
        val p = pipeline(FakeAi(llm(), stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 6_000))
        p.finishRecording(meeting.id, null, 6_000)

        val m = repo.get(meeting.id)!!
        assertEquals(listOf("Hi, I'm Priya.", "Hello Priya!", "We ship on the third."), m.segments.map { it.text })
        assertEquals(2, m.speakers.size)
        assertEquals(m.segments[0].speakerId, m.segments[2].speakerId)
        assertTrue(m.segments[0].speakerId != m.segments[1].speakerId)
        assertTrue(m.segments[1].startMs in 1_500L..2_500L, "split times are interpolated")
        assertTrue(m.speakers.all { it.isPlaceholder })
    }

    @Test
    fun `compact speaker separation splits segments at the given words`() = runTest {
        val stt = FakeStt(supportsDiarization = false) {
            SttResult(
                listOf(RawSegment(0, 6_000, "Can you send the deck? Sure, I'll send it today."), RawSegment(6_000, 8_000, "Thanks.")),
                diarized = false,
            )
        }
        val compact = FakeLlm { req ->
            if (req.system == Prompts.SEPARATE_SPEAKERS_SYSTEM) {
                // "from" words differ in case and punctuation from the transcript.
                """{"turns":[{"i":0,"s":"S1"},{"i":0,"s":"S2","from":"sure i'll send"},{"i":1,"s":"S1"}]}"""
            } else {
                "{}"
            }
        }
        settings = settings.copy(autoAnalyze = false, suggestSpeakerNames = false)
        val p = pipeline(FakeAi(compact, stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 8_000))
        p.finishRecording(meeting.id, null, 8_000)

        val m = repo.get(meeting.id)!!
        assertEquals(listOf("Can you send the deck?", "Sure, I'll send it today.", "Thanks."), m.segments.map { it.text })
        assertEquals(m.segments[0].speakerId, m.segments[2].speakerId)
        assertTrue(m.segments[0].speakerId != m.segments[1].speakerId)
        assertNull(m.processingStep, "progress is cleared when processing ends")
    }

    @Test
    fun `asking about an excerpt records the conversation`() = runTest {
        val stt = FakeStt(false) { SttResult(listOf(RawSegment(0, 5_000, "We ship on the third.")), false) }
        val fakeLlm = llm()
        val p = pipeline(FakeAi(fakeLlm, stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 5_000))
        val seg = repo.get(meeting.id)!!.segments.single()

        val answer = p.ask(meeting.id, "When do we ship?", ExcerptRef(seg.startMs, seg.endMs, listOf(seg.id)))

        assertEquals("The launch is on 3 Nov [00:05].", answer.getOrThrow())
        val chat = repo.get(meeting.id)!!.chat
        assertEquals(listOf(ChatRole.USER, ChatRole.ASSISTANT), chat.map { it.role })
        val sent = fakeLlm.requests.last()
        assertTrue(sent.system.contains("<transcript>"))
        assertTrue(sent.messages.last().content.contains("<excerpt>"))
        assertTrue(sent.messages.last().content.contains("We ship on the third."))
    }

    @Test
    fun `answers to follow-up questions survive regeneration and feed the prompt`() = runTest {
        val stt = FakeStt(false) { SttResult(listOf(RawSegment(0, 5_000, "Budget TBD.")), false) }
        val fakeLlm = llm()
        val p = pipeline(FakeAi(fakeLlm, stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 5_000))
        p.generateGaps(meeting.id)
        val q = repo.get(meeting.id)!!.gaps!!.questions.single()
        repo.answerQuestion(meeting.id, q.id, "50k")

        p.refreshWithAnswers(meeting.id)

        val questions = repo.get(meeting.id)!!.gaps!!.questions
        assertEquals(1, questions.size, "the same question is not asked again")
        assertEquals("50k", questions.single().answer)
        assertTrue(fakeLlm.requests.any { it.messages.last().content.contains("A: 50k") })
    }

    @Test
    fun `renaming speakers merges duplicates and placeholders can be split out`() = runTest {
        val stt = FakeStt(true) {
            SttResult(listOf(RawSegment(0, 1_000, "one", "A"), RawSegment(1_000, 2_000, "two", "B")), true)
        }
        val p = pipeline(FakeAi(llm(), stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 2_000))
        val (a, b) = repo.get(meeting.id)!!.speakers

        repo.renameSpeaker(meeting.id, a.id, "Priya")
        assertFalse(repo.get(meeting.id)!!.speaker(a.id)!!.isPlaceholder)
        repo.renameSpeaker(meeting.id, b.id, "priya")
        val merged = repo.get(meeting.id)!!
        assertEquals(1, merged.speakers.size)
        assertTrue(merged.segments.all { it.speakerId == a.id })

        val created = repo.assignToNewSpeaker(meeting.id, setOf(merged.segments[1].id), name = null)!!
        assertTrue(created.isPlaceholder)
        assertEquals(2, repo.get(meeting.id)!!.speakers.size)
    }

    @Test
    fun `on-device diarization labels the live transcript without re-transcribing`() = runTest {
        val turns = listOf(SpeakerTurn(0, 2_100, "0"), SpeakerTurn(2_100, 4_000, "1"), SpeakerTurn(4_000, 6_000, "0"))
        val stt = FakeLocalStt(turns) {
            SttResult(
                listOf(RawSegment(0, 2_000, "Hi, I'm Priya."), RawSegment(2_200, 3_900, "Hello Priya!"), RawSegment(4_100, 6_000, "We ship.")),
                diarized = false,
            )
        }
        settings = settings.copy(autoAnalyze = false, suggestSpeakerNames = false)
        val ai = FakeAi(llm(), stt)
        val p = pipeline(ai)
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 6_000))

        p.finishRecording(meeting.id, chunkFile("recording.m4a"), 6_000)

        val m = repo.get(meeting.id)!!
        assertEquals(1, stt.diarizeCalls)
        assertEquals(1, stt.files.size, "the full recording is not transcribed again")
        assertTrue(m.speakersFinal)
        assertEquals(2, m.speakers.size)
        assertEquals(m.segments[0].speakerId, m.segments[2].speakerId)
        assertTrue(m.segments[0].speakerId != m.segments[1].speakerId)
        assertTrue((ai.llm as FakeLlm).requests.none { it.system == Prompts.SEPARATE_SPEAKERS_SYSTEM })
    }

    @Test
    fun `missing speaker models are fetched before on-device detection`() = runTest {
        val turns = listOf(SpeakerTurn(0, 2_100, "0"), SpeakerTurn(2_100, 4_000, "1"))
        val stt = FakeLocalStt(turns) {
            SttResult(listOf(RawSegment(0, 2_000, "Hi, I'm Priya."), RawSegment(2_200, 3_900, "Hello Priya!")), diarized = false)
        }
        stt.diarizationAvailable = false
        settings = settings.copy(autoAnalyze = false, suggestSpeakerNames = false)
        var fetched = 0
        val p = pipeline(FakeAi(llm(), stt)) { onProgress ->
            onProgress(0.5f)
            fetched++
            stt.diarizationAvailable = true
        }
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 4_000))
        p.finishRecording(meeting.id, chunkFile("recording.m4a"), 4_000)

        val m = repo.get(meeting.id)!!
        assertEquals(1, fetched)
        assertEquals(1, stt.diarizeCalls)
        assertEquals(2, m.speakers.size)
    }

    @Test
    fun `failed on-device detection is reported instead of falling back to the ai`() = runTest {
        val stt = FakeLocalStt(emptyList()) { SttResult(listOf(RawSegment(0, 2_000, "Hi, I'm Priya. Hello Priya!")), diarized = false) }
        stt.diarizeError = IllegalStateException("native failure")
        settings = settings.copy(autoAnalyze = false, suggestSpeakerNames = false)
        val ai = FakeAi(llm(), stt)
        val p = pipeline(ai)
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 2_000))
        p.finishRecording(meeting.id, chunkFile("recording.m4a"), 2_000)

        val m = repo.get(meeting.id)!!
        assertTrue((ai.llm as FakeLlm).requests.none { it.system == Prompts.SEPARATE_SPEAKERS_SYSTEM })
        assertEquals("Speaker detection on this phone failed: native failure", m.lastError)
        assertEquals(MeetingStatus.READY, m.status)

        val e = kotlin.runCatching { p.redetectSpeakers(meeting.id) }.exceptionOrNull()
        assertTrue(e is AiException && e.message!!.contains("native failure"), e.toString())
        assertTrue((ai.llm as FakeLlm).requests.none { it.system == Prompts.SEPARATE_SPEAKERS_SYSTEM })
    }

    @Test
    fun `a slow ai part is skipped after the time limit`() = runTest {
        val long = "word ".repeat(700)
        val slow = object : com.kaliscriber.core.ai.llm.LlmClient {
            override val config = AppSettings().configFor(com.kaliscriber.core.ai.ProviderId.OPENAI, "k")
            override suspend fun complete(request: LlmRequest): String {
                if (request.messages.last().content.contains("| TWO")) kotlinx.coroutines.delay(60_000)
                return """{"turns":[{"i":0,"s":"S1"}]}"""
            }
            override suspend fun listModels() = emptyList<String>()
        }
        val meeting = Meeting(
            id = "m", title = "t", createdAt = 0,
            segments = listOf("ONE", "TWO", "THREE").mapIndexed { i, w ->
                com.kaliscriber.core.model.TranscriptSegment("s$i", i * 60_000L, (i + 1) * 60_000L, "$w $long")
            },
        )
        val parts = mutableListOf<Int>()
        val ai = MeetingAi(FakeAi(slow, FakeStt(false) { SttResult(emptyList(), false) }), { settings })
        val result = ai.separateSpeakers(meeting, partTimeoutMs = 1_000) { part, _ -> parts += part }
        assertEquals(1, result.failedParts)
        assertTrue(result.error!!.contains("longer than"), result.error)
        assertEquals(listOf(0, 1, 2, 3), parts)
        assertEquals(3, result.result.segments.size)
    }

    @Test
    fun `a part the ai cannot separate keeps the rest of the meeting`() = runTest {
        val long = "word ".repeat(700)
        val stt = FakeStt(supportsDiarization = false) {
            SttResult(
                listOf(RawSegment(0, 60_000, "ONE $long"), RawSegment(60_000, 120_000, "TWO $long"), RawSegment(120_000, 180_000, "THREE $long")),
                diarized = false,
            )
        }
        val flaky = FakeLlm { req ->
            if (req.system != Prompts.SEPARATE_SPEAKERS_SYSTEM) "{}"
            else if (req.messages.last().content.contains("| TWO")) throw AiException("timeout")
            else """{"turns":[{"i":0,"s":"S1"}]}"""
        }
        settings = settings.copy(autoAnalyze = false, suggestSpeakerNames = false)
        val p = pipeline(FakeAi(flaky, stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 180_000))
        p.finishRecording(meeting.id, null, 180_000)

        val m = repo.get(meeting.id)!!
        assertEquals(3, m.segments.size)
        assertTrue(m.speakersFinal)
        assertEquals(m.segments[0].speakerId, m.segments[2].speakerId)
        assertTrue(m.lastError!!.contains("1 of 3 parts"), m.lastError)
        assertEquals(MeetingStatus.READY, m.status)
    }

    @Test
    fun `briefing script is stored and spoken answers ask for speech style`() = runTest {
        val stt = FakeStt(false) { SttResult(listOf(RawSegment(0, 5_000, "We ship on the third.")), false) }
        val fakeLlm = llm()
        val p = pipeline(FakeAi(fakeLlm, stt))
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, chunkFile("c0.wav").path, 0, 5_000))

        p.generateBriefingScript(meeting.id)
        assertEquals("The launch is on 3 Nov [00:05].", repo.get(meeting.id)!!.briefing!!.script)
        assertTrue(fakeLlm.requests.last().system.contains("audio briefings"))

        p.ask(meeting.id, "When?", spoken = true)
        assertTrue(fakeLlm.requests.last().system.contains(Prompts.SPOKEN_ANSWER_RULE))
    }

    @Test
    fun `meetings persist across store instances`() = runTest {
        val meeting = repo.create("Standup")
        repo.update(meeting.id) { it.copy(durationMs = 42) }
        val reopened = MeetingRepository(JsonDocumentStore(File(root, "meetings"), Meeting.serializer()) { it.id }, File(root, "audio"))
        reopened.load()
        assertEquals(42, reopened.get(meeting.id)!!.durationMs)
        assertEquals("Standup", reopened.get(meeting.id)!!.title)
        assertFalse(reopened.get(meeting.id)!!.autoTitle)
    }
}
