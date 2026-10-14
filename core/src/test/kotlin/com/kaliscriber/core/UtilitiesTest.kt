package com.kaliscriber.core

import com.kaliscriber.core.ai.LlmJson
import com.kaliscriber.core.ai.number
import com.kaliscriber.core.ai.strings
import com.kaliscriber.core.audio.Wav
import com.kaliscriber.core.speakers.SpeakerNames
import com.kaliscriber.core.transcript.TranscriptFormatter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UtilitiesTest {

    @Test
    fun `extracts json from fenced and chatty responses`() {
        val obj = LlmJson.parseObject("Sure! Here you go:\n```json\n{\"a\": \"x } y\", \"b\": [1, {\"c\": 2}]}\n```\nAnything else?")
        assertEquals("x } y", obj.strings("a").single())
    }

    @Test
    fun `tolerant readers handle odd shapes`() {
        val obj = LlmJson.parseObject("""{"keyPoints":[{"text":"one"},"two",null],"confidence":"85%"}""")
        assertEquals(listOf("one", "two"), obj.strings("keyPoints"))
        assertEquals(0.85, obj.number("confidence")!!, 0.0001)
    }

    @Test
    fun `random speaker names are unique`() {
        val taken = mutableListOf<String>()
        repeat(100) { taken += SpeakerNames.random(taken, Random(it)) }
        assertEquals(taken.size, taken.toSet().size)
    }

    @Test
    fun `timestamps format`() {
        assertEquals("00:05", TranscriptFormatter.timestamp(5_400))
        assertEquals("1:01:01", TranscriptFormatter.timestamp(3_661_000))
    }

    @Test
    fun `truncation keeps start and end`() {
        val text = "a".repeat(3000) + "b".repeat(3000)
        val out = TranscriptFormatter.truncateMiddle(text, 2000)
        assertTrue(out.startsWith("aaa") && out.endsWith("bbb") && out.contains("omitted"))
    }

    @Test
    fun `wav header describes pcm data`() {
        val header = ByteBuffer.wrap(Wav.header(32_000, 16_000)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(36 + 32_000, header.getInt(4))
        assertEquals(16_000, header.getInt(24))
        assertEquals(32_000, header.getInt(28))
        assertEquals(32_000, header.getInt(40))
        assertEquals(1_000, Wav.durationMs(32_000, 16_000))
    }
}
