package com.kaliscriber.core.sync

import com.kaliscriber.core.ai.CustomFormat
import com.kaliscriber.core.ai.SummaryFormats
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Keeps a folder (an Obsidian vault, say) with one Markdown note per meeting: written when a
 * meeting is ready, rewritten when it changes, renamed with it and removed when it is deleted.
 * The folder mirrors the app, so edits made there are replaced the next time the meeting changes.
 */
object MarkdownSync {
    /** What was last written for a meeting. */
    @Serializable
    data class Entry(val file: String, val updatedAt: Long, val transcript: Boolean)

    sealed interface Action {
        data class Write(val meetingId: String, val file: String) : Action
        data class Delete(val file: String) : Action
    }

    /** Meetings still being recorded or processed wait until they are done. */
    fun ready(meeting: Meeting): Boolean = meeting.status == MeetingStatus.READY ||
        (meeting.status == MeetingStatus.ERROR && (meeting.hasTranscript || meeting.insights != null))

    /**
     * What to do to bring the folder in line with [meetings], given what was written before
     * ([state]). Write actions come with the file each meeting now has.
     */
    fun plan(meetings: List<Meeting>, state: Map<String, Entry>, transcript: Boolean, zone: ZoneId = ZoneId.systemDefault()): List<Action> {
        val actions = mutableListOf<Action>()
        val live = meetings.filter(::ready).sortedBy { it.createdAt }
        val liveIds = live.map { it.id }.toSet()
        // Deleted meetings (and those no longer ready) lose their note.
        state.filterKeys { it !in liveIds && meetings.none { m -> m.id == it } }.values.forEach { actions += Action.Delete(it.file) }

        // Keep names that still fit their meeting's title, so links in the vault don't break.
        val names = HashMap<String, String>()
        val used = HashSet<String>()
        for (m in live) {
            val kept = state[m.id]?.file?.takeIf { fits(it, baseName(m, zone)) } ?: continue
            names[m.id] = kept
            used += kept.lowercase()
        }
        // Files of meetings still in progress stay reserved.
        state.filterKeys { it !in liveIds }.values.forEach { used += it.file.lowercase() }
        for (m in live) {
            if (m.id in names) continue
            val base = baseName(m, zone)
            var n = 1
            var name = "$base.md"
            while (name.lowercase() in used) name = "$base (${++n}).md"
            names[m.id] = name
            used += name.lowercase()
        }
        // Old names go first, so a name freed by a rename can be taken by another meeting.
        val writes = mutableListOf<Action>()
        for (m in live) {
            val name = names.getValue(m.id)
            val before = state[m.id]
            if (before != null && before.file != name) actions += Action.Delete(before.file)
            if (before == null || before.file != name || before.updatedAt != m.updatedAt || before.transcript != transcript) {
                writes += Action.Write(m.id, name)
            }
        }
        return actions + writes
    }

    private fun fits(file: String, base: String): Boolean =
        file == "$base.md" || Regex("""${Regex.escape(base)} \(\d+\)\.md""").matches(file)

    /** "2026-10-14 Weekly sync", without characters that file systems or Obsidian links reject. */
    fun baseName(meeting: Meeting, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(meeting.createdAt).atZone(zone))
        val title = meeting.title.replace(Regex("""[\\/:*?"<>|#^\[\]\p{Cntrl}]"""), " ")
            .replace(Regex("""\s+"""), " ").trim().trim('.').take(80).trim()
        return if (title.isEmpty()) day else "$day $title"
    }

    /** The note: properties Obsidian understands, then the meeting's notes (and transcript). */
    fun note(meeting: Meeting, transcript: Boolean, custom: List<CustomFormat> = emptyList(), zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val start = Instant.ofEpochMilli(meeting.createdAt).atZone(zone)
        val people = meeting.speakers.filterNot { it.isPlaceholder }.map { it.name.trim() }.filter { it.isNotEmpty() }.distinct()
        appendLine("---")
        appendLine("title: ${yaml(meeting.title)}")
        appendLine("date: ${DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(start.withNano(0))}")
        if (meeting.durationMs > 0) appendLine("duration: ${yaml(TranscriptFormatter.timestamp(meeting.durationMs))}")
        if (people.isNotEmpty()) {
            appendLine("people:")
            people.forEach { appendLine("  - ${yaml(it)}") }
        }
        appendLine("format: ${yaml(SummaryFormats.of(meeting, custom).label)}")
        val open = meeting.insights?.actionItems.orEmpty().count { !it.done }
        if (open > 0) appendLine("open_actions: $open")
        appendLine("tags:")
        appendLine("  - meeting")
        appendLine("kaliscriber_id: ${meeting.id}")
        appendLine("---")
        appendLine()
        val body = TranscriptFormatter.markdown(if (transcript) meeting else meeting.copy(segments = emptyList()))
        append(body.trimEnd())
        appendLine()
    }

    /** A YAML scalar, quoted when it could be misread. */
    private fun yaml(text: String): String {
        val plain = text.isNotEmpty() && text.none { it in ":#'\"{}[],&*!|>%@`\n" } && !text.first().isWhitespace() && !text.last().isWhitespace() &&
            text.first() !in "-?" && text.lowercase() !in setOf("true", "false", "yes", "no", "null", "~") && text.toDoubleOrNull() == null
        return if (plain) text else "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""
    }
}
