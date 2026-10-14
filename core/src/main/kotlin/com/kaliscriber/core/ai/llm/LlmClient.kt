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
)

interface LlmClient {
    val config: ProviderConfig
    suspend fun complete(request: LlmRequest): String
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
