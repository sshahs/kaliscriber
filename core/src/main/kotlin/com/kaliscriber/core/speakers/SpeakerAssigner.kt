package com.kaliscriber.core.speakers

import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.util.Ids
import kotlin.random.Random

/**
 * Maps provider speaker labels onto meeting [Speaker]s.
 *
 * Every label the app has not seen before becomes a new placeholder speaker with a random name.
 * Segments without any label (the provider could not tell who was speaking) go to a single
 * "unidentified" placeholder speaker, which also gets a random name and can be renamed or split later.
 */
object SpeakerAssigner {

    const val UNKNOWN_LABEL = "?"
    private const val MIN_RUN_WORDS = 3
    private const val MIN_RUN_MS = 1_000L

    data class Result(val speakers: List<Speaker>, val segments: List<TranscriptSegment>)

    fun assign(
        existing: List<Speaker>,
        raw: List<RawSegment>,
        offsetMs: Long = 0,
        random: Random = Random.Default,
    ): Result {
        val speakers = existing.toMutableList()
        val segments = raw.map { seg ->
            val label = seg.speakerLabel?.trim()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_LABEL
            val speaker = speakers.firstOrNull { it.label == label }
                ?: newPlaceholder(speakers, label, random).also { speakers += it }
            TranscriptSegment(
                id = Ids.new(),
                startMs = offsetMs + seg.startMs,
                endMs = offsetMs + maxOf(seg.endMs, seg.startMs),
                text = seg.text.trim(),
                speakerId = speaker.id,
                confidence = seg.confidence,
                words = seg.words?.map { it.copy(startMs = offsetMs + it.startMs) },
            )
        }
        return Result(speakers, segments)
    }

    /**
     * Labels existing transcript segments with the speaker turns from a separate diarization pass,
     * picking for each segment the speaker it overlaps most, or splitting it where the speaker
     * changes when it has word timings. Named (non-placeholder) speakers are
     * dropped too, because diarization speaker ids are new; users rename once at the end.
     */
    fun applyTurns(
        segments: List<TranscriptSegment>,
        turns: List<SpeakerTurn>,
        random: Random = Random.Default,
    ): Result {
        val speakers = mutableListOf<Speaker>()
        fun speakerFor(label: String) = speakers.firstOrNull { it.label == label }
            ?: newPlaceholder(speakers, label, random).also { speakers += it }
        val labelled = segments.flatMap { seg ->
            val pieces = splitAtTurns(seg, turns)
            if (pieces != null) {
                pieces.map { (speaker, piece) -> piece.copy(speakerId = speakerFor("diar:$speaker").id) }
            } else {
                val label = bestTurn(seg, turns)?.let { "diar:${it.speaker}" } ?: UNKNOWN_LABEL
                listOf(seg.copy(speakerId = speakerFor(label).id))
            }
        }
        return Result(speakers, labelled)
    }

    /**
     * Splits a segment with word timings where the diarized speaker changes, so a line that holds
     * a quick exchange between two people is shared out between them. Null when the segment has
     * no word timings or no turn covers its words.
     */
    private fun splitAtTurns(seg: TranscriptSegment, turns: List<SpeakerTurn>): List<Pair<String, TranscriptSegment>>? {
        val words = seg.words?.takeIf { it.isNotEmpty() } ?: return null
        val nearby = turns.filter { it.endMs > seg.startMs && it.startMs < seg.endMs }
        if (nearby.isEmpty()) return null
        // Each word goes to the turn it starts in; during overlapping speech, stay with the current speaker.
        val labels = arrayOfNulls<String>(words.size)
        var current: String? = null
        words.forEachIndexed { i, w ->
            val candidates = nearby.filter { w.startMs >= it.startMs && w.startMs < it.endMs }.map { it.speaker }
            current = when {
                candidates.isEmpty() -> current
                current in candidates -> current
                else -> candidates.first()
            }
            labels[i] = current
        }
        val firstKnown = labels.firstOrNull { it != null } ?: return null
        for (i in labels.indices) {
            if (labels[i] != null) break
            labels[i] = firstKnown
        }

        // Runs of words by speaker, as [start, end) word index ranges.
        val runs = mutableListOf<Triple<Int, Int, String>>()
        labels.forEachIndexed { i, label ->
            val last = runs.lastOrNull()
            if (last != null && last.third == label) runs[runs.lastIndex] = Triple(last.first, i + 1, last.third)
            else runs += Triple(i, i + 1, label!!)
        }
        // A word or two of someone else is usually a timing wobble, not a real turn: fold it into a neighbour.
        fun runMs(r: Triple<Int, Int, String>) =
            (if (r.second < words.size) words[r.second].startMs else seg.endMs) - words[r.first].startMs
        while (runs.size > 1) {
            val i = runs.indices.firstOrNull { (runs[it].second - runs[it].first) < MIN_RUN_WORDS && runMs(runs[it]) < MIN_RUN_MS } ?: break
            val into = if (i > 0) i - 1 else 1
            val lo = minOf(i, into)
            val hi = maxOf(i, into)
            runs[lo] = Triple(runs[lo].first, runs[hi].second, runs[into].third)
            runs.removeAt(hi)
            // Neighbours with the same speaker join up.
            var j = 1
            while (j < runs.size) {
                if (runs[j].third == runs[j - 1].third) {
                    runs[j - 1] = Triple(runs[j - 1].first, runs[j].second, runs[j].third)
                    runs.removeAt(j)
                } else {
                    j++
                }
            }
        }
        if (runs.size == 1) return listOf(runs[0].third to seg)

        return runs.mapIndexed { n, (from, to, speaker) ->
            val pieceWords = words.subList(from, to)
            val piece = seg.copy(
                id = if (n == 0) seg.id else Ids.new(),
                startMs = if (n == 0) seg.startMs else pieceWords.first().startMs,
                endMs = if (n == runs.lastIndex) seg.endMs else words[to].startMs,
                text = pieceWords.joinToString("") { it.text }.trim(),
                words = pieceWords.toList(),
            )
            speaker to piece
        }.filter { it.second.text.isNotEmpty() }
    }

    private fun bestTurn(seg: TranscriptSegment, turns: List<SpeakerTurn>): SpeakerTurn? {
        var best: SpeakerTurn? = null
        var bestOverlap = 0L
        // Sum overlap per speaker, since one segment can span several short turns of the same person.
        val bySpeaker = turns.groupBy { it.speaker }
        for ((_, speakerTurns) in bySpeaker) {
            val overlap = speakerTurns.sumOf { t -> (minOf(seg.endMs, t.endMs) - maxOf(seg.startMs, t.startMs)).coerceAtLeast(0) }
            if (overlap > bestOverlap) {
                bestOverlap = overlap
                best = speakerTurns.first()
            }
        }
        if (best != null) return best
        // No overlap (e.g. a zero-length segment): use the nearest turn within two seconds.
        val mid = (seg.startMs + seg.endMs) / 2
        return turns.minByOrNull { t -> if (mid < t.startMs) t.startMs - mid else if (mid > t.endMs) mid - t.endMs else 0 }
            ?.takeIf { t -> mid in (t.startMs - 2_000)..(t.endMs + 2_000) }
    }

    fun newPlaceholder(existing: List<Speaker>, label: String?, random: Random = Random.Default): Speaker =
        Speaker(
            id = Ids.new(),
            name = SpeakerNames.random(existing.map { it.name }, random),
            isPlaceholder = true,
            label = label,
            colorIndex = nextColor(existing),
        )

    fun nextColor(existing: List<Speaker>): Int = (existing.maxOfOrNull { it.colorIndex } ?: -1) + 1
}
