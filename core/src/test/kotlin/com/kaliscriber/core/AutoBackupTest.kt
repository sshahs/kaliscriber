package com.kaliscriber.core

import com.kaliscriber.core.backup.AutoBackups
import com.kaliscriber.core.backup.BackupEvery
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AutoBackupTest {
    private val hour = 3_600_000L
    private fun at(text: String) = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `automatic backups are named by time and only they are tidied away`() {
        assertEquals("KaliScriber auto 2026-11-03 0215.ksbackup", AutoBackups.fileName(at("2026-11-03T02:15"), ZoneOffset.UTC))
        val names = listOf(
            "KaliScriber auto 2026-11-01 0200.ksbackup",
            "KaliScriber auto 2026-11-03 0200.ksbackup",
            "KaliScriber 2026-10-01.ksbackup", // made by hand: never deleted
            "KaliScriber auto 2026-10-30 0200.ksbackup",
            "KaliScriber auto 2026-11-02 0200 (1).ksbackup",
            "notes.txt",
        )
        assertEquals(
            listOf("KaliScriber auto 2026-11-01 0200.ksbackup", "KaliScriber auto 2026-10-30 0200.ksbackup"),
            AutoBackups.toDelete(names, keep = 2),
        )
        assertTrue(AutoBackups.toDelete(names, keep = 10).isEmpty())
        assertEquals(3, AutoBackups.toDelete(names, keep = 0).size, "the newest is always kept")
    }

    @Test
    fun `a backup is due once its interval has passed, give or take a little`() {
        val last = at("2026-11-03T02:00")
        assertTrue(AutoBackups.due(BackupEvery.DAILY, 0, last), "never backed up")
        assertFalse(AutoBackups.due(BackupEvery.DAILY, last, last + 12 * hour))
        assertTrue(AutoBackups.due(BackupEvery.DAILY, last, last + 23 * hour), "a little early is fine")
        assertFalse(AutoBackups.due(BackupEvery.WEEKLY, last, last + 3 * 24 * hour))
        assertTrue(AutoBackups.due(BackupEvery.WEEKLY, last, last + 7 * 24 * hour))
        assertFalse(AutoBackups.due(BackupEvery.OFF, 0, last))
    }
}
