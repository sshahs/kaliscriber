package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.ask.AskScope
import com.kaliscriber.core.ask.LibraryAsk
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.search.MeetingSearch
import com.kaliscriber.core.search.MemoryCorpus
import com.kaliscriber.core.search.TranscriptIndex
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.tasks.ActionItems
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LibraryTest {
    private val root: File = Files.createTempDirectory("lib").toFile()

    private fun meeting(id: String, title: String, createdAt: Long, vararg lines: String, insights: MeetingInsights? = null) = Meeting(
        id = id,
        title = title,
        createdAt = createdAt,
        status = MeetingStatus.READY,
        speakers = listOf(Speaker("p", "Priya", isPlaceholder = false)),
        segments = lines.mapIndexed { i, text -> TranscriptSegment("$id-s$i", i * 10_000L, (i + 1) * 10_000L, text, "p") },
        insights = insights,
    )

    private val launch = meeting(
        "m1", "Q3 launch sync", 1_000,
        "Welcome everyone.",
        "We agreed the pricing goes up by ten percent in November.",
        "Sam will email the press list.",
    )
    private val hiring = meeting("m2", "Hiring plan", 2_000, "We need two more engineers.", "Budget for hiring is approved.")

    @Test
    fun `search finds the meeting and the moment, matching word forms`() {
        val results = MeetingSearch.search(listOf(launch, hiring), "price increase")
        assertEquals("m1", results.first().meeting.id)
        val hit = results.first().hits.first()
        assertEquals(MeetingSearch.Field.TRANSCRIPT, hit.field)
        assertEquals("m1-s1", hit.segmentId)
        assertEquals(10_000L, hit.startMs)
        assertTrue(MeetingSearch.search(listOf(launch, hiring), "the and of").isEmpty())
    }

    @Test
    fun `retrieval falls back to recent meetings when nothing matches`() {
        val sources = MeetingSearch.retrieve(listOf(launch, hiring), listOf("weather"), 10_000)
        assertEquals(listOf("m2", "m1"), sources.map { it.meeting.id })
        assertTrue(sources.all { it.passages.isEmpty() })
        val matched = MeetingSearch.retrieve(listOf(launch, hiring), listOf("pricing"), 10_000)
        assertEquals("m1", matched.first().meeting.id)
        assertEquals(listOf("m1-s0", "m1-s1", "m1-s2"), matched.first().passages.flatten().map { it.id })
    }

    @Test
    fun `asking across meetings cites the source moment and keeps the conversation`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        repo.create().let { } // an empty meeting is ignored
        listOf(launch, hiring).forEach { m -> repo.create(m.title).also { created -> repo.update(created.id) { m.copy(id = created.id) } } }
        val llm = FakeLlm { req ->
            when (req.system) {
                Prompts.SEARCH_KEYWORDS_SYSTEM -> """{"keywords":["pricing","price"]}"""
                else -> {
                    assertTrue(req.system.contains("[M1] \"Q3 launch sync\""), req.system)
                    "Prices rise ten percent in November [M1 00:10]."
                }
            }
        }
        val history = JsonValueStore(File(root, "ask.json"), ListSerializer(AskMessage.serializer()), emptyList())
        // Passages come from the search index, as in the app.
        val index = TranscriptIndex(JdbcSqlDb(File(root, "search.db")))
        repo.all().forEach { index.reindex(repo.get(it.id)!!) }
        val ask = LibraryAsk(repo, history, FakeAi(llm, FakeStt(false) { SttResult(emptyList(), false) }), { AppSettings() }, { index })

        val answer = ask.ask("What did we decide about price increases?")
        assertFalse(answer.isError)
        val source = answer.sources.single()
        assertEquals("Q3 launch sync", source.title)
        assertEquals(10_000L, source.startMs)
        assertTrue(source.segmentId!!.endsWith("-s1"))
        assertEquals(2, ask.messages.value.size)

        ask.clear()
        assertTrue(ask.messages.value.isEmpty())
    }

    @Test
    fun `asking with filters only reads the meetings that match`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        listOf(launch, hiring).forEach { m -> repo.create(m.title).also { created -> repo.update(created.id) { m.copy(id = created.id) } } }
        val week = 7L * 24 * 60 * 60 * 1000
        val llm = FakeLlm { req ->
            when (req.system) {
                Prompts.SEARCH_KEYWORDS_SYSTEM -> """{"keywords":["budget"]}"""
                else -> {
                    assertTrue(req.system.contains("Hiring plan") && !req.system.contains("Q3 launch sync"), req.system)
                    assertTrue(req.system.contains("filters (the last 7 days, with Priya)"), req.system)
                    "Hiring budget was approved [M1]."
                }
            }
        }
        val history = JsonValueStore(File(root, "ask2.json"), ListSerializer(AskMessage.serializer()), emptyList())
        val ask = LibraryAsk(
            repo, history, FakeAi(llm, FakeStt(false) { SttResult(emptyList(), false) }), { AppSettings() },
            { MemoryCorpus(repo.all().map { repo.get(it.id)!! }) }, clock = Clock { week + 1_500 },
        )
        // Names match whatever their case.
        val answer = ask.ask("What about money?", AskScope(AskScope.Period.WEEK, person = "Priya"))
        assertFalse(answer.isError, answer.content)
        assertEquals("the last 7 days, with Priya", ask.messages.value.first().scope)
        assertTrue(AskScope(person = "PRIYA").matches(hiring, 0))

        val none = ask.ask("Anything?", AskScope(person = "Sam"))
        assertTrue(none.isError && none.content.contains("No meetings match"), none.content)
        assertTrue(AskScope(format = "General").matches(hiring, 0))
        assertFalse(AskScope(format = "1:1").matches(hiring, 0))
    }

    @Test
    fun `citations map several meetings and ignore unknown labels`() {
        val sources = MeetingSearch.retrieve(listOf(launch, hiring), listOf("pricing", "hiring"), 10_000)
        val cited = LibraryAsk.citedSources("Yes [M1 00:10, M2] and also [M9 01:00].", sources, MemoryCorpus(listOf(launch, hiring)))
        assertEquals(2, cited.size)
        assertEquals(setOf("m1", "m2"), cited.map { it.meetingId }.toSet())
    }

    @Test
    fun `action items are grouped by due date and the digest lists what is due`() {
        val today = LocalDate.of(2026, 10, 14)
        val items = listOf(
            ActionItem("a", "Overdue thing", dueDateIso = "2026-10-10"),
            ActionItem("b", "Today thing", owner = "Sam", dueDateIso = "2026-10-14"),
            ActionItem("c", "Soon thing", dueDateIso = "2026-10-18"),
            ActionItem("d", "Later thing", dueDateIso = "2026-12-01"),
            ActionItem("e", "Someday thing"),
            ActionItem("f", "Finished", dueDateIso = "2026-10-01", done = true),
        )
        val m = launch.copy(insights = MeetingInsights(actionItems = items))
        val grouped = ActionItems.grouped(ActionItems.all(listOf(m)), today)
        assertEquals(ActionItems.Bucket.entries.toList(), grouped.map { it.first })
        val digest = ActionItems.digest(listOf(m), today)
        assertEquals(listOf("a"), digest.overdue.map { it.item.id })
        assertEquals(listOf("b"), digest.dueToday.map { it.item.id })
        assertEquals(listOf("Sam"), ActionItems.owners(ActionItems.all(listOf(m))))
    }

    @Test
    fun `regenerating keeps done items and the user's edits`() {
        val previous = listOf(
            ActionItem("x1", "Email the press list", owner = "Sam", done = true),
            ActionItem("x2", "Book the venue", owner = "Priya", dueDateIso = "2026-11-01", edited = true),
            ActionItem("x3", "Draft the blog post", edited = true),
        )
        val fresh = listOf(
            ActionItem("n1", "Email the press list today", owner = "Sam"),
            ActionItem("n2", "Book the venue", owner = "Alex", dueDateIso = "2026-10-20"),
            ActionItem("n3", "Order the banners"),
        )
        val merged = ActionItems.carryOver(previous, fresh)
        assertEquals(listOf("x1", "x2", "n3", "x3"), merged.map { it.id })
        assertTrue(merged[0].done)
        assertEquals("Priya", merged[1].owner)
        assertEquals("2026-11-01", merged[1].dueDateIso)
    }
}
