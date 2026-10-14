package com.kaliscriber.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Tolerant helpers for reading JSON written by language models, which often wrap it in code
 * fences or prose, and do not always respect the requested types.
 */
object LlmJson {

    fun parseObject(text: String): JsonObject {
        val candidate = extractJsonObject(text)
            ?: throw AiException("The AI response did not contain JSON: ${text.take(200)}")
        return try {
            AiJson.parseToJsonElement(candidate).jsonObject
        } catch (e: Exception) {
            throw AiException("The AI response was not valid JSON: ${e.message}", cause = e)
        }
    }

    /** Finds the first balanced `{...}` block, honouring strings and escapes. */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }
}

fun JsonObject.str(vararg keys: String): String? {
    for (key in keys) {
        val value = this[key]?.asText()?.trim()
        if (!value.isNullOrEmpty()) return value
    }
    return null
}

fun JsonObject.list(vararg keys: String): List<JsonElement> {
    for (key in keys) {
        val value = this[key]
        if (value is JsonArray) return value
        if (value is JsonPrimitive && value.isString && value.content.isNotBlank()) return listOf(value)
    }
    return emptyList()
}

fun JsonObject.strings(vararg keys: String): List<String> =
    list(*keys).mapNotNull { it.asText()?.trim()?.takeIf(String::isNotEmpty) }

fun JsonObject.number(vararg keys: String): Double? {
    for (key in keys) {
        val p = this[key] as? JsonPrimitive ?: continue
        p.doubleOrNull?.let { return it }
        p.contentOrNull?.trim()?.removeSuffix("%")?.toDoubleOrNull()?.let { return if (it > 1) it / 100 else it }
    }
    return null
}

/** Text of a primitive, or of the most text-like field of an object. */
fun JsonElement.asText(): String? = when (this) {
    is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonObject -> (this["text"] ?: this["name"] ?: this["title"] ?: this["value"] ?: values.firstOrNull())?.asText()
    is JsonArray -> joinToString(", ") { it.asText().orEmpty() }
}
