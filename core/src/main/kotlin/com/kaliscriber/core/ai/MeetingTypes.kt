package com.kaliscriber.core.ai

import com.kaliscriber.core.model.Meeting
import kotlinx.serialization.Serializable

/**
 * Kinds of meeting with their own summary format. Every format keeps the common notes (summary,
 * action items, decisions…) and adds sections that matter for that kind of meeting.
 */
@Serializable
enum class MeetingType(val label: String, val focus: String, val sections: List<Pair<String, String>>) {
    GENERAL("General", "", emptyList()),
    ONE_ON_ONE(
        "1:1",
        "This is a one-to-one meeting between a manager and a report, or two colleagues.",
        listOf(
            "Updates" to "what each person reported on their work",
            "Feedback" to "feedback given in either direction, with specifics",
            "Wellbeing & concerns" to "workload, morale or personal concerns raised (keep it factual and respectful)",
            "Growth" to "career, skills and development topics",
        ),
    ),
    STANDUP(
        "Stand-up",
        "This is a short team stand-up: keep everything brief and per person.",
        listOf(
            "By person" to "one item per person: \"Name: done …; next …; blockers …\"",
            "Blockers" to "every blocker, who has it and who can help",
        ),
    ),
    SALES_CALL(
        "Sales call",
        "This is a call with a customer or prospect; write it for the seller's follow-up.",
        listOf(
            "Customer needs" to "the customer's goals, problems and requirements",
            "Budget, authority & timeline" to "budget, who decides, and timing, as stated",
            "Objections" to "concerns or objections and how they were answered",
            "Competitors" to "other products or vendors mentioned",
            "Commitments" to "what each side agreed to do next",
        ),
    ),
    INTERVIEW(
        "Interview",
        "This is a job interview; write it for the hiring decision, fairly and based only on what was said.",
        listOf(
            "Candidate background" to "experience and skills the candidate described",
            "Strengths" to "evidence of strengths, with examples",
            "Concerns" to "gaps or concerns, with examples",
            "Questions & answers" to "key questions asked and the gist of each answer",
            "Recommendation" to "a cautious overall read and what to probe next; say if the evidence is thin",
        ),
    ),
    LECTURE(
        "Lecture",
        "This is a lecture, class or talk; write study notes.",
        listOf(
            "Main concepts" to "the ideas taught, each explained in a sentence or two",
            "Definitions" to "terms and their definitions",
            "Examples" to "examples and demonstrations used",
            "Review questions" to "questions to test understanding of the material",
            "References" to "books, papers, links or names mentioned",
        ),
    ),
    ;

    companion object {
        private val PATTERNS = listOf(
            STANDUP to Regex("""\b(stand-?up|daily|scrum|huddle)\b"""),
            ONE_ON_ONE to Regex("""\b(1\s*:\s*1|1-1|1on1|one[- ]on[- ]one|1-on-1|catch[- ]?up with)\b"""),
            INTERVIEW to Regex("""\b(interview|screening|candidate)\b"""),
            SALES_CALL to Regex("""\b(sales|discovery|prospect|demo|customer call|client call|pitch|renewal)\b"""),
            LECTURE to Regex("""\b(lecture|class|seminar|lesson|course|webinar|talk|tutorial|workshop)\b"""),
        )

        /** The kind of meeting its title (or its calendar event's) suggests; GENERAL when nothing does. */
        fun guess(meeting: Meeting): MeetingType {
            val text = listOfNotNull(meeting.calendarEvent?.title, meeting.title.takeIf { !meeting.autoTitle }).joinToString(" ").lowercase()
            return PATTERNS.firstOrNull { (_, pattern) -> pattern.containsMatchIn(text) }?.first ?: GENERAL
        }

        /** The meeting's chosen type, or a guess. */
        fun of(meeting: Meeting): MeetingType = meeting.type ?: guess(meeting)
    }
}
