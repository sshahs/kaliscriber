package com.kaliscriber.core.pipeline

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiServices
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.stt.SpeakerDiarizer
import com.kaliscriber.core.ai.stt.VoiceEmbedder
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Briefing
import com.kaliscriber.core.model.ChatMessage
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.speakers.VoiceLibrary
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
    private val voices: VoiceLibrary? = null,
    private val embedder: () -> VoiceEmbedder? = { null },
) {

    /** Transcribes one live chunk and appends it. Returns false (and queues the chunk) on failure. */
    suspend fun transcribeChunk(meetingId: String, chunk: AudioChunk): Boolean = try {
        val result = transcribeFile(File(chunk.path), chunk.endMs - chunk.startMs, repo.get(meetingId)?.expectedSpeakers)
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

    private suspend fun transcribeFile(file: File, durationMs: Long?, speakers: Int? = null): SttResult {
        val language = settings().transcriptionLanguage.takeIf { it.isNotBlank() }
        return ai.stt().transcribe(file, language, durationMs, speakers)
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
            val result = transcribeFile(audioFile, durationMs, repo.get(meetingId)?.expectedSpeakers)
            replaceTranscript(meetingId, result, durationMs)
            if (!result.diarized) applyLocalDiarization(meetingId, audioFile)
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
        // On-device engines label the live transcript instead of transcribing everything again.
        if (stt is SpeakerDiarizer) return applyLocalDiarization(meetingId, audioFile)
        val size = audioFile.length()
        if (!stt.supportsDiarization || size <= 0 || size > stt.maxUploadBytes) return false
        return try {
            val result = transcribeFile(audioFile, durationMs, repo.get(meetingId)?.expectedSpeakers)
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

    /** Runs the on-device diarizer over [audioFile] and relabels the transcript. True if it ran. */
    private suspend fun applyLocalDiarization(meetingId: String, audioFile: File): Boolean {
        val diarizer = runCatching { ai.stt() }.getOrNull() as? SpeakerDiarizer ?: return false
        if (!settings().localDiarization || !diarizer.diarizationAvailable) return false
        return try {
            val turns = diarizer.diarize(audioFile, repo.get(meetingId)?.expectedSpeakers)
            if (turns.isEmpty()) return false
            repo.update(meetingId) { m ->
                val result = SpeakerAssigner.applyTurns(m.segments, turns, random)
                m.copy(speakers = result.speakers, segments = result.segments, speakersFinal = true)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.update(meetingId) { it.copy(lastError = "On-device speaker detection failed: ${e.message}") }
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
        if (s.rememberVoices) step(errors, "Voice recognition") { recognizeVoices(meetingId) }
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

    /**
     * Computes a voiceprint for every speaker and names those whose voice matches someone the user
     * named in an earlier meeting. Unmatched speakers keep their placeholder names.
     */
    suspend fun recognizeVoices(meetingId: String) {
        val library = voices ?: return
        val voiceprints = computeVoiceprints(meetingId) ?: return
        val matches = library.assign(voiceprints)
        repo.update(meetingId) { m ->
            m.copy(speakers = m.speakers.map { sp ->
                val print = voiceprints[sp.id]
                val match = matches[sp.id]
                when {
                    print == null -> sp
                    match != null && sp.isPlaceholder && m.speakers.none { it.id != sp.id && it.name.equals(match.profile.name, true) } ->
                        sp.copy(
                            name = match.profile.name,
                            isPlaceholder = false,
                            voiceProfileId = match.profile.id,
                            embedding = print.toList(),
                            suggestedName = null,
                            suggestionReason = null,
                        )
                    else -> sp.copy(embedding = print.toList())
                }
            })
        }
    }

    /** Voiceprints for the meeting's speakers from their longest stretches of speech, or null if unavailable. */
    private suspend fun computeVoiceprints(meetingId: String, onlySpeaker: String? = null): Map<String, FloatArray>? {
        val engine = embedder() ?: return null
        if (!engine.voiceEmbeddingAvailable) return null
        val meeting = repo.get(meetingId) ?: return null
        val audio = meeting.audioPath?.let(::File)?.takeIf { it.exists() } ?: return null
        val spans = meeting.segments
            .filter { it.speakerId != null && (onlySpeaker == null || it.speakerId == onlySpeaker) }
            .filter { it.endMs - it.startMs >= MIN_VOICEPRINT_SEGMENT_MS }
            .groupBy { it.speakerId!! }
            .mapValues { (_, segs) ->
                var total = 0L
                segs.sortedByDescending { it.endMs - it.startMs }
                    .takeWhile { seg -> (total < MAX_VOICEPRINT_MS).also { total += seg.endMs - seg.startMs } }
                    .sortedBy { it.startMs }
                    .map { it.startMs..it.endMs }
            }
            .filterValues { it.isNotEmpty() }
        if (spans.isEmpty()) return emptyMap()
        return engine.embed(audio, spans)
    }

    /**
     * Renames a speaker everywhere in the meeting. With voice memory on, the speaker's voice is saved
     * under that name so they are recognised automatically in future meetings.
     */
    suspend fun renameSpeaker(meetingId: String, speakerId: String, name: String) {
        repo.renameSpeaker(meetingId, speakerId, name)
        val library = voices ?: return
        if (!settings().rememberVoices || name.isBlank()) return
        try {
            val meeting = repo.get(meetingId) ?: return
            // A rename to an existing name merges speakers, so look the speaker up by name.
            val speaker = meeting.speakers.firstOrNull { it.name.equals(name.trim(), true) } ?: return
            val print = speaker.embedding?.toFloatArray()
                ?: computeVoiceprints(meetingId, onlySpeaker = speaker.id)?.get(speaker.id)
                ?: return
            val profile = library.enroll(speaker.name, print)
            repo.update(meetingId) { m ->
                m.copy(speakers = m.speakers.map {
                    if (it.id == speaker.id) it.copy(voiceProfileId = profile.id, embedding = print.toList()) else it
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rename itself succeeded; remembering the voice is best effort.
            repo.update(meetingId) { it.copy(lastError = "Could not save ${name.trim()}'s voice: ${e.message}") }
        }
    }

    /** Sets how many people are in the meeting (null = detect automatically). */
    suspend fun setExpectedSpeakers(meetingId: String, count: Int?) {
        repo.update(meetingId) { it.copy(expectedSpeakers = count?.takeIf { n -> n > 0 }) }
    }

    /**
     * Detects speakers again, e.g. after the expected number of speakers changed: on-device
     * diarization when available, else a diarizing transcription service, else the AI model.
     * Then recognises saved voices and suggests names.
     */
    suspend fun redetectSpeakers(meetingId: String) {
        val meeting = repo.get(meetingId) ?: return
        if (meeting.segments.isEmpty()) throw AiException("There is no transcript yet.")
        val audio = meeting.audioPath?.let(::File)?.takeIf { it.exists() }
        repo.update(meetingId) { it.copy(status = MeetingStatus.ANALYZING, lastError = null) }
        try {
            val done = audio != null && tryFullDiarization(meetingId, audio, meeting.durationMs)
            if (!done) separateSpeakers(meetingId)
            if (settings().rememberVoices) recognizeVoices(meetingId)
            if (settings().suggestSpeakerNames) runCatching { suggestSpeakerNames(meetingId) }
        } finally {
            repo.update(meetingId) { it.copy(status = MeetingStatus.READY) }
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

    /** Writes a spoken recap script for the meeting and stores it (audio is rendered by the app). */
    suspend fun generateBriefingScript(meetingId: String): String {
        val meeting = repo.get(meetingId) ?: throw AiException("Meeting not found")
        val script = meetingAi.briefingScript(meeting)
        repo.update(meetingId) { it.copy(briefing = Briefing(script = script, createdAt = clock.now())) }
        return script
    }

    suspend fun setBriefingAudio(meetingId: String, path: String?) {
        repo.update(meetingId) { m -> m.copy(briefing = m.briefing?.copy(audioPath = path)) }
    }

    /**
     * Asks the AI about the meeting (optionally about a selected excerpt) and records the exchange.
     * [spoken] asks for a short answer without Markdown, for voice conversations.
     */
    suspend fun ask(meetingId: String, question: String, excerpt: ExcerptRef? = null, spoken: Boolean = false): Result<String> {
        val before = repo.get(meetingId) ?: return Result.failure(IllegalArgumentException("Meeting not found"))
        val userMessage = ChatMessage(Ids.new(), ChatRole.USER, question.trim(), clock.now(), excerpt)
        repo.update(meetingId) { it.copy(chat = it.chat + userMessage) }
        return try {
            val answer = meetingAi.answer(before, before.chat, question.trim(), excerpt, spoken)
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

    private companion object {
        const val MIN_VOICEPRINT_SEGMENT_MS = 1_000L
        const val MAX_VOICEPRINT_MS = 45_000L
    }

    private suspend fun deleteQuietly(path: String) = withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
}
