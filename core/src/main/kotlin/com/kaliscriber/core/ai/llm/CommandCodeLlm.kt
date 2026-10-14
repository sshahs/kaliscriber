package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.ProviderConfig
import okhttp3.OkHttpClient

/**
 * Command Code's Provider API (https://api.commandcode.ai/provider/v1). Claude models are served on
 * the Anthropic Messages wire, everything else on OpenAI Chat Completions.
 *
 * With zero data retention on, every request carries `x-cmd-zdr: 1`: Command Code then only routes
 * to upstreams that retain nothing, and fails with 422 rather than silently falling back.
 */
class CommandCodeLlm(
    config: ProviderConfig,
    private val http: OkHttpClient,
) : LlmClient {

    override val config: ProviderConfig = config.copy(
        extraHeaders = config.extraHeaders + headersFor(config),
    )

    private val chatWire by lazy { OpenAiCompatibleLlm(this.config, http) }

    // The Messages wire lives at <base>/v1/messages, so drop the trailing /v1 the shared base carries.
    private val messagesWire by lazy {
        AnthropicLlm(this.config.copy(baseUrl = this.config.baseUrl.removeSuffix("/v1")), http)
    }

    private fun wireFor(model: String): LlmClient =
        if (model.startsWith("claude", ignoreCase = true)) messagesWire else chatWire

    override suspend fun complete(request: LlmRequest): String = wireFor(config.chatModel).complete(request)

    override suspend fun listModels(): List<String> = chatWire.listModels()

    companion object {
        const val ZDR_HEADER = "x-cmd-zdr"

        fun headersFor(config: ProviderConfig): Map<String, String> = buildMap {
            if (config.apiKey.isNotBlank()) put("Authorization", "Bearer ${config.apiKey}")
            if (config.zeroDataRetention) put(ZDR_HEADER, "1")
        }
    }
}
