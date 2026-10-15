package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.stt.DiarizedBlock
import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.speakers.BlockMerge
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.speakers.StableSpeakers
import com.kaliscriber.core.speakers.VoiceLibrary
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveSpeakersTest {
    private val root: File = Files.createTempDirectory("live").toFile()
    private val repo = MeetingRepository.inDir(root)
    private val library = VoiceLibrary(JsonValueStore(File(root, "voices.json"), ListSerializer(VoiceProfile.serializer()), emptyList()))
    private val alice = floatArrayOf(1f, 0f, 0f)
    private val bob = floatArrayOf(0f, 1f, 0f)
    private val carol = floatArrayOf(0f, 0f, 1f)

    private fun merged(vararg voices: Pair<String, FloatArray>) = BlockMerge.Merged(
        voices.mapIndexed { i, (id, _) -> SpeakerTurn(i * 1000L, i * 1000L + 1000, id) },
        voices.associate { (id, print) -> id to DiarizedBlock.Voice(print, 10.0) },
    )

    @Test
    fun `speakers keep their keys when merging numbers them differently`() {
        val stable = StableSpeakers()
        val first = stable.stabilize(merged("0" to alice, "1" to bob))
        val second = stable.stabilize(merged("0" to bob, "1" to carol, "2" to alice))
        val aliceKey = first.turns[0].speaker
        val bobKey = first.turns[1].speaker
        assertEquals(listOf(bobKey, aliceKey), listOf(second.turns[0].speaker, second.turns[2].speaker))
        assertTrue(second.turns[1].speaker !in setOf(aliceKey, bobKey), "a new voice gets a new key")
    }

    @Test
    fun `speakers are labelled during the meeting and names given meanwhile stay`() = runTest {
        library.enroll("Priya", alice)
        val chunkMs = 30_000L
        val stt = FakeLocalStt(emptyList()) { file -> SttResult(listOf(RawSegment(0, chunkMs, "Line from ${file.name}.")), false) }
        // Alice speaks in even chunks, Bob in odd ones; the diarizer numbers them differently in each block.
        stt.block = { files, offset ->
            val swap = offset >= 10 * 60_000L
            fun label(isAlice: Boolean) = if (isAlice != swap) "x" else "y"
            DiarizedBlock(
                files.indices.map { i ->
                    val start = offset + i * chunkMs
                    SpeakerTurn(start, start + chunkMs, label((start / chunkMs) % 2 == 0L))
                },
                mapOf(label(true) to DiarizedBlock.Voice(alice, 60.0), label(false) to DiarizedBlock.Voice(bob, 60.0)),
            )
        }
        val fake = FakeLlm { """{"title":"Sync","summary":"Done.","actionItems":[]}""" }
        val settings = AppSettings(liveNotes = false)
        val pipeline = MeetingPipeline(
            repo, FakeAi(fake, stt), MeetingAi(FakeAi(fake, stt), { settings }), { settings },
            random = Random(3), voices = library, liveScope = backgroundScope,
        )
        val meeting = repo.create()
        fun chunk(i: Int) = AudioChunk(i, File(root, "c$i.wav").apply { writeBytes(ByteArray(10)) }.path, i * chunkMs, (i + 1) * chunkMs)

        (0 until 22).forEach { pipeline.transcribeChunk(meeting.id, chunk(it)) }
        suspend fun waitFor(check: (Meeting) -> Boolean) = withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (!check(repo.get(meeting.id)!!)) delay(20) }
        }
        waitFor { m -> m.speakers.any { it.name == "Priya" } }
        var m = repo.get(meeting.id)!!
        val priya = m.speakers.single { it.name == "Priya" }
        assertEquals(setOf(priya.id), m.segments.filter { it.startMs < 10 * 60_000L && (it.startMs / chunkMs) % 2 == 0L }.map { it.speakerId }.toSet())
        assertTrue(m.segments.filter { it.startMs < 10 * 60_000L }.none { m.speaker(it.speakerId)?.label == SpeakerAssigner.UNKNOWN_LABEL })
        assertTrue(m.segments.filter { it.startMs >= 10 * 60_000L }.all { m.speaker(it.speakerId)?.label == SpeakerAssigner.UNKNOWN_LABEL }, "later lines wait for the next block")

        // The user names Bob while still recording.
        val bobSpeaker = m.speakers.single { it.label?.startsWith(SpeakerAssigner.LIVE_PREFIX) == true && it.id != priya.id }
        pipeline.renameSpeaker(meeting.id, bobSpeaker.id, "Sam")
        assertTrue(library.profiles.value.any { it.name == "Sam" }, "the voice found during the meeting is remembered")

        (22 until 41).forEach { pipeline.transcribeChunk(meeting.id, chunk(it)) }
        waitFor { mm -> mm.segments.filter { it.startMs < 20 * 60_000L }.none { mm.speaker(it.speakerId)?.label == SpeakerAssigner.UNKNOWN_LABEL } }
        pipeline.finishRecording(meeting.id, File(root, "recording.m4a").apply { writeBytes(ByteArray(10)) }, 41 * chunkMs)

        m = repo.get(meeting.id)!!
        assertEquals(setOf("Priya", "Sam"), m.speakers.map { it.name }.toSet())
        assertTrue(m.speakersFinal)
        m.segments.forEach { seg ->
            val expected = if ((seg.startMs / chunkMs) % 2 == 0L) "Priya" else "Sam"
            assertEquals(expected, m.speaker(seg.speakerId)?.name, "line at ${seg.startMs}")
        }
        assertEquals(0, stt.diarizeCalls)
    }

    @Test
    fun `speakers merged during the meeting stay merged`() {
        val first = SpeakerAssigner.relabel(
            emptyList(),
            listOf(seg("a", 0), seg("b", 1000)),
            listOf(SpeakerTurn(0, 1000, "s0"), SpeakerTurn(1000, 2000, "s1")),
            SpeakerAssigner.LIVE_PREFIX,
        )
        assertEquals(2, first.speakers.size)
        // The user merges the second speaker into the first...
        val (keep, gone) = first.speakers
        val merged = keep.copy(aliases = listOfNotNull(gone.label))
        // ...and the next update still has two voices.
        val next = SpeakerAssigner.relabel(
            listOf(merged),
            first.segments.map { it.copy(speakerId = keep.id) },
            listOf(SpeakerTurn(0, 1000, "s0"), SpeakerTurn(1000, 2000, "s1")),
            SpeakerAssigner.LIVE_PREFIX,
        )
        assertEquals(listOf(keep.id), next.speakers.map { it.id })
    }

    private fun seg(id: String, start: Long) = com.kaliscriber.core.model.TranscriptSegment(id, start, start + 1000, "text $id")
}
