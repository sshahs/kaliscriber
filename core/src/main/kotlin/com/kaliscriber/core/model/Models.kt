package com.kaliscriber.core.model

import kotlinx.serialization.Serializable

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
    val updatedAt: Long = createdAt,
) {
    fun speaker(id: String?): Speaker? = speakers.firstOrNull { it.id == id }
    val plainTranscript: String get() = segments.joinToString(" ") { it.text }
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
    /** Saved voice this speaker was recognised as, or enrolled into. */
    val voiceProfileId: String? = null,
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

/** When a recognized word starts (ms into the meeting) and its text. */
@Serializable
data class WordTiming(val startMs: Long, val text: String)

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
