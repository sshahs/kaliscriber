package com.kaliscriber.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

/** How network failures are retried. The delays are replaceable so tests run fast. */
object NetworkRetry {
    /** Waits before the 2nd and 3rd attempt. */
    var delaysMs: List<Long> = listOf(2_000, 6_000)
    /** Longest Retry-After honoured from a 429/503 reply. */
    var maxRetryAfterMs: Long = 30_000
}

/**
 * Sends [request], retrying failures that happen before the provider could have done any work
 * (DNS lookups, refused connections, connect timeouts) and short-lived overload replies
 * (429/502/503/504). A read timeout is not retried: the model may still be busy on that request.
 */
internal suspend fun OkHttpClient.send(request: Request, provider: ProviderId, onCall: (Call) -> Unit = {}): Response {
    var attempt = 0
    while (true) {
        var waitMs: Long
        try {
            val response = newCall(request).also(onCall).await()
            if (response.code !in RETRY_STATUS || attempt >= NetworkRetry.delaysMs.size) return response
            waitMs = response.header("Retry-After")?.toLongOrNull()?.times(1000) ?: NetworkRetry.delaysMs[attempt]
            response.close()
        } catch (e: IOException) {
            if (!isRetryable(e) || attempt >= NetworkRetry.delaysMs.size) throw networkError(provider, request, e)
            waitMs = NetworkRetry.delaysMs[attempt]
        }
        delay(waitMs.coerceAtMost(NetworkRetry.maxRetryAfterMs))
        attempt++
    }
}

private val RETRY_STATUS = setOf(429, 502, 503, 504)

private fun isRetryable(e: IOException): Boolean = when (e) {
    is UnknownHostException, is ConnectException, is NoRouteToHostException -> true
    is SocketTimeoutException -> e.message.orEmpty().contains("connect", ignoreCase = true)
    else -> false
}

/** Turns low-level network exceptions into messages people can act on. */
internal fun networkError(provider: ProviderId, request: Request, e: IOException): AiException {
    val host = request.url.host
    val message = when {
        e is UnknownHostException ->
            "Couldn't find $host (no internet, or DNS couldn't resolve it). Check your connection, VPN or Private DNS setting, then try again."
        e is ConnectException || e is NoRouteToHostException ->
            "Couldn't connect to ${provider.displayName} ($host). Check your connection and try again."
        e is SocketTimeoutException && e.message.orEmpty().contains("connect", ignoreCase = true) ->
            "Connecting to ${provider.displayName} timed out. Check your connection and try again."
        e is InterruptedIOException ->
            "${provider.displayName} took too long to answer. Try again, or pick a faster model or a lower thinking effort in Settings."
        else -> "Could not reach ${provider.displayName}: ${e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName}"
    }
    return AiException(message, cause = e)
}

/** Executes the request and returns the body text, mapping failures to readable [AiException]s. */
internal suspend fun OkHttpClient.executeForText(request: Request, provider: ProviderId): String {
    send(request, provider).use {
        val text = it.body?.string().orEmpty()
        if (!it.isSuccessful) throw httpError(provider, it.code, text)
        return text
    }
}

/**
 * Executes a streaming request and calls [onLine] for each line of the reply as it arrives (on a
 * background thread). Cancelling the caller cancels the call, which ends a read in progress.
 */
internal suspend fun OkHttpClient.streamLines(request: Request, provider: ProviderId, onLine: (String) -> Unit) {
    var call: Call? = null
    send(request, provider) { call = it }.use { response ->
        if (!response.isSuccessful) throw httpError(provider, response.code, response.body?.string().orEmpty())
        val source = response.body?.source() ?: return
        coroutineScope {
            // A blocked read only ends when the call is cancelled, which closes its connection.
            val finished = AtomicBoolean(false)
            val canceller = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) call?.cancel()
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    while (true) {
                        val line = try {
                            source.readUtf8Line()
                        } catch (e: IOException) {
                            ensureActive()
                            throw networkError(provider, request, e)
                        } ?: break
                        onLine(line)
                    }
                }
                finished.set(true)
            } finally {
                canceller.cancel()
            }
        }
    }
}

/**
 * Collects a server-sent event stream: [onEvent] gets each `data:` payload except the closing
 * `[DONE]`. Lines that are not part of an event stream are returned, so a provider that ignored
 * the request to stream and sent one ordinary JSON reply can still be read.
 */
internal suspend fun OkHttpClient.streamEvents(request: Request, provider: ProviderId, onEvent: (String) -> Unit): String {
    val other = StringBuilder()
    var sse = false
    streamLines(request, provider) { line ->
        when {
            line.startsWith("data:") -> {
                sse = true
                val data = line.removePrefix("data:").trim()
                if (data.isNotEmpty() && data != "[DONE]") onEvent(data)
            }
            line.startsWith("event:") || line.startsWith(":") || line.startsWith("id:") || line.startsWith("retry:") -> sse = true
            !sse -> other.append(line).append('\n')
        }
    }
    return other.toString()
}

/** The message of an `{"error": ...}` object sent in the middle of a stream, if [obj] is one. */
internal fun streamError(obj: JsonObject): String? {
    val error = obj["error"]
    if (error == null || error is JsonNull) return null
    return extractErrorMessage(obj.toString()).ifBlank { "unknown error" }
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
    send(request, provider).use {
        if (!it.isSuccessful) throw httpError(provider, it.code, it.body?.string().orEmpty())
        return it.body?.bytes() ?: ByteArray(0)
    }
}
