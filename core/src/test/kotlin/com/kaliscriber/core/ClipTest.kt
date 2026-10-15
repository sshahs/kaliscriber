package com.kaliscriber.core

import com.kaliscriber.core.clip.Clip
import com.kaliscriber.core.clip.Clips
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipTest {
    private val meeting = Meeting(
        "m", "Roadmap / Q3", 0, durationMs = 120_000,
        speakers = listOf(Speaker("p", "Priya", isPlaceholder = false)),
        segments = listOf(
            TranscriptSegment("a", 0, 9_000, "Welcome.", "p"),
            TranscriptSegment("b", 50_000, 58_000, " We ship in June. ", "p"),
            TranscriptSegment("c", 60_000, 64_000, "Agreed.", null),
            TranscriptSegment("d", 100_000, 110_000, "Bye.", "p"),
        ),
    )

    @Test
    fun `clips cover the moment, stay inside the recording and stay short`() {
        assertEquals(Clip(35_000, 75_000), Clips.around(55_000, 120_000))
        assertEquals(Clip(0, 25_000), Clips.around(5_000, 120_000))
        assertEquals(Clip(95_000, 120_000), Clips.around(115_000, 120_000))
        assertEquals(Clip(30_000, 70_000), Clips.forBookmark(60_000, 120_000))
        assertEquals(Clip(48_500, 65_500), Clips.forLines(meeting.segments.subList(1, 3), 120_000))
        assertNull(Clips.forLines(emptyList(), 120_000))
        assertEquals(Clips.MAX_MS, Clips.forLines(listOf(TranscriptSegment("x", 0, 600_000, "long")), 0)!!.durationMs)
    }

    @Test
    fun `the text says where the clip is from and what was said`() {
        val clip = Clips.around(55_000, 120_000)
        assertEquals("Roadmap / Q3 (00:35–01:15)\n\nPriya: We ship in June.\nAgreed.", Clips.text(meeting, clip))
        assertEquals("Roadmap-Q3-00.35.m4a", Clips.fileName(meeting, clip, "m4a"))
    }
}
