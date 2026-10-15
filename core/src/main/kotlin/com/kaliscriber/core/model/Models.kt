package com.kaliscriber.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import com.kaliscriber.core.calendar.CalendarEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
enum class MeetingStatus { RECORDING, TRANSCRIBING, ANALYZING, READY, ERROR }

@Serializable
data class Meeting(
    val id: String,
    val title: String,
    val createdAt: Long,
    /** True until the user renames the meeting; lets the AI suggest a title. */
    val autoTitle: Boolean = true,
    val durationMs: Long = 0,
    val status: MeetingStatus = MeetingStatus.RECORDING,
    /** Absolute path of the full recording (m4a), if any. */
    val audioPath: String? = null,
    val speakers: List<Speaker> = emptyList(),
    val segments: List<TranscriptSegment> = emptyList(),
    val insights: MeetingInsights? = null,
    val gaps: GapAnalysis? = null,
    val chat: List<ChatMessage> = emptyList(),
    /** Chunks whose live transcription failed, kept so they can be retried. */
    val pendingChunks: List<AudioChunk> = emptyList(),
    /** Notes written while the meeting was recorded, one per stretch of about ten minutes. */
    val sections: List<MeetingSection> = emptyList(),
    /** True once speakers come from a diarization pass over the whole meeting. */
    val speakersFinal: Boolean = false,
    val lastError: String? = null,
    val briefing: Briefing? = null,
    /** What processing is doing right now (e.g. "Detecting speakers"), shown while it runs. */
    val processingStep: String? = null,
    /** Progress of [processingStep] from 0 to 1, when known. */
    val processingProgress: Float? = null,
    /** How many people are in the meeting, if the user said; null lets the app detect it. */
    val expectedSpeakers: Int? = null,
    /** What processing did and how long each step took, oldest first. */
    val timeline: List<TimelineEntry> = emptyList(),
    /** The calendar event this meeting was recorded for. */
    val calendarEvent: CalendarEvent? = null,
    /** Moments the user marked while recording, oldest first. */
    val bookmarks: List<Bookmark> = emptyList(),
    /**
     * A summary of [segments] kept with the meeting, so lists, People pages and search can use it
     * while the transcript itself stays on disk (meeting lists leave [segments] empty).
     */
    val stats: TranscriptStats = TranscriptStats(),
    val updatedAt: Long = createdAt,
) {
    fun speaker(id: String?): Speaker? = speakers.firstOrNull { it.id == id }
    val plainTranscript: String get() = segments.joinToString(" ") { it.text }

    /** True if the meeting has any transcript, whether or not it is loaded. */
    val hasTranscript: Boolean get() = segments.isNotEmpty() || stats.lines > 0

    /** The start of the transcript, for list previews (cheap even for long meetings). */
    fun transcriptPreview(maxChars: Int = 300): String =
        if (segments.isEmpty()) stats.preview.take(maxChars) else TranscriptStats.preview(segments, maxChars)

    /** How long each speaker talked, by speaker id. */
    fun speakingMs(): Map<String, Long> = if (segments.isEmpty()) stats.speakingMs else TranscriptStats.speakingMs(segments)
}

/** What lists need to know about a transcript without loading it. */
@Serializable
data class TranscriptStats(
    val lines: Int = 0,
    val preview: String = "",
    /** Speaking time per speaker id. */
    val speakingMs: Map<String, Long> = emptyMap(),
    /** Changes whenever the transcript does, so a search index can tell it is out of date. */
    val version: Long = 0,
) {
    companion object {
        const val PREVIEW_CHARS = 300

        fun of(segments: List<TranscriptSegment>, version: Long) =
            TranscriptStats(segments.size, preview(segments, PREVIEW_CHARS), speakingMs(segments), version)

        fun preview(segments: List<TranscriptSegment>, maxChars: Int): String = buildString {
            for (seg in segments) {
                if (length >= maxChars) break
                if (isNotEmpty()) append(' ')
                append(seg.text)
            }
        }.take(maxChars)

        fun speakingMs(segments: List<TranscriptSegment>): Map<String, Long> {
            val out = HashMap<String, Long>()
            for (seg in segments) {
                val id = seg.speakerId ?: continue
                out[id] = (out[id] ?: 0L) + (seg.endMs - seg.startMs).coerceAtLeast(0)
            }
            return out
        }
    }
}

/**
 * A participant. [isPlaceholder] speakers carry a random generated name because the app could not
 * tell who was speaking; the user can rename them (or accept an AI suggestion) at any time.
 */
