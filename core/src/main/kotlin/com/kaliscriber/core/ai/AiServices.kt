package com.kaliscriber.core.ai

import com.kaliscriber.core.ai.llm.LlmClient
import com.kaliscriber.core.ai.llm.LlmClients
import com.kaliscriber.core.ai.stt.SttClient
import com.kaliscriber.core.ai.stt.SttClients
import com.kaliscriber.core.ai.tts.ElevenLabsTts
import okhttp3.OkHttpClient

/** Builds clients for the providers currently selected in [AppSettings]. */
interface AiServices {
    fun chat(): LlmClient
    fun stt(): SttClient
}

class DefaultAiServices(
    private val settings: () -> AppSettings,
    private val secrets: SecretProvider,
    private val http: OkHttpClient = defaultHttpClient(),
    /** The app's on-device speech engine, used when [ProviderId.LOCAL] is selected. */
    private val localStt: (() -> SttClient)? = null,
) : AiServices {

    override fun chat(): LlmClient = chatFor(settings().chatProvider)

    override fun stt(): SttClient = sttFor(settings().sttProvider)

    fun chatFor(id: ProviderId, settingsOverride: AppSettings = settings()): LlmClient {
        require(id.supportsChat) { "${id.displayName} does not provide chat models" }
        return LlmClients.create(resolve(id, settingsOverride), http)
    }

    fun sttFor(id: ProviderId, settingsOverride: AppSettings = settings()): SttClient {
        require(id.supportsStt) { "${id.displayName} does not provide speech-to-text" }
        if (id == ProviderId.LOCAL) {
            return localStt?.invoke() ?: throw AiException("On-device speech recognition is not available on this build.")
        }
        return SttClients.create(resolve(id, settingsOverride), http)
    }

    fun elevenLabsTts(settingsOverride: AppSettings = settings()): ElevenLabsTts =
        ElevenLabsTts(resolve(ProviderId.ELEVENLABS, settingsOverride), http)

    private fun resolve(id: ProviderId, s: AppSettings): ProviderConfig {
        val config = s.configFor(id, secrets.apiKey(id))
        if (id.keyRequired && config.apiKey.isBlank()) {
            throw AiException("Add your ${id.displayName} API key in Settings to use it.")
        }
        return config
    }
}
