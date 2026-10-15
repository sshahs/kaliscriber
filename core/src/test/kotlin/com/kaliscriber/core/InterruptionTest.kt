package com.kaliscriber.core

import com.kaliscriber.core.audio.InterruptionTracker
import com.kaliscriber.core.audio.InterruptionTracker.Event
import com.kaliscriber.core.model.Interruption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InterruptionTest {
    /** Feeds 100 ms blocks; [silent] says which ones are exact zeros or reported silenced. */
    private fun run(tracker: InterruptionTracker, blocks: Int, zero: (Int) -> Boolean, silenced: (Int) -> Boolean = { false }): List<Event> =
        (0 until blocks).mapNotNull { i -> tracker.block(i * 100L, (i + 1) * 100L, zero(i), silenced(i)) { "a phone call" } }

    @Test
    fun `Android saying the app is silenced marks a gap at once`() {
        val events = run(InterruptionTracker(), 50, zero = { it in 10..29 }, silenced = { it in 10..29 })
        assertEquals(listOf(Event.Started(1_000, "a phone call"), Event.Ended(Interruption(1_000, 2_000, "a phone call"))), events)
    }

    @Test
    fun `long exact silence counts from where it began, a short one doesn't`() {
        val events = run(InterruptionTracker(zeroRunMs = 2_000), 80, zero = { it in 5..9 || it in 30..59 })
        assertEquals(listOf(Event.Started(3_000, "a phone call"), Event.Ended(Interruption(3_000, 3_000, "a phone call"))), events)
    }

    @Test
    fun `an interruption still going when recording stops is closed`() {
        val tracker = InterruptionTracker()
        run(tracker, 20, zero = { false }, silenced = { it >= 15 })
        assertTrue(tracker.interrupted)
        assertEquals(Interruption(1_500, 500, "a phone call"), tracker.finish(2_000))
        assertNull(tracker.finish(2_000))
    }

    @Test
    fun `exact zeros are told apart from quiet sound`() {
        assertTrue(InterruptionTracker.allZero(ByteArray(10), 10))
        assertEquals(false, InterruptionTracker.allZero(byteArrayOf(0, 0, 1, 0), 4))
        assertEquals(false, InterruptionTracker.allZero(ByteArray(0), 0))
    }
}
