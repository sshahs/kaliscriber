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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
    /** Receives a line per processing step with its duration, for the platform log. */
    private val log: (String) -> Unit = {},
    /**
     * Fetches the on-device speaker detection models when they are missing, reporting progress.
     * Throws if that fails.
     */
    private val fetchDiarizationModels: suspend (onProgress: suspend (Float) -> Unit) -> Unit = {},
) {

    /** Shows what processing is doing on the meeting, and logs how long each step takes. */
    private suspend fun <T> stage(meetingId: String, label: String, block: suspend () -> T): T {
        repo.update(meetingId) { it.copy(processingStep = label, processingProgress = null) }
        val started = clock.now()
        log("$label: started")
        try {
            return block().also { log("$label: done in ${(clock.now() - started) / 1000}s") }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$label: failed after ${(clock.now() - started) / 1000}s: ${describe(e)}")
            throw e
        }
    }

    private suspend fun progress(meetingId: String, fraction: Float) {
        repo.update(meetingId) { it.copy(processingProgress = fraction.coerceIn(0f, 1f)) }
    }

    /** Transcribes one live chunk and appends it. Returns false (and queues the chunk) on failure. */
    suspend fun transcribeChunk(meetingId: String, chunk: AudioChunk): Boolean = try {
        val started = clock.now()
        val result = transcribeFile(File(chunk.path), chunk.endMs - chunk.startMs, repo.get(meetingId)?.expectedSpeakers)
        log("Chunk ${chunk.index} (${(chunk.endMs - chunk.startMs) / 1000}s audio): ${result.segments.size} segments in ${clock.now() - started} ms")
        appendChunk(meetingId, chunk, result)
        deleteQuietly(chunk.path)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        repo.update(meetingId) { m ->
            m.copy(
                pendingChunks = (m.pendingChunks.filter { it.index != chunk.index } + chunk).sortedBy { it.index },
                lastError = describe(e),
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

    /** Shows a processing step on the meeting from outside the pipeline (e.g. the recorder). */
    suspend fun noteStep(meetingId: String, label: String, fraction: Float? = null) {
        log(label)
        repo.update(meetingId) { it.copy(processingStep = label, processingProgress = fraction) }
    }

    suspend fun retryPendingChunks(meetingId: String) {
        val pending = repo.get(meetingId)?.pendingChunks.orEmpty()
        if (pending.isNotEmpty()) noteStep(meetingId, "Transcribing ${pending.size} remaining part(s)")
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
        val outcome = if (audioFile != null && settings().finalDiarizationPass) tryFullDiarization(meetingId, audioFile, durationMs) else Detection.SKIPPED
        if (outcome == Detection.DONE) {
            repo.get(meetingId)?.pendingChunks?.forEach { deleteQuietly(it.path) }
            repo.update(meetingId) { it.copy(pendingChunks = emptyList()) }
        } else {
            retryPendingChunks(meetingId)
        }
        // When on-device detection ran and failed, say why rather than fall back to the much slower AI.
        analyze(meetingId, aiSeparation = outcome != Detection.FAILED)
    }

    /** Transcribes an audio file the user imported. */
    suspend fun processImportedAudio(meetingId: String, audioFile: File, durationMs: Long?) {
        repo.update(meetingId) {
            it.copy(status = MeetingStatus.TRANSCRIBING, audioPath = audioFile.absolutePath, lastError = null)
        }
        var detection = Detection.SKIPPED
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
            if (!result.diarized) detection = applyLocalDiarization(meetingId, audioFile)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.update(meetingId) {
                it.copy(status = MeetingStatus.ERROR, lastError = describe(e), processingStep = null, processingProgress = null)
            }
            return
        }
        analyze(meetingId, aiSeparation = detection != Detection.FAILED)
    }

    /** How a speaker detection pass went. */
    private enum class Detection { DONE, SKIPPED, FAILED }

    private suspend fun tryFullDiarization(meetingId: String, audioFile: File, durationMs: Long): Detection {
        val stt = try {
            ai.stt()
        } catch (e: Exception) {
            return Detection.SKIPPED
        }
        // On-device engines label the live transcript instead of transcribing everything again.
        if (stt is SpeakerDiarizer) return applyLocalDiarization(meetingId, audioFile)
        val size = audioFile.length()
        if (!stt.supportsDiarization || size <= 0 || size > stt.maxUploadBytes) return Detection.SKIPPED
        return try {
            val result = stage(meetingId, "Detecting speakers with ${stt.config.id.displayName}") {
                transcribeFile(audioFile, durationMs, repo.get(meetingId)?.expectedSpeakers)
            }
            if (result.segments.isEmpty()) return Detection.SKIPPED
            replaceTranscript(meetingId, result, durationMs)
            Detection.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the live transcript; the AI can still separate speakers from it.
            repo.update(meetingId) { it.copy(lastError = "Full-meeting speaker detection failed: ${describe(e)}") }
            Detection.SKIPPED
        }
    }

    /** Runs the on-device diarizer over [audioFile] and relabels the transcript. */
    private suspend fun applyLocalDiarization(meetingId: String, audioFile: File): Detection {
        val diarizer = runCatching { ai.stt() }.getOrNull() as? SpeakerDiarizer ?: return Detection.SKIPPED
        if (!settings().localDiarization) return Detection.SKIPPED
        if (!diarizer.diarizationAvailable) {
            try {
                var last = -1f
                stage(meetingId, "Downloading speaker detection") {
                    fetchDiarizationModels { fraction ->
                        if (fraction - last >= 0.02f || fraction >= 1f) {
                            last = fraction
                            progress(meetingId, fraction)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                repo.update(meetingId) { it.copy(lastError = "Couldn't download speaker detection: ${describe(e)}") }
                return Detection.FAILED
            }
            if (!diarizer.diarizationAvailable) return Detection.SKIPPED
        }
        return try {
            val long = (repo.get(meetingId)?.durationMs ?: 0) > 10 * 60_000
            val label = if (long) "Detecting speakers on this phone (long meetings take several minutes)" else "Detecting speakers on this phone"
            val turns = stage(meetingId, label) {
                coroutineScope {
                    var last = 0f
                    diarizer.diarize(audioFile, repo.get(meetingId)?.expectedSpeakers) { fraction ->
                        // Throttle: the diarizer reports per chunk.
                        if (fraction - last >= 0.02f || fraction >= 1f) {
                            last = fraction
                            launch { progress(meetingId, fraction) }
                        }
                    }
                }
            }
            if (turns.isEmpty()) {
                repo.update(meetingId) { it.copy(lastError = "Speaker detection on this phone found no voices to tell apart in the recording.") }
                return Detection.FAILED
            }
            repo.update(meetingId) { m ->
                val result = SpeakerAssigner.applyTurns(m.segments, turns, random)
                m.copy(speakers = result.speakers, segments = result.segments, speakersFinal = true)
            }
            Detection.DONE
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e !is AiException) log("On-device speaker detection failed: ${e.stackTraceToString().take(4_000)}")
            repo.update(meetingId) { it.copy(lastError = "Speaker detection on this phone failed: ${describe(e)}") }
            Detection.FAILED
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

    /**
     * Speaker separation, naming and insight generation according to settings. [aiSeparation] false
     * skips separating speakers with the AI (because on-device detection already failed and said why).
     */
    suspend fun analyze(meetingId: String, aiSeparation: Boolean = true) {
        val s = settings()
        val meeting = repo.get(meetingId) ?: return
        if (meeting.segments.isEmpty()) {
            repo.update(meetingId) {
                it.copy(
                    status = if (it.pendingChunks.isEmpty()) MeetingStatus.READY else MeetingStatus.ERROR,
                    lastError = it.lastError ?: "No speech was transcribed.",
                    processingStep = null,
                    processingProgress = null,
                )
            }
            return
        }
        val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
        repo.update(meetingId) { it.copy(status = MeetingStatus.ANALYZING) }

        if (aiSeparation && s.llmSpeakerSeparation && needsSpeakerSeparation(meeting)) {
            step(errors, "Speaker separation") {
                stage(meetingId, "Separating speakers with AI") { separateSpeakers(meetingId) }?.let { errors += it }
            }
        }
        if (s.rememberVoices) {
            step(errors, "Voice recognition") { stage(meetingId, "Recognising saved voices") { recognizeVoices(meetingId) } }
        }
        // Names, summary and gaps only read the transcript, so they run side by side.
        val aiSteps = buildList {
            if (s.suggestSpeakerNames) add("Speaker names" to suspend { suggestSpeakerNames(meetingId) })
            if (s.autoAnalyze) {
                add("Summary" to suspend { generateInsights(meetingId) })
                add("Gaps & questions" to suspend { generateGaps(meetingId) })
            }
        }
        if (aiSteps.isNotEmpty()) {
            stage(meetingId, "Writing summary, action items and questions") {
                coroutineScope {
                    aiSteps.map { (name, block) -> async { step(errors, name) { timed(name) { block() } } } }.awaitAll()
                }
            }
        }
        repo.update(meetingId) {
            it.copy(
                status = MeetingStatus.READY,
                processingStep = null,
                processingProgress = null,
                lastError = errors.joinToString("\n").ifEmpty { null }
                    ?: it.lastError?.takeIf { _ -> it.pendingChunks.isNotEmpty() || !aiSeparation },
            )
        }
    }

    /** True when no speaker was ever told apart by the transcription service. */
    fun needsSpeakerSeparation(meeting: Meeting): Boolean =
        !meeting.speakersFinal && meeting.segments.isNotEmpty() &&
            meeting.segments.all { seg -> meeting.speaker(seg.speakerId)?.label == SpeakerAssigner.UNKNOWN_LABEL }

    private suspend fun <T> timed(name: String, block: suspend () -> T): T {
        val started = clock.now()
        return block().also { log("$name: done in ${(clock.now() - started) / 1000}s") }
    }

    private suspend fun step(errors: MutableList<String>, name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errors += "$name: ${describe(e)}"
            // Unexpected (non-network, non-provider) failures get a stack trace in the log.
            if (e !is AiException) log("$name failed: ${e.stackTraceToString().take(4_000)}")
        }
    }

    /** A readable reason for [e]; never blank. */
    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.cause?.message?.takeIf { it.isNotBlank() }
            ?: e.javaClass.simpleName.ifBlank { "Unknown error" }

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
            repo.update(meetingId) { it.copy(lastError = "Could not save ${name.trim()}'s voice: ${describe(e)}") }
        }
    }

    /** Sets how many people are in the meeting (null = detect automatically). */
    suspend fun setExpectedSpeakers(meetingId: String, count: Int?) {
        repo.update(meetingId) { it.copy(expectedSpeakers = count?.takeIf { n -> n > 0 }) }
    }

    /**
     * Detects speakers again, e.g. after the expected number of speakers changed: on-device
     * diarization when available, else a diarizing transcription service, else the AI model.
     * Then recognises saved voices and suggests names. [aiOnly] goes straight to the AI model.
     */
    suspend fun redetectSpeakers(meetingId: String, aiOnly: Boolean = false) {
        val meeting = repo.get(meetingId) ?: return
        if (meeting.segments.isEmpty()) throw AiException("There is no transcript yet.")
        val audio = meeting.audioPath?.let(::File)?.takeIf { it.exists() }
        val onDevice = runCatching { ai.stt() }.getOrNull() as? SpeakerDiarizer
        repo.update(meetingId) { it.copy(status = MeetingStatus.ANALYZING, lastError = null) }
        try {
            val outcome = if (!aiOnly && audio != null) tryFullDiarization(meetingId, audio, meeting.durationMs) else Detection.SKIPPED
            // On-device detection ran and failed: report it instead of starting the much slower AI pass.
            if (outcome == Detection.FAILED) {
                throw AiException(
                    (repo.get(meetingId)?.lastError ?: "Speaker detection on this phone failed.") +
                        " You can also try \"Re-detect speakers with AI\" from the menu.",
                )
            }
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            if (outcome != Detection.DONE) {
                try {
                    stage(meetingId, "Separating speakers with AI") { separateSpeakers(meetingId) }?.let { errors += it }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val hint = if (onDevice != null && (!onDevice.diarizationAvailable || !settings().localDiarization)) {
                        " Tip: download the speaker detection pack (Settings → On-device speech) to detect speakers on the phone, without the internet."
                    } else {
                        ""
                    }
                    throw AiException("Couldn't re-detect speakers: ${describe(e)}$hint", cause = e)
                }
            }
            if (settings().rememberVoices) {
                step(errors, "Voice recognition") { stage(meetingId, "Recognising saved voices") { recognizeVoices(meetingId) } }
            }
            if (settings().suggestSpeakerNames) step(errors, "Speaker names") { suggestSpeakerNames(meetingId) }
            if (errors.isNotEmpty()) repo.update(meetingId) { it.copy(lastError = errors.joinToString("\n")) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.update(meetingId) { it.copy(lastError = describe(e)) }
            throw e
        } finally {
            repo.update(meetingId) { it.copy(status = MeetingStatus.READY, processingStep = null, processingProgress = null) }
        }
    }

    /** Separates speakers with the AI model. Returns a note when some parts of the meeting could not be done. */
    suspend fun separateSpeakers(meetingId: String): String? {
        val meeting = repo.get(meetingId) ?: return null
        val separation = meetingAi.separateSpeakers(meeting) { part, parts ->
            val label = if (part < parts) "Separating speakers with AI (part ${part + 1} of $parts)" else "Separating speakers with AI"
            noteStep(meetingId, label, part.toFloat() / parts)
        }
        val result = separation.result
        if (result.segments.isEmpty()) return null
        repo.update(meetingId) { it.copy(speakers = result.speakers, segments = result.segments, speakersFinal = true) }
        return if (separation.failedParts > 0) {
            "The AI couldn't separate speakers in ${separation.failedParts} of ${separation.parts} parts of the meeting " +
                "(${separation.error ?: "no reason given"}); those lines stay unassigned."
        } else {
            null
        }
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
            val message = describe(e)
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
