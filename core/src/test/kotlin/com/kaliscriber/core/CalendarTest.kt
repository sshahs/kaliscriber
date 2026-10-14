package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.calendar.CalDavCalendar
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.calendar.Ics
import com.kaliscriber.core.calendar.MeetingPrep
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarTest {
    private val london = ZoneId.of("Europe/London")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: ZoneId = london) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    private val ics = """
        BEGIN:VCALENDAR
        VERSION:2.0
        BEGIN:VEVENT
        UID:weekly-sync@example.com
        RECURRENCE-ID:20261014T090000Z
        DTSTART:20261014T090000Z
        DTEND:20261014T100000Z
        SUMMARY:Weekly sync\, pricing
        ORGANIZER;CN="Sharma, Priya":mailto:priya@example.com
        ATTENDEE;CN=Sam Lee;PARTSTAT=ACCEPTED:mailto:sam@example.com
        ATTENDEE;PARTSTAT=NEEDS-ACTION:mailto:jo.bloggs@example.com
        ATTENDEE;CN=Ana;PARTSTAT=DECLINED:mailto:ana@example.com
        ATTENDEE;CN=Room 4;CUTYPE=ROOM:mailto:room4@example.com
        LOCATION:Room 4
        BEGIN:VALARM
        ACTION:DISPLAY
        DESCRIPTION:Not the event
        END:VALARM
        END:VEVENT
        BEGIN:VEVENT
        UID:design
        DTSTART;TZID=Europe/London:20261014T140000
        DURATION:PT1H30M
        SUMMARY:Design review with a very long title that the server folded onto
          a second line
        END:VEVENT
        BEGIN:VEVENT
        UID:holiday
        DTSTART;VALUE=DATE:20261015
        SUMMARY:Holiday
        END:VEVENT
        BEGIN:VEVENT
        UID:gone
        DTSTART:20261014T120000Z
        DTEND:20261014T130000Z
        STATUS:CANCELLED
        SUMMARY:Cancelled
        END:VEVENT
        END:VCALENDAR
    """.trimIndent().replace("\n", "\r\n")

    @Test
    fun `events are read from icalendar text`() {
        val events = Ics.events(ics, london)
        assertEquals(listOf("Weekly sync, pricing", "Design review with a very long title that the server folded onto a second line", "Holiday"), events.map { it.title })
        val sync = events[0]
        assertEquals(at(2026, 10, 14, 10), sync.start, "09:00 UTC is 10:00 in London in October")
        assertEquals(listOf("Sharma, Priya", "Sam Lee", "Jo Bloggs"), sync.attendees, "declined people and rooms are left out")
        assertEquals("Room 4", sync.location)
        assertEquals("weekly-sync@example.com#20261014T090000Z", sync.uid)
        val design = events[1]
        assertEquals(at(2026, 10, 14, 14), design.start)
        assertEquals(at(2026, 10, 14, 15, 30), design.end)
        assertTrue(events[2].allDay)
    }

    @Test
    fun `calendar query reads events from the multistatus reply`() = runTest {
        val server = MockWebServer().apply { start() }
        try {
            val escaped = ics.replace("&", "&amp;").replace("<", "&lt;").replace("\r", "&#13;")
            server.enqueue(MockResponse().setResponseCode(401).setHeader("WWW-Authenticate", "Basic realm=\"dav\""))
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:" xmlns:cal="urn:ietf:params:xml:ns:caldav">""" +
                        """<d:response><d:href>/dav.php/calendars/me/default/a.ics</d:href><d:propstat><d:prop>""" +
                        """<cal:calendar-data>$escaped</cal:calendar-data></d:prop></d:propstat></d:response></d:multistatus>""",
                ),
            )
            val calendar = CalDavCalendar(OkHttpClient(), server.url("/dav.php/calendars/me/default").toString(), "me", "pw")
            val events = calendar.events(at(2026, 10, 14, 0), at(2026, 10, 15, 0))
            assertEquals(listOf("Weekly sync, pricing", "Design review with a very long title that the server folded onto a second line"), events.map { it.title }.take(2))
            server.takeRequest()
            val sent = server.takeRequest()
            assertEquals("REPORT", sent.method)
            assertEquals("1", sent.getHeader("Depth"))
            assertTrue(sent.getHeader("Authorization")!!.startsWith("Basic "))
            val body = sent.body.readUtf8()
            assertTrue(body.contains("<C:expand start=\"20261013T230000Z\""), body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `the event being recorded and the next one are found`() {
        val events = Ics.events(ics, london)
        assertEquals("Weekly sync, pricing", MeetingPrep.eventAt(events, at(2026, 10, 14, 9, 50))?.title, "starting a few minutes early counts")
        assertNull(MeetingPrep.eventAt(events, at(2026, 10, 14, 12)), "nothing on at noon; all-day events don't count")
        assertEquals("Design review", MeetingPrep.next(events, at(2026, 10, 14, 12))?.title?.take(13))
        assertEquals(3, MeetingPrep.expectedSpeakers(events[0]))
    }

    @Test
    fun `prep gathers the invitees' open tasks and the last meeting`() = runTest {
        val root = Files.createTempDirectory("cal").toFile()
        val repo = MeetingRepository(JsonDocumentStore(File(root, "m"), Meeting.serializer()) { it.id }, File(root, "a"))
        val last = repo.create("Weekly sync, pricing")
        repo.update(last.id) {
            it.copy(
                createdAt = at(2026, 10, 7, 10),
                speakers = listOf(Speaker("s", "Sam", isPlaceholder = false)),
                segments = listOf(TranscriptSegment("x", 0, 1000, "Hi", speakerId = "s")),
                insights = MeetingInsights(summary = "s", actionItems = listOf(ActionItem("a", "Send the price list", owner = "Sam"))),
            )
        }
        val event = Ics.events(ics, london)[0]
        val prep = MeetingPrep.prep(event, repo.all(), emptyList())
        assertEquals(listOf("Sam"), prep.people.map { it.name })
        assertEquals(listOf("Send the price list"), prep.people.single().openItems.map { it.item.task })
        assertEquals(last.id, prep.lastMeeting?.id)
        assertTrue(MeetingPrep.question(event).contains("Sam Lee"))
    }

    @Test
    fun `a recording takes its title and invitees from the calendar`() = runTest {
        val root = Files.createTempDirectory("cal2").toFile()
        val repo = MeetingRepository(JsonDocumentStore(File(root, "m"), Meeting.serializer()) { it.id }, File(root, "a"))
        var settings = AppSettings()
        val llm = FakeLlm { """{"title":"Something else","summary":"Done.","actionItems":[]}""" }
        val stt = FakeStt(false) { SttResult(emptyList(), false) }
        val p = MeetingPipeline(repo, FakeAi(llm, stt), MeetingAi(FakeAi(llm, stt), { settings }), { settings })
        val meeting = repo.create()
        val event = CalendarEvent("u", "Pricing review", 0, 3_600_000, listOf("Priya Sharma", "Sam Lee"))
        p.attachCalendarEvent(meeting.id, event)
        repo.update(meeting.id) { it.copy(segments = listOf(TranscriptSegment("x", 0, 1000, "Hello"))) }
        p.generateInsights(meeting.id)
        val m = repo.get(meeting.id)!!
        assertEquals("Pricing review", m.title, "the calendar title is kept")
        assertEquals(2, m.expectedSpeakers)
        assertTrue(llm.requests.last().system.contains("Invited (from the calendar; the speakers are likely among them): Priya Sharma, Sam Lee"))
    }
}
