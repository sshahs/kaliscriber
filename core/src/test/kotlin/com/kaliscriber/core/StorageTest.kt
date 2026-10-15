package com.kaliscriber.core

import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.RecordingRetention
import com.kaliscriber.core.store.RecordingRetention.DAY_MS
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageTest {
    private val root: File = Files.createTempDirectory("storage").toFile()
    private var now = 100 * DAY_MS
    private val repo = MeetingRepository.inDir(root, clock = { now })

    private suspend fun meeting(title: String, ageDays: Int, transcript: Boolean = true, audioBytes: Int = 1000): String {
        now = 100 * DAY_MS - ageDays * DAY_MS
        val m = repo.create(title, MeetingStatus.READY)
        val audio = File(repo.audioDir(m.id), "recording.m4a").apply { writeBytes(ByteArray(audioBytes)) }
        repo.update(m.id) {
            it.copy(
                audioPath = audio.absolutePath,
                segments = if (transcript) listOf(TranscriptSegment("s", 0, 1000, "Hello.")) else emptyList(),
            )
        }
        now = 100 * DAY_MS
        return m.id
    }

    @Test
    fun `only old, finished, transcribed recordings are due`() = runTest {
        repo.load()
        val old = meeting("Old", 40)
        meeting("Old but never transcribed", 40, transcript = false)
        meeting("Recent", 10)
        val waiting = meeting("Old with audio left to transcribe", 50)
        repo.update(waiting) { it.copy(pendingChunks = listOf(AudioChunk(0, "x", 0, 1))) }
        val processing = meeting("Old and being processed", 60)
        repo.update(processing) { it.copy(status = MeetingStatus.ANALYZING) }

        assertEquals(listOf(old), RecordingRetention.due(repo.all(), 30, now).map { it.id })
        assertEquals(emptyList(), RecordingRetention.due(repo.all(), 0, now), "0 keeps recordings")
        assertEquals(listOf("Keep forever", "30 days", "90 days", "6 months", "1 year"), RecordingRetention.CHOICES.map(RecordingRetention::label))
    }

    @Test
    fun `deleting a recording keeps the transcript and frees its space`() = runTest {
        repo.load()
        val id = meeting("Weekly", 40, audioBytes = 5000)
        val chunks = File(repo.audioDir(id), "chunks").apply { mkdirs() }
        File(chunks, "chunk-0.m4a").writeBytes(ByteArray(700))
        val recording = File(repo.withoutTranscript(id)!!.audioPath!!)

        assertEquals(5700L, repo.deleteRecording(id))
        assertFalse(recording.exists())
        assertFalse(chunks.exists())
        val m = repo.get(id)!!
        assertNull(m.audioPath)
        assertEquals(now, m.audioDeletedAt)
        assertEquals("Hello.", m.segments.single().text)
        assertEquals(0L, repo.deleteRecording(id), "nothing left to delete")
    }

    @Test
    fun `files outside the app's audio folder are never deleted`() = runTest {
        repo.load()
        val id = meeting("Imported", 40)
        val elsewhere = File(root, "elsewhere.m4a").apply { writeBytes(ByteArray(100)) }
        repo.update(id) { it.copy(audioPath = elsewhere.absolutePath) }
        assertEquals(0L, repo.deleteRecording(id))
        assertTrue(elsewhere.exists())
        assertNull(repo.get(id)!!.audioPath)
    }
}
