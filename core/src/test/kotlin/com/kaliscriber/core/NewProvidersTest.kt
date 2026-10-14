package com.kaliscriber.core

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.stt.SttClients
import com.kaliscriber.core.ai.tts.ElevenLabsTts
import com.kaliscriber.core.ai.tts.SpeechText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewProvidersTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun settings(id: ProviderId, path: String, transform: (ProviderSettings) -> ProviderSettings = { it }) =
        AppSettings().withProvider(id) { transform(ProviderSettings(baseUrl = server.url(path).toString().trimEnd('/'))) }

    private val request = LlmRequest("sys", listOf(LlmMessage(LlmRole.USER, "hi")))

    @Test
    fun `command code sends zdr header by default on chat completions`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val config = settings(ProviderId.COMMAND_CODE, "/provider/v1").configFor(ProviderId.COMMAND_CODE, "user_abc")
        assertEquals("ok", LlmClients.create(config, http).complete(request))
        val recorded = server.takeRequest()
        assertEquals("/provider/v1/chat/completions", recorded.path)
        assertEquals("Bearer user_abc", recorded.getHeader("Authorization"))
        assertEquals("1", recorded.getHeader("x-cmd-zdr"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("deepseek/deepseek-v4-flash", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `command code without zdr omits the header`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        val config = settings(ProviderId.COMMAND_CODE, "/provider/v1") { it.copy(zeroDataRetention = false) }
            .configFor(ProviderId.COMMAND_CODE, "user_abc")
        LlmClients.create(config, http).complete(request)
        assertNull(server.takeRequest().getHeader("x-cmd-zdr"))
    }

    @Test
    fun `command code routes claude models to the messages wire`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"hello"}]}"""))
        val config = settings(ProviderId.COMMAND_CODE, "/provider/v1") { it.copy(chatModel = "claude-sonnet-5-5") }
            .configFor(ProviderId.COMMAND_CODE, "user_abc")
        assertEquals("hello", LlmClients.create(config, http).complete(request))
        val recorded = server.takeRequest()
        assertEquals("/provider/v1/messages", recorded.path)
        assertEquals("1", recorded.getHeader("x-cmd-zdr"))
        assertEquals("Bearer user_abc", recorded.getHeader("Authorization"))
    }

    @Test
    fun `command code 422 explains the zdr routing failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"error":{"message":"No ZDR upstream"}}"""))
        val config = settings(ProviderId.COMMAND_CODE, "/provider/v1").configFor(ProviderId.COMMAND_CODE, "k")
        val e = assertFailsWith<AiException> { LlmClients.create(config, http).complete(request) }
        assertTrue(e.message!!.contains("zero-data-retention"), e.message)
    }

    @Test
    fun `elevenlabs scribe groups words into speaker segments`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"language_code":"en","text":"Hi there. Hello.","words":[
                {"text":"Hi","start":0.0,"end":0.3,"type":"word","speaker_id":"speaker_0"},
                {"text":" ","start":0.3,"end":0.35,"type":"spacing","speaker_id":"speaker_0"},
                {"text":"there.","start":0.35,"end":0.8,"type":"word","speaker_id":"speaker_0"},
                {"text":"(laughs)","start":0.8,"end":1.0,"type":"audio_event","speaker_id":"speaker_0"},
                {"text":" ","start":1.0,"end":1.1,"type":"spacing","speaker_id":"speaker_1"},
                {"text":"Hello.","start":1.1,"end":1.6,"type":"word","speaker_id":"speaker_1"}]}""",
            ),
        )
        val config = settings(ProviderId.ELEVENLABS, "").configFor(ProviderId.ELEVENLABS, "xi")
        val result = SttClients.create(config, http).transcribe(tempAudio(), language = "en")
        assertEquals(listOf("Hi there.", "Hello."), result.segments.map { it.text })
        assertEquals(listOf("speaker_0", "speaker_1"), result.segments.map { it.speakerLabel })
        assertEquals(1_100L, result.segments[1].startMs)
        val recorded = server.takeRequest()
        assertEquals("/v1/speech-to-text", recorded.path)
        assertEquals("xi", recorded.getHeader("xi-api-key"))
        val body = recorded.body.readUtf8()
        assertTrue(body.contains("scribe_v2") && body.contains("diarize"))
    }

    @Test
    fun `elevenlabs tts posts to the voice and concatenates parts`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2))))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(3))))
        val config = settings(ProviderId.ELEVENLABS, "") { it.copy(voiceId = "voice123") }.configFor(ProviderId.ELEVENLABS, "xi")
        val long = "First sentence. ".repeat(200) // > 2500 chars, so two requests
        val audio = ElevenLabsTts(config, http).synthesize(long)
        assertEquals(listOf<Byte>(1, 2, 3), audio.toList())
        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/v1/text-to-speech/voice123?output_format=mp3_44100_128"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("eleven_multilingual_v2", body["model_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `markdown becomes speakable text`() {
        val spoken = SpeechText.fromMarkdown("## Decisions\n- **Launch** on 3 Nov [12:34]\n- [ ] Email _press_ list")
        assertEquals("Decisions.\nLaunch on 3 Nov at 12:34.\nEmail press list.", spoken)
    }

    @Test
    fun `speech text splits at sentence boundaries`() {
        val parts = SpeechText.split("One two. Three four five. Six.", 12)
        assertTrue(parts.all { it.length <= 12 }, parts.toString())
        assertEquals("One two. Three four five. Six.", parts.joinToString(" "))
    }

    @Test
    fun `local provider is the default and needs no key`() {
        val s = AppSettings()
        assertEquals(ProviderId.LOCAL, s.sttProvider)
        assertEquals(false, ProviderId.LOCAL.keyRequired)
    }

    private fun tempAudio(): File = File.createTempFile("audio", ".wav").apply {
        writeBytes(ByteArray(64))
        deleteOnExit()
    }
}
