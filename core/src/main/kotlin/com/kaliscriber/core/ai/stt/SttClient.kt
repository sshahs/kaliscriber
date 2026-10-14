package com.kaliscriber.core.ai.stt

import com.kaliscriber.core.ai.ApiKind
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.ProviderId
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.io.File

/** A transcribed span with times relative to the start of the uploaded audio. */
data class RawSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Provider speaker label, or null when the provider could not tell. */
    val speakerLabel: String? = null,
    val confidence: Float? = null,
)

data class SttResult(
    val segments: List<RawSegment>,
    /** True when the provider performed speaker diarization. */
    val diarized: Boolean,
)

interface SttClient {
    val config: ProviderConfig

    /** Whether this provider/model labels speakers. */
    val supportsDiarization: Boolean

    val maxUploadBytes: Long get() = config.id.maxUploadBytes

    suspend fun transcribe(audio: File, language: String? = null, durationHintMs: Long? = null): SttResult
}

object SttClients {
    fun create(config: ProviderConfig, http: OkHttpClient): SttClient = when (config.id.kind) {
        ApiKind.OPENAI_COMPATIBLE -> OpenAiCompatibleStt(config, http)
        ApiKind.DEEPGRAM -> DeepgramStt(config, http)
        ApiKind.ASSEMBLYAI -> AssemblyAiStt(config, http)
        ApiKind.ANTHROPIC, ApiKind.OLLAMA ->
            throw IllegalArgumentException("${config.id.displayName} does not provide speech-to-text")
    }

    fun supportsDiarization(id: ProviderId, model: String): Boolean = when (id.kind) {
        ApiKind.DEEPGRAM, ApiKind.ASSEMBLYAI -> true
        ApiKind.OPENAI_COMPATIBLE -> model.contains("diariz", ignoreCase = true)
        else -> false
    }
}

internal fun audioMediaType(file: File): MediaType = when (file.extension.lowercase()) {
    "wav" -> "audio/wav"
    "m4a", "mp4" -> "audio/mp4"
    "mp3", "mpga", "mpeg" -> "audio/mpeg"
    "ogg", "oga" -> "audio/ogg"
    "webm" -> "audio/webm"
    "flac" -> "audio/flac"
    "aac" -> "audio/aac"
    "amr" -> "audio/amr"
    "3gp" -> "audio/3gpp"
    else -> "application/octet-stream"
}.toMediaType()

internal fun secondsToMs(value: Double?): Long = ((value ?: 0.0) * 1000).toLong()
