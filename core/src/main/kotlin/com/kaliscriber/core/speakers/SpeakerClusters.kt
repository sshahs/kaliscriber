package com.kaliscriber.core.speakers

import kotlin.math.sqrt

/**
 * Joins the speakers found in separate blocks of a long recording into one set of speakers by
 * comparing their voiceprints. Long meetings are diarized in blocks to keep memory bounded, and
 * each block numbers its speakers independently. The diarizer also tends to split one person into
 * several clusters within a block, which this merges back together.
 */
object SpeakerClusters {

    /** One speaker found by the diarizer, with their voiceprint and how much they spoke (seconds). */
    class Item(val embedding: FloatArray, val weight: Double = 1.0)

    /**
     * Returns the global speaker number for each item, numbered in order of first appearance.
     *
     * The closest pair of speakers is merged while their voices are at least [minSimilarity] alike
     * (cosine of the speaking-time weighted voiceprints). With [target] speakers, merging continues
     * until no more than [target] remain.
     */
    fun merge(items: List<Item>, target: Int? = null, minSimilarity: Double = 0.5): IntArray {
        if (items.isEmpty()) return IntArray(0)
        val members = items.indices.map { mutableListOf(it) }.toMutableList()
        val sums = items.map { item -> normalized(item.embedding).map { it * item.weight.coerceAtLeast(0.1) }.toDoubleArray() }
            .toMutableList()

        while (members.size > 1) {
            var best: Pair<Int, Int>? = null
            var bestSim = Double.NEGATIVE_INFINITY
            for (a in members.indices) {
                for (b in a + 1 until members.size) {
                    val sim = cosine(sums[a], sums[b])
                    if (sim > bestSim) {
                        bestSim = sim
                        best = a to b
                    }
                }
            }
            val tooMany = target != null && members.size > target
            if (best == null || (bestSim < minSimilarity && !tooMany)) break
            val (a, b) = best
            members[a].addAll(members[b])
            for (k in sums[a].indices) sums[a][k] += sums[b][k]
            members.removeAt(b)
            sums.removeAt(b)
        }

        val result = IntArray(items.size)
        members.sortedBy { it.min() }.forEachIndexed { speaker, list -> list.forEach { result[it] = speaker } }
        return result
    }

    private fun normalized(v: FloatArray): DoubleArray {
        val norm = sqrt(v.sumOf { it.toDouble() * it }).takeIf { it > 0 } ?: 1.0
        return DoubleArray(v.size) { v[it] / norm }
    }

    private fun cosine(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return -1.0
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0.0 || nb == 0.0) 0.0 else dot / sqrt(na * nb)
    }
}
