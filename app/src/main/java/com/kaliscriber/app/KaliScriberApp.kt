package com.kaliscriber.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.kaliscriber.app.backup.BackupManager
import com.kaliscriber.app.calendar.CalendarService
import com.kaliscriber.app.data.AndroidSqlDb
import com.kaliscriber.app.data.Deletions
import com.kaliscriber.app.data.SecretStore
import com.kaliscriber.app.export.MeetingExporter
import com.kaliscriber.app.importing.AudioImporter
import com.kaliscriber.app.processing.AppVisibility
import com.kaliscriber.app.processing.MeetingJobs
import com.kaliscriber.app.processing.ReadyNotifier
import com.kaliscriber.app.recording.RecordingManager
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.app.storage.StorageManager
import com.kaliscriber.app.shortcuts.RecordTileService
import com.kaliscriber.app.shortcuts.RecordWidget
import com.kaliscriber.app.speech.LocalSpeechEngine
import com.kaliscriber.app.speech.ModelCatalog
import com.kaliscriber.app.speech.ModelManager
import com.kaliscriber.app.speech.ModelState
import com.kaliscriber.app.speech.SpeechOutput
import com.kaliscriber.app.tasks.RecapReminder
import com.kaliscriber.app.tasks.TaskReminders
import com.kaliscriber.core.backup.LibraryBackup
import com.kaliscriber.core.recap.WeeklyRecap
import com.kaliscriber.core.recap.WeeklyRecaps
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.DefaultAiServices
import com.kaliscriber.core.ai.MeetingAi
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.defaultHttpClient
import com.kaliscriber.core.ai.usage.UsageLedger
import com.kaliscriber.core.ai.usage.UsageRecord
import com.kaliscriber.core.ask.LibraryAsk
import com.kaliscriber.core.model.AskMessage
import com.kaliscriber.core.model.AudioChunk
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.search.SearchIndexer
import com.kaliscriber.core.search.StaticEmbeddingModel
import com.kaliscriber.core.search.TextEmbedder
import com.kaliscriber.core.search.TranscriptIndex
import com.kaliscriber.core.speakers.VoiceLibrary
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.core.store.JsonDocumentStore
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.store.NoteRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import com.kaliscriber.app.sync.MarkdownFolderSync
import com.kaliscriber.app.autorecord.AutoRecorder
import com.kaliscriber.core.calendar.AutoRecord
import com.kaliscriber.core.sync.MarkdownSync
import java.io.File

class KaliScriberApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        registerActivityLifecycleCallbacks(container.visibility)
        createNotificationChannel()
        ReadyNotifier.createChannel(this)
        TaskReminders.createChannel(this)
        RecapReminder.createChannel(this)
        AutoRecorder.createChannel(this)
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
    /** Whether the app is on screen. */
    val visibility = AppVisibility()

    private val filesDir: File = appContext.filesDir

    val settingsStore = JsonValueStore(File(filesDir, "settings.json"), AppSettings.serializer(), AppSettings())
    val settings: () -> AppSettings = { settingsStore.value.value }

    val secrets = SecretStore(appContext)
    val http = defaultHttpClient()

    /** On-device speech models and the sherpa-onnx engine that uses them. */
    val models = ModelManager(appContext, http, scope)
    val localSpeech = LocalSpeechEngine(models, settings)

    /** Token usage of every AI request, kept on the phone to show what meetings cost. */
    private val usageStore = JsonValueStore(File(filesDir, "usage.json"), ListSerializer(UsageRecord.serializer()), emptyList())
    val usage = UsageLedger(usageStore)
    val ai = DefaultAiServices(settings, secrets, http, localStt = { localSpeech }, usage = usage)
    val meetingAi = MeetingAi(ai, settings)
    val speech = SpeechOutput(appContext, ai, settings)

    /** Meetings in files/meetings, their transcripts in files/transcripts and audio in files/audio. */
    val meetings = MeetingRepository.inDir(filesDir)
    /** Full-text index of every transcript line, so search doesn't need transcripts in memory. */
    val searchIndex by lazy { TranscriptIndex(AndroidSqlDb(appContext.getDatabasePath("search.db")), ::textEmbedder) }
    /** Keeps [searchIndex] up to date. */
    val searchIndexer by lazy { SearchIndexer(meetings, searchIndex, scope, log = { Log.i("KaliScriber", it) }) }

    @Volatile private var searchModel: StaticEmbeddingModel? = null

    /** Why the smart search model couldn't be loaded, if it couldn't. */
    @Volatile var searchModelError: String? = null
        private set

    /** The on-device model that finds passages by meaning, once downloaded; null until then. */
    fun textEmbedder(): TextEmbedder? {
        searchModel?.let { return it }
        val spec = ModelCatalog.searchModel
        if (!models.isReady(spec)) return null
        return synchronized(this) {
            searchModel ?: try {
                StaticEmbeddingModel.load(models.dir(spec), id = spec.id).also {
                    searchModel = it
                    searchModelError = null
                }
            } catch (e: Exception) {
                Log.w("KaliScriber", "Smart search model couldn't be loaded", e)
                searchModelError = e.message ?: e.javaClass.simpleName
                null
            }
        }
    }
    val notes = NoteRepository(JsonDocumentStore(File(filesDir, "notes"), Note.serializer()) { it.id })
    /** Remembered voices (on-device voiceprints) for recognising people across meetings. */
    private val voiceStore = JsonValueStore(File(filesDir, "voices.json"), ListSerializer(VoiceProfile.serializer()), emptyList())
    val voices = VoiceLibrary(voiceStore, model = ModelCatalog.embedding.id)
    val pipeline = MeetingPipeline(
        meetings, ai, meetingAi, settings,
        voices = voices,
        embedder = { localSpeech },
        log = { message -> Log.i("KaliScriber", message) },
        fetchDiarizationModels = { onProgress -> models.awaitDiarization(onProgress) },
        liveScope = scope,
    )
    val exporter = MeetingExporter(appContext, http, secrets, settings)
    /** Long jobs started from a meeting screen, kept running in the background. */
    val jobs = MeetingJobs(appContext, scope)
    /** Audio and video files picked in the app, shared to it or opened with it. */
    val importer = AudioImporter(appContext, meetings, pipeline, jobs, scope)
    /** What the app was opened to do from outside (a shared file, the tile or the widget). */
    val launches = LaunchRequests()
    /** "Ask across all meetings" and its conversation. */
    private val askAllStore = JsonValueStore(File(filesDir, "ask-all.json"), ListSerializer(AskMessage.serializer()), emptyList())
    val libraryAsk = LibraryAsk(
        meetings,
        askAllStore,
        ai,
        settings,
        corpus = { searchIndex },
    )
    /** Which meetings "Ask all" questions are about, kept while the app runs. */
    val askScope = kotlinx.coroutines.flow.MutableStateFlow(com.kaliscriber.core.ask.AskScope())
    /** One Markdown note per meeting in a folder the user picked, kept up to date. */
    val markdownSync = MarkdownFolderSync(
        appContext, meetings, settings,
        JsonValueStore(File(filesDir, "markdown-sync.json"), MapSerializer(String.serializer(), MarkdownSync.Entry.serializer()), emptyMap()),
    )
    /** Recaps across a week of meetings. */
    val recaps = WeeklyRecaps(
        meetings, ai, settings,
        JsonValueStore(File(filesDir, "recaps.json"), ListSerializer(WeeklyRecap.serializer()), emptyList()),
    )
    /** Events from the CalDAV calendar, for meeting titles, invitees and prep. */
    val calendar = CalendarService(http, settings, secrets, com.kaliscriber.app.calendar.PhoneCalendar(appContext))
    val recorder: RecordingManager = RecordingManager(appContext, meetings, pipeline, settings, scope, onStart = { meetingId ->
        // Start fetching speaker detection now so it is ready when the meeting ends.
        if (speakerPackMissing) models.downloadDiarization()
        // A meeting on the calendar right now gives the recording its title and invitees; one being
        // recorded by itself is known exactly.
        val planned = autoRecorder.starting
        if (planned != null || calendar.enabled) {
            scope.launch { (planned ?: calendar.eventNow())?.let { pipeline.attachCalendarEvent(meetingId, it) } }
        }
    })

    /** Records calendar meetings by themselves, from their start to their end. */
    val autoRecorder: AutoRecorder = AutoRecorder(
        appContext, calendar, recorder, pipeline, settings, visibility,
        JsonValueStore(File(filesDir, "auto-record.json"), AutoRecord.State.serializer(), AutoRecord.State()),
    )

    /** What the app stores, and deleting old recordings as the user chose. */
    val storage = StorageManager(appContext, meetings, settings, jobs)

    /** Deletions that wait a moment so they can be undone. */
    val deletions by lazy { Deletions(scope, meetings, notes, storage) }

    /** Encrypted backups of meetings, notes, voices and settings, and restoring them. */
    val backups by lazy {
        val version = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName }.getOrNull().orEmpty()
        BackupManager(
            appContext,
            LibraryBackup(meetings, notes, settingsStore, voiceStore, askAllStore, usageStore, appVersion = version),
            recorder, jobs, scope,
        )
    }

    /** True when on-device speaker detection is wanted but its models are not on the phone yet. */
    val speakerPackMissing: Boolean
        get() = settings().let { it.sttProvider == ProviderId.LOCAL && it.localDiarization } && !models.diarizationReady

    init {
        // Speech models downloaded before the speaker pack came bundled with them: fetch it on Wi-Fi.
        val metered = runCatching { appContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered }.getOrDefault(true)
        if (speakerPackMissing && models.readyAsrModels().isNotEmpty() && !metered) models.downloadDiarization()
        // Index transcripts for search in the background (all of them the first time).
        scope.launch(Dispatchers.IO) { searchIndexer.start() }
        // Once the smart search model is downloaded (or deleted), passages are embedded again.
        scope.launch {
            models.states
                .map { it[ModelCatalog.searchModel.id] is ModelState.Ready }
                .distinctUntilChanged()
                .drop(1)
                .collect { ready ->
                    if (!ready) searchModel = null
                    searchIndexer.refresh()
                }
        }
        // The Quick Settings tile and home-screen widget show whether a meeting is being recorded.
        scope.launch {
            recorder.state
                .distinctUntilChangedBy { s -> s.javaClass.name + ((s as? RecordingState.Recording)?.paused ?: "") }
                .collect { state ->
                    RecordTileService.refresh(appContext)
                    runCatching { RecordWidget.update(appContext, state) }
                }
        }
        // Keep the daily action-item reminder in step with its settings.
        scope.launch {
            settingsStore.value
                .map { it.taskReminders to it.reminderHour }
                .distinctUntilChanged()
                .collect { runCatching { TaskReminders.schedule(appContext, settings()) } }
        }
        // "Notes ready" when a meeting finishes processing while the app isn't on screen.
        scope.launch {
            var before: Map<String, MeetingStatus>? = null
            meetings.meetings.collect { list ->
                val previous = before
                if (previous != null && !visibility.foreground) {
                    list.filter { ReadyNotifier.finished(previous[it.id], it) }.forEach { ReadyNotifier.notify(appContext, it) }
                }
                before = list.associate { it.id to it.status }
            }
        }
        scope.launch {
            settingsStore.value
                .map { it.weeklyRecap }
                .distinctUntilChanged()
                .collect { runCatching { RecapReminder.schedule(appContext, settings()) } }
        }
        scope.launch {
            meetings.load()
            notes.load()
            recoverInterruptedMeetings()
            // Only once meetings are loaded, so an empty list never looks like everything was deleted.
            markdownSync.start(scope, settingsStore.value)
            startAutoRecord()
            // Recordings older than the user keeps them are deleted, keeping transcripts.
            runCatching { storage.applyRetention() }.onFailure { Log.w("KaliScriber", "Couldn't delete old recordings", it) }
            // Voices saved with the previous speaker model are learned again once the new one is here.
            models.states.first { it[ModelCatalog.embedding.id] is ModelState.Ready }
            if (voices.outdated.isNotEmpty()) {
                runCatching { pipeline.relearnVoices() }
                    .onSuccess { Log.i("KaliScriber", "Voice memory: learned $it voice(s) again with the new speaker model") }
                    .onFailure { Log.w("KaliScriber", "Voice memory: couldn't learn voices again", it) }
            }
        }
    }

    /** Keeps auto-record's alarms in step with its settings, the calendar and the recorder. */
    private fun startAutoRecord() {
        scope.launch {
            settingsStore.value
                .map { listOf(it.autoRecord, it.autoRecordWhich, it.phoneCalendar, it.scheduledRecordings, it.recordingPausedUntil) }
                .distinctUntilChanged()
                .collect {
                    autoRecorder.applySettings()
                    runCatching { autoRecorder.tick(refresh = true) }.onFailure { Log.w("KaliScriber", "Auto-record check failed", it) }
                }
        }
        // The next-recording widget follows the schedule, the calendar and the recorder.
        scope.launch {
            kotlinx.coroutines.flow.combine(autoRecorder.state, settingsStore.value, recorder.state) { a, s, r ->
                Triple(a, listOf(s.autoRecord, s.autoRecordWhich, s.scheduledRecordings, s.recordingPausedUntil), r.javaClass)
            }
                .distinctUntilChanged()
                .collect { runCatching { com.kaliscriber.app.autorecord.NextRecordingWidget.update(appContext) } }
        }
        // A recording stopped by hand, or one that ended, may change what's next.
        scope.launch {
            recorder.state
                .map { (it as? RecordingState.Recording)?.meetingId }
                .distinctUntilChanged()
                .drop(1)
                .collect { runCatching { autoRecorder.tick() } }
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
