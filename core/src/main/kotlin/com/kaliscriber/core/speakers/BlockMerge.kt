package com.kaliscriber.core.speakers

import com.kaliscriber.core.ai.stt.DiarizedBlock
import com.kaliscriber.core.ai.stt.SpeakerTurn

/**
 * Joins the speakers of separately diarized blocks of a meeting into meeting-wide speakers by
 * merging their voiceprints with [SpeakerClusters]. Used both for long recordings diarized after
 * the meeting and for blocks diarized while it was being recorded.
 */
object BlockMerge {

    /** All turns with meeting-wide speaker ids ("0", "1", ...); [speakers] is the expected head count. */
    fun merge(blocks: List<DiarizedBlock>, speakers: Int?): List<SpeakerTurn> {
        val keys = mutableListOf<Pair<Int, String>>()
        val items = mutableListOf<SpeakerClusters.Item>()
        blocks.forEachIndexed { b, block ->
            block.voices.forEach { (speaker, voice) ->
                keys += b to speaker
                items += SpeakerClusters.Item(voice.embedding, voice.seconds)
            }
        }
        if (items.isEmpty()) {
            // No usable voiceprints at all: keep each block's own labels.
            return blocks.flatMapIndexed { b, block -> block.turns.map { it.copy(speaker = "$b-${it.speaker}") } }
        }
        val ids = SpeakerClusters.merge(items, target = speakers)
        val global = keys.mapIndexed { i, key -> key to ids[i] }.toMap()
        // Block speakers with too little speech for a voiceprint are dropped; their lines go to a neighbouring turn.
        return blocks.flatMapIndexed { b, block ->
            block.turns.mapNotNull { turn -> global[b to turn.speaker]?.let { turn.copy(speaker = it.toString()) } }
        }.sortedBy { it.startMs }
    }
}