@Serializable
data class Speaker(
    val id: String,
    val name: String,
    val isPlaceholder: Boolean = true,
    /** Provider label (e.g. "A", "0") this speaker was mapped from, used to keep mapping stable. */
    val label: String? = null,
    val colorIndex: Int = 0,
    val suggestedName: String? = null,
    val suggestionReason: String? = null,
    /** Voiceprint (speaker embedding) computed on-device from this speaker's audio. */
    val embedding: List<Float>? = null,
    /** The speaker model [embedding] came from; voiceprints from different models can't be compared. */
    val voiceModel: String? = null,
    /** Saved voice this speaker was recognised as, or enrolled into. */
    val voiceProfileId: String? = null,
    /** Labels of speakers merged into this one, so later detection keeps them together. */
    val aliases: List<String> = emptyList(),
) {
    fun hasLabel(l: String): Boolean = label == l || l in aliases
}

/**
 * A moment the user marked as important while recording, at [atMs] into the meeting, with an
 * optional note. It refers to what was said just before the mark.
 */
@Serializable
data class Bookmark(val id: String, val atMs: Long, val note: String = "")

/** One processing step: when it started, how long it took and whether it worked. */
@Serializable
data class TimelineEntry(
    val label: String,
    val at: Long,
    val durationMs: Long = 0,
    val ok: Boolean = true,
)

@Serializable
data class TranscriptSegment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerId: String? = null,
    val confidence: Float? = null,
    /**
     * Word timings from on-device recognition, used to split a segment where the speaker changes.
     * Each word's text keeps its leading space, so joining them gives back [text]. Dropped on edits.
     */
    val words: List<WordTiming>? = null,
)

/** When a recognized word starts (ms into the meeting) and its text. Stored with short keys: there are thousands per meeting. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class WordTiming(
    @SerialName("s") @JsonNames("startMs") val startMs: Long,
    @SerialName("t") @JsonNames("text") val text: String,
)

/** A short spoken recap of the meeting, and the audio file it was rendered to. */
@Serializable
data class Briefing(
    val script: String,
    val audioPath: String? = null,
    val createdAt: Long = 0,
)

@Serializable
data class AudioChunk(
    val index: Int,
    val path: String,
    val startMs: Long,
    val endMs: Long,
)

@Serializable
data class MeetingInsights(
    val title: String = "",
    val summary: String = "",
    val keyPoints: List<String> = emptyList(),
    val highlights: List<Highlight> = emptyList(),
    val actionItems: List<ActionItem> = emptyList(),
    val decisions: List<String> = emptyList(),
    val topics: List<Topic> = emptyList(),
    val risks: List<String> = emptyList(),
    val nextSteps: List<String> = emptyList(),
    val sentiment: String = "",
    val generatedAt: Long = 0,
)

@Serializable
data class Highlight(
    val text: String,
    val speaker: String? = null,
    val timestamp: String? = null,
    val why: String? = null,
)

@Serializable
data class ActionItem(
    val id: String,
    val task: String,
    val owner: String? = null,
    val dueDate: String? = null,
    /** The due date as YYYY-MM-DD when it could be worked out (used for calendar tasks). */
    val dueDateIso: String? = null,
    val priority: String? = null,
    val done: Boolean = false,
    /** True once the user changed the task, owner or due date; regenerating the summary keeps those edits. */
    val edited: Boolean = false,
)

@Serializable
data class Topic(val name: String, val summary: String = "")

@Serializable
data class GapAnalysis(
    val gaps: List<Gap> = emptyList(),
    val questions: List<FollowUpQuestion> = emptyList(),
    val generatedAt: Long = 0,
)

@Serializable
data class Gap(val title: String, val detail: String = "", val suggestion: String = "")

@Serializable
data class FollowUpQuestion(
    val id: String,
    val question: String,
    val context: String = "",
    val answer: String? = null,
)

@Serializable
enum class ChatRole { USER, ASSISTANT }

@Serializable
data class ChatMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    val excerpt: ExcerptRef? = null,
    val isError: Boolean = false,
    /** The user stopped the answer while it was being written; [content] is what had arrived. */
    val stopped: Boolean = false,
)

/** AI notes on one stretch of a meeting, written while it was being recorded. */
@Serializable
data class MeetingSection(val startMs: Long, val endMs: Long, val notes: String)

/** A message in the "Ask across all meetings" conversation. */
@Serializable
data class AskMessage(
    val id: String,
    val role: ChatRole,
    val content: String,
    val createdAt: Long,
    /** Meetings (and moments in them) the answer is based on. */
    val sources: List<AskSource> = emptyList(),
    val isError: Boolean = false,
    /** The user stopped the answer while it was being written; [content] is what had arrived. */
    val stopped: Boolean = false,
)

/** A meeting an answer cites, optionally at a moment in its transcript. */
@Serializable
data class AskSource(
    val meetingId: String,
    val title: String,
    val startMs: Long? = null,
    val segmentId: String? = null,
    /** How the answer refers to the meeting, e.g. "M2". */
    val label: String = "",
)

/** A part of the meeting the user selected to ask about. */
@Serializable
data class ExcerptRef(val startMs: Long, val endMs: Long, val segmentIds: List<String>)

@Serializable
data class Note(
    val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val tags: List<String> = emptyList(),
    val meetingId: String? = null,
)
