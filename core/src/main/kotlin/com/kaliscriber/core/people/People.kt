package com.kaliscriber.core.people

import com.kaliscriber.core.model.Highlight
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.tasks.ActionItems

/**
 * Everyone the app knows about, gathered from named speakers, action item owners, highlight
 * speakers and saved voices, so each person can have a page with their meetings and commitments.
 */
object People {

    /** A meeting someone took part in, and how long they spoke in it. */
    data class Appearance(val meetingId: String, val title: String, val date: Long, val speakingMs: Long)

    data class Person(
        val name: String,
        /** Newest first. */
        val meetings: List<Appearance>,
        val openItems: List<ActionItems.Tracked>,
        val doneItems: List<ActionItems.Tracked>,
        val highlights: List<Pair<Appearance, Highlight>>,
        val voiceProfileId: String?,
    ) {
        val lastSeen: Long? get() = meetings.maxOfOrNull { it.date }
        val speakingMs: Long get() = meetings.sumOf { it.speakingMs }
    }

    /** Everyone, most recently seen first; people only known by a saved voice come last. */
    fun directory(meetings: List<Meeting>, voices: List<VoiceProfile>): List<Person> {
        val canonical = canonicalNames(meetings, voices)
        fun resolve(raw: String): String? = resolveName(raw, canonical)

        val appearances = HashMap<String, MutableMap<String, Appearance>>()
        val open = HashMap<String, MutableList<ActionItems.Tracked>>()
        val done = HashMap<String, MutableList<ActionItems.Tracked>>()
        val highlights = HashMap<String, MutableList<Pair<Appearance, Highlight>>>()
        val display = HashMap<String, String>()

        fun appear(name: String, meeting: Meeting, speakingMs: Long) {
            val key = key(name)
            display.putIfAbsent(key, name)
            val map = appearances.getOrPut(key) { LinkedHashMap() }
            val existing = map[meeting.id]
            map[meeting.id] = Appearance(meeting.id, meeting.title, meeting.createdAt, (existing?.speakingMs ?: 0) + speakingMs)
        }

        for (meeting in meetings) {
            for (speaker in meeting.speakers.filter { !it.isPlaceholder }) {
                val name = resolve(speaker.name) ?: continue
                val ms = meeting.segments.filter { it.speakerId == speaker.id }.sumOf { it.endMs - it.startMs }
                appear(name, meeting, ms)
            }
            val insights = meeting.insights ?: continue
            for (item in insights.actionItems) {
                for (owner in splitOwners(item.owner)) {
                    val name = resolve(owner) ?: continue
                    appear(name, meeting, 0)
                    val tracked = ActionItems.Tracked(meeting.id, meeting.title, meeting.createdAt, item)
                    (if (item.done) done else open).getOrPut(key(name)) { mutableListOf() } += tracked
                }
            }
            for (h in insights.highlights) {
                val name = h.speaker?.let(::resolve) ?: continue
                appear(name, meeting, 0)
                highlights.getOrPut(key(name)) { mutableListOf() } += appearances.getValue(key(name)).getValue(meeting.id) to h
            }
        }
        for (voice in voices) {
            val name = resolve(voice.name) ?: continue
            display.putIfAbsent(key(name), name)
            appearances.getOrPut(key(name)) { LinkedHashMap() }
        }

        val byVoice = voices.associateBy { key(it.name) }
        return display.map { (key, name) ->
            Person(
                name = name,
                meetings = appearances[key].orEmpty().values.sortedByDescending { it.date },
                openItems = open[key].orEmpty().sortedWith(compareBy<ActionItems.Tracked> { it.due ?: java.time.LocalDate.MAX }.thenByDescending { it.meetingDate }),
                doneItems = done[key].orEmpty().sortedByDescending { it.meetingDate },
                highlights = highlights[key].orEmpty().sortedByDescending { it.first.date },
                voiceProfileId = byVoice[key]?.id,
            )
        }.sortedWith(compareByDescending<Person> { it.lastSeen ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })
    }

    fun find(meetings: List<Meeting>, voices: List<VoiceProfile>, name: String): Person? =
        directory(meetings, voices).firstOrNull { key(it.name) == key(name) }

    private fun key(name: String) = name.trim().lowercase()

    /** Full names from speakers and saved voices; other mentions are matched to these. */
    private fun canonicalNames(meetings: List<Meeting>, voices: List<VoiceProfile>): List<String> =
        (meetings.flatMap { m -> m.speakers.filter { !it.isPlaceholder }.map { it.name } } + voices.map { it.name })
            .map { it.trim() }.filter { it.isNotEmpty() }
            .distinctBy { key(it) }

    /**
     * The name to file a mention under: the matching full name when the mention is that person's
     * first name (and only one known person has it), else the mention itself. Null for
     * placeholders like "team" or "unassigned".
     */
    private fun resolveName(raw: String, canonical: List<String>): String? {
        val name = raw.trim().trim('.', ',')
        if (name.length < 2 || key(name) in NOT_PEOPLE) return null
        canonical.firstOrNull { key(it) == key(name) }?.let { return it }
        val byFirstName = canonical.filter { key(it).substringBefore(' ') == key(name) }
        return byFirstName.singleOrNull() ?: name
    }

    /** "Sam and Priya" or "Sam, Priya" are two owners. */
    private fun splitOwners(owner: String?): List<String> =
        owner?.split(Regex(",|&|/|\\band\\b", RegexOption.IGNORE_CASE))?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()

    private val NOT_PEOPLE = setOf(
        "null", "none", "unassigned", "tbd", "n/a", "unknown", "team", "the team", "everyone", "all", "we", "us",
        "someone", "anyone", "group", "unknown speaker", "you", "me", "i",
    )
}
