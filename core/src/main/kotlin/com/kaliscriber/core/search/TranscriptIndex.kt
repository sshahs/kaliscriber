package com.kaliscriber.core.search

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.store.SqlDb

/** One transcript line as search sees it; [index] is its position in the meeting's transcript. */
data class IndexedLine(
    val meetingId: String,
    val index: Int,
    val segmentId: String,
    val startMs: Long,
    val endMs: Long,
    val speakerId: String?,
    val text: String,
) {
    fun toSegment() = TranscriptSegment(segmentId, startMs, endMs, text, speakerId)
}

/** The transcript lines search works over, wherever they are kept. */
interface SearchCorpus {
    /**
     * Lines with a word starting with any of [prefixes] (lower-case, without accents), newest
     * meetings first, at most [limit]. A superset of the lines whose word stems match.
     */
    fun candidates(prefixes: List<String>, limit: Int): List<IndexedLine>

    /** How many lines have a word starting with [prefix]. */
    fun lineFrequency(prefix: String): Int

    /** Number of lines and their average length in words. */
    fun size(): Pair<Int, Double>

    /** Lines [from] to [to] (positions, inclusive) of a meeting's transcript. */
    fun lines(meetingId: String, from: Int, to: Int): List<IndexedLine>

    /** The line being spoken at [ms] (the last one starting by then), or the first line. */
    fun lineAt(meetingId: String, ms: Long): IndexedLine?
}

/** Search over meetings already in memory with their transcripts (tests, or a handful of meetings). */
class MemoryCorpus(meetings: List<Meeting>) : SearchCorpus {
    private class Entry(val line: IndexedLine, val words: List<String>)

    private val entries = meetings.sortedByDescending { it.createdAt }.flatMap { m ->
        m.segments.mapIndexed { i, s ->
            Entry(IndexedLine(m.id, i, s.id, s.startMs, s.endMs, s.speakerId, s.text), MeetingSearch.indexWords(s.text))
        }
    }
    private val byMeeting = entries.groupBy { it.line.meetingId }

    override fun candidates(prefixes: List<String>, limit: Int) =
        entries.filter { e -> e.words.any { w -> prefixes.any { w.startsWith(it) } } }.take(limit).map { it.line }

    override fun lineFrequency(prefix: String) = entries.count { e -> e.words.any { it.startsWith(prefix) } }

    override fun size(): Pair<Int, Double> =
        entries.size to if (entries.isEmpty()) 1.0 else entries.sumOf { it.words.size }.toDouble() / entries.size

    override fun lines(meetingId: String, from: Int, to: Int) =
        byMeeting[meetingId].orEmpty().map { it.line }.filter { it.index in from..to }

    override fun lineAt(meetingId: String, ms: Long): IndexedLine? {
        val lines = byMeeting[meetingId].orEmpty().map { it.line }
        return lines.lastOrNull { it.startMs <= ms } ?: lines.firstOrNull()
    }
}

/**
 * A full-text index of every transcript line, kept in SQLite so searching hundreds of meetings
 * needs neither their transcripts in memory nor reading them from disk. It is derived data: it can
 * be dropped and rebuilt from the meetings at any time.
 */
class TranscriptIndex(private val db: SqlDb) : SearchCorpus {

