package com.kaliscriber.core.recap

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiServices
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.LlmJson
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.usage.TaggedLlm
import com.kaliscriber.core.ai.usage.UsageTag
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.tasks.ActionItems
import com.kaliscriber.core.util.Clock
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** The AI's account of one week of meetings (Monday to Sunday). */
@Serializable
data class WeeklyRecap(
    /** The Monday the week starts on, as yyyy-MM-dd. */
    val weekStart: String,
    val overview: String = "",
    val decisions: List<String> = emptyList(),
    val themes: List<String> = emptyList(),
    val risks: List<String> = emptyList(),
    val followUps: List<String> = emptyList(),
    /** The meetings it was written from. */
    val meetingIds: List<String> = emptyList(),
    val createdAt: Long = 0,
)

/**
 * Weekly recaps: which meetings fall in a week, what is still open from them, and the AI's summary
 * across them, written from each meeting's notes (no transcripts are loaded).
 */
class WeeklyRecaps(
    private val meetings: MeetingRepository,
    private val ai: AiServices,
    private val settings: () -> AppSettings,
    private val store: JsonValueStore<List<WeeklyRecap>>,
    private val clock: Clock = Clock.System,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    val recaps: StateFlow<List<WeeklyRecap>> = store.value

    fun recap(week: LocalDate): WeeklyRecap? = store.value.value.firstOrNull { it.weekStart == week.toString() }

    /** Meetings recorded in the week starting [week], oldest first. */
    fun meetingsIn(week: LocalDate): List<Meeting> {
        val from = week.atStartOfDay(zone()).toInstant().toEpochMilli()
        val to = week.plusDays(7).atStartOfDay(zone()).toInstant().toEpochMilli()
        return meetings.all().filter { it.createdAt in from until to }.sortedBy { it.createdAt }
    }

    /** Open action items from the week's meetings, by owner (people with most first, unowned last). */
    fun openItems(week: LocalDate): List<Pair<String?, List<ActionItems.Tracked>>> {
        val open = ActionItems.all(meetingsIn(week)).filterNot { it.item.done }
        val owners = ActionItems.owners(open)
        return owners.map { owner -> owner to open.filter { it.item.owner?.trim() == owner } } +
            listOfNotNull(open.filter { it.item.owner.isNullOrBlank() }.takeIf { it.isNotEmpty() }?.let { null to it })
    }

    /** Open items from any meeting due in the 7 days after [week] ends, earliest first. */
    fun dueNextWeek(week: LocalDate): List<ActionItems.Tracked> {
        val start = week.plusDays(7)
        return ActionItems.all(meetings.all())
            .filter { !it.item.done && it.due?.let { d -> !d.isBefore(start) && d.isBefore(start.plusDays(7)) } == true }
            .sortedBy { it.due }
    }

    /** Writes (or rewrites) the recap of the week starting [week] and keeps it. */
    suspend fun write(week: LocalDate): WeeklyRecap {
        meetings.load()
        val included = meetingsIn(week)
        if (included.isEmpty()) throw AiException("No meetings were recorded that week.")
        val request = LlmRequest(
            system = Prompts.weeklyRecapSystem(settings().outputLanguage, settings().glossary),
            messages = listOf(LlmMessage(LlmRole.USER, context(week, included))),
            jsonMode = true,
        )
        val reply = TaggedLlm(ai.chat(), UsageTag(null, "Weekly recap")).complete(request)
        val obj = LlmJson.parseObject(reply)
        val recap = WeeklyRecap(
            weekStart = week.toString(),
            overview = (obj["overview"] as? JsonPrimitive)?.content?.trim().orEmpty(),
            decisions = obj.strings("decisions"),
            themes = obj.strings("themes"),
            risks = obj.strings("risks"),
            followUps = obj.strings("followUps"),
            meetingIds = included.map { it.id },
            createdAt = clock.now(),
        )
        store.update { list -> (list.filterNot { it.weekStart == recap.weekStart } + recap).sortedByDescending { it.weekStart }.take(KEEP) }
        return recap
    }

    /** What the AI reads: each meeting's notes, newest last, within a size budget. */
    internal fun context(week: LocalDate, included: List<Meeting>): String {
        val perMeeting = (BUDGET / included.size).coerceAtLeast(1_500)
        return buildString {
            appendLine("Week of ${label(week)}. ${included.size} meeting${if (included.size == 1) "" else "s"}.")
            for (m in included) {
                appendLine()
                appendLine(notesOf(m).let { if (it.length > perMeeting) it.take(perMeeting) + "…" else it })
            }
        }.take(BUDGET + 2_000)
    }

    private fun notesOf(m: Meeting): String = buildString {
        val type = MeetingType.of(m)
        append("## ${m.title} — ${Prompts.formatDate(m.createdAt)}")
        if (m.durationMs > 0) append(", ${m.durationMs / 60_000} min")
        if (type != MeetingType.GENERAL) append(" (${type.label})")
        appendLine()
        val people = m.speakers.filterNot { it.isPlaceholder }.map { it.name }
        if (people.isNotEmpty()) appendLine("People: ${people.joinToString(", ")}")
        val i = m.insights
        if (i == null) {
            m.transcriptPreview().takeIf { it.isNotBlank() }?.let { appendLine("No notes yet. Transcript starts: $it") }
            return@buildString
        }
        if (i.summary.isNotBlank()) appendLine("Summary: ${i.summary}")
        list("Decisions", i.decisions)
        list("Action items", i.actionItems.map { a ->
            (if (a.done) "[done] " else "[open] ") + a.task +
                listOfNotNull(a.owner?.let { "owner $it" }, (a.dueDateIso ?: a.dueDate)?.let { "due $it" }).joinToString(", ", " (", ")").takeIf { a.owner != null || a.dueDateIso != null || a.dueDate != null }.orEmpty()
        })
        list("Risks", i.risks)
        list("Next steps", i.nextSteps)
        i.sections.forEach { list(it.title, it.items) }
        list("Open questions", m.gaps?.gaps.orEmpty().map { it.title })
    }

    private fun StringBuilder.list(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("$title:")
        items.forEach { appendLine("- $it") }
    }

    /** The recap as Markdown, with open items by person, for sharing or exporting. */
    fun markdown(recap: WeeklyRecap): String = buildString {
        val week = LocalDate.parse(recap.weekStart)
        appendLine("# Week of ${label(week)}")
        appendLine()
        if (recap.overview.isNotBlank()) {
            appendLine(recap.overview)
            appendLine()
        }
        section("Decisions", recap.decisions)
        section("Themes", recap.themes)
        section("Risks and blockers", recap.risks)
        section("Follow up", recap.followUps)
        val open = openItems(week)
        if (open.isNotEmpty()) {
            appendLine("## Open action items")
            for ((owner, items) in open) {
                appendLine("**${owner ?: "No owner"}**")
                items.forEach { t -> appendLine("- [ ] ${t.item.task}" + (t.item.dueDateIso ?: t.item.dueDate)?.let { " (due $it)" }.orEmpty() + " — ${t.meetingTitle}") }
            }
            appendLine()
        }
        val list = meetingsIn(week)
        if (list.isNotEmpty()) {
            appendLine("## Meetings")
            list.forEach { appendLine("- ${it.title} (${DAY.format(Instant.ofEpochMilli(it.createdAt).atZone(zone()))})") }
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("## $title")
        items.forEach { appendLine("- $it") }
        appendLine()
    }

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf(String::isNotEmpty) }

    companion object {
        /** Recaps kept: about a year. */
        const val KEEP = 53
        /** Characters of meeting notes sent in one request. */
        private const val BUDGET = 60_000
        private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
        private val LABEL = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

        /** The Monday of the week [date] falls in. */
        fun weekOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

        fun label(week: LocalDate): String = LABEL.format(week)
    }
}
