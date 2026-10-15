package com.kaliscriber.core.search

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.transcript.TranscriptFormatter
import java.text.Normalizer
import kotlin.math.ln

/**
 * Keyword search over every meeting: titles, summaries, decisions, action items and transcripts.
 * Ranks with BM25 over transcript lines plus boosts for the meeting's own fields, and finds the
 * passages to hand the AI when asking across meetings.
 */
object MeetingSearch {

    enum class Field { TITLE, PEOPLE, SUMMARY, ACTION_ITEM, TRANSCRIPT }

    /** One place a query matched; [segmentId]/[startMs] are set for transcript lines. */
    data class Hit(
        val field: Field,
        val snippet: String,
        val segmentId: String? = null,
        val startMs: Long? = null,
        val score: Double = 0.0,
        /** Position of the line in its meeting's transcript. */
        val lineIndex: Int? = null,
    )

    data class Result(val meeting: Meeting, val score: Double, val hits: List<Hit>)

    /** Meetings matching [query], best first, each with its best [maxHits] matches. Meetings need their transcripts. */
    fun search(meetings: List<Meeting>, query: String, maxHits: Int = 3): List<Result> =
        search(meetings, MemoryCorpus(meetings), query, maxHits)

    /**
     * Meetings matching [query], best first, each with its best [maxHits] matches: their own fields
     * (title, people, summary, action items) from [meetings], which may leave out transcripts, and
     * transcript lines from [corpus].
     */
    fun search(meetings: List<Meeting>, corpus: SearchCorpus, query: String, maxHits: Int = 3): List<Result> {
        val terms = terms(query)
        if (terms.isEmpty()) return emptyList()
        val phrase = normalize(query).trim()
        val stats = CorpusStats(meetings, corpus)
        val byId = meetings.associateBy { it.id }
        val lineHits = corpus.candidates(terms, MAX_CANDIDATES).filter { it.meetingId in byId }.groupBy { it.meetingId }
        return meetings.mapNotNull { meeting ->
            val hits = mutableListOf<Hit>()
            fun fieldHit(field: Field, text: String?, boost: Double) {
                if (text.isNullOrBlank()) return
                val score = stats.score(terms, text) * boost + if (phrase.length > 2 && normalize(text).contains(phrase)) boost else 0.0
                if (score > 0) hits += Hit(field, snippet(text, terms), score = score)
            }
            fieldHit(Field.TITLE, meeting.title, 3.0)
            fieldHit(Field.PEOPLE, meeting.speakers.filter { !it.isPlaceholder }.joinToString(", ") { it.name }, 2.0)
            fieldHit(Field.SUMMARY, meeting.insights?.summary, 1.5)
            meeting.insights?.actionItems?.forEach { fieldHit(Field.ACTION_ITEM, listOfNotNull(it.task, it.owner).joinToString(" — "), 1.5) }
            for (line in lineHits[meeting.id].orEmpty()) {
                val score = stats.score(terms, line.text) + if (phrase.length > 2 && normalize(line.text).contains(phrase)) 2.0 else 0.0
                if (score > 0) hits += Hit(Field.TRANSCRIPT, snippet(line.text, terms), line.segmentId, line.startMs, score, line.index)
            }
            if (hits.isEmpty()) return@mapNotNull null
            val ranked = hits.sortedByDescending { it.score }
            // A meeting's score is led by its best match, with a little credit for more matches.
            val score = ranked.first().score + ranked.drop(1).take(4).sumOf { it.score } * 0.25
            Result(meeting, score, ranked.take(maxHits))
        }.sortedByDescending { it.score }
    }

    /** A labelled excerpt of one meeting for the AI, with its transcript passages. */
    data class Source(val label: String, val meeting: Meeting, val passages: List<List<TranscriptSegment>>)

    /** [retrieve] over meetings held in memory with their transcripts. */
    fun retrieve(meetings: List<Meeting>, keywords: List<String>, budgetChars: Int, maxMeetings: Int = 8): List<Source> =
        retrieve(meetings, MemoryCorpus(meetings), keywords, budgetChars, maxMeetings)

