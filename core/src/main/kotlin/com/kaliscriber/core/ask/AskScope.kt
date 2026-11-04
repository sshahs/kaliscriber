package com.kaliscriber.core.ask

import com.kaliscriber.core.ai.CustomFormat
import com.kaliscriber.core.ai.SummaryFormats
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.projects.Project
import com.kaliscriber.core.projects.Projects

/** Which meetings a question across meetings is about; the defaults mean all of them. */
data class AskScope(
    val period: Period = Period.ANY,
    /** A named person who spoke in the meeting or was invited to it. */
    val person: String? = null,
    /** The summary format's name, e.g. "1:1" or one of the user's own. */
    val format: String? = null,
    /** Only this project's meetings. */
    val project: Project? = null,
) {
    enum class Period(val label: String, val days: Int?) {
        ANY("Any time", null),
        WEEK("Last 7 days", 7),
        MONTH("Last 30 days", 30),
        QUARTER("Last 3 months", 91),
        YEAR("Last year", 365),
    }

    val active: Boolean get() = period != Period.ANY || person != null || format != null || project != null

    fun matches(meeting: Meeting, now: Long, custom: List<CustomFormat> = emptyList(), projects: List<Project> = emptyList()): Boolean {
        if (project != null && Projects.of(meeting, projects.ifEmpty { listOf(project) })?.id != project.id) return false
        val days = period.days
        if (days != null && meeting.createdAt < now - days * DAY_MS) return false
        if (person != null && !hasPerson(meeting, person)) return false
        if (format != null && SummaryFormats.of(meeting, custom).label != format) return false
        return true
    }

    /** "the last 30 days, with Priya, 1:1 meetings"; empty when nothing is narrowed. */
    fun describe(): String = listOfNotNull(
        period.days?.let { period.label.replaceFirstChar(Char::lowercase).let { "the $it" } },
        person?.let { "with $it" },
        format?.let { "$it meetings" },
        project?.let { "the ${it.name} project" },
    ).joinToString(", ")

    private fun hasPerson(meeting: Meeting, name: String): Boolean =
        meeting.speakers.any { !it.isPlaceholder && it.name.trim().equals(name.trim(), ignoreCase = true) } ||
            meeting.calendarEvent?.attendees.orEmpty().any { it.contains(name.trim(), ignoreCase = true) }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
