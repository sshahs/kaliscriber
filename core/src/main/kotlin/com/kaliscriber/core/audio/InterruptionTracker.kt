package com.kaliscriber.core.audio

import com.kaliscriber.core.model.Interruption

/**
 * Notices when the microphone stops delivering sound because something else took it. Android
 * then hands a recording app silence: it says so on Android 10 and later ("silenced"), and on
 * any version the samples become exact zeros, which a working microphone never produces for long.
 */
class InterruptionTracker(
    /** How long exact digital silence must last to count, so a muted moment isn't a gap. */
    private val zeroRunMs: Long = 2_000,
) {
    sealed interface Event {
        data class Started(val atMs: Long, val reason: String) : Event
        data class Ended(val gap: Interruption) : Event
    }

    private var zeroSinceMs: Long? = null
    private var startMs: Long? = null
    private var reason = ""

    val interrupted: Boolean get() = startMs != null

    /**
     * One block of audio from [atMs] to [endMs] of the recording. [allZero] is true when every
     * sample was exactly zero; [silenced] when Android reports this app silenced. [reason] says why,
     * and is only asked for when an interruption starts.
     */
    fun block(atMs: Long, endMs: Long, allZero: Boolean, silenced: Boolean, reason: () -> String): Event? {
        zeroSinceMs = if (allZero) zeroSinceMs ?: atMs else null
        val zeroSince = zeroSinceMs
        val longSilence = zeroSince != null && endMs - zeroSince >= zeroRunMs
        val now = silenced || longSilence
        val start = startMs
        return when {
            now && start == null -> {
                val from = if (silenced || zeroSince == null) atMs else zeroSince
                startMs = from
                this.reason = reason()
                Event.Started(from, this.reason)
            }
            !now && start != null -> {
                startMs = null
                Event.Ended(Interruption(start, atMs - start, this.reason))
            }
            else -> null
        }
    }

    /** Closes an interruption still going when recording stops at [endMs]. */
    fun finish(endMs: Long): Interruption? {
        val start = startMs ?: return null
        startMs = null
        return Interruption(start, (endMs - start).coerceAtLeast(0), reason)
    }

    companion object {
        /** True when every 16-bit little-endian sample in the first [length] bytes is exactly zero. */
        fun allZero(buffer: ByteArray, length: Int): Boolean {
            for (i in 0 until length) if (buffer[i].toInt() != 0) return false
            return length > 0
        }
    }
}
