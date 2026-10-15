package com.kaliscriber.core.ai

import com.kaliscriber.core.model.Meeting
import kotlinx.serialization.Serializable

/** One section of a summary format: its heading, and what goes in it. */
@Serializable
data class FormatSection(val title: String, val guidance: String = "")

/** A summary format the user made, with its own sections. */
@Serializable
data class CustomFormat(
    val id: String,
    val name: String,
    /** What kind of meeting it is for, told to the AI (e.g. "A weekly review of support tickets"). */
    val focus: String = "",
    val sections: List<FormatSection> = emptyList(),
    /** Comma-separated words; a meeting whose title has one of them gets this format automatically. */
    val titleWords: String = "",
)

/** A summary format, built in ([type] set) or the user's own ([custom] set). */
data class SummaryFormat(
    val label: String,
    val focus: String,
    val sections: List<Pair<String, String>>,
    val type: MeetingType? = null,
    val custom: CustomFormat? = null,
)

object SummaryFormats {
    fun of(type: MeetingType) = SummaryFormat(type.label, type.focus, type.sections, type = type)

    fun of(custom: CustomFormat) = SummaryFormat(
        custom.name.ifBlank { "Untitled format" },
        custom.focus.trim().let { if (it.isEmpty() || it.endsWith(".")) it else "$it." },
        custom.sections.filter { it.title.isNotBlank() }.map { it.title.trim() to it.guidance.trim() },
        custom = custom,
    )

    /** The built-in formats, then the user's own. */
    fun all(custom: List<CustomFormat>): List<SummaryFormat> = MeetingType.entries.map(::of) + custom.map(::of)

    /** The format the meeting's title suggests: one of the user's whose words match, else a built-in guess. */
    fun guess(meeting: Meeting, custom: List<CustomFormat>): SummaryFormat {
        val text = listOfNotNull(meeting.calendarEvent?.title, meeting.title.takeIf { !meeting.autoTitle }).joinToString(" ").lowercase()
        val mine = custom.firstOrNull { format ->
            format.titleWords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
                .any { word -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(word)}(?![\p{L}\p{N}])""").containsMatchIn(text) }
        }
        return mine?.let(::of) ?: of(MeetingType.guess(meeting))
    }

    /** The meeting's chosen format (a removed custom one falls back to a guess), or a guess. */
    fun of(meeting: Meeting, custom: List<CustomFormat>): SummaryFormat {
        meeting.formatId?.let { id -> custom.firstOrNull { it.id == id }?.let { return of(it) } }
        meeting.type?.let { return of(it) }
        return guess(meeting, custom)
    }

    /** True when the user picked the format rather than it being guessed. */
    fun chosen(meeting: Meeting, custom: List<CustomFormat>): Boolean =
        meeting.type != null || custom.any { it.id == meeting.formatId }
}
