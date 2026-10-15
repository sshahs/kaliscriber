package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiJson
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.headers
import com.kaliscriber.core.ai.streamError
import com.kaliscriber.core.ai.streamEvents
import com.kaliscriber.core.ai.toBody
import com.kaliscriber.core.ai.usage.TokenUsage
import com.kaliscriber.core.ai.usage.UsageParsing
import com.kaliscriber.core.ai.usage.reportUsage
import kotlinx.serialization.json.JsonObject
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

/** Anthropic Messages API. */
class AnthropicLlm(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
) : LlmClient {

    override suspend fun complete(request: LlmRequest): String {
        val json = http.executeForJson(baseRequest("/v1/messages").post(body(request, stream = false).toBody()).build(), config.id)
        reportUsage(UsageParsing.anthropic(json["usage"]))
        val text = json["content"]?.jsonArray.orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text
    }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String {
        val text = StringBuilder()
        var usage: TokenUsage? = null
        val req = baseRequest("/v1/messages").post(body(request, stream = true).toBody()).build()
        http.streamEvents(req, config.id) { data ->
            val obj = runCatching { AiJson.parseToJsonElement(data).jsonObject }.getOrNull() ?: return@streamEvents
            streamError(obj)?.let { throw AiException("${config.id.displayName} error: $it") }
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                // The input counts come first, the output count at the end.
                "message_start" -> usage = UsageParsing.anthropic((obj["message"] as? JsonObject)?.get("usage"))
                "message_delta" -> UsageParsing.anthropic(obj["usage"])?.let { end ->
                    usage = (usage ?: TokenUsage(0, 0)).copy(output = end.output)
                }
            }
            if (obj["type"]?.jsonPrimitive?.contentOrNull != "content_block_delta") return@streamEvents
            val delta = obj["delta"]?.jsonObject ?: return@streamEvents
            if (delta["type"]?.jsonPrimitive?.contentOrNull != "text_delta") return@streamEvents
            val piece = delta["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (piece.isNotEmpty()) {
                text.append(piece)
                onText(text.toString())
            }
        }
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        reportUsage(usage)
        return text.toString()
    }

    private fun body(request: LlmRequest, stream: Boolean): JsonObject {
        if (config.chatModel.isBlank()) throw AiException("No chat model set for ${config.id.displayName}")
        return buildJsonObject {
            if (stream) put("stream", true)
            put("model", config.chatModel)
            put("max_tokens", request.outputLimit ?: request.maxTokens)
            val prefix = request.cachePrefix?.takeIf { request.system.startsWith(it) && it.length >= MIN_CACHE_CHARS }
            if (prefix == null) {
                put("system", request.system)
            } else {
                // Mark the shared meeting text as cacheable; the task that follows is sent uncached.
                putJsonArray("system") {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", prefix)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    })
                    val rest = request.system.substring(prefix.length)
                    if (rest.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", rest) })
                }
            }
            putJsonArray("messages") {
                request.messages.normalizedTurns().forEach {
                    add(buildJsonObject {
                        put("role", if (it.role == LlmRole.USER) "user" else "assistant")
                        put("content", it.content)
                    })
                }
            }
        }
    }

    override suspend fun listModels(): List<String> {
        val json = http.executeForJson(baseRequest("/v1/models?limit=100").get().build(), config.id)
        return json["data"]?.jsonArray.orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { it["id"]?.jsonPrimitive?.contentOrNull }
    }

    private companion object {
        /** Anthropic only caches prompts of at least ~1024 tokens; shorter ones are sent plainly. */
        const val MIN_CACHE_CHARS = 4_000
    }

    private fun baseRequest(path: String) = Request.Builder()
        .url(config.baseUrl + path)
        .header("x-api-key", config.apiKey)
        .header("anthropic-version", "2023-06-01")
        .headers(config.extraHeaders)
}
