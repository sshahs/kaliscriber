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
import com.kaliscriber.core.ai.stt.SttClients
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
)

sealed interface ProviderStatus {
    data object Working : ProviderStatus
    data class Ok(val message: String) : ProviderStatus
    data class Failed(val message: String) : ProviderStatus
    data class Models(val models: List<String>) : ProviderStatus
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settingsStore.value
    val configuredKeys: StateFlow<Set<ProviderId>> = container.secrets.configured

    private val _status = MutableStateFlow<Map<ProviderId, ProviderStatus>>(emptyMap())
    val status: StateFlow<Map<ProviderId, ProviderStatus>> = _status.asStateFlow()

    fun draftFor(id: ProviderId): ProviderDraft {
        val s = settings.value.settingsFor(id)
        return ProviderDraft(
            apiKey = container.secrets.apiKey(id).orEmpty(),
            baseUrl = s.baseUrl,
            chatModel = s.chatModel,
            sttModel = s.sttModel,
        )
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { container.settingsStore.update(transform) }
    }

    fun save(id: ProviderId, draft: ProviderDraft) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { container.secrets.setApiKey(id, draft.apiKey) }
            container.settingsStore.update {
                it.withProvider(id) {
                    ProviderSettings(baseUrl = draft.baseUrl.trim(), chatModel = draft.chatModel.trim(), sttModel = draft.sttModel.trim())
                }
            }
            setStatus(id, ProviderStatus.Ok("Saved"))
        }
    }

    private fun configFor(id: ProviderId, draft: ProviderDraft) = AppSettings()
        .withProvider(id) { ProviderSettings(draft.baseUrl.trim(), draft.chatModel.trim(), draft.sttModel.trim()) }
        .configFor(id, draft.apiKey)

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
            ProviderStatus.Ok("Chat works with ${config.chatModel}: “${reply.trim().take(40)}”")
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
