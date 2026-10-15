package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.usage.TokenUsage
import com.kaliscriber.core.ai.usage.UsageRecord
import com.kaliscriber.core.backup.Backup
import com.kaliscriber.core.backup.BackupException
import com.kaliscriber.core.backup.LibraryBackup
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.Briefing
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.NoteRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupTest {
    private val root: File = Files.createTempDirectory("backup").toFile()
    private val password = "correct horse".toCharArray()

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    private fun backupOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        Backup.write(out, password, entries.asSequence().map { (path, data) -> Backup.Entry.bytes(path, data) })
    }.toByteArray()

    private suspend fun contents(backup: ByteArray, pw: CharArray = password): Map<String, ByteArray> {
        val read = linkedMapOf<String, ByteArray>()
        Backup.read(ByteArrayInputStream(backup), pw) { path, data -> read[path] = data.readBytes() }
        return read
    }

    @Test
    fun `files come back exactly, across many encrypted segments`() = runTest {
        val big = bytes(3_500_000, 1)
        val backup = backupOf("a.json" to "{\"x\":1}".toByteArray(), "audio/m/rec.m4a" to big, "empty" to ByteArray(0))
        val read = contents(backup)
        assertEquals(listOf("a.json", "audio/m/rec.m4a", "empty"), read.keys.toList())
        assertContentEquals(big, read.getValue("audio/m/rec.m4a"))
        assertEquals("{\"x\":1}", read.getValue("a.json").decodeToString())
        assertFalse(String(backup, Charsets.ISO_8859_1).contains("{\"x\":1}"), "contents are encrypted")
    }

    @Test
    fun `a wrong password, a changed byte or a cut-off file is refused`() = runTest {
        val backup = backupOf("a" to bytes(2_500_000, 2), "b" to "hello".toByteArray())

        assertEquals("Wrong password, or the backup is damaged.", assertFailsWith<BackupException> { contents(backup, "wrong".toCharArray()) }.message)

        val tampered = backup.copyOf().also { it[it.size - 100] = (it[it.size - 100] + 1).toByte() }
        assertEquals("This backup is damaged.", assertFailsWith<BackupException> { contents(tampered) }.message)

        for (cut in listOf(backup.size - 1, backup.size - 40_000, 44 + 4 + (1 shl 20) + 16)) {
            assertEquals("This backup is incomplete.", assertFailsWith<BackupException> { contents(backup.copyOf(cut)) }.message, "cut at $cut")
        }

        // Segments can't be swapped: each is sealed with its position.
        val header = 8 + 4 + 4 + 16 + 8
        val segment = 4 + (1 shl 20) + 16
        val swapped = backup.copyOf().also { b ->
            val first = backup.copyOfRange(header, header + segment)
            System.arraycopy(backup, header + segment, b, header, segment)
            System.arraycopy(first, 0, b, header + segment, segment)
        }
        assertFailsWith<BackupException> { contents(swapped) }

        assertEquals("This isn't a KaliScriber backup.", assertFailsWith<BackupException> { contents("PK\u0003\u0004 a zip".toByteArray()) }.message)
    }

    private class Phone(dir: File) {
        val meetings = MeetingRepository.inDir(dir)
        val notes = NoteRepository(JsonDocumentStore(File(dir, "notes"), Note.serializer()) { it.id })
        val settings = JsonValueStore(File(dir, "settings.json"), AppSettings.serializer(), AppSettings())
        val voices = JsonValueStore(File(dir, "voices.json"), ListSerializer(VoiceProfile.serializer()), emptyList())
        val askAll = JsonValueStore(File(dir, "ask-all.json"), ListSerializer(AskMessage.serializer()), emptyList())
        val usage = JsonValueStore(File(dir, "usage.json"), ListSerializer(UsageRecord.serializer()), emptyList())
        val backup = LibraryBackup(meetings, notes, settings, voices, askAll, usage, appVersion = "test", clock = { 1_000 })
    }

    private suspend fun Phone.meeting(title: String, vararg lines: String, recording: ByteArray? = null): String {
        val m = meetings.create(title, MeetingStatus.READY)
        val audio = recording?.let { File(meetings.audioDir(m.id), "recording.m4a").apply { writeBytes(it) } }
        val briefing = recording?.let { File(meetings.audioDir(m.id), "briefing.mp3").apply { writeBytes(it.copyOf(100)) } }
        meetings.update(m.id) {
            it.copy(
                audioPath = audio?.absolutePath,
                briefing = briefing?.let { b -> Briefing("Recap", b.absolutePath) },
                segments = lines.mapIndexed { i, t -> TranscriptSegment("${m.id}-$i", i * 1_000L, i * 1_000L + 900, t) },
            )
        }
        return m.id
    }

    @Test
    fun `restoring adds what the phone lacks and leaves the rest alone`() = runTest {
        val old = Phone(File(root, "old"))
        old.meetings.load()
        val recording = bytes(1_200_000, 3)
        val launch = old.meeting("Launch", "Prices rise in November.", "Agreed.", recording = recording)
        val hiring = old.meeting("Hiring", "Two engineers start Monday.")
        old.meetings.create("Still recording")
        old.notes.create("Ideas", "Ship the widget")
        old.settings.update { it.copy(glossary = "Priya") }
        old.voices.update { listOf(VoiceProfile("v1", "Priya", listOf(1f, 0f)), VoiceProfile("v2", "Sam", listOf(0f, 1f))) }
        old.askAll.update { listOf(AskMessage("q", ChatRole.USER, "What did we decide?", 5)) }
        old.usage.update { listOf(UsageRecord(10, "openai", "gpt", launch, "Summary", TokenUsage(100, 20))) }

        val file = ByteArrayOutputStream()
        old.backup.write(file, password, withRecordings = true)

        // The new phone already has the hiring meeting (renamed there) and one of the voices.
        val new = Phone(File(root, "new"))
        new.meetings.load()
        new.meetings.restore(old.meetings.get(hiring)!!.copy(title = "Hiring (edited here)"))
        new.voices.update { listOf(VoiceProfile("v2", "Sam S.", listOf(0f, 1f))) }
        new.usage.update { listOf(UsageRecord(10, "openai", "gpt", launch, "Summary", TokenUsage(100, 20)), UsageRecord(20, "ollama", "llama", null, "Ask", TokenUsage(5, 5))) }

        val progress = mutableListOf<String>()
        val result = new.backup.restore(ByteArrayInputStream(file.toByteArray()), password, replaceSettings = false, onProgress = { progress += it })

        assertEquals(LibraryBackup.Restored(meetingsAdded = 1, meetingsKept = 1, notesAdded = 1, voicesAdded = 1, recordings = 2, settingsRestored = false), result)
        assertEquals("Restoring a backup of 2 meetings…", progress.first(), "the meeting being recorded was left out")
        assertEquals(setOf("Launch", "Hiring (edited here)"), new.meetings.all().map { it.title }.toSet())
        val restored = new.meetings.get(launch)!!
        assertEquals(listOf("Prices rise in November.", "Agreed."), restored.segments.map { it.text })
        assertEquals(2, new.meetings.withoutTranscript(launch)!!.stats.lines)
        assertTrue(restored.audioPath!!.startsWith(File(root, "new").absolutePath), "recordings are in the new phone's folder")
        assertContentEquals(recording, File(restored.audioPath!!).readBytes())
        assertTrue(File(restored.briefing!!.audioPath!!).isFile)
        assertEquals(listOf("Ideas"), new.notes.notes.first().map { it.title })
        assertEquals(listOf("Sam S.", "Priya"), new.voices.value.value.map { it.name })
        assertEquals("What did we decide?", new.askAll.value.value.single().content)
        assertEquals(listOf(10L, 20L), new.usage.value.value.map { it.at }, "usage records are merged without duplicates")
        assertEquals("", new.settings.value.value.glossary, "settings stay unless asked for")

        // Restoring again changes nothing.
        val again = new.backup.restore(ByteArrayInputStream(file.toByteArray()), password, replaceSettings = true)
        assertEquals(LibraryBackup.Restored(0, 2, 0, 0, 0, settingsRestored = true), again)
        assertEquals("Priya", new.settings.value.value.glossary)
        assertEquals(2, new.voices.value.value.size)
    }

    @Test
    fun `without recordings, restored meetings have no audio to play`() = runTest {
        val old = Phone(File(root, "old2"))
        old.meetings.load()
        val id = old.meeting("Standup", "Blocked on review.", recording = bytes(50_000, 4))
        val file = ByteArrayOutputStream().also { old.backup.write(it, password, withRecordings = false) }

        val new = Phone(File(root, "new2"))
        val result = new.backup.restore(ByteArrayInputStream(file.toByteArray()), password, replaceSettings = false)
        assertEquals(0, result.recordings)
        assertNull(new.meetings.get(id)!!.audioPath)
        assertNull(new.meetings.get(id)!!.briefing!!.audioPath)
        assertEquals("Blocked on review.", new.meetings.get(id)!!.segments.single().text)
        assertTrue(file.size() < 20_000, "recordings were left out (${file.size()} bytes)")
    }

    @Test
    fun `a wrong password restores nothing`() = runTest {
        val old = Phone(File(root, "old3"))
        old.meetings.load()
        old.meeting("Secret", "Nothing to see.")
        val file = ByteArrayOutputStream().also { old.backup.write(it, password, withRecordings = false) }
        val new = Phone(File(root, "new3"))
        assertFailsWith<BackupException> {
            new.backup.restore(ByteArrayInputStream(file.toByteArray()), "nope".toCharArray(), replaceSettings = true)
        }
        new.meetings.load()
        assertTrue(new.meetings.all().isEmpty())
    }
}
