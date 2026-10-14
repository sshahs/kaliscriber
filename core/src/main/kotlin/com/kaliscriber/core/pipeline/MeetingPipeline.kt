package com.kaliscriber.core.pipeline

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiServices
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.ChatMessage
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.random.Random

/**
 * Orchestrates a meeting from audio to insights:
 *
 * 1. While recording, each audio chunk is transcribed as soon as it is captured ([transcribeChunk]).
 *    Speakers the service cannot tell apart get random placeholder names.
 * 2. When recording stops ([finishRecording]) the whole file is re-transcribed with diarization if the
 *    selected service supports it, giving consistent speakers across the meeting. Failed chunks are retried.
 * 3. [analyze] then separates speakers with the LLM when no diarization was available, suggests real
 *    names for placeholder speakers, and generates the summary, insights, gaps and questions.
 */
class MeetingPipeline(
    private val repo: MeetingRepository,
    private val ai: AiServices,
    private val meetingAi: MeetingAi,
    private val settings: () -> AppSettings,
    private val clock: Clock = Clock.System,
    private val random: Random = Random.Default,
) {

    /** Transcribes one live chunk and appends it. Returns false (and queues the chunk) on failure. */
    suspend fun transcribeChunk(meetingId: String, chunk: AudioChunk): Boolean = try {
        val result = transcribeFile(File(chunk.path), chunk.endMs - chunk.startMs)
        appendChunk(meetingId, chunk, result)
        deleteQuietly(chunk.path)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        repo.update(meetingId) { m ->
            m.copy(
                pendingChunks = (m.pendingChunks.filter { it.index != chunk.index } + chunk).sortedBy { it.index },
                lastError = e.message ?: "Transcription failed",
                durationMs = maxOf(m.durationMs, chunk.endMs),
            )
        }
        false
    }

    private suspend fun appendChunk(meetingId: String, chunk: AudioChunk, result: SttResult) {
        repo.update(meetingId) { m ->
            val assigned = SpeakerAssigner.assign(m.speakers, result.segments, offsetMs = chunk.startMs, random = random)
            m.copy(
                speakers = assigned.speakers,
                segments = (m.segments + assigned.segments).sortedBy { it.startMs },
                pendingChunks = m.pendingChunks.filter { it.index != chunk.index },
                durationMs = maxOf(m.durationMs, chunk.endMs),
            )
        }
    }

    private suspend fun transcribeFile(file: File, durationMs: Long?): SttResult {
        val language = settings().transcriptionLanguage.takeIf { it.isNotBlank() }
        return ai.stt().transcribe(file, language, durationMs)
    }

    suspend fun retryPendingChunks(meetingId: String) {
        val pending = repo.get(meetingId)?.pendingChunks.orEmpty()
        for (chunk in pending) {
            if (!File(chunk.path).exists()) {
                repo.update(meetingId) { m -> m.copy(pendingChunks = m.pendingChunks.filter { it.index != chunk.index }) }
                continue
            }
            transcribeChunk(meetingId, chunk)
        }
    }

    /** Called once recording has stopped and [audioFile] (the full recording) is finalized. */
    suspend fun finishRecording(meetingId: String, audioFile: File?, durationMs: Long) {
        repo.update(meetingId) {
            it.copy(
                status = MeetingStatus.TRANSCRIBING,
                audioPath = audioFile?.takeIf { f -> f.exists() && f.length() > 0 }?.absolutePath ?: it.audioPath,
                durationMs = maxOf(it.durationMs, durationMs),
                lastError = null,
            )
        }
        val fullPassDone = audioFile != null && settings().finalDiarizationPass && tryFullDiarization(meetingId, audioFile, durationMs)
        if (fullPassDone) {
            repo.get(meetingId)?.pendingChunks?.forEach { deleteQuietly(it.path) }
            repo.update(meetingId) { it.copy(pendingChunks = emptyList()) }
        } else {
            retryPendingChunks(meetingId)
        }
        analyze(meetingId)
    }

    /** Transcribes an audio file the user imported. */
    suspend fun processImportedAudio(meetingId: String, audioFile: File, durationMs: Long?) {
        repo.update(meetingId) {
            it.copy(status = MeetingStatus.TRANSCRIBING, audioPath = audioFile.absolutePath, lastError = null)
        }
        try {
            val stt = ai.stt()
            if (audioFile.length() > stt.maxUploadBytes) {
                throw AiException(
                    "This file is ${audioFile.length() / (1024 * 1024)} MB, over the ${stt.maxUploadBytes / (1024 * 1024)} MB " +
                        "limit of ${stt.config.id.displayName}. Choose Deepgram or AssemblyAI for long recordings.",
                )
            }
            val result = transcribeFile(audioFile, durationMs)
            replaceTranscript(meetingId, result, durationMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.update(meetingId) { it.copy(status = MeetingStatus.ERROR, lastError = e.message ?: "Transcription failed") }
            return
        }
        analyze(meetingId)
    }

    private suspend fun tryFullDiarization(meetingId: String, audioFile: File, durationMs: Long): Boolean {
        val stt = try {
            ai.stt()
        } catch (e: Exception) {
            return false
        }
        val size = audioFile.length()
        if (!stt.supportsDiarization || size <= 0 || size > stt.maxUploadBytes) return false
        return try {
            val result = transcribeFile(audioFile, durationMs)
            if (result.segments.isEmpty()) return false
            replaceTranscript(meetingId, result, durationMs)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the live transcript; just note why the better pass did not happen.
            repo.update(meetingId) { it.copy(lastError = "Full-meeting speaker detection failed: ${e.message}") }
            false
        }
    }

    private suspend fun replaceTranscript(meetingId: String, result: SttResult, durationMs: Long?) {
        repo.update(meetingId) { m ->
            // Keep speakers the user already named if their provider label shows up again.
            val named = m.speakers.filter { !it.isPlaceholder && it.label != null && result.segments.any { s -> s.speakerLabel == it.label } }
            val assigned = SpeakerAssigner.assign(named, result.segments, random = random)
            val used = assigned.segments.mapNotNull { it.speakerId }.toSet()
            m.copy(
                speakers = assigned.speakers.filter { it.id in used },
                segments = assigned.segments.sortedBy { it.startMs },
                speakersFinal = result.diarized,
                durationMs = maxOf(m.durationMs, durationMs ?: 0, assigned.segments.maxOfOrNull { it.endMs } ?: 0),
            )
        }
    }

    /** Speaker separation, naming and insight generation according to settings. */
    suspend fun analyze(meetingId: String) {
        val s = settings()
        val meeting = repo.get(meetingId) ?: return
        if (meeting.segments.isEmpty()) {
            repo.update(meetingId) {
                it.copy(
                    status = if (it.pendingChunks.isEmpty()) MeetingStatus.READY else MeetingStatus.ERROR,
                    lastError = it.lastError ?: "No speech was transcribed.",
                )
            }
            return
        }
        val errors = mutableListOf<String>()
        repo.update(meetingId) { it.copy(status = MeetingStatus.ANALYZING) }

        if (s.llmSpeakerSeparation && needsSpeakerSeparation(meeting)) {
            step(errors, "Speaker separation") { separateSpeakers(meetingId) }
        }
        if (s.suggestSpeakerNames) step(errors, "Speaker names") { suggestSpeakerNames(meetingId) }
        if (s.autoAnalyze) {
            step(errors, "Summary") { generateInsights(meetingId) }
            step(errors, "Gaps & questions") { generateGaps(meetingId) }
        }
        repo.update(meetingId) {
            it.copy(
                status = MeetingStatus.READY,
                lastError = errors.joinToString("\n").ifEmpty { null } ?: it.lastError?.takeIf { _ -> it.pendingChunks.isNotEmpty() },
            )
        }
    }

    /** True when no speaker was ever told apart by the transcription service. */
    fun needsSpeakerSeparation(meeting: Meeting): Boolean =
        !meeting.speakersFinal && meeting.segments.isNotEmpty() &&
            meeting.segments.all { seg -> meeting.speaker(seg.speakerId)?.label == SpeakerAssigner.UNKNOWN_LABEL }

    private suspend fun step(errors: MutableList<String>, name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "$name: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    suspend fun separateSpeakers(meetingId: String) {
        val meeting = repo.get(meetingId) ?: return
        val result = meetingAi.separateSpeakers(meeting)
        if (result.segments.isEmpty()) return
        repo.update(meetingId) { it.copy(speakers = result.speakers, segments = result.segments, speakersFinal = true) }
    }

    suspend fun suggestSpeakerNames(meetingId: String) {
        val meeting = repo.get(meetingId) ?: return
        val suggestions = meetingAi.suggestNames(meeting).filter { it.confidence >= 0.5 }.associateBy { it.speakerId }
        if (suggestions.isEmpty()) return
        repo.update(meetingId) { m ->
            m.copy(speakers = m.speakers.map { sp ->
                val suggestion = suggestions[sp.id]
                if (suggestion != null && sp.isPlaceholder) sp.copy(suggestedName = suggestion.name, suggestionReason = suggestion.reason) else sp
            })
        }
    }

    suspend fun generateInsights(meetingId: String) {
        val meeting = repo.get(meetingId) ?: return
        val insights = meetingAi.insights(meeting)
        repo.update(meetingId) {
            it.copy(
                insights = insights,
                title = if (it.autoTitle && insights.title.isNotBlank()) insights.title else it.title,
            )
        }
    }

    suspend fun generateGaps(meetingId: String) {
        val meeting = repo.get(meetingId) ?: return
        val gaps = meetingAi.gaps(meeting)
        repo.update(meetingId) { it.copy(gaps = gaps) }
    }

    /** Re-runs the summary using the user's answers to follow-up questions. */
    suspend fun refreshWithAnswers(meetingId: String) {
        generateInsights(meetingId)
        generateGaps(meetingId)
    }

    /** Asks the AI about the meeting (optionally about a selected excerpt) and records the exchange. */
    suspend fun ask(meetingId: String, question: String, excerpt: ExcerptRef? = null): Result<String> {
        val before = repo.get(meetingId) ?: return Result.failure(IllegalArgumentException("Meeting not found"))
        val userMessage = ChatMessage(Ids.new(), ChatRole.USER, question.trim(), clock.now(), excerpt)
        repo.update(meetingId) { it.copy(chat = it.chat + userMessage) }
        return try {
            val answer = meetingAi.answer(before, before.chat, question.trim(), excerpt)
            repo.update(meetingId) { it.copy(chat = it.chat + ChatMessage(Ids.new(), ChatRole.ASSISTANT, answer, clock.now())) }
            Result.success(answer)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val message = e.message ?: "Something went wrong"
            repo.update(meetingId) {
                it.copy(chat = it.chat + ChatMessage(Ids.new(), ChatRole.ASSISTANT, message, clock.now(), isError = true))
            }
            Result.failure(e)
        }
    }

    private suspend fun deleteQuietly(path: String) = withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
}
