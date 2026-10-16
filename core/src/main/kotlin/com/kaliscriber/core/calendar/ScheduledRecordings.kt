package com.kaliscriber.core.calendar

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A recording the user scheduled in the app, from [start] to [end] (epoch ms of the first time). */
@Serializable
data class ScheduledRecording(
    val id: String,
    val title: String,
    val start: Long,
    val end: Long,
    val repeat: Repeat = Repeat.NONE,
) {
    @Serializable
    enum class Repeat(val label: String) {
        NONE("Once"),
        WEEKDAYS("Every weekday"),
        WEEKLY("Every week"),
    }
}

/** Turns the user's scheduled recordings into occurrences that auto-record treats like calendar events. */
object ScheduledRecordings {
    /** Uid prefix that marks an occurrence as one the user scheduled, recorded whatever the calendar settings. */
    const val UID_PREFIX = "app:"

    fun isScheduled(event: CalendarEvent): Boolean = event.uid.startsWith(UID_PREFIX)

    /** Occurrences overlapping [from]..[to]; repeats keep their wall-clock time across daylight-saving changes. */
    fun occurrences(list: List<ScheduledRecording>, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> =
        list.flatMap { s ->
            val length = (s.end - s.start).coerceAtLeast(0)
            val first = Instant.ofEpochMilli(s.start).atZone(zone)
            val starts = when (s.repeat) {
                ScheduledRecording.Repeat.NONE -> listOf(s.start)
                else -> {
                    // From a day before the window (an occurrence may still be running) to its end.
                    var day = maxOf(first.toLocalDate(), date(from, zone).minusDays(1))
                    val last = date(to, zone)
                    buildList {
                        while (!day.isAfter(last)) {
                            if (repeatsOn(s.repeat, first.dayOfWeek, day.dayOfWeek)) {
                                add(day.atTime(first.toLocalTime()).atZone(zone).toInstant().toEpochMilli())
                            }
                            day = day.plusDays(1)
                        }
                    }
                }
            }
            starts.filter { it + length > from && it < to }.map { start ->
                CalendarEvent(uid = UID_PREFIX + s.id, title = s.title.ifBlank { "Scheduled recording" }, start = start, end = start + length)
            }
        }.sortedBy { it.start }

    /** The next time [schedule] records, or null when a one-off is over. */
    fun next(schedule: ScheduledRecording, now: Long, zone: ZoneId = ZoneId.systemDefault()): CalendarEvent? =
        occurrences(listOf(schedule), now, now + 8 * DAY_MS, zone).firstOrNull { it.end > now }

    /** One-offs that ended over a day ago are dropped. */
    fun pruned(list: List<ScheduledRecording>, now: Long): List<ScheduledRecording> =
        list.filter { it.repeat != ScheduledRecording.Repeat.NONE || it.end > now - DAY_MS }

    private fun repeatsOn(repeat: ScheduledRecording.Repeat, firstDay: DayOfWeek, day: DayOfWeek): Boolean = when (repeat) {
        ScheduledRecording.Repeat.NONE -> false
        ScheduledRecording.Repeat.WEEKLY -> day == firstDay
        ScheduledRecording.Repeat.WEEKDAYS -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY
    }

    private fun date(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private const val DAY_MS = 24L * 3_600_000
}
