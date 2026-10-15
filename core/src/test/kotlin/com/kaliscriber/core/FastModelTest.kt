package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.DefaultAiServices
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FastModelTest {
    private val meeting = Meeting(
        "m", "Planning", 1, status = MeetingStatus.READY,
        speakers = listOf(Speaker("a", "Amber Falcon", isPlaceholder = true)),
        segments = listOf(TranscriptSegment("s1", 0, 1000, "I'm Priya, let's plan the launch with cali scribe.", "a")),
    )

    @Test
    fun `quick background tasks use the fast model and the rest the main one`() = runTest {
        val calls = mutableListOf<String>()
        fun model(name: String) = FakeLlm(respond = { _ -> calls.add(name); "{}" })
        val ai = FakeAi(model("main"), FakeStt(false) { error("no audio") }, fast = model("fast"))
        val meetingAi = MeetingAi(ai, { AppSettings(glossary = "KaliScriber") })

        meetingAi.sectionNotes(meeting, meeting.segments)
        meetingAi.suggestNames(meeting)
        meetingAi.glossaryFixes(meeting)
        assertEquals(listOf("fast", "fast", "fast"), calls)

        calls.clear()
        meetingAi.insights(meeting)
        meetingAi.gaps(meeting)
        assertEquals(listOf("main", "main"), calls)
    }

    @Test
    fun `the fast model is the chosen provider and model, or the main one`() {
        val keys = mutableMapOf(ProviderId.OPENAI to "k1")
        var settings = AppSettings(chatProvider = ProviderId.OPENAI)
        val ai = DefaultAiServices({ settings }, { keys[it] })

        assertEquals(ai.chat().config, ai.fastChat().config, "no fast model chosen")

        settings = settings.copy(fastChatProvider = ProviderId.ANTHROPIC, fastChatModel = "small-model")
        assertEquals(ProviderId.OPENAI, ai.fastChat().config.id, "no key for the fast model's provider yet")

        keys[ProviderId.ANTHROPIC] = "k2"
        assertEquals(ProviderId.ANTHROPIC to "small-model", ai.fastChat().config.let { it.id to it.chatModel })
        assertEquals(ProviderId.OPENAI, ai.chat().config.id)

        // A smaller model from the same provider leaves the main model as it is.
        settings = settings.copy(fastChatProvider = ProviderId.OPENAI, fastChatModel = "mini")
        assertEquals("mini", ai.fastChat().config.chatModel)
        assertEquals(ProviderId.OPENAI.defaultChatModel, ai.chat().config.chatModel)

        settings = settings.copy(fastChatModel = "")
        assertEquals(ProviderId.OPENAI.defaultChatModel, ai.fastChat().config.chatModel, "blank uses the provider's model")
    }
}
