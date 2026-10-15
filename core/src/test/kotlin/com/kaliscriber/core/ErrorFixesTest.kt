package com.kaliscriber.core

import com.kaliscriber.core.ai.ErrorFix
import com.kaliscriber.core.ai.ErrorFixes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ErrorFixesTest {
    @Test
    fun `errors the app shows lead to their fix`() {
        val cases = mapOf(
            "Add your OpenAI API key in Settings to use it." to ErrorFix.API_KEY,
            "Summary: Anthropic Claude error 401: invalid x-api-key. Check the API key for Anthropic Claude in Settings." to ErrorFix.API_KEY,
            "Download an on-device speech model in Settings → On-device speech first." to ErrorFix.SPEECH_MODEL,
            "Could not load Parakeet v3: bad file. Try deleting and downloading it again." to ErrorFix.SPEECH_MODEL,
            "OpenAI error 404: model not found. Check the base URL and model name for OpenAI." to ErrorFix.MODEL,
            "No chat model set for Ollama Cloud" to ErrorFix.MODEL,
            "Command Code error 422: no route. This model has no zero-data-retention route at Command Code." to ErrorFix.MODEL,
            "Gaps & questions: DeepSeek error 429. Rate limited or out of credit at DeepSeek." to ErrorFix.CREDIT,
            "Couldn't find api.openai.com (no internet, or DNS couldn't resolve it)." to ErrorFix.CONNECTION,
            "Connecting to Groq timed out. Check your connection and try again." to ErrorFix.CONNECTION,
            "OpenAI took too long to answer. Try again, or pick a faster model." to ErrorFix.CONNECTION,
        )
        cases.forEach { (message, fix) -> assertEquals(fix, ErrorFixes.of(message), message) }
        assertNull(ErrorFixes.of("No speech was transcribed."))
        assertNull(ErrorFixes.of(null))
        assertNull(ErrorFixes.of("OpenAI error 4041 happened"), "only whole status codes count")
    }
}
