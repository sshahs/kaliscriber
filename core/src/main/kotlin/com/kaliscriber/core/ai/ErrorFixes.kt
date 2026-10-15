package com.kaliscriber.core.ai

/** What someone can do about an error, so the app can offer it as a button. */
enum class ErrorFix {
    /** Add or correct an API key. */
    API_KEY,

    /** Check the provider's model name or base URL. */
    MODEL,

    /** Download (or download again) an on-device speech model. */
    SPEECH_MODEL,

    /** The provider is rate limiting or out of credit: wait, top up or switch provider. */
    CREDIT,

    /** The network failed or a request timed out: try again. */
    CONNECTION,
}

/** Reads what fixes an error from its message (see [httpError], [networkError] and the app's own messages). */
object ErrorFixes {
    fun of(message: String?): ErrorFix? {
        val m = message?.lowercase() ?: return null
        return when {
            "api key" in m || Regex("""error 40[13]\b""").containsMatchIn(m) -> ErrorFix.API_KEY
            "on-device speech model" in m || ("could not load" in m && "download" in m) -> ErrorFix.SPEECH_MODEL
            Regex("""error 404\b""").containsMatchIn(m) || "no chat model set" in m || "no transcription model set" in m ||
                "zero-data-retention" in m -> ErrorFix.MODEL
            Regex("""error 429\b""").containsMatchIn(m) || "out of credit" in m || "rate limited" in m -> ErrorFix.CREDIT
            "couldn't find" in m || "couldn't connect" in m || "timed out" in m || "took too long" in m ||
                "could not reach" in m -> ErrorFix.CONNECTION
            else -> null
        }
    }
}
