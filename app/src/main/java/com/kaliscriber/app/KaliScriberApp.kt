package com.kaliscriber.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.kaliscriber.app.data.SecretStore
import com.kaliscriber.app.export.MeetingExporter
import com.kaliscriber.app.recording.RecordingManager
import com.kaliscriber.app.speech.LocalSpeechEngine
import com.kaliscriber.app.speech.ModelManager
import com.kaliscriber.app.speech.SpeechOutput
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.DefaultAiServices
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.defaultHttpClient
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.speakers.VoiceLibrary
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

class KaliScriberApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RECORDING_CHANNEL, getString(R.string.recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val RECORDING_CHANNEL = "recording"
    }
}

/** Manual dependency container; one per process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val filesDir: File = appContext.filesDir

    val settingsStore = JsonValueStore(File(filesDir, "settings.json"), AppSettings.serializer(), AppSettings())
    val settings: () -> AppSettings = { settingsStore.value.value }

    val secrets = SecretStore(appContext)
    val http = defaultHttpClient()

    /** On-device speech models and the sherpa-onnx engine that uses them. */
    val models = ModelManager(appContext, http, scope)
    val localSpeech = LocalSpeechEngine(models, settings)

    val ai = DefaultAiServices(settings, secrets, http, localStt = { localSpeech })
    val meetingAi = MeetingAi(ai, settings)
    val speech = SpeechOutput(appContext, ai, settings)

    val meetings = MeetingRepository(
        JsonDocumentStore(File(filesDir, "meetings"), Meeting.serializer()) { it.id },
        audioRoot = File(filesDir, "audio"),
    )
    val notes = NoteRepository(JsonDocumentStore(File(filesDir, "notes"), Note.serializer()) { it.id })
    /** Remembered voices (on-device voiceprints) for recognising people across meetings. */
    val voices = VoiceLibrary(
        JsonValueStore(File(filesDir, "voices.json"), ListSerializer(VoiceProfile.serializer()), emptyList()),
    )
    val pipeline = MeetingPipeline(
        meetings, ai, meetingAi, settings,
        voices = voices,
        embedder = { localSpeech },
        log = { message -> Log.i("KaliScriber", message) },
    )
    val exporter = MeetingExporter(appContext, http, secrets, settings)
    val recorder = RecordingManager(appContext, meetings, pipeline, settings, scope)

    init {
        scope.launch {
            meetings.load()
            notes.load()
            recoverInterruptedMeetings()
        }
    }

    /** Meetings left mid-recording or mid-processing by a killed process are made usable again. */
    private suspend fun recoverInterruptedMeetings() {
        val active = setOf(MeetingStatus.RECORDING, MeetingStatus.TRANSCRIBING, MeetingStatus.ANALYZING)
        for (meeting in meetings.all()) {
            if (meeting.status !in active || recorder.isActive(meeting.id)) continue
            val leftover = File(meetings.audioDir(meeting.id), "chunks").listFiles().orEmpty()
                .mapNotNull { RecordingManager.parseChunk(it) }
            meetings.update(meeting.id) { m ->
                val pending = (m.pendingChunks + leftover).distinctBy { it.index }.sortedBy(AudioChunk::index)
                m.copy(
                    status = if (m.segments.isEmpty() && pending.isEmpty()) MeetingStatus.ERROR else MeetingStatus.READY,
                    pendingChunks = pending,
                    // The full recording is only valid once it was finalized; keep it only if it was.
                    audioPath = m.audioPath?.takeIf { m.status != MeetingStatus.RECORDING },
                    lastError = "The app was closed while this meeting was being processed." +
                        if (pending.isNotEmpty()) " Tap retry to transcribe the remaining audio." else "",
                )
            }
        }
    }
}
