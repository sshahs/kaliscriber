package com.kaliscriber.core.tasks

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.search.MeetingSearch
import java.time.LocalDate

/** Action items from every meeting, for the task list and the daily reminder. */
object ActionItems {

    /** An action item with the meeting it came from. */
    data class Tracked(val meetingId: String, val meetingTitle: String, val meetingDate: Long, val item: ActionItem) {
        val due: LocalDate? get() = item.dueDateIso?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    }

    enum class Bucket(val title: String) {
        OVERDUE("Overdue"),
        TODAY("Due today"),
        SOON("Next 7 days"),
        LATER("Later"),
        NO_DATE("No due date"),
        DONE("Done"),
    }

    fun all(meetings: List<Meeting>): List<Tracked> = meetings.flatMap { m ->
        m.insights?.actionItems.orEmpty().map { Tracked(m.id, m.title, m.createdAt, it) }
    }

    fun bucket(t: Tracked, today: LocalDate): Bucket {
        if (t.item.done) return Bucket.DONE
        val due = t.due ?: return Bucket.NO_DATE
        return when {
            due.isBefore(today) -> Bucket.OVERDUE
            due == today -> Bucket.TODAY
            !due.isAfter(today.plusDays(7)) -> Bucket.SOON
            else -> Bucket.LATER
        }
    }

    /** Items in [Bucket] order; within one, earliest due first, then newest meeting first. */
    fun grouped(items: List<Tracked>, today: LocalDate): List<Pair<Bucket, List<Tracked>>> =
        items.groupBy { bucket(it, today) }
            .toSortedMap(compareBy { it.ordinal })
            .map { (bucket, list) ->
                bucket to list.sortedWith(compareBy<Tracked> { it.due ?: LocalDate.MAX }.thenByDescending { it.meetingDate })
            }

    /** People who own at least one item, most items first. */
    fun owners(items: List<Tracked>): List<String> =
        items.mapNotNull { it.item.owner?.trim()?.takeIf { o -> o.isNotEmpty() } }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }

    data class Digest(val overdue: List<Tracked>, val dueToday: List<Tracked>) {
        val isEmpty: Boolean get() = overdue.isEmpty() && dueToday.isEmpty()
    }

    /** What the morning reminder lists: open items that are overdue or due [today]. */
    fun digest(meetings: List<Meeting>, today: LocalDate): Digest {
        val open = all(meetings).filter { !it.item.done }
        return Digest(
            overdue = open.filter { bucket(it, today) == Bucket.OVERDUE }.sortedBy { it.due },
            dueToday = open.filter { bucket(it, today) == Bucket.TODAY },
        )
    }

    /**
     * Keeps what the user did to action items when the summary is regenerated: an item that matches
     * an earlier one (same or nearly the same task) keeps its id and done state, and the user's own
     * edits to owner and due date win over the AI's new reading.
     */
    fun carryOver(previous: List<ActionItem>, fresh: List<ActionItem>): List<ActionItem> {
        val unused = previous.toMutableList()
        val merged = fresh.map { item ->
            val match = unused.maxByOrNull { similarity(it.task, item.task) }?.takeIf { similarity(it.task, item.task) >= 0.6 }
                ?: return@map item
            unused.remove(match)
            item.copy(
                id = match.id,
                done = match.done,
                owner = if (match.edited) match.owner else item.owner ?: match.owner,
                dueDate = if (match.edited) match.dueDate else item.dueDate ?: match.dueDate,
                dueDateIso = if (match.edited) match.dueDateIso else item.dueDateIso ?: match.dueDateIso,
                task = if (match.edited) match.task else item.task,
                edited = match.edited,
            )
        }
        // Items the user changed but the new summary no longer mentions are kept, not silently lost.
        return merged + unused.filter { it.edited || it.done }
    }

    private fun similarity(a: String, b: String): Double {
        val x = MeetingSearch.terms(a).toSet()
        val y = MeetingSearch.terms(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return if (a.trim().equals(b.trim(), true)) 1.0 else 0.0
        return (x intersect y).size.toDouble() / (x union y).size
    }
}
