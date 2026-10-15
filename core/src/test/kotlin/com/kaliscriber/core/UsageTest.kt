package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.usage.MeteredLlm
import com.kaliscriber.core.ai.usage.ModelPrice
import com.kaliscriber.core.ai.usage.TaggedLlm
import com.kaliscriber.core.ai.usage.TokenUsage
import com.kaliscriber.core.ai.usage.UsageLedger
import com.kaliscriber.core.ai.usage.UsageRecord
import com.kaliscriber.core.ai.usage.UsageTag
import com.kaliscriber.core.store.JsonValueStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageTest {
    private lateinit var server: MockWebServer
    private val root: File = Files.createTempDirectory("usage").toFile()
    private val ledger = UsageLedger(JsonValueStore(File(root, "usage.json"), ListSerializer(UsageRecord.serializer()), emptyList()))
    private val request = LlmRequest("You are helpful.", listOf(LlmMessage(LlmRole.USER, "Hello there")))

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private fun client(id: ProviderId) = TaggedLlm(
        MeteredLlm(
            LlmClients.create(
                AppSettings().withProvider(id) { ProviderSettings(baseUrl = server.url("").toString().trimEnd('/'), chatModel = "m1") }.configFor(id, "k"),
                OkHttpClient(),
            ),
            ledger,
        ),
        UsageTag("meeting-1", "Summary"),
    )

    private fun sse(vararg events: String) = MockResponse().setBody(events.joinToString("") { "data: $it\n\n" })

    @Test
    fun `token counts are read from each provider's replies`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Hi"}}],"usage":{"prompt_tokens":120,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":100}}}"""))
        client(ProviderId.OPENAI).complete(request)
        server.enqueue(sse("""{"choices":[{"delta":{"content":"Hi"}}]}""", """{"choices":[],"usage":{"prompt_tokens":50,"completion_tokens":5,"prompt_cache_hit_tokens":40}}""", "[DONE]"))
        client(ProviderId.OPENAI).stream(request) {}
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"Hi"}],"usage":{"input_tokens":10,"cache_read_input_tokens":200,"cache_creation_input_tokens":5,"output_tokens":7}}"""))
        client(ProviderId.ANTHROPIC).complete(request)
        server.enqueue(
            sse(
                """{"type":"message_start","message":{"usage":{"input_tokens":12,"cache_read_input_tokens":300,"output_tokens":1}}}""",
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hi"}}""",
                """{"type":"message_delta","usage":{"output_tokens":9}}""",
            ),
        )
        client(ProviderId.ANTHROPIC).stream(request) {}
        server.enqueue(MockResponse().setBody("{\"message\":{\"content\":\"Hi\"},\"done\":false}\n{\"message\":{\"content\":\"\"},\"done\":true,\"prompt_eval_count\":33,\"eval_count\":4}\n"))
        client(ProviderId.OLLAMA_CLOUD).stream(request) {}

        val usages = ledger.records.value.map { it.usage }
        assertEquals(
            listOf(
                TokenUsage(120, 8, 100),
                TokenUsage(50, 5, 40),
                TokenUsage(215, 7, 200),
                TokenUsage(312, 9, 300),
                TokenUsage(33, 4, 0),
            ),
            usages,
        )
        assertTrue(ledger.records.value.all { it.meetingId == "meeting-1" && it.purpose == "Summary" && it.model == "m1" })
    }

    @Test
    fun `usage is estimated when a provider doesn't report it`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"Twelve chars"}}]}"""))
        client(ProviderId.OPENAI).complete(request)
        val usage = ledger.records.value.single().usage
        assertTrue(usage.estimated)
        assertEquals(("You are helpful.".length + "Hello there".length) / 4L, usage.input)
        assertEquals(3L, usage.output)
    }

    @Test
    fun `costs follow the prices entered, with cached input priced separately`() {
        val records = listOf(
            UsageRecord(0, "OPENAI", "gpt", "m", "Summary", TokenUsage(1_000_000, 100_000, 400_000)),
            UsageRecord(0, "DEEPSEEK", "chat", null, "Ask", TokenUsage(10, 10)),
        )
        val prices = mapOf("OPENAI/gpt" to ModelPrice(input = 2.0, output = 8.0, cachedInput = 0.5))
        // 600k uncached x $2 + 400k cached x $0.50 + 100k output x $8, per million.
        assertEquals(1.2 + 0.2 + 0.8, UsageLedger.cost(records, prices)!!, 1e-9)
        assertEquals(null, UsageLedger.cost(records.drop(1), prices))
        assertEquals(listOf("OPENAI/gpt", "DEEPSEEK/chat"), UsageLedger.byModel(records).map { it.key })
    }
}
