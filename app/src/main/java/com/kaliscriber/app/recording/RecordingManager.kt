package com.kaliscriber.app.recording

import com.kaliscriber.app.diagnostics.AppLog
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Bookmark
import com.kaliscriber.core.model.Interruption
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.store.MeetingRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

sealed interface RecordingState {
    data object Idle : RecordingState

    data class Recording(
        val meetingId: String,
        val elapsedMs: Long = 0,
        val paused: Boolean = false,
        val level: Float = 0f,
        /** Chunks captured but not yet transcribed. */
        val queuedChunks: Int = 0,
        /** Which microphone is in use, when the user chose one. */
        val micNote: String? = null,
        /** What has taken the microphone (e.g. "a phone call") while nothing is being recorded. */
        val interruption: String? = null,
    ) : RecordingState

    /** Capture stopped; the final transcription and AI analysis are running. */
    data class Finishing(val meetingId: String) : RecordingState
}

/**
 * Owns the microphone session for a meeting. Lives for the whole process so recording continues
 * when the UI goes away; [RecordingService] keeps the process in the foreground meanwhile.
 */
class RecordingManager(
    private val context: Context,
    private val meetings: MeetingRepository,
    private val pipeline: MeetingPipeline,
    private val settings: () -> AppSettings,
    private val scope: CoroutineScope,
    /** Called with the new meeting's id when recording starts. */
    private val onStart: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lock = Mutex()
    private var pendingMeetingId: String? = null
    private var engine: AudioCaptureEngine? = null
    private var audioFile: File? = null
    private var chunks: Channel<AudioChunk>? = null
    private var consumer: Job? = null
    /** Parts of the current recording waiting to be transcribed; each recording has its own. */
    private var queued = AtomicInteger(0)

    fun isActive(meetingId: String): Boolean = when (val s = _state.value) {
        is RecordingState.Recording -> s.meetingId == meetingId
        is RecordingState.Finishing -> s.meetingId == meetingId
        RecordingState.Idle -> pendingMeetingId == meetingId
    }

    /** True while [meetingId] is being recorded or its recording is about to begin. */
    fun isCapturing(meetingId: String): Boolean =
        pendingMeetingId == meetingId || (_state.value as? RecordingState.Recording)?.meetingId == meetingId

    /** Meetings whose recording stopped and that are still being processed, oldest first. */
    private val finishing = mutableListOf<String>()

    /**
     * Creates the meeting and starts the foreground service, which then calls [beginCapture]. A new
     * meeting can start while the previous one is still being processed (back-to-back meetings).
     */
    suspend fun start(title: String?): String = lock.withLock {
        check(_state.value !is RecordingState.Recording && pendingMeetingId == null) { "A meeting is already being recorded" }
        val meeting = meetings.create(title)
        pendingMeetingId = meeting.id
        AppLog.i(TAG, "Recording ${meeting.id}: starting")
        onStart(meeting.id)
        ContextCompat.startForegroundService(
            context,
            Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_START),
        )
        meeting.id
    }

    /** Called by [RecordingService] once it is in the foreground. Returns false if capture failed. */
    fun beginCapture(): Boolean {
        val meetingId = pendingMeetingId ?: return _state.value is RecordingState.Recording
        pendingMeetingId = null
        val dir = meetings.audioDir(meetingId)
        val file = File(dir, "recording.m4a")
        val channel = Channel<AudioChunk>(Channel.UNLIMITED)
        val queued = AtomicInteger(0).also { this.queued = it }

        val captureEngine = AudioCaptureEngine(
            context = context,
            cleanup = settings().audioCleanup,
            microphone = settings().microphone,
            outputFile = file,
            chunkDir = File(dir, "chunks"),
            chunkMillis = settings().chunkSeconds.coerceIn(10, 300) * 1000L,
            listener = object : AudioCaptureEngine.Listener {
                override fun onChunk(chunk: AudioChunk) {
                    queued.incrementAndGet()
                    channel.trySend(chunk)
                    updateRecording(meetingId) { it.copy(queuedChunks = queued.get()) }
                }

                override fun onProgress(capturedMs: Long, level: Float) {
                    updateRecording(meetingId) { it.copy(elapsedMs = capturedMs, level = level) }
                }

                override fun onInterruption(reason: String?) {
                    AppLog.i(TAG, "Recording $meetingId: " + (reason?.let { "microphone taken by $it" } ?: "microphone back"))
                    updateRecording(meetingId) { it.copy(interruption = reason) }
                }

                override fun onGap(gap: Interruption) {
                    scope.launch { meetings.update(meetingId) { it.copy(interruptions = it.interruptions + gap) } }
                }

                override fun onError(error: Throwable) {
                    AppLog.e(TAG, "Recording $meetingId: capture failed", error)
                    scope.launch {
                        meetings.update(meetingId) { it.copy(lastError = "Recording stopped: ${error.message}") }
                        stop()
                    }
                }
            },
        )
        try {
            captureEngine.start()
        } catch (e: Exception) {
            AppLog.w(TAG, "Recording $meetingId: the microphone couldn't start", e)
            channel.close()
            scope.launch {
                meetings.update(meetingId) { it.copy(status = MeetingStatus.ERROR, lastError = e.message ?: "Could not record") }
            }
            _state.value = RecordingState.Idle
            return false
        }
        engine = captureEngine
        audioFile = file
        chunks = channel
        consumer = scope.launch {
            for (chunk in channel) {
                pipeline.transcribeChunk(meetingId, chunk)
                queued.decrementAndGet()
                updateRecording(meetingId) { it.copy(queuedChunks = queued.get()) }
            }
        }
        _state.value = RecordingState.Recording(meetingId, micNote = captureEngine.micNote)
        AppLog.i(TAG, "Recording $meetingId: capturing (mic: ${captureEngine.micNote ?: settings().microphone.label}, chunks of ${settings().chunkSeconds}s)")
        return true
    }

    fun setPaused(paused: Boolean) {
        engine?.paused = paused
        updateRecording { it.copy(paused = paused, level = 0f) }
    }

    fun stop() {
        scope.launch { stopAndProcess() }
    }

    /** Marks the current moment of the meeting being recorded as important. Null when not recording. */
    suspend fun bookmark(note: String = ""): Bookmark? {
        val current = _state.value as? RecordingState.Recording ?: return null
        if (current.meetingId.isEmpty()) return null
        return meetings.addBookmark(current.meetingId, current.elapsedMs, note)
    }

    private class Stopped(
        val meetingId: String,
        val durationMs: Long,
        val file: File?,
        val chunks: Channel<AudioChunk>?,
        val consumer: Job?,
        val queued: AtomicInteger,
    )

    private suspend fun stopAndProcess() {
        val stopped = lock.withLock {
            val current = _state.value as? RecordingState.Recording ?: return@withLock null
            _state.value = RecordingState.Finishing(current.meetingId)
            synchronized(finishing) { finishing += current.meetingId }
            val captureEngine = engine
            engine = null
            val durationMs = withContext(Dispatchers.IO) { captureEngine?.stop() ?: current.elapsedMs }
            val file = audioFile?.takeIf { it.exists() && it.length() > 0 }
            audioFile = null
            // Handed over to the processing below, so a new recording can start with its own.
            AppLog.i(TAG, "Recording ${current.meetingId}: stopped after ${durationMs / 1000}s, recording file ${file?.length()?.let { "${it / 1024} KB" } ?: "missing"}, ${queued.get()} part(s) still to transcribe")
            Stopped(current.meetingId, durationMs, file, chunks, consumer, queued).also {
                chunks = null
                consumer = null
            }
        } ?: return
        try {
            // Let live transcription of the queued chunks finish before the final pass.
            // Recording has stopped, so stop showing the meeting as recording.
            meetings.update(stopped.meetingId) { it.copy(status = MeetingStatus.TRANSCRIBING) }
            val left = stopped.queued.get()
            if (left > 0) pipeline.noteStep(stopped.meetingId, "Transcribing the last $left part(s) of the meeting")
            stopped.chunks?.close()
            stopped.consumer?.join()
            pipeline.finishRecording(stopped.meetingId, stopped.file, stopped.durationMs)
        } finally {
            // Another meeting may be recording (or still finishing) by now; only this one's state ends here.
            synchronized(finishing) {
                finishing.remove(stopped.meetingId)
                if (_state.value == RecordingState.Finishing(stopped.meetingId)) {
                    _state.value = finishing.lastOrNull()?.let { RecordingState.Finishing(it) } ?: RecordingState.Idle
                }
            }
        }
    }

    /** Changes the recording state, only for [meetingId] when given (a finished meeting's late updates don't count). */
    private inline fun updateRecording(meetingId: String? = null, transform: (RecordingState.Recording) -> RecordingState.Recording) {
        _state.update { s -> if (s is RecordingState.Recording && (meetingId == null || s.meetingId == meetingId)) transform(s) else s }
    }

    companion object {
        fun chunkFileName(index: Int, startMs: Long, endMs: Long) =
            String.format(Locale.ROOT, "chunk_%05d_%d_%d.wav", index, startMs, endMs)

        private val CHUNK_NAME = Regex("""chunk_(\d+)_(\d+)_(\d+)\.wav""")

        /** Recovers chunk timing from its file name after the process was killed mid-meeting. */
        fun parseChunk(file: File): AudioChunk? {
            val match = CHUNK_NAME.matchEntire(file.name) ?: return null
            val (index, start, end) = match.destructured
            return AudioChunk(index.toInt(), file.absolutePath, start.toLong(), end.toLong())
        }
    }
}

private const val TAG = "Recording"
