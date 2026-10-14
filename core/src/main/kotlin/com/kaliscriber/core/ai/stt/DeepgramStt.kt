package com.kaliscriber.core.ai.stt

import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.executeForJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** Deepgram pre-recorded transcription with diarization and utterances. */
class DeepgramStt(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
) : SttClient {

    override val supportsDiarization: Boolean = true

    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?): SttResult {
        val url = "${config.baseUrl}/v1/listen".toHttpUrl().newBuilder()
            .addQueryParameter("model", config.sttModel.ifBlank { "nova-3" })
            .addQueryParameter("diarize", "true")
            .addQueryParameter("utterances", "true")
            .addQueryParameter("smart_format", "true")
            .addQueryParameter("punctuate", "true")
            .apply {
                if (language.isNullOrBlank()) addQueryParameter("detect_language", "true")
                else addQueryParameter("language", language)
            }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token ${config.apiKey}")
            .post(audio.asRequestBody(audioMediaType(audio)))
            .build()
        return parse(http.executeForJson(request, config.id), durationHintMs)
    }

    internal fun parse(json: JsonObject, durationHintMs: Long?): SttResult {
        val results = json["results"]?.jsonObject
        val utterances = results?.get("utterances")?.jsonArray.orEmpty().filterIsInstance<JsonObject>()
        if (utterances.isNotEmpty()) {
            val segments = utterances.mapNotNull { u ->
                val text = u["transcript"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                RawSegment(
                    startMs = secondsToMs(u["start"]?.jsonPrimitive?.doubleOrNull),
                    endMs = secondsToMs(u["end"]?.jsonPrimitive?.doubleOrNull),
                    text = text,
                    speakerLabel = u["speaker"]?.jsonPrimitive?.contentOrNull,
                    confidence = u["confidence"]?.jsonPrimitive?.floatOrNull,
                )
            }
            return SttResult(segments, diarized = segments.any { it.speakerLabel != null })
        }
        val transcript = results?.get("channels")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("transcript")?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (transcript.isEmpty()) return SttResult(emptyList(), diarized = false)
        return SttResult(listOf(RawSegment(0, durationHintMs ?: 0, transcript)), diarized = false)
    }
}
