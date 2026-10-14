package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.speakers.VoiceLibrary
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceMemoryTest {
    private val root: File = Files.createTempDirectory("voices").toFile()
    private val repo = MeetingRepository(JsonDocumentStore(File(root, "meetings"), Meeting.serializer()) { it.id }, File(root, "audio"))
    private val library = VoiceLibrary(JsonValueStore(File(root, "voices.json"), ListSerializer(VoiceProfile.serializer()), emptyList()))
    private var settings = AppSettings(autoAnalyze = false, suggestSpeakerNames = false, llmSpeakerSeparation = false)

    // Two clearly different "voices" plus a little noise so prints are never identical.
    private val priyaVoice = floatArrayOf(1f, 0.1f, 0f, 0f)
    private val samVoice = floatArrayOf(0f, 0.1f, 1f, 0.2f)
    private fun voiceFor(text: String) = when {
        text.contains("Priya") || text.contains("ship") -> priyaVoice
        else -> samVoice
    }.map { it + 0.02f }.toFloatArray()

    @Test
    fun `library matches best voice with threshold and uniqueness`() = runTest {
        library.enroll("Priya", priyaVoice)
        library.enroll("Sam", samVoice)
        val matches = library.assign(mapOf("a" to priyaVoice, "b" to samVoice, "c" to floatArrayOf(0f, 0f, 0f, 1f)))
        assertEquals("Priya", matches["a"]!!.profile.name)
        assertEquals("Sam", matches["b"]!!.profile.name)
        assertNull(matches["c"], "an unknown voice stays unmatched")
        // Same name enrolls into the same profile.
        library.enroll("priya", priyaVoice)
        assertEquals(2, library.profiles.value.size)
        assertEquals(2, library.profiles.value.first { it.name.equals("priya", true) }.samples)
    }

    @Test
    fun `renaming enrolls the voice and the next meeting recognises it`() = runTest {
        val stt = FakeLocalStt(listOf(SpeakerTurn(0, 3_000, "0"), SpeakerTurn(3_000, 6_000, "1"))) {
            SttResult(listOf(RawSegment(0, 2_900, "Hi, I'm Priya."), RawSegment(3_100, 6_000, "Morning everyone.")), false)
        }
        val embedder = FakeEmbedder(::voiceFor)
        val p = MeetingPipeline(repo, FakeAi(FakeLlm { "{}" }, stt), MeetingAi(FakeAi(FakeLlm { "{}" }, stt), { settings }), { settings },
            random = Random(3), voices = library, embedder = { embedder })

        suspend fun recordMeeting(): Meeting {
            val meeting = repo.create()
            embedder.segmentsText = { id -> repo.get(meeting.id)!!.segments.filter { it.speakerId == id }.joinToString(" ") { it.text } }
            p.transcribeChunk(meeting.id, AudioChunk(0, File(root, "c${meeting.id}.wav").apply { writeBytes(ByteArray(8)) }.path, 0, 6_000))
            p.finishRecording(meeting.id, File(root, "r${meeting.id}.m4a").apply { writeBytes(ByteArray(8)) }, 6_000)
            return repo.get(meeting.id)!!
        }

        val first = recordMeeting()
        assertTrue(first.speakers.all { it.isPlaceholder }, "nobody is known yet")
        assertTrue(first.speakers.all { it.embedding != null }, "voiceprints are computed")
        val priya = first.speakers.first { sp -> first.segments.any { it.speakerId == sp.id && it.text.contains("Priya") } }

        p.renameSpeaker(first.id, priya.id, "Priya")
        assertEquals(listOf("Priya"), library.profiles.value.map { it.name })
        assertTrue(repo.get(first.id)!!.speaker(priya.id)!!.voiceProfileId != null)

        val second = recordMeeting()
        val named = second.speakers.filter { !it.isPlaceholder }
        assertEquals(listOf("Priya"), named.map { it.name }, "Priya is recognised; the other voice stays a placeholder")
        assertEquals(1, second.speakers.count { it.isPlaceholder })
    }

    @Test
    fun `expected speaker count reaches the diarizer and prompts`() = runTest {
        val stt = FakeLocalStt(listOf(SpeakerTurn(0, 6_000, "0"))) { SttResult(listOf(RawSegment(0, 6_000, "Hello.")), false) }
        val llm = FakeLlm { "{}" }
        val p = MeetingPipeline(repo, FakeAi(llm, stt), MeetingAi(FakeAi(llm, stt), { settings }), { settings }, random = Random(1))
        val meeting = repo.create()
        p.setExpectedSpeakers(meeting.id, 3)
        p.transcribeChunk(meeting.id, AudioChunk(0, File(root, "x.wav").apply { writeBytes(ByteArray(8)) }.path, 0, 6_000))
        p.finishRecording(meeting.id, File(root, "x.m4a").apply { writeBytes(ByteArray(8)) }, 6_000)
        assertEquals(3, stt.lastSpeakerCount)
        p.ask(meeting.id, "Who spoke?")
        assertTrue(llm.requests.last().system.contains("Number of participants: 3"))

        p.setExpectedSpeakers(meeting.id, null)
        p.redetectSpeakers(meeting.id)
        assertNull(stt.lastSpeakerCount)
        assertEquals(2, stt.diarizeCalls)
    }

    @Test
    fun `live meetings tell the model the meeting is in progress`() = runTest {
        val stt = FakeStt(false) { SttResult(listOf(RawSegment(0, 65_000, "Budget talk.")), false) }
        val llm = FakeLlm { "Answer" }
        val p = MeetingPipeline(repo, FakeAi(llm, stt), MeetingAi(FakeAi(llm, stt), { settings }), { settings })
        val meeting = repo.create()
        p.transcribeChunk(meeting.id, AudioChunk(0, File(root, "l.wav").apply { writeBytes(ByteArray(8)) }.path, 0, 65_000))
        p.ask(meeting.id, "What did I miss?")
        assertTrue(llm.requests.last().system.contains("still in progress"))
        assertFalse(llm.requests.last().system.contains("Duration:"))
    }

    @Test
    fun `voices saved with an earlier speaker model are learned again`() = runTest {
        // Saved with the old model (no tag), in a meeting whose recording is still on the phone.
        library.enroll("Priya", floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f, 0.3f))
        val old = library.profiles.value.single()
        val meeting = repo.create()
        val audio = File(root, "old.m4a").apply { writeBytes(ByteArray(8)) }
        repo.update(meeting.id) {
            it.copy(
                audioPath = audio.path,
                speakers = listOf(com.kaliscriber.core.model.Speaker("p", "Priya", isPlaceholder = false, voiceProfileId = old.id)),
                segments = listOf(com.kaliscriber.core.model.TranscriptSegment("s", 0, 5_000, "Priya here", speakerId = "p")),
            )
        }
        val newLibrary = VoiceLibrary(JsonValueStore(File(root, "voices.json"), ListSerializer(VoiceProfile.serializer()), emptyList()), model = "titanet")
        assertEquals(listOf("Priya"), newLibrary.outdated.map { it.name })
        assertTrue(newLibrary.assign(mapOf("x" to priyaVoice)).isEmpty(), "an outdated voice is not compared with new voiceprints")

        val embedder = FakeEmbedder(::voiceFor).apply { segmentsText = { "Priya" } }
        val p = MeetingPipeline(repo, FakeAi(FakeLlm { "{}" }, FakeStt(false) { SttResult(emptyList(), false) }),
            MeetingAi(FakeAi(FakeLlm { "{}" }, FakeStt(false) { SttResult(emptyList(), false) }), { settings }), { settings },
            voices = newLibrary, embedder = { embedder })
        assertEquals(1, p.relearnVoices())

        val profile = newLibrary.profiles.value.single()
        assertEquals(old.id, profile.id, "the saved voice keeps its identity")
        assertEquals("titanet", profile.model)
        assertTrue(newLibrary.outdated.isEmpty())
        assertEquals("Priya", newLibrary.assign(mapOf("x" to priyaVoice))["x"]?.profile?.name)
        assertEquals("titanet", repo.get(meeting.id)!!.speaker("p")!!.voiceModel)
    }
}
