package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.headers
import com.kaliscriber.core.ai.toBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
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
        if (config.chatModel.isBlank()) throw AiException("No chat model set for ${config.id.displayName}")
        val body = buildJsonObject {
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
        val json = http.executeForJson(baseRequest("/v1/messages").post(body.toBody()).build(), config.id)
        val text = json["content"]?.jsonArray.orEmpty()
            .filterIsInstance<JsonObject>()
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text
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
