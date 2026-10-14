package com.kaliscriber.core.ai

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AiException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

internal val AiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    encodeDefaults = true
}

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(30, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.MINUTES)
    .writeTimeout(5, TimeUnit.MINUTES)
    .callTimeout(15, TimeUnit.MINUTES)
    .build()

internal suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}

internal fun JsonElement.toBody(): RequestBody = toString().toRequestBody(JSON_MEDIA)

/** Executes the request and returns the body text, mapping failures to readable [AiException]s. */
internal suspend fun OkHttpClient.executeForText(request: Request, provider: ProviderId): String {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw AiException("Could not reach ${provider.displayName}: ${e.message ?: e.javaClass.simpleName}", cause = e)
    }
    response.use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw httpError(provider, it.code, text)
        return text
    }
}

internal suspend fun OkHttpClient.executeForJson(request: Request, provider: ProviderId): JsonObject {
    val text = executeForText(request, provider)
    return try {
        AiJson.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        throw AiException("${provider.displayName} returned an unexpected response: ${text.take(200)}", cause = e)
    }
}

internal fun httpError(provider: ProviderId, code: Int, body: String): AiException {
    val detail = extractErrorMessage(body)
    val hint = when (code) {
        401, 403 -> "Check the API key for ${provider.displayName} in Settings."
        404 -> "Check the base URL and model name for ${provider.displayName}."
        413 -> "The audio file is too large for ${provider.displayName}."
        422 -> if (provider.supportsZdr) {
            "This model has no zero-data-retention route at ${provider.displayName}. Pick another model or turn off ZDR in Settings."
        } else {
            null
        }
        429 -> "Rate limited or out of credit at ${provider.displayName}."
        else -> null
    }
    val message = buildString {
        append("${provider.displayName} error $code")
        if (detail.isNotBlank()) append(": ").append(detail)
        if (hint != null) append(". ").append(hint)
    }
    return AiException(message, statusCode = code)
}

private fun extractErrorMessage(body: String): String {
    val obj = runCatching { AiJson.parseToJsonElement(body).jsonObject }.getOrNull() ?: return body.take(300)
    val error = obj["error"]
    return when {
        error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull ?: error.toString()
        error != null -> runCatching { error.jsonPrimitive.content }.getOrDefault(error.toString())
        obj["message"] != null -> obj["message"]!!.jsonPrimitive.contentOrNull.orEmpty()
        obj["err_msg"] != null -> obj["err_msg"]!!.jsonPrimitive.contentOrNull.orEmpty()
        else -> body.take(300)
    }
}

internal fun Request.Builder.bearer(key: String): Request.Builder =
    if (key.isBlank()) this else header("Authorization", "Bearer $key")

internal fun Request.Builder.headers(extra: Map<String, String>): Request.Builder =
    apply { extra.forEach { (name, value) -> header(name, value) } }

/** Executes the request and returns the raw body bytes (e.g. synthesized audio). */
internal suspend fun OkHttpClient.executeForBytes(request: Request, provider: ProviderId): ByteArray {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw AiException("Could not reach ${provider.displayName}: ${e.message ?: e.javaClass.simpleName}", cause = e)
    }
    response.use {
        if (!it.isSuccessful) throw httpError(provider, it.code, it.body?.string().orEmpty())
        return it.body?.bytes() ?: ByteArray(0)
    }
}
