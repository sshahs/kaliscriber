package com.kaliscriber.core

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.stt.AssemblyAiStt
import com.kaliscriber.core.ai.stt.SttClients
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderClientsTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun config(id: ProviderId, path: String = "") = AppSettings()
        .withProvider(id) { ProviderSettings(baseUrl = server.url(path).toString().trimEnd('/')) }
        .configFor(id, "secret-key")

    private val request = LlmRequest(
        system = "sys",
        messages = listOf(LlmMessage(LlmRole.USER, "hi"), LlmMessage(LlmRole.ASSISTANT, "hello"), LlmMessage(LlmRole.USER, "again")),
        jsonMode = true,
    )

    @Test
    fun `openai compatible chat sends bearer key and json mode`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"answer"}}]}"""))
        val text = LlmClients.create(config(ProviderId.OPENAI, "/v1"), http).complete(request)
        assertEquals("answer", text)
        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        assertEquals("Bearer secret-key", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("gpt-5-mini", body["model"]!!.jsonPrimitive.content)
        assertEquals("json_object", body["response_format"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val roles = body["messages"]!!.jsonArray.map { it.jsonObject["role"]!!.jsonPrimitive.content }
        assertEquals(listOf("system", "user", "assistant", "user"), roles)
    }

    @Test
    fun `openrouter omits json mode it may not support`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        LlmClients.create(config(ProviderId.OPENROUTER), http).complete(request)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertTrue("response_format" !in body)
    }

    @Test
    fun `anthropic uses messages api headers and joins text blocks`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"there"}]}"""))
        val text = LlmClients.create(config(ProviderId.ANTHROPIC), http).complete(request)
        assertEquals("Hello there", text)
        val recorded = server.takeRequest()
        assertEquals("/v1/messages", recorded.path)
        assertEquals("secret-key", recorded.getHeader("x-api-key"))
        assertEquals("2023-06-01", recorded.getHeader("anthropic-version"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("sys", body["system"]!!.jsonPrimitive.content)
        assertEquals("claude-sonnet-5-5", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ollama cloud uses native chat api with bearer key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"model":"gpt-oss:120b","message":{"role":"assistant","content":"{\"a\":1}"},"done":true}"""))
        val text = LlmClients.create(config(ProviderId.OLLAMA_CLOUD), http).complete(request)
        assertEquals("{\"a\":1}", text)
        val recorded = server.takeRequest()
        assertEquals("/api/chat", recorded.path)
        assertEquals("Bearer secret-key", recorded.getHeader("Authorization"))
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("false", body["stream"]!!.jsonPrimitive.content)
        assertEquals("json", body["format"]!!.jsonPrimitive.content)
        assertEquals("gpt-oss:120b", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ollama lists cloud models`() = runTest {
        server.enqueue(MockResponse().setBody("""{"models":[{"name":"qwen3-coder:480b"},{"name":"gpt-oss:120b"}]}"""))
        val models = LlmClients.create(config(ProviderId.OLLAMA_CLOUD), http).listModels()
        assertEquals(listOf("gpt-oss:120b", "qwen3-coder:480b"), models)
        assertEquals("/api/tags", server.takeRequest().path)
    }

    @Test
    fun `http errors carry a helpful message`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided"}}"""))
        val e = assertFailsWith<AiException> { LlmClients.create(config(ProviderId.GROQ), http).complete(request) }
        assertEquals(401, e.statusCode)
        assertTrue(e.message!!.contains("Incorrect API key provided"))
        assertTrue(e.message!!.contains("Check the API key for Groq"))
    }

    @Test
    fun `openai diarize model requests diarized json and keeps speakers`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"text":"Hi. Hello.","segments":[
                {"type":"transcript.text.segment","id":"seg_0","start":0.0,"end":1.5,"text":"Hi.","speaker":"A"},
                {"type":"transcript.text.segment","id":"seg_1","start":1.6,"end":3.0,"text":"Hello.","speaker":"B"}]}""",
            ),
        )
        val stt = SttClients.create(config(ProviderId.OPENAI), http)
        val result = stt.transcribe(tempAudio("wav"), language = "en")
        assertTrue(result.diarized)
        assertEquals(listOf("A", "B"), result.segments.map { it.speakerLabel })
        assertEquals(1600, result.segments[1].startMs)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("diarized_json"))
        assertTrue(body.contains("chunking_strategy"))
        assertTrue(body.contains("gpt-4o-transcribe-diarize"))
    }

    @Test
    fun `whisper style models return timed segments without speakers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"a b","segments":[{"start":0,"end":2.5,"text":" a "},{"start":2.5,"end":4,"text":"b"}]}"""))
        val stt = SttClients.create(config(ProviderId.GROQ), http)
        val result = stt.transcribe(tempAudio("wav"))
        assertEquals(false, result.diarized)
        assertEquals(listOf("a", "b"), result.segments.map { it.text })
        assertTrue(server.takeRequest().body.readUtf8().contains("verbose_json"))
    }

    @Test
    fun `plain json transcription spans the chunk`() = runTest {
        server.enqueue(MockResponse().setBody("""{"text":"Just text"}"""))
        val settings = AppSettings().withProvider(ProviderId.OPENAI) {
            ProviderSettings(baseUrl = server.url("").toString().trimEnd('/'), sttModel = "gpt-4o-mini-transcribe")
        }
        val stt = SttClients.create(settings.configFor(ProviderId.OPENAI, "k"), http)
        val result = stt.transcribe(tempAudio("wav"), durationHintMs = 30_000)
        assertEquals(1, result.segments.size)
        assertEquals(30_000, result.segments[0].endMs)
    }

    @Test
    fun `deepgram uses token auth and utterances`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":{"utterances":[
                {"start":0.5,"end":2.0,"transcript":"Morning all","speaker":0,"confidence":0.9},
                {"start":2.1,"end":4.0,"transcript":"Hi","speaker":1,"confidence":0.8}]}}""",
            ),
        )
        val result = SttClients.create(config(ProviderId.DEEPGRAM), http).transcribe(tempAudio("m4a"))
        assertEquals(listOf("0", "1"), result.segments.map { it.speakerLabel })
        val recorded = server.takeRequest()
        assertEquals("Token secret-key", recorded.getHeader("Authorization"))
        assertTrue(recorded.path!!.contains("diarize=true"))
        assertTrue(recorded.path!!.contains("model=nova-3"))
        assertEquals("audio/mp4", recorded.getHeader("Content-Type"))
    }

    @Test
    fun `assemblyai uploads creates job and polls`() = runTest {
        server.enqueue(MockResponse().setBody("""{"upload_url":"https://cdn/x"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"job1","status":"queued"}"""))
        server.enqueue(MockResponse().setBody("""{"id":"job1","status":"processing"}"""))
        server.enqueue(
            MockResponse().setBody(
                """{"id":"job1","status":"completed","text":"x","utterances":[{"speaker":"A","text":"Hello","start":0,"end":900,"confidence":0.9}]}""",
            ),
        )
        val stt = AssemblyAiStt(config(ProviderId.ASSEMBLYAI), http, pollIntervalMs = 1)
        val result = stt.transcribe(tempAudio("wav"))
        assertEquals("A", result.segments.single().speakerLabel)
        assertEquals("/v2/upload", server.takeRequest().path)
        val create = server.takeRequest()
        assertEquals("/v2/transcript", create.path)
        assertTrue(create.body.readUtf8().contains("\"speaker_labels\":true"))
        assertEquals("/v2/transcript/job1", server.takeRequest().path)
    }

    private fun tempAudio(ext: String): File = File.createTempFile("audio", ".$ext").apply {
        writeBytes(ByteArray(64))
        deleteOnExit()
    }
}
