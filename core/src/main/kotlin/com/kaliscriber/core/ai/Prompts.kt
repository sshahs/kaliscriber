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

    /**
     * The shared opening of every request about one meeting: general rules, the glossary and the
     * meeting itself. It is identical across the summary, gaps, names, Ask and briefing requests
     * (only the task that follows differs), so providers can cache it and later requests start faster.
     */
    fun meetingSystem(meeting: Meeting, transcript: String, outputLanguage: String, glossary: String) = buildString {
        appendLine("You are KaliScriber, an expert meeting assistant and note taker. The meeting below was transcribed by speech recognition, so some words, names and terms may be misheard.")
        appendLine("General rules:")
        appendLine("- Base everything on the meeting and the user's clarifications unless your task says otherwise. Never invent facts, names, dates or numbers.")
        appendLine("- $PLACEHOLDER_RULE")
        appendLine("- Timestamps are [mm:ss] or [h:mm:ss] from the start of the meeting.")
        appendLine("- ${languageRule(outputLanguage)}")
        glossaryBlock(glossary)?.let {
            appendLine()
            append(it)
        }
        appendLine()
        append(meetingContext(meeting, transcript))
    }

    /** The user's glossary as a prompt block, or null when it is empty. */
    fun glossaryBlock(glossary: String): String? {
        val terms = glossaryTerms(glossary)
        if (terms.isEmpty()) return null
        return buildString {
            appendLine("<glossary>")
            appendLine("Correct spellings of names and terms the user uses. The transcript may have misheard them; always write them as spelled here:")
            terms.forEach { appendLine("- $it") }
            appendLine("</glossary>")
        }
    }

    /** One glossary entry per non-empty line (e.g. "Priya Sharma — product lead"). */
    fun glossaryTerms(glossary: String): List<String> = glossary.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(200)

    /** For running notes written every ten minutes or so while a meeting is recorded. */
    fun sectionNotesSystem(outputLanguage: String, glossary: String) = buildString {
        appendLine("You take running notes during a live meeting. Summarise the part of the transcript you are given as compact Markdown notes that will later be used to write the meeting summary:")
        appendLine("- topics discussed, with the key facts, numbers and names")
        appendLine("- decisions made")
        appendLine("- action items, with owner and deadline when stated")
        appendLine("- open questions, risks and concerns")
        appendLine("- notable quotes with their [mm:ss] timestamps")
        appendLine("Speaker labels are not reliable yet: attribute things only to people named in the speech, never to the speaker labels.")
        appendLine("Keep every fact, number and name; no filler. At most 250 words. ${languageRule(outputLanguage)}")
        glossaryBlock(glossary)?.let { append(it) }
    }

    const val TASK_INSIGHTS = "# Task: meeting notes"
    const val TASK_GAPS = "# Task: gaps and questions"
    const val TASK_NAMES = "# Task: identify speakers"
    const val TASK_ANSWER = "# Task: answer questions"
    const val TASK_BRIEFING = "# Task: audio briefing"
    const val TASK_GLOSSARY = "# Task: fix misheard terms"

    val INSIGHTS_TASK = """
        $TASK_INSIGHTS
        Turn the meeting into accurate, useful notes. Return ONLY a JSON object with this shape:
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

    val GAPS_TASK = """
        $TASK_GAPS
        Find what is missing, unclear or unresolved in the meeting, then write questions that the user (the participant who recorded it) can answer to fill those gaps. Look for:
        - action items without an owner or a deadline
        - decisions that were discussed but not actually made
        - vague references ("that issue", "the numbers from last week") that a reader who was absent could not follow
        - numbers, dates, names or links mentioned but not specified
        - questions raised in the meeting that were never answered
        - garbled or inaudible parts of the transcript
        - unidentified speakers whose identity matters
        Return ONLY a JSON object:
        {
          "gaps": [{"title": "short label", "detail": "what is missing and where, citing timestamps", "suggestion": "how to close the gap"}],
          "questions": [{"question": "a direct question to the user", "context": "why you are asking, with a timestamp"}]
        }
        Ask at most 8 questions, most important first. Do not ask about anything the user has already answered.
    """.trimIndent()

    val ANSWER_TASK = """
        $TASK_ANSWER
        Answer the user's questions about the meeting, using it as the source of truth.
        - Cite timestamps like [12:34] when you refer to specific moments.
        - If the meeting does not answer the question, say so plainly, then give your best inference clearly labelled as an inference.
        - When asked to fill gaps or draft something (an email, follow-ups, a plan), you may go beyond the meeting but say which parts are your suggestions.
        - Be concise. Markdown is supported.
    """.trimIndent()

    val BRIEFING_TASK = """
        $TASK_BRIEFING
        Write an audio briefing: a spoken catch-up on the meeting for someone who missed it, read aloud by a text-to-speech voice.
        - 150 to 300 words, about one to two minutes when spoken.
        - Plain flowing sentences only: no Markdown, bullet points, headings, emoji or timestamps.
        - Open with what the meeting was about in one sentence, then the key decisions, who owns which next steps (with deadlines), and anything unresolved.
        - Use people's names when known. Unidentified speakers have placeholder names; call them "one participant" instead.
        Reply with the script only.
    """.trimIndent()

    fun namesTask(placeholders: List<String>) = """
        $TASK_NAMES
        Some speakers have random placeholder names because the app could not identify them: ${placeholders.joinToString()}.
        Using only evidence in the meeting (self-introductions, being addressed by name such as "Thanks, Priya", or a stated role), suggest the real name of each placeholder speaker. Do not guess without evidence; omit speakers you cannot identify.
        Return ONLY a JSON object:
        {"speakers": [{"placeholder": "Amber Falcon", "name": "Priya", "confidence": 0.9, "reason": "short evidence with a timestamp"}]}
    """.trimIndent()

    val GLOSSARY_TASK = """
        $TASK_GLOSSARY
        Find words in the transcript that speech recognition misheard and that should be one of the glossary terms (for example "pre a" for "Priya", or "cali scribe" for "KaliScriber").
        Return ONLY a JSON object listing each misheard phrase exactly as it appears in the transcript and its correction:
        {"fixes": [{"from": "pre a", "to": "Priya"}]}
        Only include confident fixes to glossary terms. Use an empty array when there are none.
    """.trimIndent()

    /** For "Ask across all meetings": [context] holds labelled meeting excerpts from [MeetingSearch]. */
    fun libraryAskSystem(context: String, today: Long, outputLanguage: String, glossary: String = "") = buildString {
        appendLine("You are KaliScriber's assistant. Answer the user's question using their recorded meetings, excerpts of which are below. Today is ${formatDate(today)}.")
        appendLine("- Each meeting is labelled [M1], [M2]… with its date. Cite the meeting and moment after each claim, like [M2 12:34], or [M2] for something from its summary.")
        appendLine("- Use only what the excerpts say. If they don't answer the question, say so and suggest what to search for instead.")
        appendLine("- Mention dates when the timing matters, and point out when meetings disagree or a decision changed later.")
        appendLine("- Be concise. Markdown is supported.")
        appendLine("- $PLACEHOLDER_RULE")
        appendLine("- ${languageRule(outputLanguage)}")
        glossaryBlock(glossary)?.let { append(it) }
        appendLine()
        appendLine("<meetings>")
        appendLine(context)
        appendLine("</meetings>")
    }

    const val SEARCH_KEYWORDS_SYSTEM = """You turn a question about past meetings into search keywords. Include the question's key terms, likely synonyms and related words someone might have said out loud (e.g. "pricing" -> "price", "cost", "discount"), and any names. Reply ONLY with JSON: {"keywords": ["...", "..."]} with at most 15 single words."""

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

    const val SEPARATE_SPEAKERS_SYSTEM = """You are diarizing a meeting transcript that has no reliable speaker labels. Each input line is one segment: "<index>| text". A segment may contain more than one speaker.
Work out who said what from turn-taking cues: questions and answers, greetings, people addressing each other, "I"/"you" references, changes of topic or style.
Label speakers S1, S2, S3... in order of first appearance, reusing the labels already established when they are given. Use "?" only when you genuinely cannot tell.
Return ONLY a compact JSON object. Give every segment index with its speaker. Do not repeat the segment text. When the speaker changes inside a segment, add another entry for the same index whose "from" holds the first 3 to 6 words of the new speaker's part, copied exactly:
{"turns": [{"i": 0, "s": "S1"}, {"i": 1, "s": "S2"}, {"i": 1, "s": "S1", "from": "Sure, I can take"}]}"""

    const val SPOKEN_ANSWER_RULE = """The user is talking to you by voice and will hear your answer read aloud. Reply in two to four short, natural spoken sentences. No Markdown, lists, headings or symbols. Say times like "about twelve minutes in" rather than citing [12:34]."""

    fun noteSystem(outputLanguage: String) = """
        You are KaliScriber, an AI note-taking assistant. You work on the user's notes, which may be typed or dictated (and so contain transcription errors).
        Keep every fact, number, name and commitment. Never invent information. Output Markdown.
        ${languageRule(outputLanguage)}
    """.trimIndent()

    fun formatDate(millis: Long): String =
        SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date(millis))
}
