package com.kaliscriber.core

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.model.TranscriptStats
import com.kaliscriber.core.search.MeetingSearch
import com.kaliscriber.core.search.SearchIndexer
import com.kaliscriber.core.search.TextEmbedder
import com.kaliscriber.core.search.TranscriptIndex
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Words about the same thing share a direction, as a real embedding model's would. */
class ConceptEmbedder(override val id: String = "concepts") : TextEmbedder {
    private val concepts = listOf(
        setOf("cost", "costs", "price", "prices", "pricing", "expensive", "cheap"),
        setOf("hire", "hiring", "recruit", "recruiting", "engineers", "candidates"),
        setOf("holiday", "vacation", "leave", "off"),
    )
    override val dimensions = concepts.size + 1

    override fun embed(text: String): FloatArray {
        val v = FloatArray(dimensions)
        for (word in MeetingSearch.indexWords(text)) {
            val c = concepts.indexOfFirst { word in it }
            v[if (c >= 0) c else concepts.size] += if (c >= 0) 1f else 0.05f
        }
        return v
    }
}

class SemanticSearchTest {
    private val root: File = Files.createTempDirectory("semantic").toFile()

    private fun meeting(id: String, createdAt: Long, vararg lines: String) = Meeting(
        id, id, createdAt, status = MeetingStatus.READY,
        segments = lines.mapIndexed { i, t -> TranscriptSegment("$id-$i", i * 10_000L, i * 10_000L + 9_000, t) },
        stats = TranscriptStats(lines.size, version = 1),
    )

    @Test
    fun `questions find passages by meaning when no words match`() {
        val index = TranscriptIndex(JdbcSqlDb(File(root, "s.db"))) { ConceptEmbedder() }
        val launch = meeting("launch", 2, "Let's go through the launch plan.", "Prices go up ten percent in November.", "That keeps us competitive.")
        val team = meeting("team", 1, "We need two more engineers.", "Recruiting starts next week.")
        listOf(launch, team).forEach(index::reindex)
        val light = listOf(launch, team).map { it.copy(segments = emptyList()) }

        val hits = index.similar("How expensive will it be?", 3)
        assertEquals("launch", hits.first().meetingId)

        // "expensive" appears nowhere, so only the meaning finds the pricing discussion.
        val sources = MeetingSearch.retrieve(light, index, listOf("expensive"), 10_000, question = "How expensive will it be?")
        assertEquals("launch", sources.first().meeting.id)
        assertTrue(sources.first().passages.flatten().any { it.text.contains("Prices go up") })
        assertEquals(
            "team",
            MeetingSearch.retrieve(light, index, listOf("candidates"), 10_000, question = "Any candidates to hire?").first().meeting.id,
        )
    }

    @Test
    fun `passage vectors survive storage closely enough to rank`() {
        val v = floatArrayOf(0.5f, -0.25f, 0.1f, 0f, 0.9f)
        val back = TranscriptIndex.dequantize(TranscriptIndex.quantize(v))
        v.indices.forEach { assertTrue(abs(v[it] - back[it]) < 0.005f, "${v[it]} vs ${back[it]}") }
    }

    @Test
    fun `meetings are embedded again once a search model is available`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        val created = repo.create("Launch")
        repo.update(created.id) { it.copy(segments = listOf(TranscriptSegment("x", 0, 1_000, "Prices rise in November."))) }
        var model: TextEmbedder? = null
        val index = TranscriptIndex(JdbcSqlDb(File(root, "s.db"))) { model }
        SearchIndexer(repo, index, backgroundScope).catchUp()
        assertEquals(null, index.states().getValue(created.id).embeddedWith)
        assertTrue(index.similar("cost", 3).isEmpty())

        model = ConceptEmbedder()
        SearchIndexer(repo, index, backgroundScope).catchUp()
        assertEquals("concepts", index.states().getValue(created.id).embeddedWith)
        assertEquals(created.id, index.similar("What will it cost?", 3).single().meetingId)
    }
}
