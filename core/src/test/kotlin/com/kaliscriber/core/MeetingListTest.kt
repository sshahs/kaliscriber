package com.kaliscriber.core

import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.library.MeetingList
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class MeetingListTest {
    private val today = LocalDate.of(2026, 10, 15) // a Thursday
    private fun at(day: LocalDate, hour: Int = 10) = day.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun meeting(id: String, day: LocalDate, vararg people: String, status: MeetingStatus = MeetingStatus.READY) = Meeting(
        id, id, at(day), status = status,
        speakers = people.mapIndexed { i, p -> Speaker("s$i", p, isPlaceholder = p.startsWith("~")) },
    )

    @Test
    fun `meetings are grouped by when they took place`() {
        Locale.setDefault(Locale.ENGLISH)
        val list = listOf(
            meeting("a", today), meeting("b", today.minusDays(1)), meeting("c", today.minusDays(3)),
            meeting("d", today.minusDays(5)), meeting("e", today.minusDays(14)), meeting("f", LocalDate.of(2025, 12, 1)),
            meeting("g", today, ),
        )
        val groups = MeetingList.grouped(list, at(today, 18), ZoneOffset.UTC)
        assertEquals(
            listOf("Today" to listOf("a", "g"), "Yesterday" to listOf("b"), "Earlier this week" to listOf("c"),
                "Last week" to listOf("d"), "October" to listOf("e"), "December 2025" to listOf("f")),
            groups.map { (h, ms) -> h to ms.map { it.id }.sorted() },
        )
    }

    @Test
    fun `filters narrow the list by attention, person and format`() {
        val list = listOf(
            meeting("standup", today, "Priya", "Sam").copy(title = "Daily standup", autoTitle = false),
            meeting("failed", today, "Priya", status = MeetingStatus.ERROR),
            meeting("waiting", today, "~Amber Falcon").copy(pendingChunks = listOf(AudioChunk(0, "x", 0, 1))),
            meeting("one", today, "Sam").copy(title = "1:1 with Sam", autoTitle = false),
        )
        assertEquals(listOf("failed", "waiting"), MeetingList.filter(list, MeetingList.Filter(needsAttention = true)).map { it.id })
        assertEquals(listOf("standup", "one"), MeetingList.filter(list, MeetingList.Filter(person = "sam")).map { it.id })
        assertEquals(listOf("standup"), MeetingList.filter(list, MeetingList.Filter(type = MeetingType.STANDUP)).map { it.id })
        assertEquals(list, MeetingList.filter(list, MeetingList.Filter()))
        assertEquals(listOf("Priya", "Sam"), MeetingList.people(list), "placeholders are not people")
        assertEquals(listOf(MeetingType.GENERAL, MeetingType.ONE_ON_ONE, MeetingType.STANDUP), MeetingList.types(list))
    }
}
