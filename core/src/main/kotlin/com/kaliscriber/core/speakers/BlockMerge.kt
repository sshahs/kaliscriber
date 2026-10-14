package com.kaliscriber.core.speakers

import com.kaliscriber.core.ai.stt.DiarizedBlock
import com.kaliscriber.core.ai.stt.SpeakerTurn

/**
 * Joins the speakers of separately diarized blocks of a meeting into meeting-wide speakers by
 * merging their voiceprints with [SpeakerClusters]. Used both for long recordings diarized after
 * the meeting and for blocks diarized while it was being recorded.
 */
object BlockMerge {

    /** Meeting-wide speaker turns, and each speaker's voiceprint (keyed like the turns' speakers). */
    class Merged(val turns: List<SpeakerTurn>, val voices: Map<String, DiarizedBlock.Voice>)

    /** All turns with meeting-wide speaker ids ("0", "1", ...); [speakers] is the expected head count. */
    fun merge(blocks: List<DiarizedBlock>, speakers: Int?): List<SpeakerTurn> = mergeWithVoices(blocks, speakers).turns

    /** Like [merge], also giving each meeting-wide speaker a voiceprint: the speaking-time weighted average of theirs. */
    fun mergeWithVoices(blocks: List<DiarizedBlock>, speakers: Int?): Merged {
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
            return Merged(blocks.flatMapIndexed { b, block -> block.turns.map { it.copy(speaker = "$b-${it.speaker}") } }, emptyMap())
        }
        val ids = SpeakerClusters.merge(items, target = speakers)
        val global = keys.mapIndexed { i, key -> key to ids[i] }.toMap()
        // Block speakers with too little speech for a voiceprint are dropped; their lines go to a neighbouring turn.
        val turns = blocks.flatMapIndexed { b, block ->
            block.turns.mapNotNull { turn -> global[b to turn.speaker]?.let { turn.copy(speaker = it.toString()) } }
        }.sortedBy { it.startMs }
        val voices = items.indices.groupBy { ids[it] }.map { (id, members) ->
            val size = items[members.first()].embedding.size
            val sum = FloatArray(size)
            for (i in members) {
                val unit = VoiceLibrary.normalize(items[i].embedding)
                if (unit.size != size) continue
                val w = items[i].weight.coerceAtLeast(0.1).toFloat()
                for (k in 0 until size) sum[k] += unit[k] * w
            }
            id.toString() to DiarizedBlock.Voice(VoiceLibrary.normalize(sum), members.sumOf { items[it].weight })
        }.toMap()
        return Merged(turns, voices)
    }
}

/**
 * Keeps speakers' identities while a meeting is diarized block by block during recording. Each
 * time the blocks are merged again, the merged speakers may be numbered differently, so they are
 * matched by voice to the speakers found before and keep those speakers' keys.
 */
class StableSpeakers(private val minSimilarity: Float = 0.5f) {
    private val known = LinkedHashMap<String, FloatArray>()
    private var next = 0

    /** [merged] with each speaker renamed to its lasting key. */
    @Synchronized
    fun stabilize(merged: BlockMerge.Merged): BlockMerge.Merged {
        // Without voiceprints the blocks keep their own labels, which already stay the same.
        if (merged.voices.isEmpty()) return merged
        val pairs = mutableListOf<Triple<String, String, Float>>()
        for ((id, voice) in merged.voices) {
            for ((key, print) in known) pairs += Triple(id, key, VoiceLibrary.cosine(voice.embedding, print.toList()))
        }
        val keyOf = mutableMapOf<String, String>()
        val taken = mutableSetOf<String>()
        for ((id, key, score) in pairs.sortedByDescending { it.third }) {
            if (score < minSimilarity || id in keyOf || key in taken) continue
            keyOf[id] = key
            taken += key
        }
        // New voices get new keys, in order of first appearance.
        val order = merged.turns.map { it.speaker }.distinct() + merged.voices.keys
        for (id in order.distinct()) if (id !in keyOf) keyOf[id] = "s${next++}"
        for ((id, voice) in merged.voices) known[keyOf.getValue(id)] = voice.embedding
        return BlockMerge.Merged(
            merged.turns.map { it.copy(speaker = keyOf.getValue(it.speaker)) },
            merged.voices.mapKeys { keyOf.getValue(it.key) },
        )
    }
}
