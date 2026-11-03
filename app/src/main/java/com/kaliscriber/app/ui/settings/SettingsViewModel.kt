package com.kaliscriber.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import android.net.Uri
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.calendar.AutoRecord
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.Manifest
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.backup.BackupManager
import com.kaliscriber.app.storage.StorageManager
import com.kaliscriber.app.data.ExportSecret
import com.kaliscriber.app.export.ExportTarget
import com.kaliscriber.app.export.exportMessage
import com.kaliscriber.app.speech.LocalModel
import com.kaliscriber.app.speech.ModelRole
import com.kaliscriber.app.speech.ModelState
import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ExportSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.core.ai.stt.SttClients
import com.kaliscriber.core.ai.tts.ElevenLabsTts
import com.kaliscriber.core.ai.tts.Voice
import com.kaliscriber.core.ai.usage.ModelPrice
import com.kaliscriber.core.ai.usage.UsageLedger
import com.kaliscriber.core.audio.Wav
import com.kaliscriber.core.speakers.VoiceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Unsaved edits for one provider card. */
data class ProviderDraft(
    val apiKey: String,
    val baseUrl: String,
    val chatModel: String,
    val sttModel: String,
    val zeroDataRetention: Boolean = true,
    val voiceId: String = "",
    val ttsModel: String = "",
    val reasoningEffort: String = "",
)

sealed interface ProviderStatus {
    data object Working : ProviderStatus
    data class Ok(val message: String) : ProviderStatus
    data class Failed(val message: String) : ProviderStatus
    data class Models(val models: List<String>) : ProviderStatus
    data class Voices(val voices: List<Voice>) : ProviderStatus
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsStore.value
    val configuredKeys: StateFlow<Set<ProviderId>> = container.secrets.configured
    val modelStates: StateFlow<Map<String, ModelState>> = container.models.states

    init {
        viewModelScope.launch { container.models.refresh() }
    }

    /** Names of people identified in meetings, for adding to the glossary. */
    fun knownPeople(): List<String> = com.kaliscriber.core.people.People.directory(container.meetings.all(), container.voices.profiles.value)
        .map { it.name }

    /** Text-to-speech engines installed on the phone, as (package, name). */
    fun ttsEngines(): List<Pair<String, String>> = container.speech.installedEngines()

    private val _voiceTest = MutableStateFlow<String?>(null)
    /** Result of the last "Test voice", or null. */
    val voiceTest: StateFlow<String?> = _voiceTest.asStateFlow()

    fun testVoice() {
        viewModelScope.launch {
            _voiceTest.value = "Speaking…"
            _voiceTest.value = try {
                container.speech.speak("settings-test", "This is how KaliScriber will read your summaries and answers.")
                null
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                e.message ?: "The voice didn't work."
            }
        }
    }

    fun downloadModel(model: LocalModel) {
        container.models.download(model)
        // The first recognizer downloaded becomes the active one.
        if (model.role == ModelRole.ASR && container.models.readyAsrModels().isEmpty()) {
            update { it.copy(localAsrModel = model.id) }
        }
    }

    fun cancelModel(model: LocalModel) = container.models.cancel(model)

    fun searchModelError(): String? = container.searchModelError

