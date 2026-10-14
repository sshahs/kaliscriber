package com.kaliscriber.core

import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.model.WordTiming
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.speakers.SpeakerClusters
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SpeakerSplittingTest {

    private fun words(vararg pairs: Pair<Long, String>) = pairs.map { (ms, text) -> WordTiming(ms, text) }

    private fun segment(start: Long, end: Long, words: List<WordTiming>?) = TranscriptSegment(
        id = "seg$start",
        startMs = start,
        endMs = end,
        text = words?.joinToString("") { it.text }?.trim() ?: "no word timings here",
        words = words,
    )

    @Test
    fun `a segment holding two speakers is split where the voice changes`() {
        val seg = segment(
            0, 6_000,
            words(0L to "Can", 300L to " you", 500L to " send", 800L to " the", 1_000L to " deck?",
                2_500L to " Sure,", 2_900L to " I'll", 3_200L to " do", 3_400L to " it", 3_600L to " today."),
        )
        val turns = listOf(SpeakerTurn(0, 2_200, "0"), SpeakerTurn(2_400, 6_000, "1"))
        val result = SpeakerAssigner.applyTurns(listOf(seg), turns, Random(1))
        assertEquals(listOf("Can you send the deck?", "Sure, I'll do it today."), result.segments.map { it.text })
        assertEquals(2, result.speakers.size)
        assertEquals(listOf(0L, 2_500L), result.segments.map { it.startMs })
        assertEquals(listOf(2_500L, 6_000L), result.segments.map { it.endMs })
        assertEquals(result.segments.map { it.speakerId }.distinct().size, 2)
    }

    @Test
    fun `a single stray word is not split off`() {
        val seg = segment(
            0, 4_000,
            words(0L to "We", 300L to " should", 600L to " ship", 900L to " it", 1_200L to " now", 1_500L to " because", 1_900L to " customers", 2_400L to " wait."),
        )
        // Diarization wobbles for one word in the middle.
        val turns = listOf(SpeakerTurn(0, 1_150, "0"), SpeakerTurn(1_150, 1_450, "1"), SpeakerTurn(1_450, 4_000, "0"))
        val result = SpeakerAssigner.applyTurns(listOf(seg), turns, Random(1))
        assertEquals(1, result.segments.size)
        assertEquals(seg.text, result.segments.single().text)
        assertEquals(1, result.speakers.size)
    }

    @Test
    fun `segments without word timings keep the most overlapping speaker`() {
        val seg = segment(0, 5_000, null)
        val turns = listOf(SpeakerTurn(0, 1_000, "0"), SpeakerTurn(1_000, 5_000, "1"))
        val result = SpeakerAssigner.applyTurns(listOf(seg), turns, Random(1))
        assertEquals(1, result.segments.size)
        assertEquals("diar:1", result.speakers.single().label)
    }

    @Test
    fun `word timings are shifted with the chunk offset`() {
        val raw = RawSegment(1_000, 2_000, " Hello there", words = words(1_000L to " Hello", 1_400L to " there"))
        val seg = SpeakerAssigner.assign(emptyList(), listOf(raw), offsetMs = 60_000).segments.single()
        assertEquals(listOf(61_000L, 61_400L), seg.words!!.map { it.startMs })
    }

    @Test
    fun `block speakers with matching voices become one speaker`() {
        val alice = floatArrayOf(1f, 0.1f, 0f)
        val bob = floatArrayOf(0f, 1f, 0.1f)
        val items = listOf(
            SpeakerClusters.Item(0, alice), SpeakerClusters.Item(0, bob),
            SpeakerClusters.Item(1, floatArrayOf(0.1f, 1f, 0f)), SpeakerClusters.Item(1, floatArrayOf(0.95f, 0f, 0.1f)),
        )
        assertContentEquals(intArrayOf(0, 1, 1, 0), SpeakerClusters.merge(items))
    }

    @Test
    fun `speakers in the same block stay apart unless a target forces it`() {
        val a = floatArrayOf(1f, 0f)
        val b = floatArrayOf(0.9f, 0.3f)
        val items = listOf(SpeakerClusters.Item(0, a), SpeakerClusters.Item(0, b))
        assertContentEquals(intArrayOf(0, 1), SpeakerClusters.merge(items))
        assertContentEquals(intArrayOf(0, 0), SpeakerClusters.merge(items, target = 1))
    }

    @Test
    fun `a target count merges the most similar speakers first`() {
        val items = listOf(
            SpeakerClusters.Item(0, floatArrayOf(1f, 0f, 0f)),
            SpeakerClusters.Item(1, floatArrayOf(0f, 1f, 0f)),
            SpeakerClusters.Item(2, floatArrayOf(0f, 0f, 1f)),
            SpeakerClusters.Item(3, floatArrayOf(0.2f, 0f, 1f)),
        )
        assertContentEquals(intArrayOf(0, 1, 2, 2), SpeakerClusters.merge(items, target = 3))
    }
}
