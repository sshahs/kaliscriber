package com.kaliscriber.core.series

import com.kaliscriber.core.model.Meeting

/** Who spoke how much in a meeting, how often they took the floor, and what they asked. */
object TalkStats {
    data class Person(
        val speakerId: String,
        val name: String,
        val speakingMs: Long,
        /** Share of all speaking time, 0..1. */
        val share: Float,
        /** Times they took over from someone else. */
        val turns: Int,
        val questions: Int,
        /** Their longest stretch without anyone else speaking. */
        val longestMs: Long,
    )

    data class Stats(val people: List<Person>, val speakingMs: Long, val turns: Int, val questions: Int)

    /** Null without a transcript. People are listed by speaking time, most first. */
    fun of(meeting: Meeting): Stats? {
        val segments = meeting.segments.filter { it.speakerId != null }.sortedBy { it.startMs }
        if (segments.isEmpty()) return null
        val speaking = HashMap<String, Long>()
        val turns = HashMap<String, Int>()
        val questions = HashMap<String, Int>()
        val longest = HashMap<String, Long>()
        var runSpeaker: String? = null
        var runStart = 0L
        var runEnd = 0L
        fun closeRun() {
            val id = runSpeaker ?: return
            longest[id] = maxOf(longest[id] ?: 0L, runEnd - runStart)
        }
        for (seg in segments) {
            val id = seg.speakerId!!
            speaking[id] = (speaking[id] ?: 0L) + (seg.endMs - seg.startMs).coerceAtLeast(0)
            questions[id] = (questions[id] ?: 0) + seg.text.count { it == '?' }
            if (id != runSpeaker) {
                closeRun()
                runSpeaker = id
                runStart = seg.startMs
                turns[id] = (turns[id] ?: 0) + 1
            }
            runEnd = maxOf(runEnd, seg.endMs)
        }
        closeRun()
        val total = speaking.values.sum().coerceAtLeast(1)
        val people = speaking.keys.map { id ->
            Person(
                speakerId = id,
                name = meeting.speaker(id)?.name ?: "Unknown",
                speakingMs = speaking.getValue(id),
                share = speaking.getValue(id).toFloat() / total,
                turns = turns[id] ?: 0,
                questions = questions[id] ?: 0,
                longestMs = longest[id] ?: 0,
            )
        }.sortedByDescending { it.speakingMs }
        return Stats(people, speaking.values.sum(), turns.values.sum(), questions.values.sum())
    }
}
