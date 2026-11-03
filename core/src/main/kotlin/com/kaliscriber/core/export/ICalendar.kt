package com.kaliscriber.core.export

import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** iCalendar (RFC 5545) for CalDAV servers such as Baikal: the meeting as an event, action items as tasks. */
object ICalendar {

    fun meetingUid(meeting: Meeting) = "kaliscriber-meeting-${meeting.id}"
    fun taskUid(meeting: Meeting, item: ActionItem) = "kaliscriber-task-${meeting.id}-${item.id}"

    fun event(meeting: Meeting, description: String, now: Long = System.currentTimeMillis()): String {
        val end = meeting.createdAt + meeting.durationMs.coerceAtLeast(60_000)
        return calendar(
            "VEVENT",
            listOf(
                "UID" to meetingUid(meeting),
                "DTSTAMP" to utc(now),
                "DTSTART" to utc(meeting.createdAt),
                "DTEND" to utc(end),
                "SUMMARY" to escape(meeting.title),
                "DESCRIPTION" to escape(description),
                "CATEGORIES" to "KaliScriber",
            ),
        )
    }

    fun task(meeting: Meeting, item: ActionItem, now: Long = System.currentTimeMillis()): String {
        val description = buildString {
            append("From meeting: ${meeting.title}")
            item.owner?.let { append("\nOwner: $it") }
            item.dueDate?.let { append("\nDue: $it") }
        }
        val props = mutableListOf(
            "UID" to taskUid(meeting, item),
            "DTSTAMP" to utc(now),
            "LAST-MODIFIED" to utc(now),
            "SUMMARY" to escape(item.task),
            "DESCRIPTION" to escape(description),
            "STATUS" to if (item.done) "COMPLETED" else "NEEDS-ACTION",
            "CATEGORIES" to "KaliScriber",
        )
        item.dueDateIso?.let { props += "DUE;VALUE=DATE" to it.replace("-", "") }
        when (item.priority?.lowercase()) {
            "high" -> props += "PRIORITY" to "1"
            "medium" -> props += "PRIORITY" to "5"
            "low" -> props += "PRIORITY" to "9"
        }
        if (item.done) props += "PERCENT-COMPLETE" to "100"
        return calendar("VTODO", props)
    }

    private fun calendar(component: String, props: List<Pair<String, String>>): String {
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//KaliScriber//Meeting notes//EN", "CALSCALE:GREGORIAN", "BEGIN:$component")
        props.forEach { (name, value) -> lines += "$name:$value" }
        lines += "END:$component"
        lines += "END:VCALENDAR"
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    fun escape(text: String) = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\n")
        .replace("\n", "\\n")

    /** Folds content lines longer than 75 octets, never splitting a UTF-8 character. */
    fun fold(line: String): String {
        if (line.toByteArray(Charsets.UTF_8).size <= 75) return line
        val out = StringBuilder()
        var count = 0
        var limit = 75
        for (ch in line) {
            val size = ch.toString().toByteArray(Charsets.UTF_8).size
            if (count + size > limit) {
                out.append("\r\n ")
                count = 0
                limit = 74
            }
            out.append(ch)
            count += size
        }
        return out.toString()
    }

    private fun utc(millis: Long): String = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(millis))
}
