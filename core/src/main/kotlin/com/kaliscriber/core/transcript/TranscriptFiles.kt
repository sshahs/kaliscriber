package com.kaliscriber.core.transcript

import com.kaliscriber.core.ai.stt.RawSegment
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Reads meeting transcripts made by other apps: WebVTT from Zoom and Teams, SRT, Word files from
 * Teams and text from Google Meet, Otter and the like. Speakers are taken from "Name: text" lines,
 * WebVTT voice tags or "Name  0:03" headings; times from cues or timestamps, estimated from the
 * number of words where a file has none.
 */
object TranscriptFiles {
    /** What a transcript file held. */
    data class Parsed(
        val lines: List<RawSegment>,
        /** The meeting's title, when the file states it. */
        val title: String? = null,
        /** When the meeting took place, when the file or its name states it. */
        val startedAt: LocalDateTime? = null,
        /** A title made from the file name, for when the file doesn't state one. */
        val fileTitle: String = "Imported transcript",
    )

    val EXTENSIONS = setOf("vtt", "srt", "txt", "docx")
    const val MAX_BYTES = 20 * 1024 * 1024

    /** True for files to import as a transcript rather than audio. */
    fun isTranscript(name: String?, mime: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext in EXTENSIONS) return true
        val type = mime?.lowercase().orEmpty()
        return type.startsWith("text/") || type == "application/x-subrip" || type == DOCX_MIME
    }

    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Parses [bytes] of a file called [name]. Throws [IllegalArgumentException] when no transcript is found. */
    fun parse(name: String, bytes: ByteArray): Parsed {
        require(bytes.size <= MAX_BYTES) { "This file is too large to be a transcript." }
        val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        val text = if (isZip) docxText(bytes) else bytes.decodeToString().removePrefix("\uFEFF")
        val parsed = parseText(text)
        val lines = merge(parsed.lines)
        require(lines.isNotEmpty()) { "No transcript was found in this file." }
        return parsed.copy(lines = lines, startedAt = parsed.startedAt ?: dateIn(name), fileTitle = titleFrom(name))
    }

    fun parseText(text: String): Parsed {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (TIMING.containsMatchIn(normalized)) cues(normalized) else plain(normalized)
    }

    /** False for labels that only number the speakers ("Speaker 1", "Unknown speaker", "Guest"). */
    fun isRealName(label: String): Boolean =
        label.isNotBlank() && !GENERIC_SPEAKER.matches(label.trim())

    // ---- WebVTT and SRT -------------------------------------------------------------------

    private val TIMING = Regex("""^\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""", RegexOption.MULTILINE)
    private val VOICE = Regex("""<v(?:\.[^\s>]+)*\s+([^>]+)>""")
    private val TAG = Regex("""<[^>]+>""")

    private fun cues(text: String): Parsed {
        val lines = mutableListOf<RawSegment>()
        val tagged = mutableSetOf<String>()
        val blocks = text.split(Regex("""\n\s*\n"""))
        for (block in blocks) {
            val rows = block.lines()
            val timingAt = rows.indexOfFirst { TIMING.containsMatchIn(it) }
            if (timingAt < 0) continue
            val timing = TIMING.find(rows[timingAt]) ?: continue
            val start = clock(timing.groupValues[1]) ?: continue
            val end = clock(timing.groupValues[2]) ?: start
            val body = rows.drop(timingAt + 1).joinToString("\n").trim()
            if (body.isEmpty()) continue
            // Teams marks the speaker with a voice tag; a cue can hold more than one.
            val voices = VOICE.findAll(body).toList()
            if (voices.isNotEmpty()) {
                val parts = body.split(VOICE).drop(1)
                voices.zip(parts).forEach { (voice, part) ->
                    val said = clean(part)
                    val name = voice.groupValues[1].trim().take(MAX_NAME)
                    tagged += name
                    if (said.isNotEmpty()) lines += RawSegment(start, end, said, name)
                }
                continue
            }
            val said = clean(body)
            if (said.isEmpty()) continue
            val (speaker, rest) = speakerPrefix(said)
            lines += RawSegment(start, end, rest, speaker)
        }
        // "Name: text" counts when most lines are written that way or the name comes up again,
        // so a lone "Note: ..." stays text.
        val names = lines.mapNotNull { it.speakerLabel }.groupingBy { it }.eachCount()
        val mostlyNamed = lines.count { it.speakerLabel != null } * 10 >= lines.size * 6
        val kept = lines.map { line ->
            val name = line.speakerLabel
            if (name != null && name !in tagged && !mostlyNamed && names.getValue(name) < 2) line.copy(speakerLabel = null, text = "$name: ${line.text}") else line
        }
        return Parsed(kept)
    }

    private fun clean(text: String): String =
        decode(text.replace(TAG, "")).lines().joinToString(" ") { it.trim() }.replace(Regex("""\s+"""), " ").trim()

    // ---- Plain text (Google Meet, Otter, Teams Word files) --------------------------------

    private const val STAMP = """((?:\d{1,2}:)?\d{1,2}:\d{2})(?:[.,]\d{1,3})?"""
    /** A line that is only a timestamp, as Google Meet writes every few minutes. */
    private val STAMP_LINE = Regex("""^\s*[\[(]?$STAMP[\])]?\s*$""")
    /** "Priya Sharma   0:03" (Otter, Teams Word files): a speaker heading, the words follow. */
    private val HEADING = Regex("""^\s*([^:\[\]()]{1,$MAX_NAME}?)(?:\s{2,}|\t)\s*$STAMP\s*$""")
    /** "[00:12] Priya: text" or "00:00:12 Priya: text". */
    private val STAMPED = Regex("""^\s*(?:[\[(]$STAMP[\])]|(\d{1,2}:\d{2}:\d{2})(?:[.,]\d{1,3})?)\s+(.+)$""")
    /** "Priya (00:12): text". */
    private val NAME_STAMP_SAID = Regex("""^\s*([^:\[\]()]{1,$MAX_NAME}?)\s+[\[(]$STAMP[\])]\s*:\s*(.+)$""")
    private val NOISE = Regex("""(?i)^.{0,60}\b(started|stopped) transcription\b.*$|^transcript$|^attendees$""")

    /** [named] when the line itself gave the speaker as "Name: text". */
    private class Line(val atMs: Long?, val speaker: String?, val text: String, val named: Boolean = false)

    private fun plain(text: String): Parsed {
        val rows = text.lines().map { it.trim() }
        var title: String? = null
        var startedAt: LocalDateTime? = null
        // Headers: "Weekly sync - Transcript" (Meet), "Weekly sync-20261012_100000-Meeting Recording" (Teams).
        for (row in rows.take(6)) {
            val meet = MEET_TITLE.find(row)
            if (meet != null && title == null) title = meet.groupValues[1].trim()
            val teams = TEAMS_TITLE.find(row)
            if (teams != null) {
                if (title == null) title = teams.groupValues[1].trim()
                if (startedAt == null) startedAt = runCatching { LocalDateTime.parse(teams.groupValues[2], TEAMS_STAMP) }.getOrNull()
            }
            if (startedAt == null) startedAt = longDate(row)
        }
        // A heading counts when its name heads more than one turn (or the only one), not for a line
        // that happens to end in a time.
        val headings = rows.mapNotNull { row -> HEADING.find(row)?.groupValues?.get(1)?.trim()?.takeIf(::looksLikeName) }
            .groupingBy { it }.eachCount()
        val headingNames = if (headings.values.sum() >= 2) headings.keys else emptySet()

        val parsed = mutableListOf<Line>()
        var speaker: String? = null
        var pendingTime: Long? = null
        var started = false
        for (row in rows) {
            if (row.isEmpty() || NOISE.matches(row)) continue
            val stampOnly = STAMP_LINE.find(row)
            if (stampOnly != null) {
                pendingTime = clock(stampOnly.groupValues[1])
                started = true
                continue
            }
            val heading = HEADING.find(row)
            if (heading != null && heading.groupValues[1].trim() in headingNames) {
                speaker = heading.groupValues[1].trim()
                pendingTime = clock(heading.groupValues[2])
                started = true
                continue
            }
            val nameStamp = NAME_STAMP_SAID.find(row)
            if (nameStamp != null && looksLikeName(nameStamp.groupValues[1])) {
                speaker = nameStamp.groupValues[1].trim()
                parsed += Line(clock(nameStamp.groupValues[2]) ?: pendingTime, speaker, nameStamp.groupValues[3].trim())
                pendingTime = null
                started = true
                continue
            }
            var at = pendingTime
            var line = row
            val stamped = STAMPED.find(row)
            if (stamped != null) {
                at = clock(stamped.groupValues[1].ifEmpty { stamped.groupValues[2] }) ?: at
                line = stamped.groupValues[3].trim()
                started = true
            }
            val (name, said) = speakerPrefix(line)
            if (name != null) {
                speaker = name
                started = true
            }
            // Text before the first speaker or timestamp is the file's header (title, date, attendees).
            if (!started || said.isEmpty()) continue
            parsed += Line(at, name ?: speaker, said, named = name != null)
            pendingTime = null
        }
        // Without any speaker or time, every line is what was said.
        if (parsed.isEmpty()) rows.filter { it.isNotEmpty() && !NOISE.matches(it) }.forEach { parsed += Line(null, null, it) }
        // As with cues, "Name:" counts when most lines are written that way or the name comes up again.
        val counts = parsed.filter { it.named }.groupingBy { it.speaker }.eachCount()
        val mostlyNamed = counts.values.sum() * 10 >= parsed.size * 6
        val accepted = parsed.mapNotNull { it.speaker }.toSet().filter { name ->
            name in headingNames || mostlyNamed || (counts[name] ?: 0) >= 2
        }.toSet()
        val lines = timed(
            parsed.map { line ->
                when {
                    line.speaker == null || line.speaker in accepted -> Triple(line.atMs, line.speaker, line.text)
                    line.named -> Triple(line.atMs, null, "${line.speaker}: ${line.text}")
                    else -> Triple(line.atMs, null, line.text)
                }
            },
        )
        return Parsed(lines, title, startedAt)
    }

    /** Times for lines that have none: spread between known times by words, or 0.4 s a word. */
    private fun timed(lines: List<Triple<Long?, String?, String>>): List<RawSegment> {
        val starts = LongArray(lines.size)
        var i = 0
        var clock = 0L
        while (i < lines.size) {
            val known = lines[i].first
            if (known != null) {
                clock = maxOf(clock, known)
                starts[i] = clock
                clock += words(lines[i].third) * MS_PER_WORD
                i++
                continue
            }
            // A run of lines without times, up to the next line that has one.
            var j = i
            while (j < lines.size && lines[j].first == null) j++
            val runWords = (i until j).sumOf { words(lines[it].third) }
            val next = if (j < lines.size) lines[j].first!! else null
            val perWord = if (next != null && next > clock && runWords > 0) (next - clock).toDouble() / runWords else MS_PER_WORD.toDouble()
            var at = clock.toDouble()
            for (k in i until j) {
                starts[k] = at.toLong()
                at += words(lines[k].third) * perWord
            }
            clock = at.toLong()
            i = j
        }
        return lines.mapIndexed { k, (_, speaker, text) ->
            val start = starts[k]
            val own = start + words(text) * MS_PER_WORD
            val end = if (k + 1 < lines.size) minOf(own, maxOf(start, starts[k + 1])) else own
            RawSegment(start, maxOf(end, start + 500), text, speaker)
        }
    }

    /** Joins a speaker's consecutive short cues (Zoom splits sentences) into readable lines. */
    private fun merge(lines: List<RawSegment>): List<RawSegment> {
        val out = mutableListOf<RawSegment>()
        for (line in lines.sortedBy { it.startMs }) {
            val last = out.lastOrNull()
            if (last != null && last.speakerLabel == line.speakerLabel && line.startMs - last.endMs < MERGE_GAP_MS &&
                last.text.length + line.text.length < MERGE_CHARS && !last.text.endsWith('?')
            ) {
                out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, line.endMs), text = last.text + " " + line.text)
            } else {
                out += line
            }
        }
        return out
    }

    private fun speakerPrefix(line: String): Pair<String?, String> {
        val colon = line.indexOf(':')
        if (colon <= 0 || colon > MAX_NAME) return null to line
        val name = line.substring(0, colon).trim().removePrefix("-").trim()
        val rest = line.substring(colon + 1).trim()
        if (rest.isEmpty() || rest.startsWith("//") || !looksLikeName(name)) return null to line
        return name to rest
    }

    private fun looksLikeName(name: String): Boolean {
        val n = name.trim()
        if (n.isEmpty() || n.length > MAX_NAME) return false
        val words = n.split(Regex("""\s+"""))
        return words.size <= 5 && n.first().isLetter() && n.none { it in ".!?,;\"" || it.isDigit() && !GENERIC_SPEAKER.matches(n) } &&
            words.first().first().let { it.isUpperCase() || !it.isLowerCase() }
    }

    private fun words(text: String) = text.split(Regex("""\s+""")).count { it.isNotEmpty() }.coerceAtLeast(1)

    /** "1:02:03.5", "02:03,500" or "2:03" in milliseconds. */
    private fun clock(text: String): Long? {
        val parts = text.trim().replace(',', '.').split(':')
        if (parts.size !in 2..3) return null
        val seconds = parts.last().toDoubleOrNull() ?: return null
        val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
        val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0
        return ((hours * 3600 + minutes * 60) * 1000 + seconds * 1000).toLong()
    }

    // ---- Word files ------------------------------------------------------------------------

    /** The paragraphs of a .docx file's main text, one per line. */
    fun docxText(bytes: ByteArray): String {
        var xml: String? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    xml = zip.readBytes().decodeToString()
                    break
                }
            }
        }
        val doc = xml ?: throw IllegalArgumentException("This file isn't a Word document.")
        return Regex("""<w:p[ >].*?</w:p>|<w:p/>""", RegexOption.DOT_MATCHES_ALL).findAll(doc).joinToString("\n") { p ->
            Regex("""<w:t(?: [^>]*)?>(.*?)</w:t>|<w:tab/>|<w:br/>""", RegexOption.DOT_MATCHES_ALL).findAll(p.value).joinToString("") {
                when {
                    it.value == "<w:tab/>" -> "\t"
                    it.value == "<w:br/>" -> "\n"
                    else -> decode(it.groupValues[1])
                }
            }
        }
    }

    private fun decode(text: String): String = text
        .replace(Regex("""&#(\d+);""")) { m -> m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value }
        .replace(Regex("""&#x([0-9a-fA-F]+);""")) { m -> m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value }
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&")

    // ---- Titles and dates ------------------------------------------------------------------

    private val MEET_TITLE = Regex("""^(.+?)\s+[-–]\s+Transcript$""", RegexOption.IGNORE_CASE)
    private val TEAMS_TITLE = Regex("""^(.+?)-(\d{8}_\d{6})-Meeting Recording""", RegexOption.IGNORE_CASE)
    private val TEAMS_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private val ZOOM_STAMP = Regex("""GMT(\d{8})-(\d{6})""")
    private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})(?:[ T_](\d{1,2})[:.]?(\d{2}))?""")
    private val LONG_DATE = Regex("""^([A-Z][a-z]+ \d{1,2}, \d{4})(?:,? (\d{1,2}:\d{2}\s?[AaPp][Mm]))?""")

    private fun longDate(row: String): LocalDateTime? {
        val m = LONG_DATE.find(row) ?: return null
        val date = runCatching { LocalDate.parse(m.groupValues[1], DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH)) }.getOrNull() ?: return null
        val time = m.groupValues[2].takeIf { it.isNotEmpty() }?.let { t ->
            runCatching { LocalTime.parse(t.uppercase().replace(Regex("""\s"""), ""), DateTimeFormatter.ofPattern("h:mma", Locale.ENGLISH)) }.getOrNull()
        }
        return date.atTime(time ?: LocalTime.NOON)
    }

    private fun dateIn(name: String): LocalDateTime? {
        // Zoom names files after the start in UTC: GMT20261012-100000_Recording.
        ZOOM_STAMP.find(name)?.let { z ->
            return runCatching {
                LocalDateTime.parse(z.groupValues[1] + z.groupValues[2], DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atZone(ZoneOffset.UTC).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            }.getOrNull()
        }
        val m = ISO_DATE.find(name) ?: return null
        return runCatching {
            val date = LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
            val hour = m.groupValues[4].toIntOrNull()
            date.atTime(if (hour != null) LocalTime.of(hour, m.groupValues[5].toInt()) else LocalTime.NOON)
        }.getOrNull()
    }

    /** The file name without extension or the words apps add ("Transcript", "Meeting Recording", dates). */
    private fun titleFrom(name: String): String {
        val base = name.substringBeforeLast('.').removeSuffix(".transcript").replace('_', ' ')
        return base
            .replace(ZOOM_STAMP, " ")
            .replace(Regex("""(?i)\b(transcript|meeting recording|recording|captions?|closed captions)\b"""), " ")
            .replace(ISO_DATE, " ")
            .replace(Regex("""\d{8}\s?\d{6}"""), " ")
            .replace(Regex("""[\s\-–()]+$|^[\s\-–()]+"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .ifBlank { "Imported transcript" }
    }

    private const val MAX_NAME = 40
    private const val MS_PER_WORD = 400L
    private const val MERGE_GAP_MS = 2_000L
    private const val MERGE_CHARS = 400
    private val GENERIC_SPEAKER = Regex("""(?i)^(speaker|unknown( speaker)?|guest|participant|person)\s*\d*$""")
}
