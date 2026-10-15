package com.kaliscriber.core

import com.kaliscriber.core.ai.tts.SentenceStream
import kotlin.test.Test
import kotlin.test.assertEquals

class SentenceStreamTest {
    @Test
    fun `sentences are released as soon as they are complete`() {
        val answer = "Prices go up ten percent in November. Dr. Lee agreed, e.g. for new customers. Ok. **Next**, the launch moves to May!"
        val stream = SentenceStream()
        val spoken = mutableListOf<String>()
        // The answer arrives a few characters at a time.
        for (end in 5..answer.length step 7) spoken += stream.feed(answer.substring(0, end))
        spoken += stream.feed(answer)
        spoken += stream.finish(answer)
        assertEquals(
            listOf(
                "Prices go up ten percent in November.",
                "Dr. Lee agreed, e.g. for new customers.",
                "Ok. Next, the launch moves to May!",
            ),
            spoken,
        )
    }

    @Test
    fun `list items and a last line without a full stop are spoken too`() {
        val answer = "Open items:\n- Send the price list\n- Book the venue"
        val stream = SentenceStream(minChars = 1)
        val spoken = stream.feed(answer) + stream.finish(answer)
        assertEquals(listOf("Open items:", "Send the price list.", "Book the venue."), spoken)
    }
}
