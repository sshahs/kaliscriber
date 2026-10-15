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

/** A stretch of a meeting's transcript (lines [first] to [last]) found by meaning, with its similarity. */
data class PassageHit(val meetingId: String, val first: Int, val last: Int, val score: Float)

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

    /** Passages closest in meaning to [text], best first; empty without an embedding model. */
    fun similar(text: String, limit: Int): List<PassageHit> = emptyList()
}

/** Groups transcript lines into passages of about [WORDS] words, the unit searched by meaning. */
object Passages {
    const val WORDS = 50

    /** Line ranges (first to last position, inclusive) and their text. */
    fun of(segments: List<TranscriptSegment>): List<Triple<Int, Int, String>> {
        val out = mutableListOf<Triple<Int, Int, String>>()
        var first = 0
        var words = 0
        val text = StringBuilder()
        segments.forEachIndexed { i, seg ->
            if (text.isNotEmpty()) text.append(' ')
            text.append(seg.text)
            words += MeetingSearch.indexWords(seg.text).size
            if (words >= WORDS || i == segments.lastIndex) {
                out += Triple(first, i, text.toString())
                first = i + 1
                words = 0
                text.clear()
            }
        }
        return out
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var na = 0f
        var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        return if (na == 0f || nb == 0f) 0f else dot / kotlin.math.sqrt(na * nb)
    }
}

/** Search over meetings already in memory with their transcripts (tests, or a handful of meetings). */
class MemoryCorpus(meetings: List<Meeting>, private val embedder: TextEmbedder? = null) : SearchCorpus {
    private class Entry(val line: IndexedLine, val words: List<String>)

    private val entries = meetings.sortedByDescending { it.createdAt }.flatMap { m ->
        m.segments.mapIndexed { i, s ->
            Entry(IndexedLine(m.id, i, s.id, s.startMs, s.endMs, s.speakerId, s.text), MeetingSearch.indexWords(s.text))
        }
    }
    private val byMeeting = entries.groupBy { it.line.meetingId }
    private val passages by lazy {
        val model = embedder ?: return@lazy emptyList()
        meetings.flatMap { m -> Passages.of(m.segments).map { (first, last, text) -> Triple(m.id, first to last, model.embed(text)) } }
    }

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

    override fun similar(text: String, limit: Int): List<PassageHit> {
        val model = embedder ?: return emptyList()
        val query = model.embed(text)
        return passages.map { (id, range, vector) -> PassageHit(id, range.first, range.second, Passages.cosine(query, vector)) }
            .sortedByDescending { it.score }.take(limit)
    }
}

/**
 * A full-text index of every transcript line, kept in SQLite so searching hundreds of meetings
 * needs neither their transcripts in memory nor reading them from disk. With an [embedder] it also
 * stores a vector per passage of about fifty words, to find passages by meaning. It is derived
 * data: it can be dropped and rebuilt from the meetings at any time.
 */
class TranscriptIndex(private val db: SqlDb, private val embedder: () -> TextEmbedder? = { null }) : SearchCorpus {

    /** What is indexed for a meeting: its transcript version and the model its passages were embedded with. */
    data class State(val version: Long, val embeddedWith: String?)

    /** Every passage vector, loaded on the first search by meaning and dropped on any change. */
    private class Vectors(val model: String, val meetings: Array<String>, val first: IntArray, val last: IntArray, val data: Array<FloatArray>)

    @Volatile private var vectors: Vectors? = null

    init {
        val version = runCatching { db.query("PRAGMA user_version") { it.long(0) }.firstOrNull() }.getOrNull() ?: 0L
        if (version != SCHEMA_VERSION) {
            db.transaction {
                for (table in listOf("line", "line_fts", "indexed", "passage")) db.execute("DROP TABLE IF EXISTS $table")
                db.execute(
                    "CREATE TABLE line (id INTEGER PRIMARY KEY, meeting_id TEXT NOT NULL, idx INTEGER NOT NULL, seg_id TEXT NOT NULL, " +
                        "start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, speaker_id TEXT, text TEXT NOT NULL, words INTEGER NOT NULL)",
                )
                db.execute("CREATE INDEX line_meeting ON line(meeting_id, idx)")
                // The words are stored lower-cased and without accents, so the plain tokenizer matches them as search does.
                db.execute("CREATE VIRTUAL TABLE line_fts USING fts4(norm, tokenize=simple)")
                db.execute(
                    "CREATE TABLE passage (id INTEGER PRIMARY KEY, meeting_id TEXT NOT NULL, first_idx INTEGER NOT NULL, " +
                        "last_idx INTEGER NOT NULL, model TEXT NOT NULL, vector BLOB NOT NULL)",
                )
                db.execute("CREATE INDEX passage_meeting ON passage(meeting_id)")
                db.execute(
                    "CREATE TABLE indexed (meeting_id TEXT PRIMARY KEY, version INTEGER NOT NULL, created_at INTEGER NOT NULL, embedded_with TEXT)",
                )
                db.execute("PRAGMA user_version = $SCHEMA_VERSION")
            }
        }
    }

    /** What is indexed for each meeting. */
    fun states(): Map<String, State> =
        db.query("SELECT meeting_id, version, embedded_with FROM indexed") { it.string(0)!! to State(it.long(1), it.string(2)) }.toMap()

