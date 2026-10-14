package com.kaliscriber.core.export

import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.transcript.TranscriptFormatter
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A simple document model every export target can render. */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Bullet(val text: String) : Block
    data class Todo(val text: String, val done: Boolean) : Block
}

/** A styled run of text: **bold** spans become bold runs. */
data class Run(val text: String, val bold: Boolean = false)

/** The meeting notes to export: title plus body as Markdown and as blocks. */
data class ExportDocument(val title: String, val markdown: String) {
    val blocks: List<Block> by lazy { ExportContent.parse(markdown) }
}

object ExportContent {

    fun document(meeting: Meeting, includeTranscript: Boolean): ExportDocument {
        val date = SimpleDateFormat("EEEE d MMMM yyyy, HH:mm", Locale.ENGLISH).format(Date(meeting.createdAt))
        val full = TranscriptFormatter.markdown(meeting)
        // Drop the H1 title (targets have their own title field) and optionally the transcript.
        var body = full.lines().dropWhile { it.isBlank() || it.startsWith("# ") }.joinToString("\n")
        if (!includeTranscript) body = body.substringBefore("## Transcript").trimEnd()
        val people = meeting.speakers.filter { !it.isPlaceholder }.joinToString { it.name }
        val header = buildString {
            append("**Date:** $date")
            if (meeting.durationMs > 0) append("  \n**Duration:** ${TranscriptFormatter.timestamp(meeting.durationMs)}")
            if (people.isNotEmpty()) append("  \n**Participants:** $people")
        }
        return ExportDocument(meeting.title, "$header\n\n$body\n\n_Created with KaliScriber_")
    }

    fun parse(markdown: String): List<Block> = markdown.lines().mapNotNull { raw ->
        val line = raw.trimEnd().removeSuffix("  ")
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> null
            trimmed.startsWith("#") -> {
                val level = trimmed.takeWhile { it == '#' }.length.coerceIn(1, 3)
                Block.Heading(level, trimmed.trimStart('#').trim())
            }
            TODO_RE.matches(trimmed) -> {
                val m = TODO_RE.matchEntire(trimmed)!!
                Block.Todo(m.groupValues[2].trim(), m.groupValues[1].equals("x", ignoreCase = true))
            }
            BULLET_RE.matches(trimmed) -> Block.Bullet(BULLET_RE.matchEntire(trimmed)!!.groupValues[1].trim())
            else -> Block.Paragraph(trimmed)
        }
    }

    /** Splits `**bold**` markers into runs and drops other inline Markdown. */
    fun runs(text: String): List<Run> {
        val clean = text.replace(Regex("(?<![\\w*])[*_](\\S[^*_]*)[*_](?![\\w*])"), "$1").replace("`", "")
        val parts = clean.split("**")
        return parts.mapIndexedNotNull { i, part -> if (part.isEmpty()) null else Run(part, bold = i % 2 == 1) }
    }

    fun plainText(doc: ExportDocument): String = buildString {
        appendLine(doc.title)
        appendLine("=".repeat(doc.title.length.coerceAtMost(60)))
        for (block in doc.blocks) {
            when (block) {
                is Block.Heading -> { appendLine(); appendLine(text(block.text).uppercase()) }
                is Block.Paragraph -> appendLine(text(block.text))
                is Block.Bullet -> appendLine("• ${text(block.text)}")
                is Block.Todo -> appendLine("${if (block.done) "[x]" else "[ ]"} ${text(block.text)}")
            }
        }
    }.trim()

    fun html(doc: ExportDocument): String = buildString {
        append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        append(escapeHtml(doc.title))
        append("</title></head><body><h1>").append(escapeHtml(doc.title)).append("</h1>")
        var inList = false
        for (block in doc.blocks) {
            val listItem = block is Block.Bullet || block is Block.Todo
            if (listItem && !inList) append("<ul>")
            if (!listItem && inList) append("</ul>")
            inList = listItem
            when (block) {
                is Block.Heading -> append("<h${block.level + 1}>").append(htmlRuns(block.text)).append("</h${block.level + 1}>")
                is Block.Paragraph -> append("<p>").append(htmlRuns(block.text)).append("</p>")
                is Block.Bullet -> append("<li>").append(htmlRuns(block.text)).append("</li>")
                is Block.Todo -> append("<li>").append(if (block.done) "☑ " else "☐ ").append(htmlRuns(block.text)).append("</li>")
            }
        }
        if (inList) append("</ul>")
        append("</body></html>")
    }

    /** A minimal Word document (.docx) that Google Docs, Word and LibreOffice open. */
    fun docx(doc: ExportDocument): ByteArray {
        val body = StringBuilder()
        fun paragraph(style: String?, prefix: String, text: String) {
            body.append("<w:p>")
            if (style != null) body.append("<w:pPr><w:pStyle w:val=\"$style\"/></w:pPr>")
            val runs = listOf(Run(prefix)).filter { it.text.isNotEmpty() } + runs(text)
            for (run in runs) {
                body.append("<w:r>")
                if (run.bold) body.append("<w:rPr><w:b/></w:rPr>")
                body.append("<w:t xml:space=\"preserve\">").append(escapeXml(run.text)).append("</w:t></w:r>")
            }
            body.append("</w:p>")
        }
        paragraph("Title", "", doc.title)
        for (block in doc.blocks) {
            when (block) {
                is Block.Heading -> paragraph("Heading${block.level}", "", block.text)
                is Block.Paragraph -> paragraph(null, "", block.text)
                is Block.Bullet -> paragraph(null, "•  ", block.text)
                is Block.Todo -> paragraph(null, if (block.done) "☑  " else "☐  ", block.text)
            }
        }
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">""" +
            style("Title", "Title", 40) + style("Heading1", "heading 1", 32) +
            style("Heading2", "heading 2", 28) + style("Heading3", "heading 3", 24) + "</w:styles>"
        val contentTypes = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
            """<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>""" +
            """<Default Extension="xml" ContentType="application/xml"/>""" +
            """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
            """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>""" +
            "</Types>"
        val rels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>""" +
            "</Relationships>"
        val documentRels = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
            """<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>""" +
            "</Relationships>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("[Content_Types].xml", contentTypes)
            put("_rels/.rels", rels)
            put("word/document.xml", documentXml)
            put("word/styles.xml", stylesXml)
            put("word/_rels/document.xml.rels", documentRels)
        }
        return out.toByteArray()
    }

    private fun style(id: String, name: String, halfPoints: Int) =
        """<w:style w:type="paragraph" w:styleId="$id"><w:name w:val="$name"/><w:basedOn w:val="Normal"/>""" +
            """<w:pPr><w:spacing w:before="240" w:after="120"/></w:pPr><w:rPr><w:b/><w:sz w:val="$halfPoints"/></w:rPr></w:style>"""

    private fun text(markdown: String) = runs(markdown).joinToString("") { it.text }

    private fun htmlRuns(markdown: String) = runs(markdown).joinToString("") {
        if (it.bold) "<strong>${escapeHtml(it.text)}</strong>" else escapeHtml(it.text)
    }

    fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun escapeXml(s: String) = escapeHtml(s).replace("'", "&apos;")
        .filter { it == '\t' || it == '\n' || it == '\r' || it >= ' ' }

    private val TODO_RE = Regex("""^[-*+]\s+\[( |x|X)]\s+(.*)$""")
    private val BULLET_RE = Regex("""^(?:[-*+]|\d+[.)])\s+(.*)$""")
}
