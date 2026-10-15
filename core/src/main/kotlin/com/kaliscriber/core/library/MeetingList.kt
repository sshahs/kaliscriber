package com.kaliscriber.core.library

import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** How the home screen lists meetings: grouped by when they took place, and filtered. */
object MeetingList {
    /** What the list is narrowed to; the defaults show everything. */
    data class Filter(
        val needsAttention: Boolean = false,
        val person: String? = null,
        val type: MeetingType? = null,
    ) {
        val active: Boolean get() = needsAttention || person != null || type != null
    }

    /** Meetings that failed, still have audio to transcribe, or finished with problems. */
    fun needsAttention(m: Meeting): Boolean =
        m.status == MeetingStatus.ERROR || m.pendingChunks.isNotEmpty() || (m.status == MeetingStatus.READY && m.lastError != null)

    fun filter(meetings: List<Meeting>, filter: Filter): List<Meeting> = meetings.filter { m ->
        (!filter.needsAttention || needsAttention(m)) &&
            (filter.person == null || m.speakers.any { !it.isPlaceholder && it.name.equals(filter.person, ignoreCase = true) }) &&
            (filter.type == null || MeetingType.of(m) == filter.type)
    }

    /** Named people across [meetings], those in most meetings first. */
    fun people(meetings: List<Meeting>): List<String> =
        meetings.flatMap { m -> m.speakers.filterNot { it.isPlaceholder }.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct() }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }

    /** Summary formats that [meetings] use, in the order they are declared. */
    fun types(meetings: List<Meeting>): List<MeetingType> {
        val used = meetings.map { MeetingType.of(it) }.toSet()
        return MeetingType.entries.filter { it in used }
    }

    /**
     * [meetings] (newest first) under headings: Today, Yesterday, Earlier this week, Last week,
     * then the month ("September", or "September 2025" for another year).
     */
    fun grouped(meetings: List<Meeting>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, List<Meeting>>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val groups = LinkedHashMap<String, MutableList<Meeting>>()
        for (m in meetings.sortedByDescending { it.createdAt }) {
            groups.getOrPut(heading(Instant.ofEpochMilli(m.createdAt).atZone(zone).toLocalDate(), today)) { mutableListOf() } += m
        }
        return groups.map { it.key to it.value }
    }

    fun heading(day: LocalDate, today: LocalDate): String {
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return when {
            !day.isBefore(today) -> "Today"
            day == today.minusDays(1) -> "Yesterday"
            !day.isBefore(weekStart) -> "Earlier this week"
            !day.isBefore(weekStart.minusWeeks(1)) -> "Last week"
            else -> {
                val month = day.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                if (day.year == today.year) month else "$month ${day.year}"
            }
        }
    }
}
