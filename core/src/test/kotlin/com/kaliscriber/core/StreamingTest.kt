package com.kaliscriber.core

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()
    private val request = LlmRequest("sys", listOf(LlmMessage(LlmRole.USER, "hi")))

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client(id: ProviderId, path: String = "") = LlmClients.create(
        AppSettings().withProvider(id) { ProviderSettings(baseUrl = server.url(path).toString().trimEnd('/'), chatModel = "m") }
            .configFor(id, "k"),
        http,
    )

    private fun sse(vararg events: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" })

    @Test
    fun `openai compatible answers arrive piece by piece`() = runTest {
        server.enqueue(
            sse(
                """{"choices":[{"delta":{"role":"assistant"}}]}""",
                """{"choices":[{"delta":{"content":"Hel"}}]}""",
                """{"choices":[{"delta":{"reasoning_content":"hmm"}}]}""",
                """{"choices":[{"delta":{"content":"lo"}}]}""",
                "[DONE]",
            ),
        )
        val seen = mutableListOf<String>()
        val text = client(ProviderId.OPENAI).stream(request) { seen += it }
        assertEquals("Hello", text)
        assertEquals(listOf("Hel", "Hello"), seen)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a provider that ignores streaming still answers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"All at once"}}]}"""))
        val seen = mutableListOf<String>()
        assertEquals("All at once", client(ProviderId.OPENAI).stream(request) { seen += it })
        assertEquals(listOf("All at once"), seen)
    }

    @Test
    fun `an error sent mid-stream is reported`() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"Par"}}]}""", """{"error":{"message":"Upstream overloaded"}}"""))
        val e = assertFailsWith<AiException> { client(ProviderId.OPENAI).stream(request) {} }
        assertTrue(e.message!!.contains("Upstream overloaded"), e.message)
    }

    @Test
    fun `http errors before the stream starts read like normal errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val e = assertFailsWith<AiException> { client(ProviderId.OPENAI).stream(request) {} }
        assertEquals(401, e.statusCode)
        assertTrue(e.message!!.contains("bad key"))
    }

    @Test
    fun `anthropic text deltas are joined`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                """
                event: message_start
                data: {"type":"message_start","message":{"id":"x"}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi "}}

                event: ping
                data: {"type":"ping"}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}

                event: message_stop
                data: {"type":"message_stop"}

                """.trimIndent(),
            ),
        )
        val seen = mutableListOf<String>()
        assertEquals("Hi there", client(ProviderId.ANTHROPIC).stream(request) { seen += it })
        assertEquals(listOf("Hi ", "Hi there"), seen)
        assertEquals("/v1/messages", server.takeRequest().path)
    }

    @Test
    fun `ollama streams one json object per line`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                {"message":{"role":"assistant","content":"One"},"done":false}
                {"message":{"role":"assistant","content":" two"},"done":false}
                {"message":{"role":"assistant","content":""},"done":true}
                """.trimIndent(),
            ),
        )
        val seen = mutableListOf<String>()
        assertEquals("One two", client(ProviderId.OLLAMA_CLOUD).stream(request) { seen += it })
        assertEquals(listOf("One", "One two"), seen)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
    }

    @Test
    fun `leaving stops a stream that is still being written`() = runBlocking {
        // The throttle applies to the upload too, so the first period is made large enough for the request.
        val first = ": " + "x".repeat(4_000) + "\n" + "data: " + """{"choices":[{"delta":{"content":"Start"}}]}""" + "\n\n"
        // The rest of the answer trickles in far slower than the test waits.
        server.enqueue(sse().setBody(first + "data: [DONE]\n\n".repeat(200)).throttleBody(first.length.toLong(), 3, TimeUnit.SECONDS))
        val started = CompletableDeferred<Unit>()
        val job = launch(Dispatchers.Default) { client(ProviderId.OPENAI).stream(request) { started.complete(Unit) } }
        withTimeout(5_000) { started.await() }
        val stopped = withTimeoutOrNull(1_500) { job.cancelAndJoin() }
        assertTrue(stopped != null && job.isCancelled, "cancelling ends the read without waiting for the server")
    }

    @Test
    fun `command code streams on the wire its model uses`() = runTest {
        server.enqueue(sse("""{"choices":[{"delta":{"content":"ok"}}]}""", "[DONE]"))
        assertEquals("ok", client(ProviderId.COMMAND_CODE, "/provider/v1").stream(request) {})
        val sent = server.takeRequest()
        assertEquals("/provider/v1/chat/completions", sent.path)
        assertEquals("1", sent.getHeader("x-cmd-zdr"))
    }
}