    init {
        val version = runCatching { db.query("PRAGMA user_version") { it.long(0) }.firstOrNull() }.getOrNull() ?: 0L
        if (version != SCHEMA_VERSION) {
            db.transaction {
                for (table in listOf("line", "line_fts", "indexed")) db.execute("DROP TABLE IF EXISTS $table")
                db.execute(
                    "CREATE TABLE line (id INTEGER PRIMARY KEY, meeting_id TEXT NOT NULL, idx INTEGER NOT NULL, seg_id TEXT NOT NULL, " +
                        "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, speaker_id TEXT, text TEXT NOT NULL, words INTEGER NOT NULL)",
                )
                db.execute("CREATE INDEX line_meeting ON line(meeting_id, idx)")
                // The words are stored lower-cased and without accents, so the plain tokenizer matches them as search does.
                db.execute("CREATE VIRTUAL TABLE line_fts USING fts4(norm, tokenize=simple)")
                db.execute("CREATE TABLE indexed (meeting_id TEXT PRIMARY KEY, version INTEGER NOT NULL, created_at INTEGER NOT NULL)")
                db.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }
    }

    /** The transcript version each meeting was indexed at. */
    fun versions(): Map<String, Long> =
        db.query("SELECT meeting_id, version FROM indexed") { it.string(0)!! to it.long(1) }.toMap()

    /** Replaces the meeting's lines with its current transcript. */
    fun reindex(meeting: Meeting) = db.transaction {
        removeLines(meeting.id)
        meeting.segments.forEachIndexed { i, seg ->
            val words = MeetingSearch.indexWords(seg.text)
            db.execute(
                "INSERT INTO line (meeting_id, idx, seg_id, start_ms, end_ms, speaker_id, text, words) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(meeting.id, i.toLong(), seg.id, seg.startMs, seg.endMs, seg.speakerId, seg.text, words.size.toLong()),
            )
            db.execute("INSERT INTO line_fts (docid, norm) VALUES (last_insert_rowid(), ?)", listOf(words.joinToString(" ")))
        }
        db.execute(
            "INSERT OR REPLACE INTO indexed (meeting_id, version, created_at) VALUES (?, ?, ?)",
            listOf(meeting.id, meeting.stats.version, meeting.createdAt),
        )
    }

    fun remove(meetingId: String) = db.transaction {
        removeLines(meetingId)
        db.execute("DELETE FROM indexed WHERE meeting_id = ?", listOf(meetingId))
    }

    private fun removeLines(meetingId: String) {
        db.execute("DELETE FROM line_fts WHERE docid IN (SELECT id FROM line WHERE meeting_id = ?)", listOf(meetingId))
        db.execute("DELETE FROM line WHERE meeting_id = ?", listOf(meetingId))
    }

    override fun candidates(prefixes: List<String>, limit: Int): List<IndexedLine> {
        val query = matchQuery(prefixes) ?: return emptyList()
        return db.query(
            "SELECT l.meeting_id, l.idx, l.seg_id, l.start_ms, l.end_ms, l.speaker_id, l.text FROM line_fts " +
                "JOIN line l ON l.id = line_fts.docid JOIN indexed i ON i.meeting_id = l.meeting_id " +
                "WHERE line_fts MATCH ? ORDER BY i.created_at DESC, l.idx LIMIT $limit",
            listOf(query),
            ::line,
        )
    }

    override fun lineFrequency(prefix: String): Int {
        val query = matchQuery(listOf(prefix)) ?: return 0
        return db.query("SELECT count(*) FROM line_fts WHERE line_fts MATCH ?", listOf(query)) { it.long(0) }.first().toInt()
    }

    override fun size(): Pair<Int, Double> =
        db.query("SELECT count(*), coalesce(avg(words), 1) FROM line") { it.long(0).toInt() to it.double(1) }.first()

    override fun lines(meetingId: String, from: Int, to: Int): List<IndexedLine> = db.query(
        "SELECT meeting_id, idx, seg_id, start_ms, end_ms, speaker_id, text FROM line WHERE meeting_id = ? AND idx BETWEEN ? AND ? ORDER BY idx",
        listOf(meetingId, from.toString(), to.toString()),
        ::line,
    )

    override fun lineAt(meetingId: String, ms: Long): IndexedLine? {
        val columns = "meeting_id, idx, seg_id, start_ms, end_ms, speaker_id, text"
        return db.query(
            "SELECT $columns FROM line WHERE meeting_id = ? AND start_ms <= ? ORDER BY idx DESC LIMIT 1",
            listOf(meetingId, ms.toString()),
            ::line,
        ).firstOrNull() ?: db.query("SELECT $columns FROM line WHERE meeting_id = ? ORDER BY idx LIMIT 1", listOf(meetingId), ::line).firstOrNull()
    }

    private fun line(row: com.kaliscriber.core.store.SqlRow) = IndexedLine(
        meetingId = row.string(0)!!,
        index = row.long(1).toInt(),
        segmentId = row.string(2)!!,
        startMs = row.long(3),
        endMs = row.long(4),
        speakerId = row.string(5),
        text = row.string(6).orEmpty(),
    )

    /** "pric* OR budget*": prefixes made only of letters and digits, so nothing in them is query syntax. */
    private fun matchQuery(prefixes: List<String>): String? =
        prefixes.filter { p -> p.isNotEmpty() && p.all { it.isLetterOrDigit() } }.distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" OR ") { "$it*" }

    companion object {
        const val SCHEMA_VERSION = 1L
    }
}