    /** AI usage this month, by model. */
    val usageThisMonth: StateFlow<List<UsageLedger.ModelTotal>> = container.usage.records
        .map { UsageLedger.byModel(UsageLedger.thisMonth(it, System.currentTimeMillis())) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun monthCost(prices: Map<String, ModelPrice>): Double? =
        UsageLedger.cost(UsageLedger.thisMonth(container.usage.records.value, System.currentTimeMillis()), prices)

    fun setPrice(key: String, price: ModelPrice?) = update { s ->
        s.copy(modelPrices = if (price == null) s.modelPrices - key else s.modelPrices + (key to price))
    }

    fun clearUsage() = viewModelScope.launch { container.usage.clear() }

    fun deleteModel(model: LocalModel) {
        viewModelScope.launch {
            container.localSpeech.unload()
            container.models.delete(model)
        }
    }

    fun selectLocalModel(model: LocalModel) {
        update { it.copy(localAsrModel = model.id) }
        viewModelScope.launch { container.localSpeech.unload() }
    }

    fun downloadDiarization() = container.models.downloadDiarization()

    // --- Voice memory ---------------------------------------------------------------------------

    val voices: StateFlow<List<VoiceProfile>> = container.voices.profiles

    fun deleteVoice(id: String) = viewModelScope.launch { container.voices.delete(id) }

    fun renameVoice(id: String, name: String) = viewModelScope.launch { container.voices.rename(id, name) }

    // --- Exports --------------------------------------------------------------------------------

    val exportSecrets: StateFlow<Set<ExportSecret>> = container.secrets.configuredSecrets

    private val _exportStatus = MutableStateFlow<Map<ExportTarget, ProviderStatus>>(emptyMap())
    val exportStatus: StateFlow<Map<ExportTarget, ProviderStatus>> = _exportStatus.asStateFlow()

    val signingSha1: String? by lazy { container.exporter.signingSha1() }

    fun updateExport(transform: (ExportSettings) -> ExportSettings) = update { it.copy(export = transform(it.export)) }

    fun saveExportSecret(secret: ExportSecret, value: String) {
        viewModelScope.launch(Dispatchers.IO) { container.secrets.setSecret(secret, value) }
    }

    fun testExport(target: ExportTarget) {
        _exportStatus.update { it + (target to ProviderStatus.Working) }
        viewModelScope.launch {
            // Let pending settings writes land before testing with them.
            kotlinx.coroutines.delay(300)
            val status = try {
                val result = when (target) {
                    ExportTarget.NOTION -> container.exporter.testNotion()
                    ExportTarget.AFFINE -> container.exporter.testAffine()
                    ExportTarget.CALDAV -> container.exporter.testCaldav()
                    ExportTarget.EMAIL, ExportTarget.GOOGLE_DOCS -> null
                }
                ProviderStatus.Ok(result?.message ?: "Ready")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ProviderStatus.Failed(e.exportMessage())
            }
            _exportStatus.update { it + (target to status) }
        }
    }

    private val _status = MutableStateFlow<Map<ProviderId, ProviderStatus>>(emptyMap())
    val status: StateFlow<Map<ProviderId, ProviderStatus>> = _status.asStateFlow()

    fun draftFor(id: ProviderId): ProviderDraft {
        val s = settings.value.settingsFor(id)
        return ProviderDraft(
            apiKey = container.secrets.apiKey(id).orEmpty(),
            baseUrl = s.baseUrl,
            chatModel = s.chatModel,
            sttModel = s.sttModel,
            zeroDataRetention = s.zeroDataRetention,
            voiceId = s.voiceId,
            ttsModel = s.ttsModel,
            reasoningEffort = s.reasoningEffort,
        )
    }

    private val _storage = MutableStateFlow<StorageManager.Usage?>(null)
    /** What the app keeps on the phone, once measured. */
    val storage: StateFlow<StorageManager.Usage?> = _storage.asStateFlow()

    fun measureStorage() = viewModelScope.launch { _storage.value = container.storage.usage() }

    /** How many recordings, and how many bytes, keeping recordings for [days] would delete now. */
    fun recordingsDue(days: Int): Pair<Int, Long> = container.storage.due(days).let { it.size to container.storage.dueBytes(it) }

    /** Keeps recordings for [days] (0 = forever), deleting older ones now. */
    fun keepRecordings(days: Int) = viewModelScope.launch {
        container.settingsStore.update { it.copy(keepRecordingsDays = days) }
        container.storage.applyRetention()
        _storage.value = container.storage.usage()
    }

    val backupState: StateFlow<BackupManager.State> = container.backups.state

    fun backupFileName(): String = container.backups.suggestedName()

    fun backUp(uri: Uri, password: CharArray, withRecordings: Boolean) = container.backups.backUp(uri, password, withRecordings)

    fun restore(uri: Uri, password: CharArray, replaceSettings: Boolean) = container.backups.restore(uri, password, replaceSettings)

    fun dismissBackup() = container.backups.dismiss()

    /** True while this phone's settings are still the defaults, so a backup's settings lose nothing. */
    fun settingsUntouched(): Boolean = settings.value == AppSettings()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsStore.update(transform) }
    }

    // --- Recording calendar meetings ---------------------------------------------------------------

    val autoRecordState = container.autoRecorder.state

    /** What Android allows auto-record to do right now. */
    data class AutoRecordPermissions(
        val calendar: Boolean,
        val anyCalendar: Boolean,
        val mic: Boolean,
        val overlay: Boolean,
        val fullScreen: Boolean,
        val exactAlarms: Boolean,
    )

    fun autoRecordPermissions(): AutoRecordPermissions {
        val auto = container.autoRecorder
        val calendarAllowed = ContextCompat.checkSelfPermission(container.appContext, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
        val e = settings.value.export
        val caldav = e.calendarLookup && e.caldavUrl.isNotBlank()
        return AutoRecordPermissions(
            calendar = calendarAllowed,
            anyCalendar = caldav || (settings.value.phoneCalendar && calendarAllowed),
            mic = auto.micAllowed(),
            overlay = auto.overlayAllowed(),
            fullScreen = auto.fullScreenAllowed(),
            exactAlarms = auto.exactAlarmsAllowed(),
        )
    }

    fun upcomingAutoRecord(state: AutoRecord.State): CalendarEvent? = container.autoRecorder.upcoming(state)

    fun skipAutoRecord(event: CalendarEvent) {
        container.scope.launch { container.autoRecorder.skip(AutoRecord.key(event), event.end) }
    }

    fun unskipAutoRecord(event: CalendarEvent) {
        container.scope.launch { container.autoRecorder.unskip(AutoRecord.key(event)) }
    }

    // --- Task sync -----------------------------------------------------------------------------------

    val taskSyncStatus = container.taskSync.status

    fun syncTasksNow() {
        container.scope.launch { container.taskSync.sync() }
    }

    // --- Markdown folder -------------------------------------------------------------------------

    val markdownStatus = container.markdownSync.status

    fun markdownFolderName(uri: String): String? = container.markdownSync.folderName(uri)

    /** Starts writing notes into [uri], a folder picked with the system's folder chooser. */
    fun chooseMarkdownFolder(uri: Uri) {
        val resolver = container.appContext.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { resolver.takePersistableUriPermission(uri, flags) }
        val old = settings.value.export.markdownFolder
        container.scope.launch {
            if (old.isNotBlank() && old != uri.toString()) runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), flags) }
            container.markdownSync.reset()
            container.settingsStore.update { it.copy(export = it.export.copy(markdownFolder = uri.toString())) }
            container.markdownSync.sync()
        }
    }

    /** Stops keeping the folder up to date; the notes already there stay. */
    fun stopMarkdownSync() {
        val old = settings.value.export.markdownFolder
        container.scope.launch {
            container.settingsStore.update { it.copy(export = it.export.copy(markdownFolder = "")) }
            container.markdownSync.reset()
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (old.isNotBlank()) runCatching { container.appContext.contentResolver.releasePersistableUriPermission(Uri.parse(old), flags) }
        }
    }

    fun syncMarkdownNow() {
        container.scope.launch { container.markdownSync.sync() }
    }

    fun save(id: ProviderId, draft: ProviderDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.secrets.setApiKey(id, draft.apiKey) }
            container.settingsStore.update {
                it.withProvider(id) { _ -> draft.toSettings() }
            }
            setStatus(id, ProviderStatus.Ok("Saved"))
        }
    }

    private fun ProviderDraft.toSettings() = ProviderSettings(
        baseUrl = baseUrl.trim(),
        chatModel = chatModel.trim(),
        sttModel = sttModel.trim(),
        zeroDataRetention = zeroDataRetention,
        ttsModel = ttsModel.trim(),
        voiceId = voiceId.trim(),
        reasoningEffort = reasoningEffort,
    )

    private fun configFor(id: ProviderId, draft: ProviderDraft) = AppSettings()
        .withProvider(id) { draft.toSettings() }
        .configFor(id, draft.apiKey)

    fun listVoices(id: ProviderId, draft: ProviderDraft) = runForStatus(id) {
        val voices = ElevenLabsTts(configFor(id, draft), container.http).listVoices()
        if (voices.isEmpty()) ProviderStatus.Failed("No voices found") else ProviderStatus.Voices(voices)
    }

    fun fetchModels(id: ProviderId, draft: ProviderDraft) = runForStatus(id) {
        val models = LlmClients.create(configFor(id, draft), container.http).listModels()
        if (models.isEmpty()) ProviderStatus.Failed("No models returned") else ProviderStatus.Models(models)
    }

    /** Sends a tiny chat request, or transcribes a second of silence for speech-only providers. */
    fun test(id: ProviderId, draft: ProviderDraft) = runForStatus(id) {
        val config = configFor(id, draft)
        if (id.keyRequired && config.apiKey.isBlank()) throw AiException("Enter an API key first")
        if (id.supportsChat) {
            val reply = LlmClients.create(config, container.http).complete(
                LlmRequest(
                    system = "You are a connectivity check.",
                    messages = listOf(LlmMessage(LlmRole.USER, "Reply with the single word OK.")),
                    maxTokens = 16,
                ),
            )
            val zdr = if (config.zeroDataRetention) " (zero data retention)" else ""
            ProviderStatus.Ok("Chat works with ${config.chatModel}$zdr: “${reply.trim().take(40)}”")
        } else {
            val silence = File(container.appContext.cacheDir, "silence.wav")
            withContext(Dispatchers.IO) {
                Wav.write(silence, ByteArray(32_000), 32_000, 16_000)
            }
            try {
                SttClients.create(config, container.http).transcribe(silence, null, 1_000)
            } finally {
                silence.delete()
            }
            ProviderStatus.Ok("Transcription works with ${config.sttModel.ifBlank { "the default model" }}")
        }
    }

    private fun runForStatus(id: ProviderId, block: suspend () -> ProviderStatus) {
        setStatus(id, ProviderStatus.Working)
        viewModelScope.launch {
            val result = try {
                block()
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                ProviderStatus.Failed(e.message ?: "Failed")
            }
            setStatus(id, result)
        }
    }

    private fun setStatus(id: ProviderId, status: ProviderStatus) = _status.update { it + (id to status) }
}
