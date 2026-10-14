package com.kaliscriber.core.ai.stt

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.bearer
import com.kaliscriber.core.ai.executeForJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * `/audio/transcriptions` as offered by OpenAI, Groq, Mistral and compatible servers.
 *
 * - `*diarize*` models (e.g. gpt-4o-transcribe-diarize) return `diarized_json` with speaker labels.
 * - `gpt-4o-*transcribe` models only return plain `json` text.
 * - Whisper-style models return `verbose_json` with timed segments.
 */
class OpenAiCompatibleStt(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
) : SttClient {

    override val supportsDiarization: Boolean
        get() = SttClients.supportsDiarization(config.id, config.sttModel)

    internal val responseFormat: String
        get() {
            val model = config.sttModel.lowercase()
            return when {
                supportsDiarization -> "diarized_json"
                model.startsWith("gpt-4o") -> "json"
                config.id == ProviderId.MISTRAL -> "json"
                else -> "verbose_json"
            }
        }

    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?): SttResult {
        if (config.sttModel.isBlank()) throw AiException("No transcription model set for ${config.id.displayName}")
        val format = responseFormat
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(audioMediaType(audio)))
            .addFormDataPart("model", config.sttModel)
            .addFormDataPart("response_format", format)
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language", language)
                when (format) {
                    "diarized_json" -> addFormDataPart("chunking_strategy", "auto")
                    "verbose_json" -> addFormDataPart("timestamp_granularities[]", "segment")
                }
            }
            .build()
        val request = Request.Builder()
            .url("${config.baseUrl}/audio/transcriptions")
            .bearer(config.apiKey)
            .post(body)
            .build()
        return parse(http.executeForJson(request, config.id), durationHintMs)
    }

    internal fun parse(json: JsonObject, durationHintMs: Long?): SttResult {
        val segments = json["segments"]?.jsonArray.orEmpty().filterIsInstance<JsonObject>().mapNotNull { seg ->
            val text = seg["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (text.isEmpty()) return@mapNotNull null
            RawSegment(
                startMs = secondsToMs(seg["start"]?.jsonPrimitive?.doubleOrNull),
                endMs = secondsToMs(seg["end"]?.jsonPrimitive?.doubleOrNull),
                text = text,
                speakerLabel = seg["speaker"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            )
        }
        if (segments.isNotEmpty()) {
            return SttResult(segments, diarized = segments.any { it.speakerLabel != null })
        }
        val text = json["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return SttResult(emptyList(), diarized = false)
        val end = durationHintMs ?: secondsToMs(json["duration"]?.jsonPrimitive?.doubleOrNull)
        return SttResult(listOf(RawSegment(0, end, text)), diarized = false)
    }
}
