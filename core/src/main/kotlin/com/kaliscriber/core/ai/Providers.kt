package com.kaliscriber.core.ai

import kotlinx.serialization.Serializable

/** Wire protocol a provider speaks. */
enum class ApiKind { LOCAL, OPENAI_COMPATIBLE, ANTHROPIC, OLLAMA, COMMAND_CODE, DEEPGRAM, ASSEMBLYAI, ELEVENLABS }

/**
 * Every AI service the app can talk to with the user's own key (BYOK).
 * Base URLs, models and keys are all user editable; these are only defaults.
 */
@Serializable
enum class ProviderId(
    val displayName: String,
    val kind: ApiKind,
    val defaultBaseUrl: String,
    val supportsChat: Boolean,
    val supportsStt: Boolean,
    val defaultChatModel: String = "",
    val defaultSttModel: String = "",
    val keyRequired: Boolean = true,
    val keyUrl: String = "",
    /** Whether the OpenAI-style `response_format: json_object` is understood. */
    val supportsJsonMode: Boolean = false,
    val maxUploadBytes: Long = 25L * 1024 * 1024,
    val supportsTts: Boolean = false,
    /** Offers a zero-data-retention mode that can be switched on per request. */
    val supportsZdr: Boolean = false,
) {
    /** Speech-to-text on the phone with sherpa-onnx; audio never leaves the device. */
    LOCAL(
        "On-device (private, offline)", ApiKind.LOCAL, "",
        supportsChat = false, supportsStt = true,
        keyRequired = false, maxUploadBytes = Long.MAX_VALUE,
    ),
    OPENAI(
        "OpenAI", ApiKind.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
        supportsChat = true, supportsStt = true,
        defaultChatModel = "gpt-5-mini", defaultSttModel = "gpt-4o-transcribe-diarize",
        keyUrl = "https://platform.openai.com/api-keys", supportsJsonMode = true,
    ),
    ANTHROPIC(
        "Anthropic Claude", ApiKind.ANTHROPIC, "https://api.anthropic.com",
        supportsChat = true, supportsStt = false,
        defaultChatModel = "claude-sonnet-5-5",
        keyUrl = "https://console.anthropic.com/settings/keys",
    ),
    GEMINI(
        "Google Gemini", ApiKind.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai",
        supportsChat = true, supportsStt = false,
        defaultChatModel = "gemini-2.5-flash",
        keyUrl = "https://aistudio.google.com/apikey", supportsJsonMode = true,
    ),
    OLLAMA_CLOUD(
        "Ollama Cloud", ApiKind.OLLAMA, "https://ollama.com",
        supportsChat = true, supportsStt = false,
        defaultChatModel = "gpt-oss:120b",
        keyUrl = "https://ollama.com/settings/keys",
    ),
    COMMAND_CODE(
        "Command Code", ApiKind.COMMAND_CODE, "https://api.commandcode.ai/provider/v1",
        supportsChat = true, supportsStt = false,
        defaultChatModel = "deepseek/deepseek-v4-flash",
        keyUrl = "https://commandcode.ai/settings", supportsZdr = true,
    ),
    GROQ(
        "Groq", ApiKind.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1",
        supportsChat = true, supportsStt = true,
        defaultChatModel = "llama-3.3-70b-versatile", defaultSttModel = "whisper-large-v3-turbo",
        keyUrl = "https://console.groq.com/keys", supportsJsonMode = true,
    ),
    OPENROUTER(
        "OpenRouter", ApiKind.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1",
        supportsChat = true, supportsStt = false,
        defaultChatModel = "openai/gpt-5-mini",
        keyUrl = "https://openrouter.ai/keys",
    ),
    MISTRAL(
        "Mistral", ApiKind.OPENAI_COMPATIBLE, "https://api.mistral.ai/v1",
        supportsChat = true, supportsStt = true,
        defaultChatModel = "mistral-medium-latest", defaultSttModel = "voxtral-mini-latest",
        keyUrl = "https://console.mistral.ai/api-keys", supportsJsonMode = true,
    ),
    DEEPSEEK(
        "DeepSeek", ApiKind.OPENAI_COMPATIBLE, "https://api.deepseek.com/v1",
        supportsChat = true, supportsStt = false,
        defaultChatModel = "deepseek-chat",
        keyUrl = "https://platform.deepseek.com/api_keys", supportsJsonMode = true,
    ),
    DEEPGRAM(
        "Deepgram", ApiKind.DEEPGRAM, "https://api.deepgram.com",
        supportsChat = false, supportsStt = true,
        defaultSttModel = "nova-3",
        keyUrl = "https://console.deepgram.com", maxUploadBytes = 2L * 1024 * 1024 * 1024,
    ),
    ASSEMBLYAI(
        "AssemblyAI", ApiKind.ASSEMBLYAI, "https://api.assemblyai.com",
        supportsChat = false, supportsStt = true,
        defaultSttModel = "",
        keyUrl = "https://www.assemblyai.com/app/api-keys", maxUploadBytes = 2L * 1024 * 1024 * 1024,
    ),
    ELEVENLABS(
        "ElevenLabs", ApiKind.ELEVENLABS, "https://api.elevenlabs.io",
        supportsChat = false, supportsStt = true,
        defaultSttModel = "scribe_v2",
        keyUrl = "https://elevenlabs.io/app/settings/api-keys", maxUploadBytes = 1024L * 1024 * 1024,
        supportsTts = true,
    ),
    CUSTOM_OPENAI(
        "Custom (OpenAI-compatible)", ApiKind.OPENAI_COMPATIBLE, "https://example.com/v1",
        supportsChat = true, supportsStt = true,
        keyRequired = false,
    );

    companion object {
        val chatProviders: List<ProviderId> get() = entries.filter { it.supportsChat }
        val sttProviders: List<ProviderId> get() = entries.filter { it.supportsStt }
        val ttsProviders: List<ProviderId> get() = entries.filter { it.supportsTts }

        const val DEFAULT_ELEVENLABS_VOICE = "JBFqnCBsd6RMkjVDRZzb"
        const val DEFAULT_ELEVENLABS_TTS_MODEL = "eleven_multilingual_v2"
    }
}

