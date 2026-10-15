package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.transcript.TranscriptFiles
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TranscriptImportTest {
    private fun parse(name: String, text: String) = TranscriptFiles.parse(name, text.toByteArray())

    @Test
    fun `Zoom WebVTT gives speakers, times and joined sentences`() {
        val zoom = """
            WEBVTT

            1
            00:00:01.350 --> 00:00:04.020
            Priya Sharma: Hi everyone, thanks for joining.

            2
            00:00:04.100 --> 00:00:06.000
            Priya Sharma: Let's start with pricing.

            3
            00:00:07.500 --> 00:01:02.110
            Sam Lee: Prices go up &amp; we tell customers in November.
        """.trimIndent()
        val parsed = parse("GMT20261012-100000_Recording.transcript.vtt", zoom)
        assertEquals(
            listOf(
                Triple("Priya Sharma", 1_350L, "Hi everyone, thanks for joining. Let's start with pricing."),
                Triple("Sam Lee", 7_500L, "Prices go up & we tell customers in November."),
            ),
            parsed.lines.map { Triple(it.speakerLabel, it.startMs, it.text) },
        )
        assertEquals(62_110L, parsed.lines.last().endMs)
        assertEquals("Imported transcript", parsed.fileTitle, "Zoom's file name says nothing about the meeting")
        assertEquals(
            java.time.ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, java.time.ZoneOffset.UTC),
            parsed.startedAt!!.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneOffset.UTC),
        )
    }

    @Test
    fun `Teams WebVTT voice tags name the speakers`() {
        val teams = """
            WEBVTT

            0b6b3a6e-1c39-4b9d-9a3e-5d1c/12-0
            00:00:03.120 --> 00:00:06.480
            <v Priya Sharma>Let's look at the budget.</v>

            0b6b3a6e-1c39-4b9d-9a3e-5d1c/13-0
            01:02:06.480 --> 01:02:08.000
            <v Sam Lee (Contoso)>Sure, <b>one</b> minute.</v>
        """.trimIndent()
        val lines = parse("Budget review.vtt", teams).lines
        assertEquals(listOf("Priya Sharma", "Sam Lee (Contoso)"), lines.map { it.speakerLabel })
        assertEquals("Sure, one minute.", lines[1].text)
        assertEquals(3_726_480L, lines[1].startMs)
        assertEquals("Budget review", parse("Budget review.vtt", teams).fileTitle)
    }

    @Test
    fun `SRT subtitles are read, and a one-off colon is not a speaker`() {
        val srt = "1\r\n00:00:01,000 --> 00:00:03,500\r\nNote: the deck is in the shared drive.\r\n\r\n" +
            "2\r\n00:00:10,000 --> 00:00:12,000\r\nThanks, I'll look at it.\r\n"
        val lines = parse("call.srt", srt).lines
        assertEquals(listOf<String?>(null, null), lines.map { it.speakerLabel })
        assertEquals("Note: the deck is in the shared drive.", lines[0].text)
        assertEquals(10_000L, lines[1].startMs)
    }

    @Test
    fun `Google Meet text has a title, timestamps every few minutes and named lines`() {
        val meet = """
            Weekly sync - Transcript
            Attendees
            Priya Sharma, Sam Lee
            Transcript
            00:00:00

            Priya Sharma: Morning. Two things today.
            Sam Lee: Go ahead.
            Priya Sharma: First the launch, then hiring.
            00:05:00

            Sam Lee: On hiring, we have two offers out.
        """.trimIndent()
        val parsed = parse("Weekly sync - Transcript.txt", meet)
        assertEquals("Weekly sync", parsed.title)
        assertEquals(listOf("Priya Sharma", "Sam Lee", "Priya Sharma", "Sam Lee"), parsed.lines.map { it.speakerLabel })
        assertEquals(0L, parsed.lines[0].startMs)
        assertEquals(300_000L, parsed.lines[3].startMs)
        // Lines without their own time are spread over the five minutes by length.
        assertTrue(parsed.lines[1].startMs in 1..299_999 && parsed.lines[2].startMs > parsed.lines[1].startMs)
        assertFalse(parsed.lines.any { "Attendees" in it.text || "Transcript" in it.text })
    }

    @Test
    fun `Otter and Teams headings put the name and time above the words`() {
        val otter = "Priya Sharma  0:00\nHi everyone.\nShall we start?\n\nSam Lee  0:12\nYes, the numbers first.\n\nPriya Sharma  1:05\nGood idea. Let's meet at 10:30\n"
        val lines = parse("standup.txt", otter).lines
        assertEquals(listOf("Priya Sharma", "Sam Lee", "Priya Sharma"), lines.map { it.speakerLabel })
        assertEquals("Hi everyone. Shall we start?", lines[0].text)
        assertEquals(listOf(0L, 12_000L, 65_000L), lines.map { it.startMs })
        assertEquals("Good idea. Let's meet at 10:30", lines[2].text, "a time at the end of what was said isn't a heading")
    }

    @Test
    fun `Teams Word transcripts are read from the document`() {
        val paragraphs = listOf(
            "Budget review-20261012_143000-Meeting Recording",
            "October 12, 2026, 2:30PM",
            "32m 10s",
            "Priya Sharma started transcription",
            "Priya Sharma\t0:03",
            "Let&apos;s look at Q3 &amp; Q4.",
            "Sam Lee\t0:15",
            "Sure.",
            "Priya Sharma\t0:20",
            "Thanks.",
        )
        val xml = """<?xml version="1.0"?><w:document xmlns:w="x"><w:body>""" +
            paragraphs.joinToString("") { p ->
                val parts = p.split('\t')
                "<w:p><w:r><w:t xml:space=\"preserve\">${parts[0]}</w:t>" + (if (parts.size > 1) "<w:tab/><w:t>${parts[1]}</w:t>" else "") + "</w:r></w:p>"
            } + "</w:body></w:document>"
        val docx = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml")); zip.write("<Types/>".toByteArray()); zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/document.xml")); zip.write(xml.toByteArray()); zip.closeEntry()
            }
        }.toByteArray()
        val parsed = TranscriptFiles.parse("Budget review.docx", docx)
        assertEquals("Budget review", parsed.title)
        assertEquals(LocalDateTime.of(2026, 10, 12, 14, 30), parsed.startedAt)
        assertEquals(listOf("Priya Sharma", "Sam Lee", "Priya Sharma"), parsed.lines.map { it.speakerLabel })
        assertEquals("Let's look at Q3 & Q4.", parsed.lines[0].text)
        assertEquals(listOf(3_000L, 15_000L, 20_000L), parsed.lines.map { it.startMs })
    }

    @Test
    fun `plain text without speakers or times still imports`() {
        val parsed = parse("2026-10-09 notes from the call.txt", "We agreed to ship on Friday.\nMarketing will send the email.")
        assertEquals(1, parsed.lines.size, "one unnamed speaker's lines are joined")
        assertNull(parsed.lines.single().speakerLabel)
        assertEquals("notes from the call", parsed.fileTitle)
        assertEquals(LocalDateTime.of(2026, 10, 9, 12, 0), parsed.startedAt)
        assertTrue(parsed.lines.single().endMs > 0)
    }

    @Test
    fun `an imported transcript becomes a meeting with its speakers and notes`() = runTest {
        val repo = MeetingRepository.inDir(Files.createTempDirectory("import").toFile())
        repo.load()
        val llm = FakeLlm { req ->
            if ("action" in req.system.lowercase()) "{\"summary\": \"Pricing agreed.\"}" else "{}"
        }
        val ai = FakeAi(llm, FakeStt(false) { error("no audio") })
        val settings = AppSettings()
        val pipeline = MeetingPipeline(repo, ai, MeetingAi(ai, { settings }), { settings })
        val parsed = parse("pricing.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\nPriya: Prices go up.\n\n00:00:04.000 --> 00:00:05.000\nSpeaker 2: Agreed.\n")
        val meeting = repo.create(parsed.title ?: parsed.fileTitle, MeetingStatus.ANALYZING)

        pipeline.importTranscript(meeting.id, parsed)

        val m = repo.get(meeting.id)!!
        assertEquals(MeetingStatus.READY, m.status)
        assertEquals(listOf("Prices go up.", "Agreed."), m.segments.map { it.text })
        val priya = m.speaker(m.segments[0].speakerId)!!
        assertEquals("Priya" to false, priya.name to priya.isPlaceholder)
        assertTrue(m.speaker(m.segments[1].speakerId)!!.isPlaceholder, "a numbered speaker is left for the AI to name")
        assertEquals("Pricing agreed.", m.insights?.summary)
        assertEquals("Transcript imported", m.timeline.first().label)
        assertEquals(5_000L, m.durationMs)
    }

    @Test
    fun `files that hold no transcript are refused`() {
        assertFailsWith<IllegalArgumentException> { parse("empty.txt", "\n\n  \n") }
        assertTrue(TranscriptFiles.isTranscript("call.vtt", null))
        assertTrue(TranscriptFiles.isTranscript(null, "application/x-subrip"))
        assertFalse(TranscriptFiles.isTranscript("call.m4a", "audio/mp4"))
        assertFalse(TranscriptFiles.isRealName("Speaker 2"))
        assertFalse(TranscriptFiles.isRealName("Unknown speaker"))
        assertTrue(TranscriptFiles.isRealName("Priya Sharma"))
    }
}
