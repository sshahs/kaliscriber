package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BookmarksTest {
    private val root: File = Files.createTempDirectory("marks").toFile()
    private val repo = MeetingRepository(JsonDocumentStore(File(root, "m"), Meeting.serializer()) { it.id }, File(root, "a"))

    @Test
    fun `bookmarks are weighted in running notes and the summary`() = runTest {
        val meeting = repo.create()
        repo.update(meeting.id) { m ->
            m.copy(segments = (0 until 24).map { i -> TranscriptSegment("s$i", i * 30_000L, (i + 1) * 30_000L, "Line $i") })
        }
        repo.addBookmark(meeting.id, 11 * 60_000L)
        repo.addBookmark(meeting.id, 5 * 60_000L, "  pricing decision ")
        var m = repo.get(meeting.id)!!
        assertEquals(listOf(5 * 60_000L, 11 * 60_000L), m.bookmarks.map { it.atMs }, "kept in time order")
        assertEquals("pricing decision", m.bookmarks.first().note)

        val llm = FakeLlm { req -> if (req.system.contains(Prompts.TASK_INSIGHTS)) """{"title":"T","summary":"S","actionItems":[]}""" else "- notes" }
        val ai = MeetingAi(FakeAi(llm, FakeStt(false) { SttResult(emptyList(), false) }), { AppSettings() })
        ai.sectionNotes(m, m.segments.filter { it.startMs < 10 * 60_000L })
        val notesRequest = llm.requests.last().messages.single().content
        assertTrue(notesRequest.contains("[05:00] pricing decision"), notesRequest)
        assertFalse(notesRequest.contains("[11:00]"), "a mark after this stretch belongs to the next one")

        repo.editBookmark(meeting.id, m.bookmarks.last().id, "budget")
        m = repo.get(meeting.id)!!
        ai.insights(m)
        val system = llm.requests.last().system
        assertTrue(system.contains(Prompts.BOOKMARKS_RULE) && system.contains("- [05:00] pricing decision\n- [11:00] budget"), system)

        repo.deleteBookmark(meeting.id, m.bookmarks.first().id)
        assertEquals(listOf("budget"), repo.get(meeting.id)!!.bookmarks.map { it.note })
    }
}
