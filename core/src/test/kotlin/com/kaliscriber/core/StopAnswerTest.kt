package com.kaliscriber.core

import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.llm.LlmClient
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.ask.LibraryAsk
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.search.MemoryCorpus
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Writes part of an answer, then keeps "thinking" until cancelled. */
private class SlowLlm : LlmClient {
    val started = CompletableDeferred<Unit>()
    override val config = AppSettings().configFor(com.kaliscriber.core.ai.ProviderId.OPENAI, "k")
    override suspend fun complete(request: LlmRequest): String =
        if (request.system == Prompts.SEARCH_KEYWORDS_SYSTEM) """{"keywords":["price"]}""" else error("streams only")
    override suspend fun stream(request: LlmRequest, onText: (String) -> Unit): String {
        onText("Prices rise")
        onText("Prices rise ten percent")
        started.complete(Unit)
        awaitCancellation()
    }
    override suspend fun listModels() = emptyList<String>()
}

class StopAnswerTest {
    private val root: File = Files.createTempDirectory("stop").toFile()

    @Test
    fun `stopping an answer keeps what had arrived`() = runTest {
        val repo = MeetingRepository.inDir(root)
        repo.load()
        val meeting = repo.create("Sync")
        repo.update(meeting.id) { it.copy(segments = listOf(TranscriptSegment("s", 0, 1000, "Prices rise ten percent."))) }
        val llm = SlowLlm()
        val ai = FakeAi(llm, FakeStt(false) { SttResult(emptyList(), false) })
        val pipeline = MeetingPipeline(repo, ai, MeetingAi(ai, { AppSettings() }), { AppSettings() })

        val job = launch { pipeline.ask(meeting.id, "What about prices?") {} }
        llm.started.await()
        job.cancelAndJoin()
        val last = repo.get(meeting.id)!!.chat.last()
        assertEquals(ChatRole.ASSISTANT, last.role)
        assertEquals("Prices rise ten percent", last.content)
        assertTrue(last.stopped && !last.isError)

        // Across meetings too.
        val history = JsonValueStore(File(root, "ask.json"), ListSerializer(AskMessage.serializer()), emptyList())
        val ask = LibraryAsk(repo, history, ai, { AppSettings() }, { MemoryCorpus(repo.all().mapNotNull { repo.get(it.id) }) })
        val llm2 = SlowLlm()
        ai.llm = llm2
        val job2 = launch { ask.ask("What about prices?") {} }
        llm2.started.await()
        job2.cancelAndJoin()
        val reply = ask.messages.value.last()
        assertEquals("Prices rise ten percent", reply.content)
        assertTrue(reply.stopped)
    }
}
