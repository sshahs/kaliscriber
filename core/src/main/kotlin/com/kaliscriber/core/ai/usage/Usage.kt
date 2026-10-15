package com.kaliscriber.core.ai.usage

import com.kaliscriber.core.ai.llm.LlmClient
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.util.Clock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Tokens one AI request used. [cachedInput] is the part of [input] served from the provider's prompt cache. */
@Serializable
data class TokenUsage(val input: Long, val output: Long, val cachedInput: Long = 0, val estimated: Boolean = false) {
    operator fun plus(other: TokenUsage) =
        TokenUsage(input + other.input, output + other.output, cachedInput + other.cachedInput, estimated || other.estimated)
}

/** What a request was for, so usage can be shown per meeting and per task. Set with `withContext`. */
class UsageTag(val meetingId: String?, val purpose: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UsageTag>
}

/** Where an LLM client reports the usage of the request it is serving. */
internal class UsageCollector : AbstractCoroutineContextElement(Key) {
    @Volatile var usage: TokenUsage? = null

    companion object Key : CoroutineContext.Key<UsageCollector>
}

/** Called by LLM clients with the token counts the provider returned. */
internal suspend fun reportUsage(usage: TokenUsage?) {
    if (usage != null) coroutineContext[UsageCollector]?.usage = usage
}

@Serializable
data class UsageRecord(
    val at: Long,
    val provider: String,
    val model: String,
    val meetingId: String? = null,
    val purpose: String = "",
    val usage: TokenUsage,
)

/** A model's price in US dollars per million tokens; [cachedInput] defaults to [input]. */
@Serializable
data class ModelPrice(val input: Double = 0.0, val output: Double = 0.0, val cachedInput: Double? = null) {
    fun cost(u: TokenUsage): Double =
        ((u.input - u.cachedInput) * input + u.cachedInput * (cachedInput ?: input) + u.output * output) / 1_000_000.0
}

/**
 * Every AI request's token usage, kept on the phone for about a year, so the app can show what
 * each meeting and each month cost with the prices the user enters.
 */
class UsageLedger(private val store: JsonValueStore<List<UsageRecord>>, private val clock: Clock = Clock.System) {
    val records = store.value

    suspend fun record(record: UsageRecord) {
        val cutoff = clock.now() - KEEP_MS
        store.update { list -> (list.filter { it.at >= cutoff } + record).takeLast(MAX_RECORDS) }
    }

    suspend fun clear() = store.update { emptyList() }

    /** Total usage per "provider/model". */
    data class ModelTotal(val key: String, val provider: String, val model: String, val usage: TokenUsage, val requests: Int)

    companion object {
        const val KEEP_MS = 400L * 24 * 3_600_000
        const val MAX_RECORDS = 20_000

        fun key(provider: String, model: String) = "$provider/$model"

        fun byModel(records: List<UsageRecord>): List<ModelTotal> =
            records.groupBy { key(it.provider, it.model) }.map { (key, list) ->
                ModelTotal(key, list.first().provider, list.first().model, list.map { it.usage }.reduce(TokenUsage::plus), list.size)
            }.sortedByDescending { it.usage.input + it.usage.output }

        /** Records from the calendar month containing [now]. */
        fun thisMonth(records: List<UsageRecord>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<UsageRecord> {
            val month = Instant.ofEpochMilli(now).atZone(zone).let { it.year * 12 + it.monthValue }
            return records.filter { r -> Instant.ofEpochMilli(r.at).atZone(zone).let { it.year * 12 + it.monthValue } == month }
        }

        fun forMeeting(records: List<UsageRecord>, meetingId: String) = records.filter { it.meetingId == meetingId }

        /** Cost of [records] with [prices] (by "provider/model"); null when none of their models has a price. */
        fun cost(records: List<UsageRecord>, prices: Map<String, ModelPrice>): Double? {
            val priced = records.filter { key(it.provider, it.model) in prices }
            if (priced.isEmpty()) return null
            return priced.sumOf { prices.getValue(key(it.provider, it.model)).cost(it.usage) }
        }
    }
}

/** Records the usage of every request made through [inner]; estimates it when the provider doesn't say. */
class MeteredLlm(private val inner: LlmClient, private val ledger: UsageLedger, private val clock: Clock = Clock.System) : LlmClient {
    override val config get() = inner.config

    override suspend fun complete(request: LlmRequest): String = metered(request) { inner.complete(request) }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String = metered(request) { inner.stream(request, onText) }

    override suspend fun listModels(): List<String> = inner.listModels()

    private suspend fun metered(request: LlmRequest, block: suspend () -> String): String {
        val collector = UsageCollector()
        val text = withContext(collector) { block() }
        val usage = collector.usage ?: estimate(request, text)
        val tag = coroutineContext[UsageTag]
        runCatching {
            ledger.record(UsageRecord(clock.now(), config.id.name, config.chatModel, tag?.meetingId, tag?.purpose.orEmpty(), usage))
        }
        return text
    }

    private fun estimate(request: LlmRequest, answer: String): TokenUsage {
        val input = request.system.length + request.messages.sumOf { it.content.length }
        return TokenUsage(input / CHARS_PER_TOKEN, answer.length / CHARS_PER_TOKEN.toLong(), estimated = true)
    }

    private companion object {
        /** A common rule of thumb for English text. */
        const val CHARS_PER_TOKEN = 4L
    }
}

/** Reads token counts from the providers' replies. */
internal object UsageParsing {
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    /** OpenAI Chat Completions `usage`, including cached prompt tokens (OpenAI, DeepSeek and others). */
    fun openAi(reply: JsonObject): TokenUsage? {
        val u = reply["usage"] as? JsonObject ?: return null
        val input = u.long("prompt_tokens") ?: return null
        val cached = (u["prompt_tokens_details"] as? JsonObject)?.long("cached_tokens") ?: u.long("prompt_cache_hit_tokens") ?: 0
        return TokenUsage(input, u.long("completion_tokens") ?: 0, cached)
    }

    /** Anthropic `usage`: input excludes tokens read from or written to the cache, which are added here. */
    fun anthropic(usage: JsonElement?): TokenUsage? {
        val u = usage as? JsonObject ?: return null
        val read = u.long("cache_read_input_tokens") ?: 0
        val created = u.long("cache_creation_input_tokens") ?: 0
        val input = (u.long("input_tokens") ?: 0) + read + created
        return TokenUsage(input, u.long("output_tokens") ?: 0, read)
    }

    /** Ollama's counts on a finished reply. */
    fun ollama(reply: JsonObject): TokenUsage? {
        val input = reply.long("prompt_eval_count") ?: return null
        return TokenUsage(input, reply.long("eval_count") ?: 0)
    }
}

/** Counts the requests made through [inner] as [tag] (a meeting and a purpose). */
class TaggedLlm(private val inner: LlmClient, private val tag: UsageTag) : LlmClient {
    override val config get() = inner.config

    override suspend fun complete(request: LlmRequest): String = withContext(tag) { inner.complete(request) }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String = withContext(tag) { inner.stream(request, onText) }

    override suspend fun listModels(): List<String> = inner.listModels()
}
