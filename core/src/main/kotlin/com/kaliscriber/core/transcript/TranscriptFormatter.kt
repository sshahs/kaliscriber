package com.kaliscriber.core.transcript

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import java.util.Locale

object TranscriptFormatter {

    /** `hh:mm:ss` (or `mm:ss` under an hour). */
    fun timestamp(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0) / 1000)
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
    }

    /**
     * Speaker-attributed transcript for prompts. Consecutive segments by the same speaker are merged
     * into one turn to save tokens.
     */
    fun forPrompt(meeting: Meeting, segments: List<TranscriptSegment> = meeting.segments): String {
        val sb = StringBuilder()
        var currentSpeaker: String? = "\u0000"
        for (seg in segments) {
            val name = meeting.speaker(seg.speakerId)?.name ?: "Unknown speaker"
            if (name != currentSpeaker) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append('[').append(timestamp(seg.startMs)).append("] ").append(name).append(": ")
                currentSpeaker = name
            } else {
                sb.append(' ')
            }
            sb.append(seg.text.trim())
        }
        return sb.toString()
    }

    /** Keeps the start and end of very long transcripts so a single request stays within limits. */
    fun truncateMiddle(text: String, maxChars: Int): String {
        if (text.length <= maxChars || maxChars < 1000) return text
        val keep = (maxChars - 100) / 2
        return text.take(keep) + "\n\n[... ${text.length - 2 * keep} characters of the middle of the meeting omitted ...]\n\n" + text.takeLast(keep)
    }

    fun markdown(meeting: Meeting): String = buildString {
        appendLine("# ${meeting.title}")
        appendLine()
        val insights = meeting.insights
        if (insights != null) {
            if (insights.summary.isNotBlank()) {
                appendLine("## Summary"); appendLine(insights.summary); appendLine()
            }
            insights.sections.forEach { section(it.title, it.items) }
            section("Key points", insights.keyPoints)
            section("Decisions", insights.decisions)
            if (insights.actionItems.isNotEmpty()) {
                appendLine("## Action items")
                insights.actionItems.forEach { item ->
                    val meta = listOfNotNull(item.owner, item.dueDate?.let { "due $it" }, item.priority).joinToString(", ")
                    appendLine("- [${if (item.done) "x" else " "}] ${item.task}${if (meta.isNotEmpty()) " ($meta)" else ""}")
                }
                appendLine()
            }
            if (insights.highlights.isNotEmpty()) {
                appendLine("## Highlights")
                insights.highlights.forEach { h ->
                    val who = listOfNotNull(h.timestamp, h.speaker).joinToString(" ")
                    appendLine("- ${if (who.isNotEmpty()) "[$who] " else ""}${h.text}")
                }
                appendLine()
            }
            section("Next steps", insights.nextSteps)
            section("Risks", insights.risks)
        }
        section("Bookmarks", meeting.bookmarks.map { b -> "[${timestamp(b.atMs)}]" + if (b.note.isNotBlank()) " ${b.note}" else "" })
        section(
            "Not recorded",
            meeting.interruptions.map { g -> "[${timestamp(g.atMs)}] for ${timestamp(g.durationMs)}" + g.reason.takeIf { it.isNotBlank() }?.let { " ($it had the microphone)" }.orEmpty() },
        )
        val gaps = meeting.gaps
        if (gaps != null && gaps.questions.isNotEmpty()) {
            appendLine("## Open questions")
            gaps.questions.forEach { q ->
                appendLine("- ${q.question}")
                if (!q.answer.isNullOrBlank()) appendLine("  - Answer: ${q.answer}")
            }
            appendLine()
        }
        if (meeting.segments.isNotEmpty()) {
            appendLine("## Transcript")
            appendLine(forPrompt(meeting).replace("\n", "\n\n"))
        }
    }

    private fun StringBuilder.section(title: String, items: List<String>) {
        if (items.isEmpty()) return
        appendLine("## $title")
        items.forEach { appendLine("- $it") }
        appendLine()
    }
}
