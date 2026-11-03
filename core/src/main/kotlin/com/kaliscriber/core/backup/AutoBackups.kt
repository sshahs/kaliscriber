package com.kaliscriber.core.backup

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How often a backup is made by itself. */
@Serializable
enum class BackupEvery(val label: String, val days: Int) {
    OFF("Off", 0),
    DAILY("Every day", 1),
    WEEKLY("Every week", 7),
}

/** Backups made by themselves: what they're called, when the next is due and which old ones go. */
object AutoBackups {
    private const val PREFIX = "KaliScriber auto "
    private const val SUFFIX = ".ksbackup"
    private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
    private val NAME = Regex("""^KaliScriber auto \d{4}-\d{2}-\d{2} \d{4}( \(\d+\))?\.ksbackup$""")

    /** "KaliScriber auto 2026-11-03 0215.ksbackup": sorts by time, and tells automatic backups apart from the user's own. */
    fun fileName(now: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        PREFIX + STAMP.format(Instant.ofEpochMilli(now).atZone(zone)) + SUFFIX

    fun isAutomatic(name: String): Boolean = NAME.matches(name)

    /** The automatic backups among [names] to delete so that only the newest [keep] remain. */
    fun toDelete(names: List<String>, keep: Int): List<String> =
        names.filter(::isAutomatic).sortedDescending().drop(keep.coerceAtLeast(1))

    /** True when a backup is due at [now], given the last one at [lastAt] (0 for never). */
    fun due(every: BackupEvery, lastAt: Long, now: Long): Boolean {
        if (every == BackupEvery.OFF) return false
        // A little slack, so a daily backup at about the same time each day isn't pushed back a day.
        val interval = every.days * 24L * 3_600_000 - SLACK_MS
        return lastAt <= 0 || now - lastAt >= interval
    }

    private const val SLACK_MS = 2L * 3_600_000
}

/** How the latest automatic backup went. */
@Serializable
data class AutoBackupStatus(
    val lastAt: Long = 0,
    val lastFile: String = "",
    val lastError: String? = null,
    val lastErrorAt: Long = 0,
)
