package com.kaliscriber.core.ask

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiServices
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.LlmJson
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.strings
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.AskSource
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.search.MeetingSearch
import com.kaliscriber.core.search.SearchCorpus
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * "Ask across all meetings": finds the meetings and passages relevant to a question with
 * [MeetingSearch], has the AI answer from them with citations like [M2 12:34], and turns those
 * citations into [AskSource]s the app can open. The conversation is kept in [history].
 */
class LibraryAsk(
    private val meetings: MeetingRepository,
    private val history: JsonValueStore<List<AskMessage>>,
    private val ai: AiServices,
    private val settings: () -> AppSettings,
    /** Where transcript passages are found: the search index, or the meetings themselves in tests. */
    private val corpus: () -> SearchCorpus,
    private val clock: Clock = Clock.System,
) {
    val messages: StateFlow<List<AskMessage>> = history.value

    suspend fun clear() {
        history.update { emptyList() }
    }

    /**
     * Asks [question]; the answer (or an error message) is added to the conversation and returned.
     * [onText] gets the answer written so far while it arrives.
     */
    suspend fun ask(question: String, onText: ((String) -> Unit)? = null): AskMessage {
        val q = question.trim()
        val previous = history.value.value
        history.update { it + AskMessage(Ids.new(), ChatRole.USER, q, clock.now()) }
        val reply = try {
            answer(q, previous, onText)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AskMessage(Ids.new(), ChatRole.ASSISTANT, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName, clock.now(), isError = true)
        }
        history.update { it + reply }
        return reply
    }

    private suspend fun answer(question: String, previous: List<AskMessage>, onText: ((String) -> Unit)?): AskMessage {
        val all = meetings.all().filter { it.hasTranscript || it.insights != null }
        if (all.isEmpty()) throw AiException("There are no transcribed meetings to search yet.")
        // Follow-ups ("and who owns it?") lean on the previous question for what to look up.
        val lastQuestion = previous.lastOrNull { it.role == ChatRole.USER && !it.isError }?.content
        val keywords = (expandKeywords(question) + MeetingSearch.terms(question) + MeetingSearch.terms(lastQuestion.orEmpty())).distinct()
        val budget = settings().maxTranscriptChars.coerceAtMost(CONTEXT_BUDGET)
        val sources = MeetingSearch.retrieve(all, corpus(), keywords, budget)
        val context = sources.joinToString("\n") { MeetingSearch.render(it, Prompts::formatDate) }

        val turns = previous.filter { !it.isError }.takeLast(10).map {
            LlmMessage(if (it.role == ChatRole.USER) LlmRole.USER else LlmRole.ASSISTANT, it.content)
        } + LlmMessage(LlmRole.USER, question)
        val request = LlmRequest(system = Prompts.libraryAskSystem(context, clock.now(), settings().outputLanguage, settings().glossary), messages = turns)
        val text = (if (onText == null) ai.chat().complete(request) else ai.chat().stream(request, onText)).trim()
        val cited = citedSources(text, sources, corpus())
        val shown = cited.ifEmpty { sources.take(3).map { AskSource(it.meeting.id, it.meeting.title, label = it.label) } }
        return AskMessage(Ids.new(), ChatRole.ASSISTANT, text, clock.now(), sources = shown)
    }

    /** Search words and likely synonyms for [question] from the AI; just the question's own words if that fails. */
    private suspend fun expandKeywords(question: String): List<String> = try {
        val reply = ai.chat().complete(
            LlmRequest(
                system = Prompts.SEARCH_KEYWORDS_SYSTEM,
                messages = listOf(LlmMessage(LlmRole.USER, question)),
                jsonMode = true,
                outputLimit = 400,
            ),
        )
        LlmJson.parseObject(reply).strings("keywords")
            .flatMap { MeetingSearch.terms(it) }
            .take(20)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** Characters of meeting excerpts sent with one question; keeps answers quick. */
        const val CONTEXT_BUDGET = 60_000

        private val BRACKETS = Regex("""\[([^\]]{1,80})]""")
        private val CITATION = Regex("""M(\d+)(?:\s*[@,]?\s*(\d{1,2}(?::\d{2}){1,2}))?""")

        /** Sources for the [M2 12:34] style citations in [answer], in the order they first appear. */
        fun citedSources(answer: String, sources: List<MeetingSearch.Source>, corpus: SearchCorpus): List<AskSource> {
            val byLabel = sources.associateBy { it.label }
            val out = LinkedHashMap<String, AskSource>()
            for (bracket in BRACKETS.findAll(answer)) {
                for (match in CITATION.findAll(bracket.groupValues[1])) {
                    val source = byLabel["M${match.groupValues[1]}"] ?: continue
                    val meeting = source.meeting
                    val ms = match.groupValues[2].takeIf { it.isNotEmpty() }?.let(::parseTime)
                    val segment = ms?.let { t -> corpus.lineAt(meeting.id, t + 999) }
                    val key = "${meeting.id}@${segment?.segmentId}"
                    if (key !in out) {
                        out[key] = AskSource(meeting.id, meeting.title, ms ?: segment?.startMs, segment?.segmentId, label = source.label)
                    }
                }
            }
            return out.values.toList()
        }

        private fun parseTime(text: String): Long? {
            val parts = text.split(':').map { it.toLongOrNull() ?: return null }
            return when (parts.size) {
                2 -> (parts[0] * 60 + parts[1]) * 1000
                3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
                else -> null
            }
        }
    }
}
