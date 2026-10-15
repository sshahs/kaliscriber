package com.kaliscriber.core.store

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus

/** Which recordings to delete when the user keeps them only for a number of days. */
object RecordingRetention {
    const val DAY_MS = 24L * 60 * 60 * 1000

    /** The choices offered in Settings, in days (0 keeps recordings). */
    val CHOICES = listOf(0, 30, 90, 180, 365)

    /**
     * Finished meetings older than [days] that still have a recording. Meetings without a
     * transcript, or with audio still waiting to be transcribed, keep theirs: the recording is all
     * there is of them.
     */
    fun due(meetings: List<Meeting>, days: Int, now: Long): List<Meeting> =
        if (days <= 0) {
            emptyList()
        } else {
            meetings.filter { m ->
                m.audioPath != null && m.status == MeetingStatus.READY && m.pendingChunks.isEmpty() && m.hasTranscript &&
                    now - m.createdAt > days * DAY_MS
            }
        }

    fun label(days: Int): String = when (days) {
        0 -> "Keep forever"
        180 -> "6 months"
        365 -> "1 year"
        else -> "$days days"
    }
}
