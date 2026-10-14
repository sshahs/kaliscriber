package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.bearer
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.toBody
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

/**
 * Ollama's native API. Defaults to Ollama Cloud (https://ollama.com) with a Bearer API key, but any
 * remotely hosted Ollama server works by changing the base URL.
 */
class OllamaLlm(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
) : LlmClient {

    override suspend fun complete(request: LlmRequest): String {
        if (config.chatModel.isBlank()) throw AiException("No chat model set for ${config.id.displayName}")
        val body = buildJsonObject {
            put("model", config.chatModel)
            put("stream", false)
            if (request.jsonMode) put("format", "json")
            request.outputLimit?.let { limit -> putJsonObject("options") { put("num_predict", limit) } }
            putJsonArray("messages") {
                add(buildJsonObject { put("role", "system"); put("content", request.system) })
                request.messages.normalizedTurns().forEach {
                    add(buildJsonObject {
                        put("role", if (it.role == LlmRole.USER) "user" else "assistant")
                        put("content", it.content)
                    })
                }
            }
        }
        val req = Request.Builder().url("${config.baseUrl}/api/chat").bearer(config.apiKey).post(body.toBody()).build()
        val json = http.executeForJson(req, config.id)
        val text = json["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text
    }

    override suspend fun listModels(): List<String> {
        val req = Request.Builder().url("${config.baseUrl}/api/tags").bearer(config.apiKey).get().build()
        val json = http.executeForJson(req, config.id)
        return json["models"]?.jsonArray.orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { (it["name"] ?: it["model"])?.jsonPrimitive?.contentOrNull }
            .sorted()
    }
}
