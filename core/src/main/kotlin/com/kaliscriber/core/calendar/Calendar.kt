package com.kaliscriber.core.calendar

import com.kaliscriber.core.export.BasicOrDigestAuthenticator
import com.kaliscriber.core.export.ExportException
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.people.People
import com.kaliscriber.core.speakers.VoiceProfile
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** A calendar event, as much of it as the app uses. Times are epoch milliseconds. */
@Serializable
data class CalendarEvent(
    val uid: String,
    val title: String,
    val start: Long,
    val end: Long,
    /** Display names of the people invited (organizer included), without those who declined. */
    val attendees: List<String> = emptyList(),
    val allDay: Boolean = false,
    val location: String? = null,
)

/** Reads the VEVENTs of iCalendar (RFC 5545) text. */
object Ics {

    fun events(text: String, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> {
        val lines = unfold(text)
        val out = mutableListOf<CalendarEvent>()
        var props: MutableList<Prop>? = null
        var depth = 0
        for (line in lines) {
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    props = mutableListOf()
                    depth = 0
                }
                line.equals("END:VEVENT", ignoreCase = true) -> {
                    props?.let { p -> toEvent(p, zone)?.let(out::add) }
                    props = null
                }
                // Alarms and other nested components inside an event are skipped.
                line.startsWith("BEGIN:", ignoreCase = true) && props != null -> depth++
                line.startsWith("END:", ignoreCase = true) && props != null -> depth--
                props != null && depth == 0 -> parse(line)?.let(props::add)
            }
        }
        return out
    }

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    /** Long lines continue on the next line after a space or tab. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<StringBuilder>()
        for (raw in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) out.last().append(raw, 1, raw.length)
            else out += StringBuilder(raw)
        }
        return out.map { it.toString() }.filter { it.isNotBlank() }
    }

    private fun parse(line: String): Prop? {
        // The value starts at the first colon outside a quoted parameter.
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val c = line[i]
            if (c == '"') quoted = !quoted
            if (c == ':' && !quoted) {
                colon = i
                break
            }
        }
        if (colon < 0) return null
        val head = splitOutsideQuotes(line.substring(0, colon), ';')
        val params = head.drop(1).mapNotNull { p ->
            val eq = p.indexOf('=')
            if (eq < 0) null else p.substring(0, eq).uppercase() to p.substring(eq + 1).trim('"')
        }.toMap()
        return Prop(head.first().uppercase(), params, line.substring(colon + 1))
    }

    private fun splitOutsideQuotes(s: String, sep: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (c in s) {
            if (c == '"') quoted = !quoted
            if (c == sep && !quoted) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        parts += current.toString()
        return parts
    }

    private fun text(value: String) = value
        .replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")
        .trim()

    private fun toEvent(props: List<Prop>, zone: ZoneId): CalendarEvent? {
        fun prop(name: String) = props.firstOrNull { it.name == name }
        if (prop("STATUS")?.value?.equals("CANCELLED", ignoreCase = true) == true) return null
        val startProp = prop("DTSTART") ?: return null
        val allDay = startProp.params["VALUE"].equals("DATE", ignoreCase = true) || startProp.value.length == 8
        val start = time(startProp, zone) ?: return null
        val end = prop("DTEND")?.let { time(it, zone) }
            ?: prop("DURATION")?.let { d -> duration(d.value)?.let { start + it } }
            ?: (start + if (allDay) 86_400_000L else 0L)
        val organizer = prop("ORGANIZER")?.let(::person)
        val attendees = props.filter { it.name == "ATTENDEE" }
            .filter { a ->
                !a.params["PARTSTAT"].equals("DECLINED", ignoreCase = true) &&
                    !a.params["ROLE"].equals("NON-PARTICIPANT", ignoreCase = true) &&
                    (a.params["CUTYPE"]?.uppercase() ?: "INDIVIDUAL") in setOf("INDIVIDUAL", "UNKNOWN")
            }
            .map(::person)
        val people = (listOfNotNull(organizer) + attendees).distinctBy { it.second.ifBlank { it.first }.lowercase() }
        // Occurrences of a recurring event share its UID; the recurrence id tells them apart.
        val uid = prop("UID")?.value.orEmpty() + (prop("RECURRENCE-ID")?.value?.let { "#$it" } ?: "")
        return CalendarEvent(
            uid = uid.ifBlank { "event-$start" },
            title = prop("SUMMARY")?.value?.let(::text).orEmpty().ifBlank { "Meeting" },
            start = start,
            end = maxOf(end, start),
            attendees = people.map { it.first }.filter { it.isNotBlank() },
            allDay = allDay,
            location = prop("LOCATION")?.value?.let(::text)?.takeIf { it.isNotBlank() },
        )
    }

    /** Display name and email of an ATTENDEE or ORGANIZER. */
    private fun person(p: Prop): Pair<String, String> {
        val email = p.value.substringAfter("mailto:", p.value.substringAfter("MAILTO:", p.value)).trim()
        val name = p.params["CN"]?.trim()?.takeIf { it.isNotEmpty() && !it.contains('@') }
            ?: email.substringBefore('@').split('.', '_', '-').filter { it.isNotEmpty() }
                .joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        return name to email
    }

    private val LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun time(p: Prop, zone: ZoneId): Long? = runCatching {
        val v = p.value.trim()
        when {
            v.length == 8 -> LocalDate.parse(v, DATE).atStartOfDay(zone).toInstant().toEpochMilli()
            v.endsWith("Z") -> LocalDateTime.parse(v.dropLast(1), LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
            else -> {
                val tz = p.params["TZID"]?.let { id -> runCatching { ZoneId.of(id.removePrefix("/")) }.getOrNull() } ?: zone
                LocalDateTime.parse(v, LOCAL).atZone(tz).toInstant().toEpochMilli()
            }
        }
    }.getOrNull()

    private fun duration(v: String): Long? = runCatching {
        val weeks = Regex("""^([+-]?)P(\d+)W$""").find(v.trim())
        if (weeks != null) return@runCatching weeks.groupValues[2].toLong() * 7 * 86_400_000L
        Duration.parse(v.trim()).toMillis()
    }.getOrNull()
}

