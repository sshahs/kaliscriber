package com.kaliscriber.core

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.people.People
import com.kaliscriber.core.search.MeetingSearch
import com.kaliscriber.core.search.SearchIndexer
import com.kaliscriber.core.search.TranscriptIndex
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.MeetingStore
import com.kaliscriber.core.store.StoreJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScaleTest {
    private val root: File = Files.createTempDirectory("scale").toFile()

    private fun meeting(id: String, title: String, createdAt: Long, vararg lines: String) = Meeting(
        id, title, createdAt,
        status = MeetingStatus.READY,
        speakers = listOf(Speaker("a", "Priya", isPlaceholder = false), Speaker("b", "Sam", isPlaceholder = false)),
        segments = lines.mapIndexed { i, text -> TranscriptSegment("$id-s$i", i * 10_000L, i * 10_000L + 8_000, text, if (i % 2 == 0) "a" else "b") },
    )

    private suspend fun MeetingRepository.add(m: Meeting): Meeting {
        val created = create(m.title)
        return update(created.id) { m.copy(id = created.id, createdAt = m.createdAt) }!!
    }

    @Test
    fun `meetings written by earlier versions are split and transcripts load when needed`() = runTest {
        val old = meeting("old", "Launch sync", 1_000, "We raise prices in November.", "Agreed, ten percent.")
        File(root, "meetings").mkdirs()
        File(root, "meetings/old.json").writeText(StoreJson.encodeToString(Meeting.serializer(), old))
        val repo = MeetingRepository.inDir(root)
        repo.load()

        val listed = repo.all().single()
        assertTrue(listed.segments.isEmpty(), "lists don't hold transcripts")
        assertEquals(2, listed.stats.lines)
        assertTrue(listed.hasTranscript)
        assertEquals("We raise prices in November. Agreed, ten percent.", listed.transcriptPreview())
        assertEquals(mapOf("a" to 8_000L, "b" to 8_000L), listed.speakingMs())
        assertFalse(File(root, "meetings/old.json").readText().contains("\"segments\""), "the meeting file no longer holds the transcript")
        assertTrue(File(root, "transcripts/old.json").readText().contains("November"))
        assertEquals(old.segments, repo.get("old")!!.segments)

        // A second start reads the split files.
        val again = MeetingRepository.inDir(root)
        again.load()
        assertEquals(old.segments, again.get("old")!!.segments)
        assertEquals(2, again.all().single().stats.lines)
    }

    @Test
    fun `only a few transcripts stay in memory, and never one being watched`() = runTest {
        val store = MeetingStore(File(root, "m"), File(root, "t"), MeetingRepository.PERSISTED, cacheSize = 1)
        val repo = MeetingRepository(store, File(root, "a"))
        repo.load()
        val watched = repo.add(meeting("w", "Watched", 1, "Watched line."))
        val other = repo.add(meeting("o", "Other", 2, "Other line."))
        val third = repo.add(meeting("x", "Third", 3, "Third line."))

        val seen = mutableListOf<Meeting?>()
        val watcher = backgroundScope.launch(Dispatchers.Default) { repo.observe(watched.id).collect { seen += it } }
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (seen.isEmpty()) delay(10) } }
        repo.get(other.id)
        repo.get(third.id)
        // With the files gone, only a cached transcript can still be returned.
        File(root, "t").listFiles()!!.forEach { it.delete() }
        assertEquals(listOf("Watched line."), repo.get(watched.id)!!.segments.map { it.text }, "the watched meeting stays cached")
        assertTrue(repo.get(other.id)!!.segments.isEmpty(), "older transcripts are dropped from memory")
        watcher.cancel()

        repo.update(third.id) { it.copy(title = "Renamed") }
        assertEquals("Renamed", repo.withoutTranscript(third.id)!!.title)
        assertEquals("Renamed", seen.last()?.takeIf { it.id == third.id }?.title ?: repo.get(third.id)!!.title)
    }

    @Test
    fun `search through the index finds what searching in memory finds`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        val launch = repo.add(meeting("l", "Q3 launch sync", 2_000, "Let's talk pricing for the launch.", "We raise prices ten percent in November.", "Marketing needs the price list."))
        val hiring = repo.add(meeting("h", "Hiring", 1_000, "Two engineers start in October.", "The budget allows one more hire.").copy(
            insights = MeetingInsights(summary = "Hiring plan", actionItems = listOf(ActionItem("x", "Send the price list", owner = "Sam"))),
        ))
        val index = TranscriptIndex(JdbcSqlDb(File(root, "search.db")))
        repo.all().forEach { index.reindex(repo.get(it.id)!!) }

        for (query in listOf("pricing", "price list November", "engineers", "budget hire", "Sam")) {
            val inMemory = MeetingSearch.search(listOf(repo.get(launch.id)!!, repo.get(hiring.id)!!), query)
            val indexed = MeetingSearch.search(repo.all(), index, query)
            assertEquals(inMemory.map { it.meeting.id }, indexed.map { it.meeting.id }, query)
            assertEquals(inMemory.map { r -> r.hits.map { it.segmentId } }, indexed.map { r -> r.hits.map { it.segmentId } }, query)
        }

        val sources = MeetingSearch.retrieve(repo.all(), index, listOf("november"), 10_000)
        assertEquals(launch.id, sources.first().meeting.id)
        assertEquals(listOf("l-s0", "l-s1", "l-s2"), sources.first().passages.flatten().map { it.id }, "the line and its neighbours")
    }

    @Test
    fun `the indexer catches up at start and follows changes`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        val a = repo.add(meeting("a1", "Alpha", 1, "Ship the beta on Friday."))
        val b = repo.add(meeting("b1", "Beta", 2, "Nothing about releases."))
        val index = TranscriptIndex(JdbcSqlDb(File(root, "search.db")))
        val indexer = SearchIndexer(repo, index, backgroundScope, delayMs = 10, recordingDelayMs = 10)
        indexer.start()
        suspend fun waitFor(check: () -> Boolean) = withContext(Dispatchers.Default) { withTimeout(5_000) { while (!check()) delay(10) } }

        waitFor { index.versions().keys == setOf(a.id, b.id) }
        assertEquals(listOf(a.id), MeetingSearch.search(repo.all(), index, "friday").map { it.meeting.id })

        repo.update(b.id) { m -> m.copy(segments = m.segments + TranscriptSegment("b1-new", 20_000, 21_000, "Release moved to Friday.")) }
        waitFor { MeetingSearch.search(repo.all(), index, "friday").size == 2 }

        repo.delete(a.id)
        waitFor { a.id !in index.versions() }
        assertEquals(listOf(b.id), MeetingSearch.search(repo.all(), index, "friday").map { it.meeting.id })

        // A fresh start finds nothing new to index.
        val versions = index.versions()
        SearchIndexer(repo, index, backgroundScope).catchUp()
        assertEquals(versions, index.versions())
    }

    @Test
    fun `people pages use speaking time without loading transcripts`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        repo.add(meeting("p", "Sync", 1, "One.", "Two.", "Three."))
        val priya = People.directory(repo.all(), emptyList()).first { it.name == "Priya" }
        assertEquals(16_000L, priya.speakingMs)
        assertNull(repo.all().firstOrNull { it.segments.isNotEmpty() })
        assertTrue(repo.meetings.first().all { it.segments.isEmpty() })
    }
}
