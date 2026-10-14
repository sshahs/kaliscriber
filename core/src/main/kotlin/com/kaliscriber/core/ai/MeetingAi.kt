package com.kaliscriber.core.ai

import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.ChatMessage
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.FollowUpQuestion
import com.kaliscriber.core.model.Gap
import com.kaliscriber.core.model.GapAnalysis
import com.kaliscriber.core.model.Highlight
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.Topic
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.transcript.TranscriptFormatter
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.random.Random

data class NameSuggestion(val speakerId: String, val name: String, val confidence: Double, val reason: String)

private val ISO_DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

enum class NoteAction(val label: String, val instruction: String) {
    TIDY(
        "Tidy up",
        "Rewrite these notes as clean, well-structured Markdown with headings and bullet points. Fix obvious dictation errors. Keep all information.",
    ),
    SUMMARISE("Summarise", "Write a concise summary (a short paragraph, then the key bullet points) of these notes."),
    ACTION_ITEMS(
        "Action items",
        "Extract every task, commitment and follow-up as a Markdown checklist (- [ ] task — owner, due date when known). If there are none, say so.",
    ),
    QUESTIONS(
        "Gaps & questions",
        "List what is missing, vague or unresolved in these notes, then questions the author should answer to complete them.",
    ),
    TITLE("Suggest title", "Reply with only a short title (at most 8 words) for these notes, without quotes or punctuation at the end."),
}