    /**
     * Picks what the AI should read to answer a question across meetings: for the best matching
     * meetings, a header (date, summary, decisions, action items) and the transcript passages around
     * the matching lines, within [budgetChars]. When nothing matches (e.g. "what happened last
     * week?"), the most recent meetings' summaries are used instead.
     */
    fun retrieve(
        meetings: List<Meeting>,
        corpus: SearchCorpus,
        keywords: List<String>,
        budgetChars: Int,
        maxMeetings: Int = 8,
        /** The question itself, to also find passages by meaning when the corpus can. */
        question: String? = null,
    ): List<Source> {
        val query = keywords.joinToString(" ")
        val terms = terms(query)
        val ranked = if (terms.isEmpty()) emptyList() else search(meetings, corpus, query, maxHits = Int.MAX_VALUE)
        val byId = meetings.associateBy { it.id }
        val similar = question?.let { corpus.similar(it, SIMILAR_PASSAGES) }.orEmpty()
            .filter { it.meetingId in byId && it.score >= MIN_SIMILARITY }
        val similarByMeeting = similar.groupBy { it.meetingId }

        // Reciprocal rank fusion: meetings near the top of either list come first.
        val fused = HashMap<String, Double>()
        ranked.forEachIndexed { rank, r -> fused[r.meeting.id] = (fused[r.meeting.id] ?: 0.0) + 1.0 / (RRF_K + rank) }
        similarByMeeting.keys.sortedByDescending { id -> similarByMeeting.getValue(id).maxOf { it.score } }
            .forEachIndexed { rank, id -> fused[id] = (fused[id] ?: 0.0) + 1.0 / (RRF_K + rank) }
        val lexical = ranked.associateBy { it.meeting.id }
        val chosen = if (fused.isEmpty()) {
            meetings.sortedByDescending { it.createdAt }.take(15).map { Result(it, 0.0, emptyList()) }
        } else {
            fused.entries.sortedByDescending { it.value }.take(maxMeetings).map { (id, score) ->
                lexical[id] ?: Result(byId.getValue(id), score, emptyList())
            }
        }
        var used = 0
        val sources = mutableListOf<Source>()
        chosen.forEachIndexed { i, result ->
            val meeting = result.meeting
            val label = "M${i + 1}"
            used += header(label, meeting).length
            val taken = sortedMapOf<Int, TranscriptSegment>()
            fun take(from: Int, to: Int) {
                val fresh = corpus.lines(meeting.id, from.coerceAtLeast(0), to).filter { it.index !in taken }
                used += fresh.sumOf { it.text.length + 30 }
                fresh.forEach { taken[it.index] = it.toSegment() }
            }
            // Matching lines with their neighbours, then passages that match by meaning.
            for (hit in result.hits) {
                if (used >= budgetChars) break
                val center = hit.lineIndex ?: continue
                take(center - 1, center + 1)
            }
            for (passage in similarByMeeting[meeting.id].orEmpty()) {
                if (used >= budgetChars) break
                take(passage.first, passage.last)
            }
            sources += Source(label, meeting, runs(taken))
            if (used >= budgetChars) return sources
        }
        return sources
    }

    /** The text block the AI reads for [source]. */
    fun render(source: Source, dateLabel: (Long) -> String): String = buildString {
        append(header(source.label, source.meeting, dateLabel))
        for (passage in source.passages) {
            appendLine("Excerpt:")
            appendLine(TranscriptFormatter.forPrompt(source.meeting, passage).lines().joinToString("\n") { "  $it" })
        }
    }

    private fun header(label: String, meeting: Meeting, dateLabel: (Long) -> String = { it.toString() }): String = buildString {
        appendLine("=== [$label] \"${meeting.title}\" (${dateLabel(meeting.createdAt)}, ${TranscriptFormatter.timestamp(meeting.durationMs)} long)")
        val people = meeting.speakers.filter { !it.isPlaceholder }.joinToString(", ") { it.name }
        if (people.isNotBlank()) appendLine("People: $people")
        meeting.insights?.let { ins ->
            if (ins.summary.isNotBlank()) appendLine("Summary: ${ins.summary.take(800)}")
            if (ins.decisions.isNotEmpty()) appendLine("Decisions: ${ins.decisions.joinToString("; ").take(600)}")
            val open = ins.actionItems.filter { !it.done }
            if (open.isNotEmpty()) {
                appendLine("Open action items: " + open.take(10).joinToString("; ") { a ->
                    a.task + listOfNotNull(a.owner, a.dueDate).joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
                })
            }
        }
    }

    /** Consecutive lines (by position) grouped into passages. */
    private fun runs(lines: Map<Int, TranscriptSegment>): List<List<TranscriptSegment>> {
        val out = mutableListOf<MutableList<TranscriptSegment>>()
        var last = -10
        for ((index, seg) in lines) {
            if (index != last + 1 || out.isEmpty()) out += mutableListOf<TranscriptSegment>()
            out.last() += seg
            last = index
        }
        return out
    }

