package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.ApiKind
import com.kaliscriber.core.ai.ProviderConfig
import okhttp3.OkHttpClient

enum class LlmRole { USER, ASSISTANT }

data class LlmMessage(val role: LlmRole, val content: String)

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    /** Ask the provider to return a JSON object, where it supports that. */
    val jsonMode: Boolean = false,
    val maxTokens: Int = 8192,
    /**
     * Caps the reply on every provider, including those that otherwise let a model write until it
     * hits its own limit. Bounds how long a request can run if a model starts repeating itself.
     */
    val outputLimit: Int? = null,
    /**
     * The start of [system] that stays the same across requests (the meeting and its rules), for
     * providers that need an explicit cache marker. Others cache identical prefixes on their own.
     */
    val cachePrefix: String? = null,
)

interface LlmClient {
    val config: ProviderConfig
    suspend fun complete(request: LlmRequest): String

    /**
     * Like [complete], but calls [onText] with the answer written so far as it arrives, so it can be
     * shown while the model is still writing. Returns the whole answer.
     */
    suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String = complete(request).also(onText)

    suspend fun listModels(): List<String>
}

object LlmClients {
    fun create(config: ProviderConfig, http: OkHttpClient): LlmClient = when (config.id.kind) {
        ApiKind.OPENAI_COMPATIBLE -> OpenAiCompatibleLlm(config, http)
        ApiKind.ANTHROPIC -> AnthropicLlm(config, http)
        ApiKind.OLLAMA -> OllamaLlm(config, http)
        ApiKind.COMMAND_CODE -> CommandCodeLlm(config, http)
        ApiKind.LOCAL, ApiKind.DEEPGRAM, ApiKind.ASSEMBLYAI, ApiKind.ELEVENLABS ->
            throw IllegalArgumentException("${config.id.displayName} does not provide chat models")
    }
}

/** Most chat APIs require strictly alternating turns starting with the user. */
internal fun List<LlmMessage>.normalizedTurns(): List<LlmMessage> {
    val out = mutableListOf<LlmMessage>()
    for (m in this) {
        if (m.content.isBlank()) continue
        val last = out.lastOrNull()
        if (last != null && last.role == m.role) {
            out[out.lastIndex] = last.copy(content = last.content + "\n\n" + m.content)
        } else {
            out += m
        }
    }
    if (out.firstOrNull()?.role == LlmRole.ASSISTANT) out.add(0, LlmMessage(LlmRole.USER, "(continuing)"))
    return out
}
