package com.kaliscriber.core.speakers

import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** A remembered voice: a person's name and the average of their voiceprints. Stored only on the device. */
@Serializable
data class VoiceProfile(
    val id: String,
    val name: String,
    val embedding: List<Float>,
    val samples: Int = 1,
    val updatedAt: Long = 0,
)

data class VoiceMatch(val profile: VoiceProfile, val score: Float)

/**
 * The user's saved voices. When a meeting's speakers have voiceprints, [assign] finds which saved
 * people they are; renaming a speaker [enroll]s their voice so they are recognised next time.
 */
class VoiceLibrary(
    private val store: JsonValueStore<List<VoiceProfile>>,
    private val clock: Clock = Clock.System,
) {
    val profiles: StateFlow<List<VoiceProfile>> = store.value

    /**
     * Matches each speaker's voiceprint to at most one saved voice and each saved voice to at most
     * one speaker, best scores first. Matches must clear [threshold] and beat the speaker's
     * second-best candidate by [margin], so similar voices are left for the user to name.
     */
    fun assign(
        voiceprints: Map<String, FloatArray>,
        threshold: Float = DEFAULT_THRESHOLD,
        margin: Float = DEFAULT_MARGIN,
    ): Map<String, VoiceMatch> {
        val saved = profiles.value
        if (saved.isEmpty() || voiceprints.isEmpty()) return emptyMap()
        val candidates = mutableListOf<Triple<String, VoiceProfile, Float>>()
        for ((speakerId, print) in voiceprints) {
            val scores = saved.map { it to cosine(print, it.embedding) }.sortedByDescending { it.second }
            val best = scores.first()
            val second = scores.getOrNull(1)?.second ?: -1f
            if (best.second >= threshold && best.second - second >= margin) {
                candidates += Triple(speakerId, best.first, best.second)
            }
        }
        val result = mutableMapOf<String, VoiceMatch>()
        val usedProfiles = mutableSetOf<String>()
        for ((speakerId, profile, score) in candidates.sortedByDescending { it.third }) {
            if (speakerId in result || profile.id in usedProfiles) continue
            result[speakerId] = VoiceMatch(profile, score)
            usedProfiles += profile.id
        }
        return result
    }

    /** Adds [voiceprint] to the person called [name], creating them if needed. */
    suspend fun enroll(name: String, voiceprint: FloatArray): VoiceProfile {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A name is required" }
        val print = normalize(voiceprint)
        var saved: VoiceProfile? = null
        store.update { list ->
            val existing = list.firstOrNull { it.name.equals(clean, ignoreCase = true) }
            val updated = if (existing == null || existing.embedding.size != print.size) {
                VoiceProfile(Ids.new(), clean, print.toList(), 1, clock.now())
            } else {
                // Running average, capped so the voiceprint keeps adapting to new recordings.
                val weight = existing.samples.coerceAtMost(MAX_WEIGHT).toFloat()
                val merged = FloatArray(print.size) { i -> existing.embedding[i] * weight + print[i] }
                existing.copy(
                    name = clean,
                    embedding = normalize(merged).toList(),
                    samples = existing.samples + 1,
                    updatedAt = clock.now(),
                )
            }
            saved = updated
            list.filterNot { it.id == updated.id || (existing == null && it.name.equals(clean, ignoreCase = true)) } + updated
        }
        return saved!!
    }

    suspend fun rename(id: String, name: String) = store.update { list ->
        if (name.isBlank()) list else list.map { if (it.id == id) it.copy(name = name.trim()) else it }
    }

    suspend fun delete(id: String) = store.update { list -> list.filterNot { it.id == id } }

    companion object {
        const val DEFAULT_THRESHOLD = 0.6f
        const val DEFAULT_MARGIN = 0.05f
        private const val MAX_WEIGHT = 20

        fun normalize(v: FloatArray): FloatArray {
            val norm = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
            return if (norm == 0f) v.copyOf() else FloatArray(v.size) { v[it] / norm }
        }

        fun cosine(a: FloatArray, b: List<Float>): Float {
            if (a.size != b.size || a.isEmpty()) return -1f
            var dot = 0.0
            var na = 0.0
            var nb = 0.0
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na == 0.0 || nb == 0.0) return -1f
            return (dot / (sqrt(na) * sqrt(nb))).toFloat()
        }
    }
}
