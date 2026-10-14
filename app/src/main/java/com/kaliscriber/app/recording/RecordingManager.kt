package com.kaliscriber.app.recording

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.model.AudioChunk
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
) {
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val lock = Mutex()
    private var pendingMeetingId: String? = null
    private var engine: AudioCaptureEngine? = null
    private var audioFile: File? = null
    private var chunks: Channel<AudioChunk>? = null
    private var consumer: Job? = null
    private val queued = AtomicInteger(0)

    fun isActive(meetingId: String): Boolean = when (val s = _state.value) {
        is RecordingState.Recording -> s.meetingId == meetingId
        is RecordingState.Finishing -> s.meetingId == meetingId
        RecordingState.Idle -> pendingMeetingId == meetingId
    }

    /** Creates the meeting and starts the foreground service, which then calls [beginCapture]. */
    suspend fun start(title: String?): String = lock.withLock {
        check(_state.value == RecordingState.Idle) { "A meeting is already being recorded" }
        val meeting = meetings.create(title)
        pendingMeetingId = meeting.id
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
        queued.set(0)

        val captureEngine = AudioCaptureEngine(
            outputFile = file,
            chunkDir = File(dir, "chunks"),
            chunkMillis = settings().chunkSeconds.coerceIn(10, 300) * 1000L,
            listener = object : AudioCaptureEngine.Listener {
                override fun onChunk(chunk: AudioChunk) {
                    queued.incrementAndGet()
                    channel.trySend(chunk)
                    updateRecording { it.copy(queuedChunks = queued.get()) }
                }

                override fun onProgress(capturedMs: Long, level: Float) {
                    updateRecording { it.copy(elapsedMs = capturedMs, level = level) }
                }

                override fun onError(error: Throwable) {
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
                updateRecording { it.copy(queuedChunks = queued.get()) }
            }
        }
        _state.value = RecordingState.Recording(meetingId)
        return true
    }

    fun setPaused(paused: Boolean) {
        engine?.paused = paused
        updateRecording { it.copy(paused = paused, level = 0f) }
    }

    fun stop() {
        scope.launch { stopAndProcess() }
    }

    private class Stopped(val meetingId: String, val durationMs: Long, val file: File?)

    private suspend fun stopAndProcess() {
        val stopped = lock.withLock {
            val current = _state.value as? RecordingState.Recording ?: return@withLock null
            _state.value = RecordingState.Finishing(current.meetingId)
            val captureEngine = engine
            engine = null
            val durationMs = withContext(Dispatchers.IO) { captureEngine?.stop() ?: current.elapsedMs }
            val file = audioFile?.takeIf { it.exists() && it.length() > 0 }
            audioFile = null
            Stopped(current.meetingId, durationMs, file)
        } ?: return
        try {
            // Let live transcription of the queued chunks finish before the final pass.
            // Recording has stopped, so stop showing the meeting as recording.
            meetings.update(stopped.meetingId) { it.copy(status = MeetingStatus.TRANSCRIBING) }
            if (queued.get() > 0) pipeline.noteStep(stopped.meetingId, "Transcribing the last ${queued.get()} part(s) of the meeting")
            chunks?.close()
            consumer?.join()
            chunks = null
            consumer = null
            pipeline.finishRecording(stopped.meetingId, stopped.file, stopped.durationMs)
        } finally {
            _state.value = RecordingState.Idle
        }
    }

    private inline fun updateRecording(transform: (RecordingState.Recording) -> RecordingState.Recording) {
        _state.update { s -> if (s is RecordingState.Recording) transform(s) else s }
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
