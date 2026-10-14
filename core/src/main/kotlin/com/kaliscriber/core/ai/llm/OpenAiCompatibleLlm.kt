package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiJson
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.bearer
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.headers
import com.kaliscriber.core.ai.streamError
import com.kaliscriber.core.ai.streamEvents
import com.kaliscriber.core.ai.toBody
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    override suspend fun complete(request: LlmRequest): String = withEffortFallback { effort ->
        textOf(http.executeForJson(httpRequest(request, effort, stream = false), config.id))
    }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String = withEffortFallback { effort ->
        val text = StringBuilder()
        val rest = http.streamEvents(httpRequest(request, effort, stream = true), config.id) { data ->
            val obj = runCatching { AiJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@streamEvents
            streamError(obj)?.let { throw AiException("${config.id.displayName} error: $it") }
            val delta = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
            val piece = delta?.get("content")?.let(::contentText).orEmpty()
            if (piece.isNotEmpty()) {
                text.append(piece)
                onText(text.toString())
            }
        }
        if (text.isBlank() && rest.isNotBlank()) {
            // The provider sent an ordinary reply instead of a stream.
            val json = runCatching { AiJson.parseToJsonElement(rest).jsonObject }.getOrNull()
                ?: throw AiException("${config.id.displayName} returned an unexpected response: ${rest.take(200)}")
            textOf(json).also(onText)
        } else {
            if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
            text.toString()
        }
    }

    /** Runs [block] with the configured thinking effort, and once more without it if the model doesn't take one. */
    private suspend fun withEffortFallback(block: suspend (effort: String?) -> String): String {
        if (config.chatModel.isBlank()) throw AiException("No chat model set for ${config.id.displayName}")
        val effort = config.reasoningEffort.takeIf { it.isNotBlank() }
        return try {
            block(effort)
        } catch (e: AiException) {
            // Not every model takes a thinking effort; retry once with the provider's default.
            if (effort != null && e.statusCode in 400..422 && e.message.orEmpty().contains("reasoning", ignoreCase = true)) {
                block(null)
            } else {
                throw e
            }
        }
    }

    private fun httpRequest(request: LlmRequest, effort: String?, stream: Boolean): Request {
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
            if (stream) put("stream", true)
        }
        return Request.Builder()
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
    }

    private fun textOf(json: JsonObject): String {
        val choice = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw AiException("${config.id.displayName} returned no choices")
        val text = choice["message"]?.jsonObject?.get("content")?.let(::contentText).orEmpty()
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text
    }

    /** Message content is usually a string, but some providers send a list of text parts. */
    private fun contentText(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.joinToString("") {
            (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        }
        else -> ""
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
