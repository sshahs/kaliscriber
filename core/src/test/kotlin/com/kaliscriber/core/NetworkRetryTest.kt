package com.kaliscriber.core

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.NetworkRetry
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import kotlinx.coroutines.test.runTest
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NetworkRetryTest {
    private lateinit var server: MockWebServer
    private val request = LlmRequest("sys", listOf(LlmMessage(LlmRole.USER, "hi")))

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
        NetworkRetry.delaysMs = listOf(1, 1)
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
        NetworkRetry.delaysMs = listOf(2_000, 6_000)
    }

    private fun config() = AppSettings()
        .withProvider(ProviderId.COMMAND_CODE) { ProviderSettings(baseUrl = server.url("/provider/v1").toString().trimEnd('/')) }
        .configFor(ProviderId.COMMAND_CODE, "user_abc")

    @Test
    fun `overloaded replies are retried`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
        assertEquals("ok", LlmClients.create(config(), OkHttpClient()).complete(request))
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `dns failures are retried then explained`() = runTest {
        var lookups = 0
        val http = OkHttpClient.Builder().dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups++
                throw UnknownHostException("No address associated with hostname")
            }
        }).build()
        val e = assertFailsWith<AiException> { LlmClients.create(config(), http).complete(request) }
        assertEquals(3, lookups, "first try plus two retries")
        assertTrue(e.message!!.contains("DNS"), e.message)
        assertTrue(e.message!!.contains("Private DNS"), e.message)
    }

    @Test
    fun `read timeouts are not retried`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val http = OkHttpClient.Builder().readTimeout(200, TimeUnit.MILLISECONDS).build()
        val e = assertFailsWith<AiException> { LlmClients.create(config(), http).complete(request) }
        assertEquals(1, server.requestCount)
        assertTrue(e.message!!.contains("took too long"), e.message)
    }
}