    /** The transcript version each meeting was indexed at. */
    fun versions(): Map<String, Long> = states().mapValues { it.value.version }

    /** The embedding model in use, if any. */
    fun embedderId(): String? = embedder()?.id

    /** Replaces the meeting's lines (and passages, with an embedding model) with its current transcript. */
    fun reindex(meeting: Meeting) {
        val model = embedder()
        // Embedding happens before the transaction, so searches aren't held up by it.
        val passages = model?.let { m -> Passages.of(meeting.segments).map { (first, last, text) -> Triple(first, last, quantize(m.embed(text))) } }
        db.transaction {
            removeRows(meeting.id)
            meeting.segments.forEachIndexed { i, seg ->
                val words = MeetingSearch.indexWords(seg.text)
                db.execute(
                    "INSERT INTO line (meeting_id, idx, seg_id, start_ms, end_ms, speaker_id, text, words) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    listOf(meeting.id, i.toLong(), seg.id, seg.startMs, seg.endMs, seg.speakerId, seg.text, words.size.toLong()),
                )
                db.execute("INSERT INTO line_fts (docid, norm) VALUES (last_insert_rowid(), ?)", listOf(words.joinToString(" ")))
            }
            passages?.forEach { (first, last, vector) ->
                db.execute(
                    "INSERT INTO passage (meeting_id, first_idx, last_idx, model, vector) VALUES (?, ?, ?, ?, ?)",
                    listOf(meeting.id, first.toLong(), last.toLong(), model.id, vector),
                )
            }
            db.execute(
                "INSERT OR REPLACE INTO indexed (meeting_id, version, created_at, embedded_with) VALUES (?, ?, ?, ?)",
                listOf(meeting.id, meeting.stats.version, meeting.createdAt, model?.id),
            )
        }
        vectors = null
    }

    fun remove(meetingId: String) {
        db.transaction {
            removeRows(meetingId)
            db.execute("DELETE FROM indexed WHERE meeting_id = ?", listOf(meetingId))
        }
        vectors = null
    }

    private fun removeRows(meetingId: String) {
        db.execute("DELETE FROM line_fts WHERE docid IN (SELECT id FROM line WHERE meeting_id = ?)", listOf(meetingId))
        db.execute("DELETE FROM line WHERE meeting_id = ?", listOf(meetingId))
        db.execute("DELETE FROM passage WHERE meeting_id = ?", listOf(meetingId))
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
        "SELECT $LINE_COLUMNS FROM line WHERE meeting_id = ? AND idx BETWEEN ? AND ? ORDER BY idx",
        listOf(meetingId, from.toString(), to.toString()),
        ::line,
    )

    override fun lineAt(meetingId: String, ms: Long): IndexedLine? =
        db.query(
            "SELECT $LINE_COLUMNS FROM line WHERE meeting_id = ? AND start_ms <= ? ORDER BY idx DESC LIMIT 1",
            listOf(meetingId, ms.toString()),
            ::line,
        ).firstOrNull() ?: db.query("SELECT $LINE_COLUMNS FROM line WHERE meeting_id = ? ORDER BY idx LIMIT 1", listOf(meetingId), ::line).firstOrNull()

    override fun similar(text: String, limit: Int): List<PassageHit> {
        val model = embedder() ?: return emptyList()
        val all = vectors?.takeIf { it.model == model.id } ?: loadVectors(model.id).also { vectors = it }
        if (all.data.isEmpty()) return emptyList()
        val query = model.embed(text)
        val scores = FloatArray(all.data.size) { Passages.cosine(query, all.data[it]) }
        return scores.indices.sortedByDescending { scores[it] }.take(limit).map {
            PassageHit(all.meetings[it], all.first[it], all.last[it], scores[it])
        }
    }

    private fun loadVectors(model: String): Vectors {
        val rows = db.query("SELECT meeting_id, first_idx, last_idx, vector FROM passage WHERE model = ?", listOf(model)) {
            PassageRow(it.string(0)!!, it.long(1).toInt(), it.long(2).toInt(), dequantize(it.blob(3)!!))
        }
        return Vectors(
            model,
            Array(rows.size) { rows[it].meetingId },
            IntArray(rows.size) { rows[it].first },
            IntArray(rows.size) { rows[it].last },
            Array(rows.size) { rows[it].vector },
        )
    }

    private class PassageRow(val meetingId: String, val first: Int, val last: Int, val vector: FloatArray)

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
        const val SCHEMA_VERSION = 2L
        private const val LINE_COLUMNS = "meeting_id, idx, seg_id, start_ms, end_ms, speaker_id, text"

        /** A vector as one signed byte per value plus a scale: a quarter of the space, close enough for ranking. */
        fun quantize(v: FloatArray): ByteArray {
            val max = v.maxOfOrNull { kotlin.math.abs(it) }?.takeIf { it > 0f } ?: 1f
            val scale = max / 127f
            val out = java.nio.ByteBuffer.allocate(4 + v.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            out.putFloat(scale)
            v.forEach { out.put(kotlin.math.round(it / scale).toInt().coerceIn(-127, 127).toByte()) }
            return out.array()
        }

        fun dequantize(bytes: ByteArray): FloatArray {
            val buf = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val scale = buf.getFloat()
            return FloatArray(bytes.size - 4) { buf.get().toFloat() * scale }
        }
    }
}
