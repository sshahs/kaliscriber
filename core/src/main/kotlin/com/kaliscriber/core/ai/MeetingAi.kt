package com.kaliscriber.core.ai

import com.kaliscriber.core.ai.llm.LlmClient
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.usage.TaggedLlm
import com.kaliscriber.core.ai.usage.UsageTag
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.ChatMessage
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.FollowUpQuestion
import com.kaliscriber.core.model.Gap
import com.kaliscriber.core.model.GapAnalysis
import com.kaliscriber.core.model.Highlight
import com.kaliscriber.core.model.InsightSection
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * The chat model for [purpose] (the fast one for [AppSettings.FAST_TASKS]), with its requests
     * counted towards [meeting] (or none).
     */
    private fun chat(meeting: Meeting?, purpose: String): LlmClient =
        TaggedLlm(if (purpose in AppSettings.FAST_TASKS) ai.fastChat() else ai.chat(), UsageTag(meeting?.id, purpose))

    private fun purposeOf(task: String): String = when {
        task.startsWith(Prompts.TASK_INSIGHTS) -> "Summary"
        task.startsWith(Prompts.TASK_GAPS) -> "Gaps & questions"
        task.startsWith(Prompts.TASK_NAMES) -> "Speaker names"
        task.startsWith(Prompts.TASK_GLOSSARY) -> "Glossary fixes"
        else -> "Meeting"
    }

    private suspend fun json(system: String, user: String, outputLimit: Int? = null, meeting: Meeting? = null, purpose: String = "Other"): JsonObject {
        val text = chat(meeting, purpose).complete(
            LlmRequest(system = system, messages = listOf(LlmMessage(LlmRole.USER, user)), jsonMode = true, outputLimit = outputLimit),
        )
        return LlmJson.parseObject(text)
    }

    /** The shared, cacheable start of every request about [meeting] (see [Prompts.meetingSystem]). */
    private fun shared(meeting: Meeting, transcript: String = transcriptFor(meeting)): String =
        Prompts.meetingSystem(meeting, transcript, settings().outputLanguage, settings().glossary)

    /** Runs [task] on [meeting] and parses the JSON reply; the meeting text forms a cacheable prefix. */
    private suspend fun meetingJson(meeting: Meeting, task: String, transcript: String = transcriptFor(meeting)): JsonObject {
        val prefix = shared(meeting, transcript)
        val text = chat(meeting, purposeOf(task)).complete(
            LlmRequest(
                system = prefix + "\n" + task,
                messages = listOf(LlmMessage(LlmRole.USER, "Do the task now and reply with the JSON only.")),
                jsonMode = true,
                cachePrefix = prefix,
            ),
        )
        return LlmJson.parseObject(text)
    }

    /**
     * What the summary and gaps read: normally the transcript. With [fromNotes], a long meeting that
     * has notes written while it was recorded is read as those notes plus the transcript after the
     * last one, which is much shorter, so the summary is ready sooner.
     */
    internal fun insightsSource(meeting: Meeting, fromNotes: Boolean): String {
        val full = transcriptFor(meeting)
        val sections = meeting.sections.sortedBy { it.startMs }
        if (!fromNotes || sections.isEmpty()) return full
        val coveredUntil = sections.last().endMs
        val tail = meeting.segments.filter { it.startMs >= coveredUntil }
        val text = buildString {
            appendLine("Notes written while the meeting was recorded (speaker labels were not known yet):")
            sections.forEach { section ->
                appendLine("## [${TranscriptFormatter.timestamp(section.startMs)}–${TranscriptFormatter.timestamp(section.endMs)}]")
                appendLine(section.notes.trim())
            }
            if (tail.isNotEmpty()) {
                appendLine()
                appendLine("Transcript from [${TranscriptFormatter.timestamp(coveredUntil)}] to the end:")
                appendLine(TranscriptFormatter.forPrompt(meeting, tail))
            }
        }
        // Only worth it when the notes are clearly shorter than the transcript itself.
        return if (text.length < full.length * 0.6) text else full
    }

    /** Compact notes on one stretch of a meeting that is still being recorded. */
    suspend fun sectionNotes(meeting: Meeting, part: List<TranscriptSegment>): String {
        val user = buildString {
            appendLine("Meeting: ${meeting.title}")
            appendLine("<transcript_part>")
            appendLine(TranscriptFormatter.forPrompt(meeting, part))
            appendLine("</transcript_part>")
            val from = part.firstOrNull()?.startMs ?: 0
            val to = (part.lastOrNull()?.endMs ?: 0) + BOOKMARK_GRACE_MS
            val marks = meeting.bookmarks.filter { it.atMs in from..to }
            if (marks.isNotEmpty()) {
                appendLine("<bookmarks>")
                appendLine(Prompts.bookmarkLines(marks))
                appendLine("</bookmarks>")
            }
        }
        return chat(meeting, "Running notes").complete(
            LlmRequest(
                system = Prompts.sectionNotesSystem(settings().outputLanguage, settings().glossary),
                messages = listOf(LlmMessage(LlmRole.USER, user)),
                outputLimit = 1_500,
            ),
        ).trim()
    }

    suspend fun insights(meeting: Meeting, fromNotes: Boolean = false): MeetingInsights {
        requireTranscript(meeting)
        val type = MeetingType.of(meeting)
        val obj = meetingJson(meeting, Prompts.insightsTask(type), insightsSource(meeting, fromNotes))
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
            sections = obj.list("sections").mapNotNull { el ->
                val sec = el as? JsonObject ?: return@mapNotNull null
                InsightSection(sec.str("title", "name") ?: return@mapNotNull null, sec.strings("items", "points"))
            }.filter { it.items.isNotEmpty() },
            type = type,
            generatedAt = clock.now(),
        )
    }

    suspend fun gaps(meeting: Meeting, fromNotes: Boolean = false): GapAnalysis {
        requireTranscript(meeting)
        val obj = meetingJson(meeting, Prompts.GAPS_TASK, insightsSource(meeting, fromNotes))
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
        onText: ((String) -> Unit)? = null,
    ): String {
        requireTranscript(meeting)
        val messages = history.filterNot { it.isError }.takeLast(20).map {
            LlmMessage(if (it.role == ChatRole.USER) LlmRole.USER else LlmRole.ASSISTANT, it.content)
        } + LlmMessage(LlmRole.USER, if (excerpt != null) Prompts.excerptQuestion(meeting, excerpt, question) else question)
        val prefix = shared(meeting)
        var system = prefix + "\n" + Prompts.ANSWER_TASK
        if (spoken) system += "\n" + Prompts.SPOKEN_ANSWER_RULE
        val request = LlmRequest(system = system, messages = messages, cachePrefix = prefix)
        val chat = chat(meeting, "Ask")
        return (if (onText == null) chat.complete(request) else chat.stream(request, onText)).trim()
    }

    /** A natural, speakable recap of the meeting for an audio briefing. */
    suspend fun briefingScript(meeting: Meeting): String {
        requireTranscript(meeting)
        val prefix = shared(meeting)
        return chat(meeting, "Audio briefing").complete(
            LlmRequest(
                system = prefix + "\n" + Prompts.BRIEFING_TASK,
                messages = listOf(LlmMessage(LlmRole.USER, "Write the briefing now.")),
                cachePrefix = prefix,
            ),
        ).trim()
    }

    suspend fun suggestNames(meeting: Meeting): List<NameSuggestion> {
        val placeholders = meeting.speakers.filter { it.isPlaceholder }
        if (placeholders.isEmpty() || meeting.segments.isEmpty()) return emptyList()
        val obj = meetingJson(meeting, Prompts.namesTask(placeholders.map { it.name }))
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
     * Misheard glossary terms in the transcript, as (exact text in the transcript, correction) pairs.
     * Empty when the glossary is empty.
     */
    suspend fun glossaryFixes(meeting: Meeting): List<Pair<String, String>> {
        if (Prompts.glossaryTerms(settings().glossary).isEmpty() || meeting.segments.isEmpty()) return emptyList()
        val obj = meetingJson(meeting, Prompts.GLOSSARY_TASK)
        return obj.list("fixes", "corrections").mapNotNull { el ->
            val f = el as? JsonObject ?: return@mapNotNull null
            val from = f.str("from", "wrong")?.trim()?.takeIf { it.length >= 2 } ?: return@mapNotNull null
            val to = f.str("to", "right", "correct")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            if (from.equals(to, ignoreCase = false)) null else from to to
        }
    }

    /** Outcome of [separateSpeakers]: the relabelled transcript, and how many parts the AI could not do. */
    class Separation(val result: SpeakerAssigner.Result, val parts: Int, val failedParts: Int, val error: String?)

    /**
     * Uses the LLM to split an unlabelled transcript into speaker turns. Returns new speakers and
     * segments; existing named (non-placeholder) speakers are kept. Long transcripts go in parts;
     * a part that fails twice, or takes longer than [partTimeoutMs], keeps its lines unassigned
     * instead of holding up or losing the whole meeting.
     */
    suspend fun separateSpeakers(
        meeting: Meeting,
        windowChars: Int = 6_000,
        partTimeoutMs: Long = SEPARATION_PART_TIMEOUT_MS,
        onPart: suspend (part: Int, parts: Int) -> Unit = { _, _ -> },
    ): Separation {
        requireTranscript(meeting)
        val keptSpeakers = meeting.speakers.filter { !it.isPlaceholder && meeting.segments.any { s -> s.speakerId == it.id } }
        val speakers = keptSpeakers.toMutableList()
        val out = mutableListOf<TranscriptSegment>()
        val labelToSpeaker = mutableMapOf<String, Speaker>()
        val parts = windows(meeting.segments, windowChars)
        var failed = 0
        var lastError: String? = null

        for ((partIndex, window) in parts.withIndex()) {
            onPart(partIndex, parts.size)
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
            // The compact reply needs far less than this, even with some thinking; the cap stops a
            // model that starts repeating itself from running for many minutes.
            val outputLimit = SEPARATION_OUTPUT_LIMIT
            var obj: JsonObject? = null
            for (attempt in 1..2) {
                try {
                    obj = withTimeoutOrNull(partTimeoutMs) { json(Prompts.SEPARATE_SPEAKERS_SYSTEM, user, outputLimit, meeting, "Speaker separation") }
                    if (obj != null) break
                    // A part that timed out is not retried: it would most likely time out again.
                    lastError = "the AI took longer than ${partTimeoutMs / 60_000} minutes"
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message ?: e.javaClass.simpleName
                }
            }
            if (obj == null) failed++
            val turns = obj?.list("turns", "assignments").orEmpty().mapNotNull { el ->
                val t = el as? JsonObject ?: return@mapNotNull null
                val index = (t["i"] as? JsonPrimitive)?.intOrNull ?: (t["index"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                    ?: return@mapNotNull null
                // "from" marks where a new speaker starts inside a segment; older replies sent the full "text".
                Turn(index, t.str("s", "speaker") ?: SpeakerAssigner.UNKNOWN_LABEL, t.str("from", "text"))
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
        onPart(parts.size, parts.size)
        if (failed == parts.size) throw AiException("The AI could not separate the speakers: ${lastError ?: "no reason given"}")
        val used = out.mapNotNull { it.speakerId }.toSet()
        return Separation(
            SpeakerAssigner.Result(speakers.filter { it.id in used }, out),
            parts = parts.size,
            failedParts = failed,
            error = lastError,
        )
    }

    private data class Turn(val index: Int, val label: String, val from: String?)

    /**
     * Splits [seg] where the model says another speaker starts ("from" = their first words). Cut
     * points that can't be found in the text are ignored, so the transcript text is never changed.
     */
    private fun splitSegment(seg: TranscriptSegment, turns: List<Turn>): List<Pair<String, TranscriptSegment>> {
        if (turns.isEmpty()) return listOf(SpeakerAssigner.UNKNOWN_LABEL to seg)
        val text = seg.text
        // (start offset in text, speaker label), in order.
        val cuts = mutableListOf(0 to turns.first().label)
        for (turn in turns.drop(1)) {
            val from = turn.from?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val at = findFrom(text, from, after = cuts.last().first + 1) ?: continue
            if (turn.label == cuts.last().second) continue
            cuts += at to turn.label
        }
        if (cuts.size == 1) return listOf(cuts.first().second to seg)

        val total = text.length.coerceAtLeast(1)
        val span = (seg.endMs - seg.startMs).coerceAtLeast(0)
        return cuts.mapIndexed { i, (start, label) ->
            val end = if (i == cuts.lastIndex) text.length else cuts[i + 1].first
            val startMs = seg.startMs + span * start / total
            val endMs = if (i == cuts.lastIndex) seg.endMs else seg.startMs + span * end / total
            label to seg.copy(id = Ids.new(), startMs = startMs, endMs = endMs, text = text.substring(start, end).trim(), words = null)
        }.filter { it.second.text.isNotEmpty() }
    }

    /** Index in [text] where the words of [from] start (case and punctuation insensitive), at or after [after]. */
    private fun findFrom(text: String, from: String, after: Int): Int? {
        val direct = text.indexOf(from, after, ignoreCase = true)
        if (direct >= 0) return direct
        val words = from.split(Regex("\\s+")).map { w -> w.trim { !it.isLetterOrDigit() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val pattern = words.joinToString("[^\\p{L}\\p{N}]+") { Regex.escape(it) }
        return Regex(pattern, RegexOption.IGNORE_CASE).find(text, after.coerceAtMost(text.length))?.range?.first
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
        return chat(null, "Notes").complete(
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

/** How long one part of AI speaker separation may take before it is skipped. */
internal const val SEPARATION_PART_TIMEOUT_MS = 5 * 60_000L

/** Bookmarks this soon after a stretch of transcript still refer to it. */
internal const val BOOKMARK_GRACE_MS = 30_000L
private const val SEPARATION_OUTPUT_LIMIT = 16_000
