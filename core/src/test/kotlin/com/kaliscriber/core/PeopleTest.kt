package com.kaliscriber.core

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Highlight
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.people.People
import com.kaliscriber.core.speakers.VoiceProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PeopleTest {
    private val older = Meeting(
        "m1", "Kickoff", createdAt = 1_000,
        speakers = listOf(Speaker("a", "Priya Sharma", isPlaceholder = false), Speaker("b", "Amber Falcon")),
        segments = listOf(TranscriptSegment("s1", 0, 4_000, "Hi", "a"), TranscriptSegment("s2", 4_000, 5_000, "Hey", "b")),
        insights = MeetingInsights(
            actionItems = listOf(
                ActionItem("x", "Draft plan", owner = "Priya", dueDateIso = "2026-10-20"),
                ActionItem("y", "Book room", owner = "Sam and Priya", done = true),
                ActionItem("z", "Tidy up", owner = "Team"),
            ),
            highlights = listOf(Highlight("We ship in November", speaker = "Priya")),
        ),
    )
    private val newer = Meeting(
        "m2", "Review", createdAt = 2_000,
        speakers = listOf(Speaker("c", "priya sharma", isPlaceholder = false)),
        segments = listOf(TranscriptSegment("s3", 0, 2_000, "Done", "c")),
    )

    @Test
    fun `people are gathered across meetings and first names map to full names`() {
        val people = People.directory(listOf(older, newer), listOf(VoiceProfile("v", "Priya Sharma", listOf(1f)), VoiceProfile("w", "Lee", listOf(1f))))
        val priya = people.first()
        assertEquals("Priya Sharma", priya.name)
        assertEquals(listOf("m2", "m1"), priya.meetings.map { it.meetingId })
        assertEquals(6_000L, priya.speakingMs)
        assertEquals(listOf("x"), priya.openItems.map { it.item.id })
        assertEquals(listOf("y"), priya.doneItems.map { it.item.id })
        assertEquals("We ship in November", priya.highlights.single().second.text)
        assertEquals("v", priya.voiceProfileId)

        assertNotNull(people.firstOrNull { it.name == "Sam" })
        assertNull(people.firstOrNull { it.name.equals("Team", true) }, "generic owners are not people")
        assertNull(people.firstOrNull { it.name == "Amber Falcon" }, "placeholder speakers are not people")
        assertEquals("Lee", people.last().name, "people known only by voice come last")
    }
}
