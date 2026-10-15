package com.kaliscriber.core

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.recap.WeeklyRecap
import com.kaliscriber.core.recap.WeeklyRecaps
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeeklyRecapTest {
    private val root: File = Files.createTempDirectory("recap").toFile()
    private val repo = MeetingRepository.inDir(root)
    private val week = LocalDate.of(2026, 10, 12) // a Monday

    private fun at(date: LocalDate, hour: Int) = date.atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private suspend fun meeting(title: String, createdAt: Long, insights: MeetingInsights?): Meeting {
        val m = repo.create(title, MeetingStatus.READY)
        return repo.update(m.id) { it.copy(createdAt = createdAt, insights = insights) }!!
    }

    private fun recaps(llm: FakeLlm) = WeeklyRecaps(
        repo,
        FakeAi(llm, FakeStt(false) { error("no audio") }),
        { AppSettings() },
        JsonValueStore(File(root, "recaps.json"), ListSerializer(WeeklyRecap.serializer()), emptyList()),
        clock = { 42 },
        zone = { ZoneOffset.UTC },
    )

    @Test
    fun `the recap is written from the week's meeting notes`() = runTest {
        repo.load()
        meeting(
            "Q3 launch sync", at(week, 10),
            MeetingInsights(
                summary = "Agreed launch pricing.",
                decisions = listOf("Prices rise 10% in November"),
                actionItems = listOf(
                    ActionItem("a1", "Send the price list", owner = "Sam", dueDateIso = "2026-10-20"),
                    ActionItem("a2", "Book the venue", done = true, owner = "Priya"),
                    ActionItem("a3", "Update the website"),
                ),
            ),
        )
        meeting("Hiring", at(week.plusDays(4), 15), MeetingInsights(summary = "Two offers out.", actionItems = listOf(ActionItem("b1", "Chase references", owner = "Sam"))))
        meeting("Last week's retro", at(week.minusDays(1), 23), MeetingInsights(summary = "Retro notes."))
        meeting("Next Monday", at(week.plusDays(7), 0), null)

        val llm = FakeLlm {
            """{"overview": "Launch pricing settled; hiring moving.", "decisions": ["Prices rise 10% in November (Q3 launch sync)"],
               "themes": ["Launch"], "risks": [], "followUps": ["Check Sam sent the price list"]}"""
        }
        val recaps = recaps(llm)
        assertEquals(listOf("Q3 launch sync", "Hiring"), recaps.meetingsIn(week).map { it.title })

        val recap = recaps.write(week)
        val sent = llm.requests.single().messages.single().content
        assertTrue("Q3 launch sync" in sent && "Hiring" in sent)
        assertFalse("retro" in sent || "Next Monday" in sent, "only that week's meetings")
        assertTrue("[open] Send the price list (owner Sam, due 2026-10-20)" in sent)
        assertTrue("[done] Book the venue (owner Priya)" in sent)
        assertEquals("Launch pricing settled; hiring moving.", recap.overview)
        assertEquals(listOf("Check Sam sent the price list"), recap.followUps)
        assertEquals(42L, recap.createdAt)
        assertEquals(recap, recaps.recap(week))

        // Open items by owner, unowned last; done items are left out.
        assertEquals(
            listOf("Sam" to listOf("Send the price list", "Chase references"), null to listOf("Update the website")),
            recaps.openItems(week).map { (owner, items) -> owner to items.map { it.item.task } },
        )
        assertEquals(listOf("Send the price list"), recaps.dueNextWeek(week).map { it.item.task })

        val md = recaps.markdown(recap)
        assertTrue(md.startsWith("# Week of 12 October 2026"))
        assertTrue("## Decisions\n- Prices rise 10% in November (Q3 launch sync)" in md)
        assertTrue("**No owner**\n- [ ] Update the website — Q3 launch sync" in md)
        assertTrue("- Hiring (Fri 16 Oct)" in md)

        // Writing it again replaces the week's recap.
        recaps.write(week)
        assertEquals(1, recaps.recaps.value.size)
    }

    @Test
    fun `an empty week has nothing to recap`() = runTest {
        repo.load()
        assertFailsWith<AiException> { recaps(FakeLlm { "{}" }).write(week) }
        assertEquals(week, WeeklyRecaps.weekOf(week.plusDays(6)))
        assertEquals(week, WeeklyRecaps.weekOf(week))
    }
}
