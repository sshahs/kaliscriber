package com.kaliscriber.core.calendar

import com.kaliscriber.core.ai.CustomFormat
import com.kaliscriber.core.ai.MeetingType
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/** A recording the user scheduled in the app, from [start] to [end] (epoch ms of the first time). */
@Serializable
data class ScheduledRecording(
    val id: String,
    val title: String,
    val start: Long,
    val end: Long,
    val repeat: Repeat = Repeat.NONE,
    /** For [Repeat.CUSTOM]: ISO days of the week, 1 (Monday) to 7 (Sunday). */
    val days: List<Int> = emptyList(),
    /** The summary format to write the notes in; null (with no [formatId]) guesses it from the title. */
    val type: MeetingType? = null,
    /** One of the user's own summary formats, instead of [type]. */
    val formatId: String? = null,
    /** How many people take part, for telling speakers apart; null detects it. */
    val expectedSpeakers: Int? = null,
    /** A notification this many minutes before it starts; 0 for none. */
    val remindMinutes: Int = 0,
    /** What the meeting is about, kept as a note with the recording. */
    val agenda: String = "",
    /** Paused schedules are kept but don't record. */
    val enabled: Boolean = true,
) {
    @Serializable
    enum class Repeat(val label: String) {
        NONE("Once"),
        DAILY("Every day"),
        WEEKDAYS("Every weekday"),
        WEEKLY("Every week"),
        CUSTOM("On chosen days"),
    }
}

/** Turns the user's scheduled recordings into occurrences that auto-record treats like calendar events. */
object ScheduledRecordings {
    /** Uid prefix that marks an occurrence as one the user scheduled, recorded whatever the calendar settings. */
    const val UID_PREFIX = "app:"

    fun isScheduled(event: CalendarEvent): Boolean = event.uid.startsWith(UID_PREFIX)

    /** The schedule an occurrence came from. */
    fun scheduleFor(event: CalendarEvent, list: List<ScheduledRecording>): ScheduledRecording? =
        if (!isScheduled(event)) null else list.firstOrNull { UID_PREFIX + it.id == event.uid }

    /** Occurrences overlapping [from]..[to]; repeats keep their wall-clock time across daylight-saving changes. */
    fun occurrences(list: List<ScheduledRecording>, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> =
        list.filter { it.enabled }.flatMap { s -> occurrencesOf(s, from, to, zone) }.sortedBy { it.start }

    private fun occurrencesOf(s: ScheduledRecording, from: Long, to: Long, zone: ZoneId): List<CalendarEvent> {
        val length = (s.end - s.start).coerceAtLeast(0)
        val first = Instant.ofEpochMilli(s.start).atZone(zone)
        val starts = if (s.repeat == ScheduledRecording.Repeat.NONE) {
            listOf(s.start)
        } else {
            // From a day before the window (an occurrence may still be running) to its end.
            var day = maxOf(first.toLocalDate(), date(from, zone).minusDays(1))
            val last = date(to, zone)
            buildList {
                while (!day.isAfter(last)) {
                    if (repeatsOn(s, first.dayOfWeek, day.dayOfWeek)) add(day.atTime(first.toLocalTime()).atZone(zone).toInstant().toEpochMilli())
                    day = day.plusDays(1)
                }
            }
        }
        return starts.filter { it + length > from && it < to }.map { start ->
            CalendarEvent(uid = UID_PREFIX + s.id, title = s.title.ifBlank { "Scheduled recording" }, start = start, end = start + length)
        }
    }

    /** The next time [schedule] records (even when paused), or null when a one-off is over. */
    fun next(schedule: ScheduledRecording, now: Long, zone: ZoneId = ZoneId.systemDefault()): CalendarEvent? =
        occurrencesOf(schedule, now, now + 8 * DAY_MS, zone).firstOrNull { it.end > now }

    /** When to remind about [event] before it starts, or null for no reminder. */
    fun reminderAt(event: CalendarEvent, list: List<ScheduledRecording>): Long? =
        scheduleFor(event, list)?.remindMinutes?.takeIf { it > 0 }?.let { event.start - it * 60_000L }

    /** One-offs that ended over a day ago are dropped. */
    fun pruned(list: List<ScheduledRecording>, now: Long): List<ScheduledRecording> =
        list.filter { it.repeat != ScheduledRecording.Repeat.NONE || it.end > now - DAY_MS }

    /** "Every Monday", "Mon, Wed and Fri", "Every weekday"…; empty for a one-off. */
    fun repeatLabel(s: ScheduledRecording, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val firstDay = Instant.ofEpochMilli(s.start).atZone(zone).dayOfWeek
        return when (s.repeat) {
            ScheduledRecording.Repeat.NONE -> ""
            ScheduledRecording.Repeat.WEEKLY -> "Every ${firstDay.getDisplayName(TextStyle.FULL, locale)}"
            ScheduledRecording.Repeat.CUSTOM -> {
                val names = customDays(s, firstDay).sorted().map { DayOfWeek.of(it).getDisplayName(TextStyle.SHORT, locale) }
                when (names.size) {
                    7 -> ScheduledRecording.Repeat.DAILY.label
                    1 -> "Every ${DayOfWeek.of(customDays(s, firstDay).first()).getDisplayName(TextStyle.FULL, locale)}"
                    else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
                }
            }
            else -> s.repeat.label
        }
    }

    /** The format's name for showing: one of [custom], a built-in one, or null for automatic. */
    fun formatLabel(s: ScheduledRecording, custom: List<CustomFormat>): String? =
        s.formatId?.let { id -> custom.firstOrNull { it.id == id }?.name } ?: s.type?.label

    private fun customDays(s: ScheduledRecording, firstDay: DayOfWeek): List<Int> =
        s.days.filter { it in 1..7 }.distinct().ifEmpty { listOf(firstDay.value) }

    private fun repeatsOn(s: ScheduledRecording, firstDay: DayOfWeek, day: DayOfWeek): Boolean = when (s.repeat) {
        ScheduledRecording.Repeat.NONE -> false
        ScheduledRecording.Repeat.DAILY -> true
        ScheduledRecording.Repeat.WEEKLY -> day == firstDay
        ScheduledRecording.Repeat.WEEKDAYS -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
        ScheduledRecording.Repeat.CUSTOM -> day.value in customDays(s, firstDay)
    }

    private fun date(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private const val DAY_MS = 24L * 3_600_000
}