/** All AI reasoning over meetings and notes. Stateless; persistence lives in the pipeline. */
class MeetingAi(
    private val ai: AiServices,
    private val settings: () -> AppSettings,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
) {

    private fun transcriptFor(meeting: Meeting): String =
        TranscriptFormatter.truncateMiddle(TranscriptFormatter.forPrompt(meeting), settings().maxTranscriptChars)

    private suspend fun json(system: String, user: String): JsonObject {
        val text = ai.chat().complete(
            LlmRequest(system = system, messages = listOf(LlmMessage(LlmRole.USER, user)), jsonMode = true),
        )
        return LlmJson.parseObject(text)
    }

    suspend fun insights(meeting: Meeting): MeetingInsights {
        requireTranscript(meeting)
        val obj = json(
            Prompts.insightsSystem(settings().outputLanguage),
            Prompts.meetingContext(meeting, transcriptFor(meeting)) + "\nProduce the JSON now.",
        )
        val previous = meeting.insights?.actionItems.orEmpty()
        return MeetingInsights(
            title = obj.str("title").orEmpty(),
            summary = obj.str("summary", "executiveSummary").orEmpty(),
            keyPoints = obj.strings("keyPoints", "key_points"),
            highlights = obj.list("highlights").mapNotNull { el ->
                val h = el as? JsonObject ?: return@mapNotNull el.asText()?.let { Highlight(text = it) }
                val text = h.str("text", "quote") ?: return@mapNotNull null
                Highlight(text = text, speaker = h.str("speaker"), timestamp = h.str("timestamp", "time"), why = h.str("why", "reason"))
            },
            actionItems = obj.list("actionItems", "action_items").mapNotNull { el ->
                val a = el as? JsonObject
                val task = (a?.str("task", "item", "description") ?: el.asText())?.trim() ?: return@mapNotNull null
                // Keep the done state for tasks that survive a regeneration.
                val wasDone = previous.any { it.done && it.task.equals(task, ignoreCase = true) }
                ActionItem(
                    id = Ids.new(),
                    task = task,
                    owner = a?.str("owner", "assignee")?.takeUnless { it.equals("null", true) || it.equals("unassigned", true) },
                    dueDate = a?.str("dueDate", "due", "deadline")?.takeUnless { it.equals("null", true) },
                    dueDateIso = a?.str("dueDateIso", "due_date_iso")?.takeIf { ISO_DATE.matches(it) },
                    priority = a?.str("priority")?.lowercase(),
                    done = wasDone,
                )
            },
            decisions = obj.strings("decisions"),
            topics = obj.list("topics").mapNotNull { el ->
                val t = el as? JsonObject ?: return@mapNotNull el.asText()?.let { Topic(it) }
                Topic(name = t.str("name", "topic", "title") ?: return@mapNotNull null, summary = t.str("summary").orEmpty())
            },
            risks = obj.strings("risks", "concerns"),
            nextSteps = obj.strings("nextSteps", "next_steps"),
            sentiment = obj.str("sentiment", "tone").orEmpty(),
            generatedAt = clock.now(),
        )
    }

    suspend fun gaps(meeting: Meeting): GapAnalysis {
        requireTranscript(meeting)
        val obj = json(
            Prompts.gapsSystem(settings().outputLanguage),
            Prompts.meetingContext(meeting, transcriptFor(meeting)) + "\nProduce the JSON now.",
        )
        val answered = meeting.gaps?.questions.orEmpty().filter { !it.answer.isNullOrBlank() }
        val fresh = obj.list("questions").mapNotNull { el ->
            val q = el as? JsonObject
            val text = (q?.str("question", "text") ?: el.asText())?.trim() ?: return@mapNotNull null
            FollowUpQuestion(id = Ids.new(), question = text, context = q?.str("context", "why").orEmpty())
        }.filter { new -> answered.none { it.question.equals(new.question, ignoreCase = true) } }
        return GapAnalysis(
            gaps = obj.list("gaps").mapNotNull { el ->
                val g = el as? JsonObject ?: return@mapNotNull el.asText()?.let { Gap(title = it) }
                Gap(
                    title = g.str("title", "gap", "name") ?: return@mapNotNull null,
                    detail = g.str("detail", "description").orEmpty(),
                    suggestion = g.str("suggestion", "fix").orEmpty(),
                )
            },
            // Answered questions are kept so the user's clarifications are never lost.
            questions = answered + fresh,
            generatedAt = clock.now(),
        )
    }

    suspend fun answer(
        meeting: Meeting,
        history: List<ChatMessage>,
        question: String,
        excerpt: ExcerptRef?,
        spoken: Boolean = false,
    ): String {
        requireTranscript(meeting)
        val messages = history.filterNot { it.isError }.takeLast(20).map {
            LlmMessage(if (it.role == ChatRole.USER) LlmRole.USER else LlmRole.ASSISTANT, it.content)
        } + LlmMessage(LlmRole.USER, if (excerpt != null) Prompts.excerptQuestion(meeting, excerpt, question) else question)
        var system = Prompts.askSystem(meeting, transcriptFor(meeting), settings().outputLanguage)
        if (spoken) system += "\n\n" + Prompts.SPOKEN_ANSWER_RULE
        return ai.chat().complete(LlmRequest(system = system, messages = messages)).trim()
    }

    /** A natural, speakable recap of the meeting for an audio briefing. */
    suspend fun briefingScript(meeting: Meeting): String {
        requireTranscript(meeting)
        return ai.chat().complete(
            LlmRequest(
                system = Prompts.briefingSystem(settings().outputLanguage),
                messages = listOf(LlmMessage(LlmRole.USER, Prompts.meetingContext(meeting, transcriptFor(meeting)) + "\nWrite the briefing now.")),
            ),
        ).trim()
    }

    suspend fun suggestNames(meeting: Meeting): List<NameSuggestion> {
        val placeholders = meeting.speakers.filter { it.isPlaceholder }
        if (placeholders.isEmpty() || meeting.segments.isEmpty()) return emptyList()
        val obj = json(
            Prompts.SPEAKER_NAMES_SYSTEM,
            Prompts.meetingContext(meeting, transcriptFor(meeting)) +
                "\nPlaceholder speakers to identify: ${placeholders.joinToString { it.name }}\nProduce the JSON now.",
        )
        return obj.list("speakers").mapNotNull { el ->
            val s = el as? JsonObject ?: return@mapNotNull null
            val placeholder = s.str("placeholder", "speaker") ?: return@mapNotNull null
            val name = s.str("name", "suggestedName")?.takeUnless { it.equals("null", true) || it.equals("unknown", true) }
                ?: return@mapNotNull null
            val speaker = placeholders.firstOrNull { it.name.equals(placeholder, ignoreCase = true) } ?: return@mapNotNull null
            NameSuggestion(speaker.id, name, s.number("confidence") ?: 0.5, s.str("reason", "evidence").orEmpty())
        }
    }

    /**
     * Uses the LLM to split an unlabelled transcript into speaker turns. Returns new speakers and
     * segments; existing named (non-placeholder) speakers are kept.
     */
    suspend fun separateSpeakers(meeting: Meeting, windowChars: Int = 6_000): SpeakerAssigner.Result {
        requireTranscript(meeting)
        val keptSpeakers = meeting.speakers.filter { !it.isPlaceholder && meeting.segments.any { s -> s.speakerId == it.id } }
        val speakers = keptSpeakers.toMutableList()
        val out = mutableListOf<TranscriptSegment>()
        val labelToSpeaker = mutableMapOf<String, Speaker>()

        for (window in windows(meeting.segments, windowChars)) {
            val user = buildString {
                if (labelToSpeaker.isNotEmpty()) {
                    appendLine("Speakers established so far, with a recent line from each:")
                    labelToSpeaker.forEach { (label, speaker) ->
                        val last = out.lastOrNull { it.speakerId == speaker.id }?.text?.take(160).orEmpty()
                        appendLine("$label: \"$last\"")
                    }
                    appendLine()
                }
                meeting.expectedSpeakers?.let { appendLine("The meeting has $it speakers in total.") }
                appendLine("Segments:")
                window.forEachIndexed { i, seg -> appendLine("$i| ${seg.text.replace('\n', ' ')}") }
            }
            val obj = json(Prompts.SEPARATE_SPEAKERS_SYSTEM, user)
            val turns = obj.list("turns", "assignments").mapNotNull { el ->
                val t = el as? JsonObject ?: return@mapNotNull null
                val index = (t["i"] as? JsonPrimitive)?.intOrNull ?: (t["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                Turn(index, t.str("s", "speaker") ?: SpeakerAssigner.UNKNOWN_LABEL, t.str("text"))
            }.groupBy { it.index }

            window.forEachIndexed { i, seg ->
                val segTurns = turns[i].orEmpty()
                val pieces = splitSegment(seg, segTurns)
                for ((label, piece) in pieces) {
                    val key = "llm:${label.trim().uppercase()}"
                    val speaker = labelToSpeaker[label.trim().uppercase()]
                        ?: speakers.firstOrNull { it.label == key }
                        ?: SpeakerAssigner.newPlaceholder(speakers, key, random).also { speakers += it }
                    labelToSpeaker[label.trim().uppercase()] = speaker
                    out += piece.copy(speakerId = speaker.id)
                }
            }
        }
        val used = out.mapNotNull { it.speakerId }.toSet()
        return SpeakerAssigner.Result(speakers.filter { it.id in used }, out)
    }

    private data class Turn(val index: Int, val label: String, val text: String?)

    /** Splits [seg] into the model's turns when its text faithfully matches, otherwise keeps it whole. */
    private fun splitSegment(seg: TranscriptSegment, turns: List<Turn>): List<Pair<String, TranscriptSegment>> {
        if (turns.isEmpty()) return listOf(SpeakerAssigner.UNKNOWN_LABEL to seg)
        val withText = turns.filter { !it.text.isNullOrBlank() }
        val joined = withText.joinToString(" ") { it.text!!.trim() }
        val faithful = withText.size == turns.size && turns.size > 1 &&
            normalized(joined) == normalized(seg.text)
        if (!faithful) return listOf(turns.first().label to seg)

        val total = withText.sumOf { it.text!!.length }.coerceAtLeast(1)
        val span = (seg.endMs - seg.startMs).coerceAtLeast(0)
        var cursor = seg.startMs
        return withText.mapIndexed { i, turn ->
            val end = if (i == withText.lastIndex) seg.endMs else cursor + span * turn.text!!.length / total
            val piece = seg.copy(id = Ids.new(), startMs = cursor, endMs = end, text = turn.text!!.trim())
            cursor = end
            turn.label to piece
        }
    }

    private fun normalized(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun windows(segments: List<TranscriptSegment>, maxChars: Int): List<List<TranscriptSegment>> {
        val result = mutableListOf<List<TranscriptSegment>>()
        var current = mutableListOf<TranscriptSegment>()
        var size = 0
        for (seg in segments) {
            if (current.isNotEmpty() && size + seg.text.length > maxChars) {
                result += current
                current = mutableListOf()
                size = 0
            }
            current += seg
            size += seg.text.length
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    suspend fun noteAction(note: Note, action: NoteAction): String = noteInstruction(note, action.instruction)

    suspend fun noteInstruction(note: Note, instruction: String): String {
        if (note.content.isBlank()) throw AiException("The note is empty.")
        return ai.chat().complete(
            LlmRequest(
                system = Prompts.noteSystem(settings().outputLanguage),
                messages = listOf(
                    LlmMessage(LlmRole.USER, "$instruction\n\n<note title=\"${note.title}\">\n${note.content}\n</note>"),
                ),
            ),
        ).trim()
    }

    private fun requireTranscript(meeting: Meeting) {
        if (meeting.segments.isEmpty()) throw AiException("There is no transcript yet.")
    }
}
