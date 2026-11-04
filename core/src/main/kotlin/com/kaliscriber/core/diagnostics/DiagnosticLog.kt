package com.kaliscriber.core.diagnostics

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A log of what the app did (recordings, auto-record, processing, sync, backups and errors), kept
 * on the phone in two small files that take turns, so a problem report can show what led up to a
 * problem. Nothing is sent anywhere; the user decides whether to share a report.
 */
class DiagnosticLog(
    private val dir: File,
    private val maxBytes: Long = 192 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    enum class Level { INFO, WARN, ERROR }

    private val current get() = File(dir, "app.log")
    private val previous get() = File(dir, "app.1.log")

    @Synchronized
    fun add(level: Level, tag: String, message: String, error: Throwable? = null) {
        try {
            dir.mkdirs()
            if (current.length() > maxBytes) {
                previous.delete()
                current.renameTo(previous)
            }
            val line = buildString {
                append(TIME.format(Instant.ofEpochMilli(clock()).atZone(zone)))
                append(' ').append(level.name.first()).append(' ').append(tag).append(": ")
                append(ProblemReport.scrub(message.replace('\n', ' ')))
                if (error != null) append('\n').append(ProblemReport.scrub(stack(error)))
                append('\n')
            }
            current.appendText(line)
        } catch (_: Exception) {
            // Logging must never break the app.
        }
    }

    /** The log, oldest first. */
    @Synchronized
    fun text(): String = buildString {
        if (previous.exists()) append(previous.readText())
        if (current.exists()) append(current.readText())
    }

    @Synchronized
    fun clear() {
        previous.delete()
        current.delete()
    }

    fun sizeBytes(): Long = (if (previous.exists()) previous.length() else 0L) + (if (current.exists()) current.length() else 0L)

    companion object {
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

        /** The first lines of [error]'s stack trace, with its causes. */
        fun stack(error: Throwable, maxLines: Int = 25): String {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val lines = sw.toString().trimEnd().lines()
            return (lines.take(maxLines) + if (lines.size > maxLines) listOf("\t… ${lines.size - maxLines} more lines") else emptyList())
                .joinToString("\n")
        }
    }
}

/** Puts a problem report together and keeps secrets out of it. */
object ProblemReport {
    private val KEYS = Regex("""\b(?:sk|pk|rk|gsk|xai|key|tok)[-_][A-Za-z0-9_\-]{12,}|\bAIza[A-Za-z0-9_\-]{20,}|\b[A-Za-z0-9_]{32,}\b""")
    private val BEARER = Regex("""(?i)\b(bearer|basic)(\s+)[A-Za-z0-9._~+/=-]{16,}""")
    private val ASSIGNED = Regex("""(?i)\b(api[-_]?key|password|secret|token)(["']?\s*[:=]\s*["']?)[^\s"',;&]+""")
    private val EMAIL = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val URL_AUTH = Regex("""(https?://)[^/\s:@]+:[^/\s@]+@""")

    /** [text] without API keys, passwords, tokens or email addresses. */
    fun scrub(text: String): String = text
        .replace(URL_AUTH, "$1[hidden]@")
        .replace(BEARER) { "${it.groupValues[1]}${it.groupValues[2]}[hidden]" }
        .replace(ASSIGNED) { "${it.groupValues[1]}${it.groupValues[2]}[hidden]" }
        .replace(KEYS, "[hidden]")
        .replace(EMAIL, "[email]")

    /** The report: [facts] about the phone and the app's settings, then the log. */
    fun build(title: String, facts: List<Pair<String, String>>, log: String, maxLogChars: Int = 300_000): String = buildString {
        appendLine(title)
        appendLine("=".repeat(title.length))
        appendLine()
        facts.forEach { (k, v) -> appendLine("$k: ${scrub(v)}") }
        appendLine()
        appendLine("Log (oldest first)")
        appendLine("------------------")
        val body = log.ifBlank { "(nothing logged yet)" }
        append(if (body.length > maxLogChars) "…\n" + body.takeLast(maxLogChars).substringAfter('\n') else body)
    }
}
