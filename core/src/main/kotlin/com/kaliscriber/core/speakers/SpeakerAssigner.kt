package com.kaliscriber.core.speakers

import com.kaliscriber.core.ai.stt.RawSegment
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
