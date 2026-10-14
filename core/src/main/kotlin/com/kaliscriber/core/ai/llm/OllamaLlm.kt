package com.kaliscriber.core.ai.llm

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiJson
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.bearer
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.streamError
import com.kaliscriber.core.ai.streamLines
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
        val json = http.executeForJson(httpRequest(request, stream = false), config.id)
        val text = json["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text
    }

    /** Ollama streams one JSON object per line. */
    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String {
        val text = StringBuilder()
        http.streamLines(httpRequest(request, stream = true), config.id) { line ->
            if (line.isBlank()) return@streamLines
            val obj = runCatching { AiJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@streamLines
            streamError(obj)?.let { throw AiException("${config.id.displayName} error: $it") }
            val piece = obj["message"]?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (piece.isNotEmpty()) {
                text.append(piece)
                onText(text.toString())
            }
        }
        if (text.isBlank()) throw AiException("${config.id.displayName} returned an empty answer")
        return text.toString()
    }

    private fun httpRequest(request: LlmRequest, stream: Boolean): Request {
        if (config.chatModel.isBlank()) throw AiException("No chat model set for ${config.id.displayName}")
        val body = buildJsonObject {
            put("model", config.chatModel)
            put("stream", stream)
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
        return Request.Builder().url("${config.baseUrl}/api/chat").bearer(config.apiKey).post(body.toBody()).build()
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
