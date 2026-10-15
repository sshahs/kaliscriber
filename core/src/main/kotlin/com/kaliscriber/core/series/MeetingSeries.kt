package com.kaliscriber.core.series

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting

/**
 * Recurring meetings: occurrences of the same calendar event, or meetings you gave the same title
 * (dates and numbers aside, so "Weekly sync 12 Oct" and "Weekly sync 19 Oct" match).
 */
object MeetingSeries {
    /** What the previous meeting in the series left behind. */
    data class SinceLast(
        val previous: Meeting,
        val decisions: List<String>,
        /** Its action items not ticked off yet. */
        val stillOpen: List<ActionItem>,
        /** Its action items done since. */
        val done: List<ActionItem>,
    )

    fun sameSeries(a: Meeting, b: Meeting): Boolean {
        val uidA = a.calendarEvent?.uid
        val uidB = b.calendarEvent?.uid
        if (uidA != null && uidA == uidB) return true
        val titleA = titleKey(a) ?: return false
        return titleA == titleKey(b)
    }

    /** Earlier meetings in [meeting]'s series, newest first. */
    fun history(meeting: Meeting, all: List<Meeting>): List<Meeting> =
        all.filter { it.id != meeting.id && it.createdAt < meeting.createdAt && sameSeries(meeting, it) }
            .sortedByDescending { it.createdAt }

    fun sinceLast(meeting: Meeting, all: List<Meeting>): SinceLast? {
        val previous = history(meeting, all).firstOrNull() ?: return null
        val items = previous.insights?.actionItems.orEmpty()
        return SinceLast(previous, previous.insights?.decisions.orEmpty(), items.filterNot { it.done }, items.filter { it.done })
    }

    /** A title with dates, numbers, weekdays and months left out; null for titles the app made up. */
    fun titleKey(meeting: Meeting): String? {
        val title = meeting.calendarEvent?.title ?: meeting.title.takeIf { !meeting.autoTitle } ?: return null
        val words = title.lowercase()
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotEmpty() && it.none(Char::isDigit) && it !in CALENDAR_WORDS }
        return words.joinToString(" ").takeIf { it.length >= 3 }
    }

    private val CALENDAR_WORDS = setOf(
        "mon", "monday", "tue", "tues", "tuesday", "wed", "wednesday", "thu", "thur", "thurs", "thursday",
        "fri", "friday", "sat", "saturday", "sun", "sunday",
        "jan", "january", "feb", "february", "mar", "march", "apr", "april", "may", "jun", "june", "jul", "july",
        "aug", "august", "sep", "sept", "september", "oct", "october", "nov", "november", "dec", "december",
        "am", "pm", "week", "wk",
    )
}