    /**
     * Search terms in [text]: lower-cased word stems without accents, punctuation or common filler
     * words, so "price", "prices" and "pricing" all become "pric".
     */
    fun terms(text: String): List<String> = words(text).filter { it.length >= 2 && it !in STOP_WORDS }.map(::stem).distinct()

    private fun words(text: String): List<String> = normalize(text).split(NON_WORD).filter { it.isNotEmpty() }

    /** The words of [text] as the search index stores them: lower case, without accents or punctuation. */
    fun indexWords(text: String): List<String> = words(text)

    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Most transcript lines considered per search; the newest meetings' lines come first. */
    private const val MAX_CANDIDATES = 5_000

    /** Passages found by meaning for a question, and how alike they must be to count. */
    private const val SIMILAR_PASSAGES = 12
    private const val MIN_SIMILARITY = 0.25f

    /** The usual constant for reciprocal rank fusion. */
    private const val RRF_K = 60.0

    /** A light English stemmer: drops common endings so word forms match. */
    private fun stem(word: String): String {
        if (word.length <= 4 || word.any { it.isDigit() }) return word
        var w = word
        for (suffix in SUFFIXES) {
            if (w.endsWith(suffix) && w.length - suffix.length >= 3) {
                w = w.dropLast(suffix.length)
                break
            }
        }
        return if (w.length > 3 && w.endsWith('e')) w.dropLast(1) else w
    }

    private val SUFFIXES = listOf("ations", "ation", "ments", "ment", "ings", "ing", "ies", "ied", "ers", "es", "ed", "ly", "er", "s")

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")

    /** A short piece of [text] around the first matching word. */
    private fun snippet(text: String, terms: List<String>, width: Int = 160): String {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= width) return clean
        val lower = normalize(clean)
        val at = terms.mapNotNull { t -> lower.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: 0
        val start = (at - width / 3).coerceIn(0, (clean.length - width).coerceAtLeast(0))
        val end = (start + width).coerceAtMost(clean.length)
        return (if (start > 0) "…" else "") + clean.substring(start, end).trim() + if (end < clean.length) "…" else ""
    }

    /** BM25 statistics over all transcript lines and meeting fields. */
    private class CorpusStats(meetings: List<Meeting>, private val corpus: SearchCorpus) {
        private val fields = meetings.flatMap { listOfNotNull(it.title, it.insights?.summary) }.map { doc -> words(doc).map(::stem) }
        private val size: Int
        private val avgLength: Double
        private val docFreq = HashMap<String, Int>()

        init {
            val (lines, averageWords) = corpus.size()
            size = lines + fields.size
            val fieldWords = fields.sumOf { it.size }
            avgLength = if (size == 0) 1.0 else (lines * averageWords + fieldWords) / size
        }

        private fun df(term: String): Int = docFreq.getOrPut(term) {
            corpus.lineFrequency(term) + fields.count { term in it }
        }

        fun score(queryTerms: List<String>, text: String): Double {
            val stems = words(text).map(::stem)
            if (stems.isEmpty()) return 0.0
            val counts = stems.groupingBy { it }.eachCount()
            var score = 0.0
            for (term in queryTerms) {
                val tf = counts[term] ?: 0
                if (tf == 0) continue
                val df = df(term).coerceAtLeast(1)
                val idf = ln(1 + (size - df + 0.5) / (df + 0.5))
                score += idf * (tf * (K1 + 1)) / (tf + K1 * (1 - B + B * stems.size / avgLength))
            }
            return score
        }

        companion object {
            const val K1 = 1.2
            const val B = 0.75
        }
    }

    private val STOP_WORDS = setOf(
        "the", "a", "an", "and", "or", "but", "of", "to", "in", "on", "at", "for", "with", "is", "are", "was", "were",
        "be", "been", "it", "this", "that", "these", "those", "we", "you", "they", "he", "she", "i", "me", "my", "our",
        "your", "their", "what", "which", "who", "whom", "when", "where", "why", "how", "did", "do", "does", "about",
        "from", "as", "by", "so", "if", "then", "than", "there", "here", "any", "all", "some", "can", "could", "would",
        "should", "will", "just", "into", "over", "up", "out", "not", "no", "yes", "um", "uh", "like", "yeah", "ok", "okay",
        "has", "have", "had", "say", "said", "tell", "me", "us", "anything", "something", "meeting", "meetings",
    )
}
