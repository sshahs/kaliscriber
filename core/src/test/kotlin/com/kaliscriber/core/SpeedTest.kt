package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.AnthropicLlm
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.stt.DiarizedBlock
import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.model.WordTiming
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.speakers.BlockMerge
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.StoreJson
import com.kaliscriber.core.transcript.Glossary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeedTest {
    private val root: File = Files.createTempDirectory("speed").toFile()
    private val repo = MeetingRepository.inDir(root)
    private var settings = AppSettings()

    private val insightsJson = """{"title":"Sync","summary":"Done.","actionItems":[]}"""

    private fun llm() = FakeLlm { req ->
        when {
            req.system.contains(Prompts.TASK_INSIGHTS) -> insightsJson
            req.system.contains(Prompts.TASK_GAPS) -> """{"gaps":[],"questions":[]}"""
            req.system.contains(Prompts.TASK_GLOSSARY) -> """{"fixes":[{"from":"pre a","to":"Priya"}]}"""
            req.system.startsWith("You take running notes") -> "- Pricing: up 10% in November [05:00]"
            else -> "ok"
        }
    }

    @Test
    fun `summary and gaps share the same cacheable meeting prefix`() = runTest {
        val fake = llm()
        val ai = MeetingAi(FakeAi(fake, FakeStt(false) { SttResult(emptyList(), false) }), { settings.copy(glossary = "Priya Sharma") })
        val meeting = Meeting("m", "Sync", 0, segments = listOf(TranscriptSegment("s", 0, 1000, "Hello")))
        ai.insights(meeting)
        ai.gaps(meeting)
        val (a, b) = fake.requests
        assertTrue(a.cachePrefix != null && a.cachePrefix == b.cachePrefix)
        assertTrue(a.system.startsWith(a.cachePrefix!!) && b.system.startsWith(b.cachePrefix!!))
        assertTrue(a.cachePrefix!!.contains("<glossary>") && a.cachePrefix!!.contains("Priya Sharma"))
        assertFalse(a.cachePrefix!!.contains(Prompts.TASK_INSIGHTS), "the task comes after the shared part")
    }

    @Test
    fun `anthropic marks the shared prefix as cacheable`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
            val config = AppSettings().withProvider(ProviderId.ANTHROPIC) { ProviderSettings(baseUrl = server.url("").toString().trimEnd('/')) }
                .configFor(ProviderId.ANTHROPIC, "k")
            val prefix = "Meeting transcript. ".repeat(400)
            AnthropicLlm(config, OkHttpClient()).complete(
                LlmRequest(system = prefix + "Task", messages = listOf(LlmMessage(LlmRole.USER, "go")), cachePrefix = prefix),
            )
            val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            val system = body["system"] as JsonArray
            assertEquals(2, system.size)
            assertTrue(system[0].jsonObject.containsKey("cache_control"))
            assertFalse(system[1].jsonObject.containsKey("cache_control"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `blocks diarized separately are joined by voice`() {
        val alice = floatArrayOf(1f, 0f)
        val bob = floatArrayOf(0f, 1f)
        val first = DiarizedBlock(
            listOf(SpeakerTurn(0, 1000, "0"), SpeakerTurn(1000, 2000, "1")),
            mapOf("0" to DiarizedBlock.Voice(alice, 1.0), "1" to DiarizedBlock.Voice(bob, 1.0)),
        )
        // In the second block the diarizer happens to number them the other way round.
        val second = DiarizedBlock(
            listOf(SpeakerTurn(5000, 6000, "0"), SpeakerTurn(6000, 7000, "1")),
            mapOf("0" to DiarizedBlock.Voice(bob, 1.0), "1" to DiarizedBlock.Voice(alice, 1.0)),
        )
        val turns = BlockMerge.merge(listOf(first, second), speakers = null)
        assertEquals(listOf("0", "1", "1", "0"), turns.map { it.speaker })
    }

    @Test
    fun `speakers are detected and notes written while recording`() = runTest {
        val fake = llm()
        val chunkMs = 30_000L
        val stt = FakeLocalStt(emptyList()) { file -> SttResult(listOf(RawSegment(0, chunkMs, "Line from ${file.name}.")), false) }
        // Speakers A and B take turns, one chunk each.
        stt.block = { files, offset ->
            DiarizedBlock(
                files.indices.map { i ->
                    val start = offset + i * chunkMs
                    SpeakerTurn(start, start + chunkMs, if ((start / chunkMs) % 2 == 0L) "a" else "b")
                },
                mapOf("a" to DiarizedBlock.Voice(floatArrayOf(1f, 0f), 60.0), "b" to DiarizedBlock.Voice(floatArrayOf(0f, 1f), 30.0)),
            )
        }
        val pipeline = MeetingPipeline(
            repo, FakeAi(fake, stt), MeetingAi(FakeAi(fake, stt), { settings }), { settings },
            random = Random(1), liveScope = backgroundScope,
        )
        val meeting = repo.create()
        val chunks = (0 until 21).map { i ->
            AudioChunk(i, File(root, "c$i.wav").apply { writeBytes(ByteArray(10)) }.path, i * chunkMs, (i + 1) * chunkMs)
        }
        chunks.forEach { pipeline.transcribeChunk(meeting.id, it) }
        assertTrue(chunks.all { File(it.path).exists() }, "live detection keeps the audio until the meeting ends")
        // Running notes are written in the background once ten minutes are transcribed.
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (repo.get(meeting.id)!!.sections.isEmpty()) delay(20) }
        }

        pipeline.finishRecording(meeting.id, File(root, "recording.m4a").apply { writeBytes(ByteArray(10)) }, 21 * chunkMs)

        val m = repo.get(meeting.id)!!
        assertEquals(0, stt.diarizeCalls, "the full recording is not diarized again")
        assertEquals(2, stt.blockCalls, "one ten-minute block during the meeting, one short block at the end")
        assertEquals(2, m.speakers.size)
        assertTrue(m.speakersFinal)
        assertTrue(chunks.none { File(it.path).exists() }, "chunk audio is removed afterwards")
        assertEquals(0L, m.sections.single().startMs)
        val summaryRequest = fake.requests.last { it.system.contains(Prompts.TASK_INSIGHTS) }
        assertTrue(summaryRequest.system.contains("Notes written while the meeting was recorded"))
        assertEquals("Sync", m.title)

        // Each step is on the meeting's timeline.
        val labels = m.timeline.map { it.label }
        assertTrue(labels.any { it.startsWith("Speakers in 00:00") && it.endsWith("(while recording)") }, labels.toString())
        assertTrue(labels.any { it.startsWith("Notes for 00:00") }, labels.toString())
        assertTrue(labels.any { it.startsWith("Transcribed 21 parts while recording") }, labels.toString())
        assertTrue("Summary" in labels && "Gaps & questions" in labels, labels.toString())
        assertTrue(labels.indexOf("Recording stopped") in 0 until labels.indexOfFirst { it.startsWith("Ready") }, labels.toString())
    }

    @Test
    fun `glossary fixes misheard words as whole words only`() {
        val fixes = listOf("pre a" to "Priya", "cali scribe" to "KaliScriber")
        assertEquals("Thanks Priya, KaliScriber works. Prepare it.", Glossary.apply("Thanks pre a, Cali Scribe works. Prepare it.", fixes))
    }

    @Test
    fun `glossary fixes are applied to the transcript`() = runTest {
        settings = settings.copy(glossary = "Priya")
        val stt = FakeStt(false) { SttResult(listOf(RawSegment(0, 2_000, "Thanks pre a.")), false) }
        val fake = llm()
        val pipeline = MeetingPipeline(repo, FakeAi(fake, stt), MeetingAi(FakeAi(fake, stt), { settings }), { settings })
        val meeting = repo.create()
        pipeline.transcribeChunk(meeting.id, AudioChunk(0, File(root, "g.wav").apply { writeBytes(ByteArray(10)) }.path, 0, 2_000))
        assertEquals(1, pipeline.applyGlossary(meeting.id))
        assertEquals("Thanks Priya.", repo.get(meeting.id)!!.segments.single().text)
    }

    @Test
    fun `progress updates stay in memory and files are compact`() = runTest {
        val meeting = repo.create()
        repo.update(meeting.id) {
            it.copy(segments = listOf(TranscriptSegment("s", 0, 1000, "Hi there", words = listOf(WordTiming(0, "Hi"), WordTiming(400, " there")))))
        }
        val file = File(root, "meetings/${meeting.id}.json")
        val saved = file.readText()
        repo.update(meeting.id) { it.copy(processingStep = "Working", processingProgress = 0.5f) }
        assertEquals(saved, file.readText(), "a progress change does not rewrite the file")
        assertEquals("Working", repo.get(meeting.id)!!.processingStep)
        val transcript = File(root, "transcripts/${meeting.id}.json").readText()
        assertTrue(transcript.contains("\"s\":400") && !transcript.contains("startMs\":400"), transcript)
        assertFalse(saved.contains("Hi there\"") && saved.contains("segments"), "the transcript is kept out of the meeting file")
        // Files written by older versions, with long keys, still load.
        val old = StoreJson.decodeFromString(WordTiming.serializer(), """{"startMs":5,"text":"x"}""")
        assertEquals(WordTiming(5, "x"), old)
    }
}
