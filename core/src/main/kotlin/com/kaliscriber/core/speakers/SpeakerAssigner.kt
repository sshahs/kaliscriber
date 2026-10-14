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
            )
        }
        return Result(speakers, segments)
    }

    /**
     * Labels existing transcript segments with the speaker turns from a separate diarization pass,
     * picking for each segment the speaker it overlaps most. Named (non-placeholder) speakers are
     * dropped too, because diarization speaker ids are new; users rename once at the end.
     */
    fun applyTurns(
        segments: List<TranscriptSegment>,
        turns: List<SpeakerTurn>,
        random: Random = Random.Default,
    ): Result {
        val speakers = mutableListOf<Speaker>()
        val labelled = segments.map { seg ->
            val label = bestTurn(seg, turns)?.let { "diar:${it.speaker}" } ?: UNKNOWN_LABEL
            val speaker = speakers.firstOrNull { it.label == label }
                ?: newPlaceholder(speakers, label, random).also { speakers += it }
            seg.copy(speakerId = speaker.id)
        }
        return Result(speakers, labelled)
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
