package com.kaliscriber.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.ProviderSettings
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.llm.LlmMessage
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.llm.LlmRole
import com.kaliscriber.app.data.ExportSecret
import com.kaliscriber.app.export.ExportTarget
import com.kaliscriber.app.export.exportMessage
import com.kaliscriber.app.speech.LocalModel
import com.kaliscriber.core.ai.ExportSettings
import com.kaliscriber.core.speakers.VoiceProfile
import com.kaliscriber.app.speech.ModelRole
import com.kaliscriber.app.speech.ModelState
import com.kaliscriber.core.ai.stt.SttClients
import com.kaliscriber.core.ai.tts.ElevenLabsTts
import com.kaliscriber.core.ai.tts.Voice
import com.kaliscriber.core.audio.Wav
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsStore.update(transform) }
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
