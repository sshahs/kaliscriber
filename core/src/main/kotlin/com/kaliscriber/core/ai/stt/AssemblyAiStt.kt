package com.kaliscriber.core.ai.stt

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.toBody
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/** AssemblyAI: upload, create a transcript job with speaker labels, then poll until done. */
class AssemblyAiStt(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
    private val pollIntervalMs: Long = 3_000,
    private val maxPolls: Int = 1_200,
) : SttClient {

    override val supportsDiarization: Boolean = true

    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?): SttResult {
        val upload = http.executeForJson(
            request("/v2/upload").post(audio.asRequestBody(audioMediaType(audio))).build(),
            config.id,
        )
        val uploadUrl = upload["upload_url"]?.jsonPrimitive?.contentOrNull
            ?: throw AiException("AssemblyAI upload failed")

        val job = http.executeForJson(
            request("/v2/transcript").post(buildJsonObject {
                put("audio_url", uploadUrl)
                put("speaker_labels", true)
                if (config.sttModel.isNotBlank()) put("speech_model", config.sttModel)
                if (language.isNullOrBlank()) put("language_detection", true) else put("language_code", language)
            }.toBody()).build(),
            config.id,
        )
        val id = job["id"]?.jsonPrimitive?.contentOrNull ?: throw AiException("AssemblyAI did not return a job id")

        repeat(maxPolls) {
            val status = http.executeForJson(request("/v2/transcript/$id").get().build(), config.id)
            when (status["status"]?.jsonPrimitive?.contentOrNull) {
                "completed" -> return parse(status, durationHintMs)
                "error" -> throw AiException(
                    "AssemblyAI error: " + (status["error"]?.jsonPrimitive?.contentOrNull ?: "unknown"),
                )
            }
            delay(pollIntervalMs)
        }
        throw AiException("AssemblyAI transcription timed out")
    }

    internal fun parse(json: JsonObject, durationHintMs: Long?): SttResult {
        val utterances = json["utterances"]?.takeIf { it is kotlinx.serialization.json.JsonArray }?.jsonArray
            .orEmpty().filterIsInstance<JsonObject>()
        if (utterances.isNotEmpty()) {
            val segments = utterances.mapNotNull { u ->
                val text = u["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                RawSegment(
                    startMs = u["start"]?.jsonPrimitive?.longOrNull ?: 0,
                    endMs = u["end"]?.jsonPrimitive?.longOrNull ?: 0,
                    text = text,
                    speakerLabel = u["speaker"]?.jsonPrimitive?.contentOrNull,
                    confidence = u["confidence"]?.jsonPrimitive?.floatOrNull,
                )
            }
            return SttResult(segments, diarized = segments.any { it.speakerLabel != null })
        }
        val text = json["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (text.isEmpty()) return SttResult(emptyList(), diarized = false)
        return SttResult(listOf(RawSegment(0, durationHintMs ?: 0, text)), diarized = false)
    }

    private fun request(path: String) = Request.Builder()
        .url(config.baseUrl + path)
        .header("Authorization", config.apiKey)
}
