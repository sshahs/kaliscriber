package com.kaliscriber.core.pipeline

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AiServices
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.ai.Prompts
import com.kaliscriber.core.ai.stt.DiarizedBlock
import com.kaliscriber.core.ai.stt.SpeakerDiarizer
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.calendar.MeetingPrep
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.ai.stt.VoiceEmbedder
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Briefing
import com.kaliscriber.core.model.ChatMessage
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingSection
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.TimelineEntry
import com.kaliscriber.core.speakers.BlockMerge
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.speakers.StableSpeakers
import com.kaliscriber.core.speakers.VoiceLibrary
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.tasks.ActionItems
import com.kaliscriber.core.transcript.Glossary
import com.kaliscriber.core.transcript.TranscriptFiles
import com.kaliscriber.core.transcript.TranscriptFormatter
import com.kaliscriber.core.util.Clock
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    /**
     * Runs work in the background while a meeting is being recorded: speaker detection on each
     * ten-minute block and running notes. Without it, all of that happens after the meeting ends.
     */
    private val liveScope: CoroutineScope? = null,
) {

    /** Work in progress for a meeting that is still being recorded. */
    private class LiveSession(val firstChunk: Int, val detectsSpeakers: Boolean) {
        val lock = Mutex()
        /** Recorded chunks not yet diarized. */
        val waiting = mutableListOf<AudioChunk>()
        /** Every chunk seen, so their audio files can be removed when the meeting ends. */
        val chunks = mutableListOf<AudioChunk>()
        val blocks = mutableListOf<DiarizedBlock>()
        /** Keeps each speaker's identity as blocks are added and merged again. */
        val stable = StableSpeakers()
        var diarizing: Job? = null
        var notes: Job? = null
        @Volatile var diarizationFailed = false
    }

    private val live = ConcurrentHashMap<String, LiveSession>()

    /** Per meeting: parts transcribed while recording, the time that took and the audio length (ms). */
    private val chunkStats = ConcurrentHashMap<String, LongArray>()

    /** The on-device diarizer when speakers should be detected during recording, else null. */
    private fun liveDiarizer(): SpeakerDiarizer? {
        val s = settings()
        if (liveScope == null || !s.localDiarization || !s.finalDiarizationPass) return null
        return (runCatching { ai.stt() }.getOrNull() as? SpeakerDiarizer)?.takeIf { it.diarizationAvailable }
    }

    /** Called for each recorded chunk, in order, once its transcription was attempted. */
    private fun onLiveChunk(meetingId: String, chunk: AudioChunk) {
        val scope = liveScope ?: return
        val session = live.getOrPut(meetingId) { LiveSession(chunk.index, detectsSpeakers = liveDiarizer() != null) }
        synchronized(session) {
            session.chunks += chunk
            if (session.detectsSpeakers && !session.diarizationFailed) {
                session.waiting += chunk
                if (session.diarizing?.isActive != true) session.diarizing = scope.launch { diarizeLiveBlocks(meetingId, session, final = false) }
            }
            if (settings().liveNotes && session.notes?.isActive != true) session.notes = scope.launch { writeSectionNotes(meetingId) }
        }
    }

    /** Diarizes waiting chunks in blocks of about [LIVE_BLOCK_MS]; [final] also takes a shorter last block. */
    private suspend fun diarizeLiveBlocks(meetingId: String, session: LiveSession, final: Boolean) = session.lock.withLock {
        val diarizer = liveDiarizer() ?: run {
            session.diarizationFailed = true
            return@withLock
        }
        while (!session.diarizationFailed) {
            val block = synchronized(session) {
                val waitingMs = session.waiting.sumOf { it.endMs - it.startMs }
                if (session.waiting.isEmpty() || (!final && waitingMs < LIVE_BLOCK_MS)) return@synchronized null
                // Blocks of at most LIVE_BLOCK_MS keep memory use bounded.
                var total = 0L
                val take = session.waiting.takeWhile { c -> (total < LIVE_BLOCK_MS).also { total += c.endMs - c.startMs } }
                repeat(take.size) { session.waiting.removeAt(0) }
                take
            } ?: break
            val started = clock.now()
            try {
                val result = diarizer.diarizeBlock(block.map { File(it.path) }, block.first().startMs)
                if (result == null) {
                    session.diarizationFailed = true
                } else {
                    session.blocks += result
                    addToTimeline(
                        meetingId,
                        "Speakers in ${TranscriptFormatter.timestamp(block.first().startMs)}–${TranscriptFormatter.timestamp(block.last().endMs)}" +
                            if (final) "" else " (while recording)",
                        started,
                    )
                    log(
                        "Live speaker detection: ${TranscriptFormatter.timestamp(block.first().startMs)}–" +
                            "${TranscriptFormatter.timestamp(block.last().endMs)} in ${(clock.now() - started) / 1000}s, ${result.voices.size} voices",
                    )
                    // The transcript so far shows who is speaking while the meeting goes on; the end labels it all.
                    if (!final) {
                        try {
                            labelLiveSpeakers(meetingId, session, untilMs = block.last().endMs)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            log("Labelling speakers during the meeting failed: ${describe(e)}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                session.diarizationFailed = true
                log("Live speaker detection failed, will detect after the meeting: ${describe(e)}")
            }
        }
    }

    /**
     * Labels the transcript up to [untilMs] with the speakers found in the blocks diarized so far.
     * Speakers keep their names from one update to the next, including names given during the
     * meeting, and voices saved in voice memory are named straight away. False if there were no turns.
     */
    private suspend fun labelLiveSpeakers(meetingId: String, session: LiveSession, untilMs: Long, final: Boolean = false): Boolean {
        val merged = session.stable.stabilize(BlockMerge.mergeWithVoices(session.blocks, repo.get(meetingId)?.expectedSpeakers))
        if (merged.turns.isEmpty()) return false
        val prints = merged.voices.mapValues { it.value.embedding }
        val matches = voices?.assign(prints).orEmpty()
        repo.update(meetingId) { m ->
            val result = SpeakerAssigner.relabel(m.speakers, m.segments, merged.turns, SpeakerAssigner.LIVE_PREFIX, untilMs, random)
            val speakers = result.speakers.map { sp ->
                val key = sp.label?.takeIf { it.startsWith(SpeakerAssigner.LIVE_PREFIX) }?.removePrefix(SpeakerAssigner.LIVE_PREFIX)
                val print = key?.let(prints::get) ?: return@map sp
                val match = matches[key]
                if (match != null && sp.isPlaceholder && result.speakers.none { it.id != sp.id && it.name.equals(match.profile.name, true) }) {
                    sp.copy(
                        name = match.profile.name,
                        isPlaceholder = false,
                        voiceProfileId = match.profile.id,
                        embedding = print.toList(),
                        voiceModel = voices?.model,
                    )
                } else {
                    sp.copy(embedding = print.toList(), voiceModel = voices?.model)
                }
            }
            m.copy(speakers = speakers, segments = result.segments, speakersFinal = m.speakersFinal || final)
        }
        return true
    }

    /**
     * Ends live work for a meeting: diarizes the last block and labels the whole transcript with the
     * speakers found during the meeting. False when live detection did not cover the whole meeting,
     * so the full recording is used instead.
     */
    private suspend fun finishLive(meetingId: String): Boolean {
        val session = live.remove(meetingId) ?: return false
        session.notes?.cancelAndJoin()
        try {
            if (session.firstChunk != 0 || session.diarizationFailed || liveDiarizer() == null) return false
            stage(meetingId, "Detecting speakers in the last part of the meeting") {
                session.diarizing?.join()
                diarizeLiveBlocks(meetingId, session, final = true)
            }
            if (session.diarizationFailed || session.blocks.isEmpty()) return false
            return labelLiveSpeakers(meetingId, session, untilMs = Long.MAX_VALUE, final = true)
        } finally {
            val pending = repo.get(meetingId)?.pendingChunks.orEmpty().map { it.path }.toSet()
            session.chunks.filter { it.path !in pending }.forEach { deleteQuietly(it.path) }
        }
    }

    /** Writes notes for each stretch of about [LIVE_NOTES_MS] of transcript while recording. */
    private suspend fun writeSectionNotes(meetingId: String) {
        while (true) {
            val m = repo.get(meetingId) ?: return
            if (m.status != MeetingStatus.RECORDING || !settings().liveNotes) return
            val from = m.sections.lastOrNull()?.endMs ?: 0L
            val rest = m.segments.filter { it.startMs >= from }
            if (rest.isEmpty() || rest.last().endMs - from < LIVE_NOTES_MS) return
            val part = rest.takeWhile { it.startMs < from + LIVE_NOTES_MS }.ifEmpty { return }
            val started = clock.now()
            val notes = try {
                meetingAi.sectionNotes(m, part)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Running notes failed (the summary will use the transcript): ${describe(e)}")
                return
            }
            log("Running notes for ${TranscriptFormatter.timestamp(from)}–${TranscriptFormatter.timestamp(part.last().endMs)} in ${(clock.now() - started) / 1000}s")
            addToTimeline(meetingId, "Notes for ${TranscriptFormatter.timestamp(from)}–${TranscriptFormatter.timestamp(part.last().endMs)} (while recording)", started)
            repo.update(meetingId) { it.copy(sections = it.sections + MeetingSection(from, part.last().endMs, notes)) }
        }
    }

    /** Shows what processing is doing on the meeting, and logs how long each step takes. */
    private suspend fun <T> stage(meetingId: String, label: String, block: suspend () -> T): T {
        repo.update(meetingId) { it.copy(processingStep = label, processingProgress = null) }
        return timed(meetingId, label, block)
    }

    /** Runs [block] and notes how long it took in the log and on the meeting's timeline. */
    private suspend fun <T> timed(meetingId: String, label: String, block: suspend () -> T): T {
        val started = clock.now()
        log("$label: started")
        try {
            return block().also {
                log("$label: done in ${(clock.now() - started) / 1000}s")
                addToTimeline(meetingId, label, started)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("$label: failed after ${(clock.now() - started) / 1000}s: ${describe(e)}")
            addToTimeline(meetingId, label, started, ok = false)
            throw e
        }
    }

    /** Adds a step that began at [started] (and ends now) to the meeting's timeline. */
    private suspend fun addToTimeline(meetingId: String, label: String, started: Long, ok: Boolean = true) {
        val entry = TimelineEntry(label, started, (clock.now() - started).coerceAtLeast(0), ok)
        repo.update(meetingId) { it.copy(timeline = (it.timeline + entry).takeLast(MAX_TIMELINE)) }
    }

    private suspend fun progress(meetingId: String, fraction: Float) {
        repo.update(meetingId) { it.copy(processingProgress = fraction.coerceIn(0f, 1f)) }
    }

    /**
     * Transcribes one chunk and appends it. Returns false (and queues the chunk) on failure. [recording]
     * is true for chunks arriving live, which also feed speaker detection and notes during the meeting.
     */
    suspend fun transcribeChunk(meetingId: String, chunk: AudioChunk, recording: Boolean = true): Boolean {
        val ok = try {
            val started = clock.now()
            val result = transcribeFile(File(chunk.path), chunk.endMs - chunk.startMs, repo.get(meetingId)?.expectedSpeakers)
            log("Chunk ${chunk.index} (${(chunk.endMs - chunk.startMs) / 1000}s audio): ${result.segments.size} segments in ${clock.now() - started} ms")
            appendChunk(meetingId, chunk, result)
            if (recording) {
                val stats = chunkStats.getOrPut(meetingId) { LongArray(3) }
                synchronized(stats) {
                    stats[0]++
                    stats[1] += clock.now() - started
                    stats[2] += chunk.endMs - chunk.startMs
                }
            }
            // Live speaker detection still needs the audio; it removes the file when the meeting ends.
            val neededLive = live[meetingId]?.let { it.detectsSpeakers && !it.diarizationFailed } ?: (recording && liveDiarizer() != null)
            if (!neededLive) deleteQuietly(chunk.path)
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
        if (recording) onLiveChunk(meetingId, chunk)
        return ok
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
            transcribeChunk(meetingId, chunk, recording = false)
        }
    }

    /** Called once recording has stopped and [audioFile] (the full recording) is finalized. */
    suspend fun finishRecording(meetingId: String, audioFile: File?, durationMs: Long) {
        chunkStats.remove(meetingId)?.let { (parts, tookMs, audioMs) ->
            val first = clock.now() - durationMs
            addToTimeline(meetingId, "Transcribed $parts parts while recording (${audioMs / 60_000} min of audio in ${tookMs / 1000}s)", first)
        }
        addToTimeline(meetingId, "Recording stopped", clock.now())
        repo.update(meetingId) {
            it.copy(
                status = MeetingStatus.TRANSCRIBING,
                audioPath = audioFile?.takeIf { f -> f.exists() && f.length() > 0 }?.absolutePath ?: it.audioPath,
                durationMs = maxOf(it.durationMs, durationMs),
                lastError = null,
            )
        }
        // On-device engines label the live transcript, so parts that failed to transcribe are retried first.
        val onDevice = runCatching { ai.stt() }.getOrNull() is SpeakerDiarizer
        if (onDevice) retryPendingChunks(meetingId)
        val outcome = when {
            finishLive(meetingId) -> Detection.DONE
            audioFile != null && settings().finalDiarizationPass -> tryFullDiarization(meetingId, audioFile, durationMs)
            else -> Detection.SKIPPED
        }
        if (!onDevice) {
            if (outcome == Detection.DONE) {
                // The whole recording was transcribed again, so the failed parts are covered.
                repo.get(meetingId)?.pendingChunks?.forEach { deleteQuietly(it.path) }
                repo.update(meetingId) { it.copy(pendingChunks = emptyList()) }
            } else {
                retryPendingChunks(meetingId)
            }
        }
        // When on-device detection ran and failed, say why rather than fall back to the much slower AI.
        analyze(meetingId, aiSeparation = outcome != Detection.FAILED, fromNotes = true)
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
            val result = stage(meetingId, "Transcribing with ${stt.config.id.displayName}") {
                transcribeFile(audioFile, durationMs, repo.get(meetingId)?.expectedSpeakers)
            }
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

    /**
     * Makes [meetingId] from a transcript another app wrote ([TranscriptFiles]): its lines, with the
     * speakers it names, then the usual AI steps. Without speaker names the AI separates speakers.
     */
    suspend fun importTranscript(meetingId: String, parsed: TranscriptFiles.Parsed) {
        val started = clock.now()
        val named = parsed.lines.any { it.speakerLabel != null }
        replaceTranscript(meetingId, SttResult(parsed.lines, diarized = named), parsed.lines.maxOfOrNull { it.endMs })
        repo.update(meetingId) { m ->
            m.copy(
                // Names from the file are real; "Speaker 1" and the like keep a placeholder for the AI to name.
                speakers = m.speakers.map { s ->
                    val label = s.label?.takeIf { it != SpeakerAssigner.UNKNOWN_LABEL && TranscriptFiles.isRealName(it) }
                    if (label != null) s.copy(name = label, isPlaceholder = false) else s
                },
                lastError = null,
            )
        }
        addToTimeline(meetingId, "Transcript imported", started)
        analyze(meetingId, aiSeparation = !named)
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
    suspend fun analyze(meetingId: String, aiSeparation: Boolean = true, fromNotes: Boolean = false) {
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
            if (Prompts.glossaryTerms(s.glossary).isNotEmpty()) add("Glossary fixes" to suspend { applyGlossary(meetingId) })
            if (s.autoAnalyze) {
                add("Summary" to suspend { generateInsights(meetingId, fromNotes) })
                add("Gaps & questions" to suspend { generateGaps(meetingId, fromNotes) })
            }
        }
        if (aiSteps.isNotEmpty()) {
            stage(meetingId, "Writing summary, action items and questions") {
                coroutineScope {
                    aiSteps.map { (name, block) -> async { step(errors, name) { timed(meetingId, name) { block() } } } }.awaitAll()
                }
            }
        }
        addToTimeline(meetingId, if (errors.isEmpty()) "Ready" else "Ready, with problems", clock.now(), ok = errors.isEmpty())
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
                            voiceModel = library.model,
                            suggestedName = null,
                            suggestionReason = null,
                        )
                    else -> sp.copy(embedding = print.toList(), voiceModel = library.model)
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
            // A voiceprint from an earlier speaker model can't be compared with the saved ones.
            val print = speaker.embedding?.takeIf { speaker.voiceModel == library.model }?.toFloatArray()
                ?: computeVoiceprints(meetingId, onlySpeaker = speaker.id)?.get(speaker.id)
                ?: return
            val profile = library.enroll(speaker.name, print)
            repo.update(meetingId) { m ->
                m.copy(speakers = m.speakers.map {
                    if (it.id == speaker.id) it.copy(voiceProfileId = profile.id, embedding = print.toList(), voiceModel = library.model) else it
                })
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The rename itself succeeded; remembering the voice is best effort.
            repo.update(meetingId) { it.copy(lastError = "Could not save ${name.trim()}'s voice: ${describe(e)}") }
        }
    }

    /**
     * Saved voices from an earlier speaker model can't be compared with new voiceprints. Learns them
     * again from the most recent meetings where those people spoke, while the recordings are still
     * on the phone. Returns how many voices were learned again.
     */
    suspend fun relearnVoices(): Int {
        val library = voices ?: return 0
        if (library.outdated.isEmpty() || embedder()?.voiceEmbeddingAvailable != true) return 0
        val withAudio = repo.all()
            .filter { m -> m.audioPath?.let { File(it).exists() } == true }
            .sortedByDescending { it.createdAt }
        var learned = 0
        for (profile in library.outdated) {
            val prints = mutableListOf<FloatArray>()
            for (m in withAudio) {
                if (prints.size >= RELEARN_MEETINGS) break
                val speaker = m.speakers.firstOrNull {
                    it.voiceProfileId == profile.id || (!it.isPlaceholder && it.name.equals(profile.name, ignoreCase = true))
                } ?: continue
                val print = try {
                    computeVoiceprints(m.id, onlySpeaker = speaker.id)?.get(speaker.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("Couldn't learn ${profile.name}'s voice from ${m.title}: ${describe(e)}")
                    null
                } ?: continue
                prints += print
                repo.update(m.id) { mm ->
                    mm.copy(speakers = mm.speakers.map {
                        if (it.id == speaker.id) it.copy(embedding = print.toList(), voiceModel = library.model) else it
                    })
                }
            }
            if (prints.isNotEmpty()) {
                library.relearn(profile.id, prints)
                learned++
            }
            log("Voice memory: ${profile.name} ${if (prints.isEmpty()) "has no recordings left to learn from" else "learned again from ${prints.size} meeting(s)"}")
        }
        return learned
    }

    /**
     * Links a meeting to the calendar event it was recorded for: the event's title becomes the
     * meeting's, the invitees help name speakers, and their number is the expected head count
     * unless one was already chosen.
     */
    suspend fun attachCalendarEvent(meetingId: String, event: CalendarEvent) {
        repo.update(meetingId) { m ->
            m.copy(
                calendarEvent = event,
                title = if (m.autoTitle) event.title else m.title,
                autoTitle = false,
                expectedSpeakers = m.expectedSpeakers ?: MeetingPrep.expectedSpeakers(event),
            )
        }
        log("Calendar: recording \"${event.title}\" with ${event.attendees.size} invitee(s)")
    }

    /** Sets the meeting's summary format (null guesses it from the title). */
    suspend fun setMeetingType(meetingId: String, type: MeetingType?) {
        repo.update(meetingId) { it.copy(type = type) }
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

    /** [fromNotes] lets the summary start from the notes written during the meeting, which is faster. */
    suspend fun generateInsights(meetingId: String, fromNotes: Boolean = false) {
        val meeting = repo.get(meetingId) ?: return
        val fresh = meetingAi.insights(meeting, fromNotes)
        repo.update(meetingId) {
            // Keep ticked-off and edited action items when the summary is regenerated.
            val insights = fresh.copy(actionItems = ActionItems.carryOver(it.insights?.actionItems.orEmpty(), fresh.actionItems))
            it.copy(
                insights = insights,
                title = if (it.autoTitle && insights.title.isNotBlank()) insights.title else it.title,
            )
        }
    }

    suspend fun generateGaps(meetingId: String, fromNotes: Boolean = false) {
        val meeting = repo.get(meetingId) ?: return
        val gaps = meetingAi.gaps(meeting, fromNotes)
        repo.update(meetingId) { it.copy(gaps = gaps) }
    }

    /**
     * Corrects misheard glossary terms (names, products, jargon) throughout the transcript. Returns
     * how many lines changed.
     */
    suspend fun applyGlossary(meetingId: String): Int {
        val meeting = repo.get(meetingId) ?: return 0
        val fixes = meetingAi.glossaryFixes(meeting)
        if (fixes.isEmpty()) return 0
        var changed = 0
        repo.update(meetingId) { m ->
            m.copy(segments = m.segments.map { seg ->
                val fixed = Glossary.apply(seg.text, fixes)
                if (fixed == seg.text) seg else seg.copy(text = fixed, words = null).also { changed++ }
            })
        }
        log("Glossary fixed $changed line(s)")
        return changed
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
    /** Asks about a meeting; [onText] gets the answer written so far while it arrives. */
    suspend fun ask(
        meetingId: String,
        question: String,
        excerpt: ExcerptRef? = null,
        spoken: Boolean = false,
        onText: ((String) -> Unit)? = null,
    ): Result<String> {
        val before = repo.get(meetingId) ?: return Result.failure(IllegalArgumentException("Meeting not found"))
        val userMessage = ChatMessage(Ids.new(), ChatRole.USER, question.trim(), clock.now(), excerpt)
        repo.update(meetingId) { it.copy(chat = it.chat + userMessage) }
        var partial = ""
        val track: ((String) -> Unit)? = onText?.let { forward -> { text -> partial = text; forward(text) } }
        return try {
            val answer = meetingAi.answer(before, before.chat, question.trim(), excerpt, spoken, track)
            repo.update(meetingId) { it.copy(chat = it.chat + ChatMessage(Ids.new(), ChatRole.ASSISTANT, answer, clock.now())) }
            Result.success(answer)
        } catch (e: CancellationException) {
            // Stopped while the answer was being written: keep what had arrived.
            if (partial.isNotBlank()) {
                withContext(NonCancellable) {
                    repo.update(meetingId) {
                        it.copy(chat = it.chat + ChatMessage(Ids.new(), ChatRole.ASSISTANT, partial.trim(), clock.now(), stopped = true))
                    }
                }
            }
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
        /** Length of the blocks diarized during recording. */
        const val LIVE_BLOCK_MS = 10 * 60_000L
        /** Length of the stretches summarised during recording. */
        const val LIVE_NOTES_MS = 10 * 60_000L

        const val MIN_VOICEPRINT_SEGMENT_MS = 1_000L
        /** Meetings a saved voice is learned again from when the speaker model changes. */
        const val RELEARN_MEETINGS = 3
        /** Timeline entries kept per meeting; reprocessing adds more. */
        const val MAX_TIMELINE = 80
        const val MAX_VOICEPRINT_MS = 45_000L
    }

    private suspend fun deleteQuietly(path: String) = withContext(Dispatchers.IO) { runCatching { File(path).delete() } }
}
