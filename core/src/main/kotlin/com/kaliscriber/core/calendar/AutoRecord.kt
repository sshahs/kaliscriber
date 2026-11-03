package com.kaliscriber.core.calendar

import kotlinx.serialization.Serializable

/**
 * Recording calendar meetings by themselves: which events to record, and at any moment whether to
 * start, stop or leave the recorder alone, and when to look again.
 */
object AutoRecord {
    /** Which calendar events are recorded. */
    @Serializable
    enum class Which(val label: String) {
        ALL("Every timed event"),
        WITH_OTHERS("Only events with other people invited"),
    }

    /** Shorter events are reminders rather than meetings. */
    const val MIN_MS = 5 * 60_000L

    /** Longer events are blocks of time (a conference day, "out of office") rather than meetings. */
    const val MAX_MS = 8 * 3_600_000L

    /** A meeting joined this close to its end isn't worth starting. */
    const val MIN_LEFT_MS = 2 * 60_000L

    /** One occurrence of an event (repeating events share their uid). */
    fun key(event: CalendarEvent): String = "${event.uid}@${event.start}"

    /** The recording auto-record started, and when it should stop. */
    @Serializable
    data class Active(
        val key: String,
        val meetingId: String,
        val title: String,
        val end: Long,
        /** False once the user chose to keep recording past the end. */
        val stopAtEnd: Boolean = true,
    )

    /** What auto-record remembers between alarms. */
    @Serializable
    data class State(
        val active: Active? = null,
        /** Occurrences already started (or failed to), so stopping one by hand doesn't restart it. */
        val handled: Map<String, Long> = emptyMap(),
        /** Occurrences the user said not to record. */
        val skipped: Map<String, Long> = emptyMap(),
        /** The latest events read, used when an alarm fires and the calendar can't be read quickly. */
        val events: List<CalendarEvent> = emptyList(),
        /** Occurrences already announced with a reminder. */
        val reminded: Map<String, Long> = emptyMap(),
    ) {
        /** Forgets occurrences that ended more than a day before [now]. */
        fun pruned(now: Long): State {
            val cutoff = now - 24 * 3_600_000L
            fun Map<String, Long>.recent() = filterValues { it > cutoff }
            return copy(
                handled = handled.recent(),
                skipped = skipped.recent(),
                reminded = reminded.recent(),
                events = events.filter { it.end > cutoff },
            )
        }
    }

    fun wanted(event: CalendarEvent, which: Which, state: State): Boolean {
        // Recordings the user scheduled in the app are always wanted, whatever their length.
        if (ScheduledRecordings.isScheduled(event)) return event.end > event.start && key(event) !in state.skipped
        if (event.allDay) return false
        val length = event.end - event.start
        if (length < MIN_MS || length > MAX_MS) return false
        if (which == Which.WITH_OTHERS && event.attendees.size < 2) return false
        return key(event) !in state.skipped
    }

    /** What the recorder is doing, as far as auto-record cares. */
    enum class Recorder { IDLE, AUTO, MANUAL }

    sealed interface Decision {
        data class Start(val event: CalendarEvent) : Decision
        data object Stop : Decision
        data object Nothing : Decision
    }

    /**
     * What to do at [now]. An auto recording stops at its event's end (unless the user kept it
     * going); with nothing being recorded, a wanted event in progress starts. Recordings the user
     * started are never touched.
     */
    fun decide(events: List<CalendarEvent>, now: Long, which: Which, state: State, recorder: Recorder): Decision {
        val active = state.active
        if (recorder == Recorder.AUTO && active != null) {
            return if (active.stopAtEnd && now >= active.end) Decision.Stop else Decision.Nothing
        }
        if (recorder == Recorder.MANUAL) return Decision.Nothing
        // The latest to start among those in progress: the meeting you've most likely just joined.
        val due = events
            .filter { wanted(it, which, state) && key(it) !in state.handled && it.start <= now && now < it.end - MIN_LEFT_MS }
            .maxByOrNull { it.start }
        return due?.let { Decision.Start(it) } ?: Decision.Nothing
    }

    /** When to look again: the active recording's end, or the next wanted event's start. Null for never. */
    fun nextWake(events: List<CalendarEvent>, now: Long, which: Which, state: State, recorder: Recorder): Long? {
        val stopAt = state.active?.takeIf { recorder == Recorder.AUTO && it.stopAtEnd }?.end?.takeIf { it > now }
        val nextStart = events
            .filter { wanted(it, which, state) && key(it) !in state.handled && it.start > now }
            .minOfOrNull { it.start }
        return listOfNotNull(stopAt, nextStart).minOrNull()
    }

    /** The next occurrence auto-record will start, for showing (and skipping) ahead of time. */
    fun upcoming(events: List<CalendarEvent>, now: Long, which: Which, state: State): CalendarEvent? =
        events.filter { wanted(it, which, state) && key(it) !in state.handled && it.end - MIN_LEFT_MS > now }
            .minByOrNull { it.start }
}
