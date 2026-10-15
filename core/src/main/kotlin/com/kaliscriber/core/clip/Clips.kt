package com.kaliscriber.core.clip

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.transcript.TranscriptFormatter

/** A short stretch of a meeting's audio to share, with what was said in it. */
data class Clip(val fromMs: Long, val toMs: Long) {
    val durationMs: Long get() = toMs - fromMs
}

object Clips {
    /** Around a moment such as a highlight: 20 seconds either side. */
    const val AROUND_MS = 20_000L
    /** Bookmarks mark what was just said, so their clip is mostly before the mark. */
    const val BOOKMARK_BEFORE_MS = 30_000L
    const val BOOKMARK_AFTER_MS = 10_000L
    /** A little room around selected lines so words at the edges aren't cut. */
    const val LINE_PAD_MS = 1_500L
    /** Longest clip, so a share stays small. */
    const val MAX_MS = 3 * 60_000L

    fun around(atMs: Long, totalMs: Long) = clamp(atMs - AROUND_MS, atMs + AROUND_MS, totalMs)

    fun forBookmark(atMs: Long, totalMs: Long) = clamp(atMs - BOOKMARK_BEFORE_MS, atMs + BOOKMARK_AFTER_MS, totalMs)

    /** The selected lines from the first one's start to the last one's end; null if none. */
    fun forLines(lines: List<TranscriptSegment>, totalMs: Long): Clip? {
        if (lines.isEmpty()) return null
        return clamp(lines.minOf { it.startMs } - LINE_PAD_MS, lines.maxOf { it.endMs } + LINE_PAD_MS, totalMs)
    }

    private fun clamp(from: Long, to: Long, totalMs: Long): Clip {
        val end = if (totalMs > 0) to.coerceAtMost(totalMs) else to
        val start = from.coerceAtLeast(0).coerceAtMost(end)
        return Clip(start, end.coerceAtMost(start + MAX_MS))
    }

    /** The lines of [meeting] said within the clip. */
    fun lines(meeting: Meeting, clip: Clip): List<TranscriptSegment> =
        meeting.segments.filter { it.endMs > clip.fromMs && it.startMs < clip.toMs }

    /** Text to send with the audio: where it is from, then the lines said in it. */
    fun text(meeting: Meeting, clip: Clip): String = buildString {
        append(meeting.title)
        append(" (")
        append(TranscriptFormatter.timestamp(clip.fromMs))
        append("–")
        append(TranscriptFormatter.timestamp(clip.toMs))
        appendLine(")")
        lines(meeting, clip).forEach { seg ->
            appendLine()
            val who = seg.speakerId?.let { meeting.speaker(it)?.name }
            append(if (who != null) "$who: " else "")
            append(seg.text.trim())
        }
    }.trimEnd()

    /** A file name for the clip, from the meeting title and the start time. */
    fun fileName(meeting: Meeting, clip: Clip, extension: String): String {
        val base = meeting.title.replace(Regex("""[^\p{L}\p{N}]+"""), "-").trim('-').take(40).ifEmpty { "meeting" }
        val at = TranscriptFormatter.timestamp(clip.fromMs).replace(':', '.')
        return "$base-$at.$extension"
    }
}
