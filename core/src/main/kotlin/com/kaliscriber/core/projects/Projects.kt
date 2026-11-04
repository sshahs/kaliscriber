package com.kaliscriber.core.projects

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import kotlinx.serialization.Serializable

/** A project or client that meetings are grouped under. */
@Serializable
data class Project(
    val id: String,
    val name: String,
    /** Meetings whose title has one of these words or phrases join the project by themselves. */
    val titleWords: List<String> = emptyList(),
    /** What the project is about, in the user's words. */
    val about: String = "",
    val createdAt: Long = 0,
    /** Finished: kept with its meetings but no longer offered or matched by title. */
    val archived: Boolean = false,
)

object Projects {
    /** [Meeting.projectId] for a meeting the user took out of every project. */
    const val NONE = "-"

    /** The project [meeting] belongs to: the one the user chose, or else the first whose title words match. */
    fun of(meeting: Meeting, projects: List<Project>): Project? {
        meeting.projectId?.let { id -> return if (id == NONE) null else projects.firstOrNull { it.id == id } }
        return projects.firstOrNull { !it.archived && matchesTitle(it, meeting.title) }
    }

    /** True when the project was picked by title words rather than by the user. */
    fun isAutomatic(meeting: Meeting, projects: List<Project>): Boolean = meeting.projectId == null && of(meeting, projects) != null

    fun matchesTitle(project: Project, title: String): Boolean = project.titleWords.any { word ->
        val w = word.trim()
        w.isNotEmpty() && Regex("(?<![\\p{L}\\p{N}])${Regex.escape(w)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(title)
    }

    /** Title words typed as one line, split at commas. */
    fun words(text: String): List<String> = text.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }

    /** [project]'s meetings, newest first. */
    fun meetings(project: Project, meetings: List<Meeting>, projects: List<Project>): List<Meeting> =
        meetings.filter { of(it, projects)?.id == project.id }.sortedByDescending { it.createdAt }

    /** A line from a meeting's notes, with the meeting it came from. */
    data class Item(val text: String, val meeting: Meeting)

    data class OpenAction(val item: ActionItem, val meeting: Meeting)

    /** Everything a project page shows. */
    data class Overview(
        val project: Project,
        /** Newest first. */
        val meetings: List<Meeting>,
        /** Newest first. */
        val decisions: List<Item>,
        /** Oldest meeting first, so what has waited longest leads. */
        val openActions: List<OpenAction>,
        val doneActions: Int,
        /** From the latest meetings only, since older ones were probably dealt with. */
        val risks: List<Item>,
        /** Named people, most meetings first. */
        val people: List<String>,
        /** Next steps from the latest meeting with notes. */
        val nextSteps: List<Item>,
    ) {
        val firstAt: Long? get() = meetings.lastOrNull()?.createdAt
        val lastAt: Long? get() = meetings.firstOrNull()?.createdAt
        val totalMs: Long get() = meetings.sumOf { it.durationMs }
    }

    fun overview(project: Project, meetings: List<Meeting>, projects: List<Project>): Overview {
        val mine = meetings(project, meetings, projects)
        val withNotes = mine.filter { it.insights != null }
        val people = HashMap<String, Int>()
        for (m in mine) {
            val names = (m.speakers.filter { !it.isPlaceholder }.map { it.name.trim() } +
                m.calendarEvent?.attendees.orEmpty().map { it.substringBefore('<').trim() })
                .filter { it.isNotEmpty() && '@' !in it }
                .distinctBy { it.lowercase() }
            names.forEach { people.merge(it, 1, Int::plus) }
        }
        val actions = mine.flatMap { m -> m.insights?.actionItems.orEmpty().map { OpenAction(it, m) } }
        return Overview(
            project = project,
            meetings = mine,
            decisions = withNotes.flatMap { m -> m.insights!!.decisions.map { Item(it, m) } },
            openActions = actions.filter { !it.item.done }.sortedBy { it.meeting.createdAt },
            doneActions = actions.count { it.item.done },
            risks = withNotes.take(RECENT).flatMap { m -> m.insights!!.risks.map { Item(it, m) } },
            people = people.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key },
            nextSteps = withNotes.firstOrNull()?.let { m -> m.insights!!.nextSteps.map { Item(it, m) } }.orEmpty(),
        )
    }

    /** How many of the latest meetings' risks a project page shows. */
    const val RECENT = 3
}