/**
 * Reads events from a CalDAV calendar (e.g. Baikal) with a calendar-query REPORT. Recurring events
 * are expanded by the server into the occurrences in the requested range.
 */
class CalDavCalendar(http: OkHttpClient, private val url: String, username: String, password: String) {
    private val http = http.newBuilder().authenticator(BasicOrDigestAuthenticator(username, password)).build()

    suspend fun events(from: Long, to: Long): List<CalendarEvent> {
        val collection = url.trim().let { if (it.endsWith("/")) it else "$it/" }
        if (collection.toHttpUrlOrNull() == null) throw ExportException("The CalDAV URL is not valid: ${url.trim()}")
        val range = """start="${utc(from)}" end="${utc(to)}""""
        val body = """
            <?xml version="1.0" encoding="utf-8"?>
            <C:calendar-query xmlns:D="DAV:" xmlns:C="urn:ietf:params:xml:ns:caldav">
              <D:prop><C:calendar-data><C:expand $range/></C:calendar-data></D:prop>
              <C:filter><C:comp-filter name="VCALENDAR"><C:comp-filter name="VEVENT"><C:time-range $range/></C:comp-filter></C:comp-filter></C:filter>
            </C:calendar-query>
        """.trimIndent()
        val request = Request.Builder().url(collection)
            .header("Depth", "1")
            .method("REPORT", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        val response = try {
            http.newCall(request).await()
        } catch (e: IOException) {
            throw ExportException("Could not reach your calendar: ${e.message}", e)
        }
        val text = response.use {
            if (!it.isSuccessful) throw ExportException(if (it.code == 401) "The calendar rejected the username or password." else "Calendar error ${it.code}")
            it.body?.string().orEmpty()
        }
        return parseMultistatus(text).filter { it.end > from && it.start < to }.sortedBy { it.start }
    }

    companion object {
        private val UTC = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        private val CALENDAR_DATA = Regex("""<(?:[A-Za-z0-9_-]+:)?calendar-data[^>]*>(.*?)</(?:[A-Za-z0-9_-]+:)?calendar-data>""", RegexOption.DOT_MATCHES_ALL)

        private fun utc(ms: Long) = UTC.format(Instant.ofEpochMilli(ms))

        /** Events in a CalDAV multistatus reply. */
        fun parseMultistatus(xml: String, zone: ZoneId = ZoneId.systemDefault()): List<CalendarEvent> =
            CALENDAR_DATA.findAll(xml).flatMap { Ics.events(unescape(it.groupValues[1]), zone) }
                .distinctBy { it.uid }
                .toList()

        private fun unescape(s: String): String {
            val cdata = s.trim().removePrefix("<![CDATA[").removeSuffix("]]>")
            return cdata.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
                .replace("&#13;", "\r").replace("&#xD;", "\r").replace("&#xd;", "\r").replace("&#10;", "\n").replace("&amp;", "&")
        }

        private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
            enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) = cont.resume(response)
                override fun onFailure(call: Call, e: IOException) {
                    if (!cont.isCancelled) cont.resumeWithException(e)
                }
            })
            cont.invokeOnCancellation { runCatching { cancel() } }
        }
    }
}