/** Non-secret, per-provider settings. API keys are stored separately (encrypted) by the app. */
@Serializable
data class ProviderSettings(
    val baseUrl: String = "",
    val chatModel: String = "",
    val sttModel: String = "",
    /** Zero data retention, for providers that offer it (Command Code). On by default. */
    val zeroDataRetention: Boolean = true,
    val ttsModel: String = "",
    val voiceId: String = "",
    /**
     * Thinking effort for reasoning models on Chat Completions APIs ("low", "medium", "high", "max");
     * blank uses the provider's default. Lower is much faster for summaries of long meetings.
     */
    val reasoningEffort: String = "",
)

/** Who reads text aloud. */
@Serializable
enum class TtsEngine(val displayName: String) {
    DEVICE("This phone's voice (offline)"),
    ELEVENLABS("ElevenLabs"),
}

@Serializable
data class AppSettings(
    val chatProvider: ProviderId = ProviderId.OPENAI,
    val sttProvider: ProviderId = ProviderId.LOCAL,
    val ttsEngine: TtsEngine = TtsEngine.DEVICE,
    /** Package of the phone's text-to-speech engine to use; blank uses the system default. */
    val deviceTtsEngine: String = "",
    /** Id of the on-device speech recognition model (see the app's model catalog). */
    val localAsrModel: String = "",
    /** Detect speakers on-device when a meeting ends (needs the speaker models). */
    val localDiarization: Boolean = true,
    val providers: Map<ProviderId, ProviderSettings> = emptyMap(),
    /** Length of each live transcription chunk while recording. */
    val chunkSeconds: Int = 30,
    /** ISO-639-1 hint for transcription, blank for auto-detect. */
    val transcriptionLanguage: String = "",
    /** Language for AI answers and summaries, blank to follow the meeting. */
    val outputLanguage: String = "",
    /** Re-transcribe the whole recording with diarization when the meeting ends. */
    val finalDiarizationPass: Boolean = true,
    /** Use the LLM to separate speakers when the transcription service cannot. */
    val llmSpeakerSeparation: Boolean = true,
    /** Ask the LLM to suggest real names for placeholder speakers. */
    val suggestSpeakerNames: Boolean = true,
    /** Remember voices across meetings (voiceprints stay on the device). */
    val rememberVoices: Boolean = true,
    /**
     * Write short notes every ten minutes while recording, so the summary after a long meeting
     * starts from them and is ready sooner.
     */
    val liveNotes: Boolean = true,
    /** Generate summary, insights, gaps and questions automatically after a meeting. */
    val autoAnalyze: Boolean = true,
    /** Rough cap on transcript characters sent to the LLM in one request. */
    val maxTranscriptChars: Int = 400_000,
    val export: ExportSettings = ExportSettings(),
    /** Android's noise suppression and automatic volume while recording. */
    val audioCleanup: Boolean = true,
    /** Recordings of finished meetings are deleted after this many days, keeping transcripts; 0 keeps them. */
    val keepRecordingsDays: Int = 0,
    /** Which microphone to record meetings with. */
    val microphone: Microphone = Microphone.AUTO,
    /** Prices the user entered, per "provider/model", to estimate what AI requests cost. */
    val modelPrices: Map<String, com.kaliscriber.core.ai.usage.ModelPrice> = emptyMap(),
    /**
     * Names and terms the AI should spell correctly, one per line (e.g. "Priya Sharma — product lead").
     * Added to every meeting request and used to fix misheard words in transcripts.
     */
    val glossary: String = "",
    /** Daily notification listing overdue action items and those due that day. */
    val taskReminders: Boolean = true,
    /** Hour of the day (0-23) the reminder is shown. */
    val reminderHour: Int = 9,
) {
    fun settingsFor(id: ProviderId): ProviderSettings = providers[id] ?: ProviderSettings()

    fun configFor(id: ProviderId, apiKey: String?): ProviderConfig {
        val s = settingsFor(id)
        return ProviderConfig(
            id = id,
            apiKey = apiKey?.trim().orEmpty(),
            baseUrl = s.baseUrl.ifBlank { id.defaultBaseUrl }.trimEnd('/'),
            chatModel = s.chatModel.ifBlank { id.defaultChatModel },
            sttModel = s.sttModel.ifBlank { id.defaultSttModel },
            zeroDataRetention = id.supportsZdr && s.zeroDataRetention,
            ttsModel = s.ttsModel.ifBlank { if (id.supportsTts) ProviderId.DEFAULT_ELEVENLABS_TTS_MODEL else "" },
            voiceId = s.voiceId.ifBlank { if (id.supportsTts) ProviderId.DEFAULT_ELEVENLABS_VOICE else "" },
            reasoningEffort = s.reasoningEffort.trim().lowercase(),
        )
    }

    fun withProvider(id: ProviderId, transform: (ProviderSettings) -> ProviderSettings): AppSettings =
        copy(providers = providers + (id to transform(settingsFor(id))))
}

