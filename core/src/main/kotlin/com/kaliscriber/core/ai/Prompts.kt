package com.kaliscriber.core.ai

import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.transcript.TranscriptFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Prompts {

    fun languageRule(outputLanguage: String): String =
        if (outputLanguage.isBlank()) "Write in the same language as the meeting transcript."
        else "Write in $outputLanguage."

    private const val PLACEHOLDER_RULE =
        "Speakers listed as unidentified have random placeholder names (like \"Amber Falcon\"); refer to them by that name and never invent their real name."

    fun insightsSystem(outputLanguage: String) = """
        You are KaliScriber, an expert meeting analyst and note taker. You turn meeting transcripts into accurate, useful notes.
        Rules:
        - Use only information in the transcript and the user's clarifications. Never invent facts, names, dates or numbers.
        - $PLACEHOLDER_RULE
        - Timestamps are [mm:ss] or [h:mm:ss] from the start of the meeting.
        - ${languageRule(outputLanguage)}
        Return ONLY a JSON object with this shape:
        {
          "title": "short descriptive meeting title, at most 8 words",
          "summary": "3-6 sentence executive summary",
          "keyPoints": ["important point"],
          "highlights": [{"text": "notable quote or moment", "speaker": "name", "timestamp": "mm:ss", "why": "why it matters"}],
          "actionItems": [{"task": "what needs to be done", "owner": "name or null", "dueDate": "deadline as stated or null", "dueDateIso": "the deadline as YYYY-MM-DD relative to the meeting date, or null", "priority": "high|medium|low"}],
          "decisions": ["decision that was made"],
          "topics": [{"name": "topic", "summary": "one or two sentences"}],
          "risks": ["risk, blocker or concern raised"],
          "nextSteps": ["next step"],
          "sentiment": "one sentence on the tone and engagement of the meeting"
        }
        Use empty arrays when nothing applies.
    """.trimIndent()

    fun gapsSystem(outputLanguage: String) = """
        You review meeting transcripts to find what is missing, unclear or unresolved, then write questions that the user (the participant who recorded the meeting) can answer to fill those gaps.
        Look for:
        - action items without an owner or a deadline
        - decisions that were discussed but not actually made
        - vague references ("that issue", "the numbers from last week") that a reader who was absent could not follow
        - numbers, dates, names or links mentioned but not specified
        - questions raised in the meeting that were never answered
        - garbled or inaudible parts of the transcript
        - unidentified speakers whose identity matters
        $PLACEHOLDER_RULE
        ${languageRule(outputLanguage)}
        Return ONLY a JSON object:
        {
          "gaps": [{"title": "short label", "detail": "what is missing and where, citing timestamps", "suggestion": "how to close the gap"}],
          "questions": [{"question": "a direct question to the user", "context": "why you are asking, with a timestamp"}]
        }
        Ask at most 8 questions, most important first. Do not ask about anything the user has already answered.
    """.trimIndent()

    fun askSystem(meeting: Meeting, transcript: String, outputLanguage: String) = buildString {
        appendLine("You are KaliScriber's meeting assistant. Answer the user's questions about the meeting below, using the transcript as the source of truth.")
        appendLine("- Cite timestamps like [12:34] when you refer to specific moments.")
        appendLine("- If the transcript does not answer the question, say so plainly, then give your best inference clearly labelled as an inference.")
        appendLine("- When asked to fill gaps or draft something (an email, follow-ups, a plan), you may go beyond the transcript but say which parts are your suggestions.")
        appendLine("- Be concise. Markdown is supported.")
        appendLine("- $PLACEHOLDER_RULE")
        appendLine("- ${languageRule(outputLanguage)}")
        appendLine()
        append(meetingContext(meeting, transcript))
    }

    fun meetingContext(meeting: Meeting, transcript: String) = buildString {
        appendLine("Meeting: ${meeting.title}")
        appendLine("Date: ${formatDate(meeting.createdAt)}")
        if (meeting.status == MeetingStatus.RECORDING) {
            appendLine("Status: the meeting is still in progress. The transcript so far runs to ${TranscriptFormatter.timestamp(meeting.durationMs)}; \"recently\" means the last few minutes of it.")
        } else if (meeting.durationMs > 0) {
            appendLine("Duration: ${TranscriptFormatter.timestamp(meeting.durationMs)}")
        }
        meeting.expectedSpeakers?.let { appendLine("Number of participants: $it") }
        val known = meeting.speakers.filter { !it.isPlaceholder }.map { it.name }
        val unknown = meeting.speakers.filter { it.isPlaceholder }.map { it.name }
        if (known.isNotEmpty()) appendLine("Identified speakers: ${known.joinToString()}")
        if (unknown.isNotEmpty()) appendLine("Unidentified speakers (placeholder names): ${unknown.joinToString()}")
        appendLine()
        appendLine("<transcript>")
        appendLine(transcript)
        appendLine("</transcript>")
        val insights = meeting.insights
        if (insights != null && insights.summary.isNotBlank()) {
            appendLine()
            appendLine("<summary>")
            appendLine(insights.summary)
            appendLine("</summary>")
        }
        val answered = meeting.gaps?.questions.orEmpty().filter { !it.answer.isNullOrBlank() }
        if (answered.isNotEmpty()) {
            appendLine()
            appendLine("<user_clarifications>")
            answered.forEach { appendLine("Q: ${it.question}\nA: ${it.answer}") }
            appendLine("</user_clarifications>")
        }
    }

    fun excerptQuestion(meeting: Meeting, excerpt: ExcerptRef, question: String): String {
        val selected = meeting.segments.filter { it.id in excerpt.segmentIds.toSet() }
        return buildString {
            appendLine("This question is about this part of the meeting [${TranscriptFormatter.timestamp(excerpt.startMs)}–${TranscriptFormatter.timestamp(excerpt.endMs)}]:")
            appendLine("<excerpt>")
            appendLine(TranscriptFormatter.forPrompt(meeting, selected))
            appendLine("</excerpt>")
            appendLine()
            append(question)
        }
    }

    const val SPEAKER_NAMES_SYSTEM = """You identify people in meeting transcripts. Some speakers have random placeholder names (like "Amber Falcon") because the app could not identify them.
Using only evidence in the transcript (self-introductions, being addressed by name such as "Thanks, Priya", or a stated role), suggest the real name of each placeholder speaker. Do not guess without evidence; omit speakers you cannot identify.
Return ONLY a JSON object:
{"speakers": [{"placeholder": "Amber Falcon", "name": "Priya", "confidence": 0.9, "reason": "short evidence with a timestamp"}]}"""

    const val SEPARATE_SPEAKERS_SYSTEM = """You are diarizing a meeting transcript that has no reliable speaker labels. Each input line is one segment: "<index>| text". A segment may contain more than one speaker.
Work out who said what from turn-taking cues: questions and answers, greetings, people addressing each other, "I"/"you" references, changes of topic or style.
Label speakers S1, S2, S3... in order of first appearance, reusing the labels already established when they are given. Use "?" only when you genuinely cannot tell.
Return ONLY a compact JSON object. Give every segment index with its speaker. Do not repeat the segment text. When the speaker changes inside a segment, add another entry for the same index whose "from" holds the first 3 to 6 words of the new speaker's part, copied exactly:
{"turns": [{"i": 0, "s": "S1"}, {"i": 1, "s": "S2"}, {"i": 1, "s": "S1", "from": "Sure, I can take"}]}"""

    const val SPOKEN_ANSWER_RULE = """The user is talking to you by voice and will hear your answer read aloud. Reply in two to four short, natural spoken sentences. No Markdown, lists, headings or symbols. Say times like "about twelve minutes in" rather than citing [12:34]."""

    fun briefingSystem(outputLanguage: String) = """
        You write audio briefings: a spoken catch-up on a meeting for someone who missed it, read aloud by a text-to-speech voice.
        - 150 to 300 words, about one to two minutes when spoken.
        - Plain flowing sentences only: no Markdown, bullet points, headings, emoji or timestamps.
        - Open with what the meeting was about in one sentence, then the key decisions, who owns which next steps (with deadlines), and anything unresolved.
        - Use people's names when known. Unidentified speakers have placeholder names; call them "one participant" instead.
        - Never invent facts.
        - ${languageRule(outputLanguage)}
        Reply with the script only.
    """.trimIndent()

    fun noteSystem(outputLanguage: String) = """
        You are KaliScriber, an AI note-taking assistant. You work on the user's notes, which may be typed or dictated (and so contain transcription errors).
        Keep every fact, number, name and commitment. Never invent information. Output Markdown.
        ${languageRule(outputLanguage)}
    """.trimIndent()

    private fun formatDate(millis: Long): String =
        SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date(millis))
}