/** Which calendar event a recording belongs to, and what to know before the next one. */
object MeetingPrep {
    /** Recording can start this long before an event and still belong to it. */
    const val EARLY_MS = 15 * 60_000L

    /** The event happening at [now] (or about to start), if any. */
    fun eventAt(events: List<CalendarEvent>, now: Long): CalendarEvent? =
        events.filter { !it.allDay && it.start - EARLY_MS <= now && now < it.end }
            .minByOrNull { abs(it.start - now) }

    /** The event in progress or the next one to start within [withinMs]. */
    fun next(events: List<CalendarEvent>, now: Long, withinMs: Long = 12 * 3_600_000L): CalendarEvent? =
        events.filter { !it.allDay && it.end > now && it.start < now + withinMs }.minByOrNull { it.start }

    /** How many people to expect, when the invitation makes it plausible. */
    fun expectedSpeakers(event: CalendarEvent): Int? = event.attendees.size.takeIf { it in 2..8 }

    data class Prep(
        val event: CalendarEvent,
        /** Invitees the app already knows, with their meetings and open tasks. */
        val people: List<People.Person>,
        /** The most recent meeting with the same title or with any of the invitees. */
        val lastMeeting: Meeting?,
    )

    fun prep(event: CalendarEvent, meetings: List<Meeting>, voices: List<VoiceProfile>): Prep {
        val directory = People.directory(meetings, voices)
        val people = event.attendees.mapNotNull { invitee -> directory.firstOrNull { sameName(it.name, invitee) } }.distinctBy { it.name }
        val ids = people.flatMap { p -> p.meetings.map { it.meetingId } }.toSet()
        val last = meetings
            .filter { m -> m.calendarEvent?.uid?.substringBefore('#') == event.uid.substringBefore('#') || m.title.equals(event.title, true) || m.id in ids }
            .filter { it.createdAt < event.start }
            .maxByOrNull { it.createdAt }
        return Prep(event, people, last)
    }

    /** "Priya Sharma" in an invitation and "Priya" from a meeting are the same person. */
    fun sameName(known: String, invitee: String): Boolean {
        val a = known.trim().lowercase()
        val b = invitee.trim().lowercase()
        if (a.isEmpty() || b.isEmpty()) return false
        return a == b || (!a.contains(' ') && b.substringBefore(' ') == a) || (!b.contains(' ') && a.substringBefore(' ') == b)
    }

    /** The question "Prepare" asks across all meetings. */
    fun question(event: CalendarEvent): String = buildString {
        append("Prepare me for \"${event.title}\"")
        if (event.attendees.isNotEmpty()) append(" with ${event.attendees.joinToString()}")
        append(": what did we discuss and decide last time, what is still open with these people, and what should I raise?")
    }
}
