package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.bearer
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.headers
import com.kaliscriber.core.ai.toBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** OpenAI Chat Completions, also spoken by Gemini, Groq, OpenRouter, Mistral, DeepSeek and many others. */
class OpenAiCompatibleLlm(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
) : LlmClient {

    override suspend fun complete(request: LlmRequest): String {
        if (config.chatModel.isBlank()) throw AiException("No chat model set for ${config.id.displayName}")
        val effort = config.reasoningEffort.takeIf { it.isNotBlank() }
        return try {
            send(request, effort)
        } catch (e: AiException) {
            // Not every model takes a thinking effort; retry once with the provider's default.
            if (effort != null && e.statusCode in 400..422 && e.message.orEmpty().contains("reasoning", ignoreCase = true)) {
                send(request, null)
            } else {
                throw e
            }
        }
    }

    private suspend fun send(request: LlmRequest, effort: String?): String {
        val body = buildJsonObject {
            put("model", config.chatModel)
            putJsonArray("messages") {
                add(message("system", request.system))
                request.messages.normalizedTurns().forEach {
                    add(message(if (it.role == LlmRole.USER) "user" else "assistant", it.content))
                }
            }
            if (request.jsonMode && config.id.supportsJsonMode) {
                putJsonObject("response_format") { put("type", "json_object") }
            }
            if (effort != null) put("reasoning_effort", effort)
            // OpenAI's own API only takes the newer name; compatible providers know max_tokens.
            request.outputLimit?.let { put(if (config.id == ProviderId.OPENAI) "max_completion_tokens" else "max_tokens", it) }
        }
        val req = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .bearer(config.apiKey)
            .headers(config.extraHeaders)
            .apply {
                if (config.id == ProviderId.OPENROUTER) {
                    header("HTTP-Referer", "https://github.com/sshahs/kaliscriber")
                    header("X-Title", "KaliScriber")
                }
            }
            .post(body.toBody())
            .build()
        val json = http.executeForJson(req, config.id)
        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiException("${config.id.displayName} returned no choices")
        val content = choice["message"]?.jsonObject?.get("content")
        val text = when (content) {
            is JsonPrimitive -> content.contentOrNull.orEmpty()
            is JsonArray -> content.joinToString("") {
                (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            else -> ""
        }
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text
    }

    override suspend fun listModels(): List<String> {
        val request = Request.Builder().url("${config.baseUrl}/models").bearer(config.apiKey).headers(config.extraHeaders).get().build()
        val json = http.executeForJson(request, config.id)
        val data = (json["data"] ?: json["models"])?.jsonArray ?: return emptyList()
        return data.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            (obj["id"] ?: obj["name"])?.jsonPrimitive?.contentOrNull?.removePrefix("models/")
        }.sorted()
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }
}