/** Where meeting notes can be sent. Tokens and passwords are stored encrypted by the app. */
@Serializable
data class ExportSettings(
    /** Add the full transcript to exported notes. */
    val includeTranscript: Boolean = false,
    /** Default recipients for email export, comma separated. */
    val emailTo: String = "",
    /** Notion page (URL or id) that new meeting pages are created under. */
    val notionParent: String = "",
    val affineServer: String = "https://app.affine.pro",
    val affineWorkspaceId: String = "",
    /** CalDAV calendar collection for the meeting event (e.g. Baikal .../dav.php/calendars/user/default/). */
    val caldavUrl: String = "",
    /** Collection for action items; blank uses [caldavUrl]. */
    val caldavTasksUrl: String = "",
    val caldavUser: String = "",
    val caldavEvent: Boolean = true,
    val caldavTasks: Boolean = true,
    /** Read this calendar to title meetings, know who is invited and prepare for the next one. */
    val calendarLookup: Boolean = true,
    /** Upload straight to Google Docs with the user's Google account (needs an OAuth client). */
    val googleDirectUpload: Boolean = false,
)

/** Fully resolved settings for one provider, including the key. */
data class ProviderConfig(
    val id: ProviderId,
    val apiKey: String,
    val baseUrl: String,
    val chatModel: String,
    val sttModel: String,
    val zeroDataRetention: Boolean = false,
    val ttsModel: String = "",
    val voiceId: String = "",
    val reasoningEffort: String = "",
    /** Extra headers sent with every request, e.g. Command Code's ZDR flag. */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    override fun toString(): String =
        "ProviderConfig(id=$id, baseUrl=$baseUrl, chatModel=$chatModel, sttModel=$sttModel, zdr=$zeroDataRetention, " +
            "apiKey=${if (apiKey.isBlank()) "<none>" else "<redacted>"})"
}

/** Supplies API keys, which the Android app keeps encrypted with the Android Keystore. */
fun interface SecretProvider {
    fun apiKey(id: ProviderId): String?
}

/** The microphone meetings are recorded with; AUTO lets Android choose. */
@Serializable
enum class Microphone(val label: String) {
    AUTO("Automatic"),
    PHONE("Phone"),
    WIRED("Wired headset"),
    USB("USB microphone"),
    BLUETOOTH("Bluetooth headset"),
}
