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

    /** [speakers] is the expected number of speakers, when the user knows it. */
    suspend fun transcribe(audio: File, language: String? = null, durationHintMs: Long? = null, speakers: Int? = null): SttResult
}

object SttClients {
    fun create(config: ProviderConfig, http: OkHttpClient): SttClient = when (config.id.kind) {
        ApiKind.OPENAI_COMPATIBLE -> OpenAiCompatibleStt(config, http)
        ApiKind.DEEPGRAM -> DeepgramStt(config, http)
        ApiKind.ASSEMBLYAI -> AssemblyAiStt(config, http)
        ApiKind.ELEVENLABS -> ElevenLabsStt(config, http)
        ApiKind.LOCAL -> throw IllegalArgumentException("On-device speech-to-text is provided by the app")
        ApiKind.ANTHROPIC, ApiKind.OLLAMA, ApiKind.COMMAND_CODE ->
            throw IllegalArgumentException("${config.id.displayName} does not provide speech-to-text")
    }

    fun supportsDiarization(id: ProviderId, model: String): Boolean = when (id.kind) {
        ApiKind.DEEPGRAM, ApiKind.ASSEMBLYAI, ApiKind.ELEVENLABS -> true
        ApiKind.OPENAI_COMPATIBLE -> model.contains("diariz", ignoreCase = true)
        else -> false
    }
}

/** A stretch of audio attributed to one speaker, from a diarization pass. */
data class SpeakerTurn(val startMs: Long, val endMs: Long, val speaker: String)

/**
 * Implemented by speech engines (the on-device one) that can tell speakers apart separately from
 * transcription. The pipeline then labels the existing transcript instead of re-transcribing.
 */
interface SpeakerDiarizer {
    val diarizationAvailable: Boolean
    /** [onProgress] reports 0..1 while the (possibly long) pass runs. */
    suspend fun diarize(audio: File, speakers: Int? = null, onProgress: (Float) -> Unit = {}): List<SpeakerTurn>
}

/**
 * Computes voiceprints (speaker embeddings) so the app can recognise the same person across meetings.
 * [spans] maps a speaker id to the time ranges (ms) where that speaker talks.
 */
interface VoiceEmbedder {
    val voiceEmbeddingAvailable: Boolean
    suspend fun embed(audio: File, spans: Map<String, List<LongRange>>): Map<String, FloatArray>
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
