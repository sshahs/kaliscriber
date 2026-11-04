package com.kaliscriber.core.ai

import com.kaliscriber.core.ai.llm.LlmClient
import com.kaliscriber.core.ai.llm.LlmRequest
import kotlinx.serialization.Serializable

/** What is masked in text sent to the AI. Off by default. */
@Serializable
data class RedactionSettings(
    val enabled: Boolean = false,
    val emails: Boolean = true,
    val phones: Boolean = true,
    val cards: Boolean = true,
    /**
     * People and other words to hide, one per line. Variants of the same name go on one line,
     * separated by commas ("Priya Sharma, Priya"); they share a placeholder and come back as the first.
     */
    val names: String = "",
) {
    val active: Boolean get() = enabled && (emails || phones || cards || Redaction.nameGroups(names).isNotEmpty())
}

/**
 * Masks private details in text before it goes to the AI, and puts them back in what comes back.
 * Each distinct value gets a numbered placeholder such as `[EMAIL_1]` or `[NAME_2]`, kept for the
 * life of this object, so one request (and the answer to it) uses the same placeholder throughout.
 * Numbering follows the order values first appear, so requests that start with the same meeting
 * text mask it the same way and providers can still cache it.
 */
class Redaction(private val settings: RedactionSettings) {
    private val toPlaceholder = LinkedHashMap<String, String>()
    private val toValue = HashMap<String, String>()
    private val counts = HashMap<Kind, Int>()
    private val names: List<List<String>> = nameGroups(settings.names)

    /** Name variants (longest first, so "Priya Sharma" is masked before "Priya") with their group. */
    private val nameVariants: List<Pair<String, Int>> = names
        .flatMapIndexed { group, variants -> variants.map { it to group } }
        .sortedByDescending { it.first.length }
    private val nameRegex: Regex? = nameVariants.takeIf { it.isNotEmpty() }?.let { list ->
        Regex(list.joinToString("|", prefix = "(?<![\\p{L}\\p{N}])(?:", postfix = ")(?![\\p{L}\\p{N}])") { Regex.escape(it.first) }, RegexOption.IGNORE_CASE)
    }

    enum class Kind { EMAIL, PHONE, CARD, NAME }

    /** How many values of each kind were masked so far. */
    fun masked(): Map<Kind, Int> = counts.toMap()

    fun redact(text: String): String {
        if (!settings.enabled || text.isEmpty()) return text
        var out = text
        if (settings.emails) out = EMAIL.replace(out) { placeholder(Kind.EMAIL, it.value.lowercase(), it.value) }
        if (settings.cards) {
            out = CARD.replace(out) { m ->
                val digits = m.value.filter(Char::isDigit)
                if (digits.length in 13..19 && luhn(digits) && !DATE.containsMatchIn(m.value)) placeholder(Kind.CARD, digits, m.value) else m.value
            }
        }
        if (settings.phones) {
            out = PHONE.replace(out) { m ->
                val digits = m.value.count(Char::isDigit)
                if (digits in 9..15 && !DATE.containsMatchIn(m.value) && !isPlaceholder(m.value)) placeholder(Kind.PHONE, m.value.filter { it.isDigit() || it == '+' }, m.value) else m.value
            }
        }
        nameRegex?.let { regex ->
            out = regex.replace(out) { m ->
                val group = nameVariants.first { it.first.equals(m.value, ignoreCase = true) }.second
                placeholder(Kind.NAME, "name#$group", names[group].first())
            }
        }
        return out
    }

    fun redact(request: LlmRequest): LlmRequest {
        if (!settings.enabled) return request
        // The cacheable prefix is masked first: it is the start of the system text, so both then match.
        val prefix = request.cachePrefix?.let(::redact)
        return request.copy(
            system = redact(request.system),
            cachePrefix = prefix,
            messages = request.messages.map { it.copy(content = redact(it.content)) },
        )
    }

    /** [text] with every placeholder this object handed out replaced by its value. */
    fun restore(text: String): String {
        if (toValue.isEmpty()) return text
        return BACK.replace(text) { m -> toValue["${m.groupValues[1].uppercase()}_${m.groupValues[2]}"] ?: m.value }
    }

    /**
     * Like [restore], for an answer still being written: a placeholder cut off at the end ("[NAM")
     * is left out until the rest of it arrives.
     */
    fun restorePartial(text: String): String {
        val cut = PARTIAL_TAIL.find(text)?.range?.first ?: text.length
        return restore(text.substring(0, cut))
    }

    private fun placeholder(kind: Kind, key: String, value: String): String {
        val k = "${kind.name}:$key"
        toPlaceholder[k]?.let { return it }
        val n = (counts[kind] ?: 0) + 1
        counts[kind] = n
        val name = "${kind.name}_$n"
        toPlaceholder[k] = "[$name]"
        toValue[name] = value
        return "[$name]"
    }

    private fun isPlaceholder(s: String) = s.contains('[')

    companion object {
        private val EMAIL = Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
        private val CARD = Regex("(?<![\\d])\\d(?:[ -]?\\d){12,18}(?![\\d])")
        private val PHONE = Regex("(?<![\\w+\\[])(?:\\+|00)?\\(?\\d[\\d ()\\-]{6,}\\d(?![\\w])")
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val BACK = Regex("\\[?\\b(EMAIL|PHONE|CARD|NAME)_(\\d+)\\b]?")
        private val PARTIAL_TAIL = Regex("\\[[A-Z_0-9]{0,8}$")

        /** The groups of name variants in [names]: one group per line, variants split by commas. */
        fun nameGroups(names: String): List<List<String>> = names.lines()
            .map { line -> line.split(',').map { it.trim() }.filter { it.length >= 2 } }
            .filter { it.isNotEmpty() }

        fun luhn(digits: String): Boolean {
            var sum = 0
            digits.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) {
                    d *= 2
                    if (d > 9) d -= 9
                }
                sum += d
            }
            return sum % 10 == 0
        }
    }
}

/**
 * Sends every request through a fresh [Redaction] and restores the details in the answer, so what
 * the app shows and saves has the real values while the AI only sees placeholders.
 */
class RedactingLlm(private val inner: LlmClient, private val settings: RedactionSettings) : LlmClient {
    override val config: ProviderConfig get() = inner.config

    override suspend fun complete(request: LlmRequest): String {
        val r = Redaction(settings)
        return r.restore(inner.complete(r.redact(request).withRule()))
    }

    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String {
        val r = Redaction(settings)
        return r.restore(inner.stream(r.redact(request).withRule()) { onText(r.restorePartial(it)) })
    }

    override suspend fun listModels(): List<String> = inner.listModels()

    /** Tells the model what the placeholders are, after the cacheable part so caching still works. */
    private fun LlmRequest.withRule(): LlmRequest =
        if (!PLACEHOLDER.containsMatchIn(system + messages.joinToString { it.content })) this
        else copy(system = "$system\n\n$RULE")

    private companion object {
        val PLACEHOLDER = Regex("\\[(EMAIL|PHONE|CARD|NAME)_\\d+]")
        const val RULE = "Some private details were replaced with placeholders like [NAME_1], [EMAIL_1] or [PHONE_1] before you received the text. " +
            "Treat each as the person or detail it stands for and write placeholders exactly as given (including the brackets) wherever you refer to them; never guess what they hide."
    }
}
