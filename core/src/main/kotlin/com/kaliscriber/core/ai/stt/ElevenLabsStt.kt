package com.kaliscriber.core.ai.stt

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
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
 * ElevenLabs Scribe (`/v1/speech-to-text`). Scribe returns word-level timings with a speaker id per
 * word; consecutive words by the same speaker are merged into transcript segments.
 */
class ElevenLabsStt(
    override val config: ProviderConfig,
    private val http: OkHttpClient,
) : SttClient {

    override val supportsDiarization: Boolean = true

    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?, speakers: Int?): SttResult {
        if (config.apiKey.isBlank()) throw AiException("Add your ElevenLabs API key in Settings.")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", audio.name, audio.asRequestBody(audioMediaType(audio)))
            .addFormDataPart("model_id", config.sttModel.ifBlank { "scribe_v2" })
            .addFormDataPart("diarize", "true")
            .addFormDataPart("timestamps_granularity", "word")
            .addFormDataPart("tag_audio_events", "false")
            .apply {
                if (!language.isNullOrBlank()) addFormDataPart("language_code", language)
                if (speakers != null) addFormDataPart("num_speakers", speakers.toString())
            }
            .build()
        val request = Request.Builder()
            .url("${config.baseUrl}/v1/speech-to-text")
            .header("xi-api-key", config.apiKey)
            .post(body)
            .build()
        return parse(http.executeForJson(request, config.id), durationHintMs)
    }

    internal fun parse(json: JsonObject, durationHintMs: Long?): SttResult {
        val words = json["words"]?.jsonArray.orEmpty().filterIsInstance<JsonObject>()
        val segments = mutableListOf<RawSegment>()
        val text = StringBuilder()
        var speaker: String? = null
        var start = 0L
        var end = 0L

        fun flush() {
            val t = text.toString().trim()
            if (t.isNotEmpty()) segments += RawSegment(start, maxOf(end, start), t, speaker)
            text.clear()
        }

        for (word in words) {
            val type = word["type"]?.jsonPrimitive?.contentOrNull ?: "word"
            val value = word["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (type == "audio_event") continue
            if (type == "spacing") {
                if (text.isNotEmpty()) text.append(value.ifEmpty { " " })
                continue
            }
            val wordStart = secondsToMs(word["start"]?.jsonPrimitive?.doubleOrNull)
            val wordEnd = secondsToMs(word["end"]?.jsonPrimitive?.doubleOrNull)
            val wordSpeaker = word["speaker_id"]?.jsonPrimitive?.contentOrNull
            val pause = text.isNotEmpty() && wordStart - end > PAUSE_SPLIT_MS
            val tooLong = text.isNotEmpty() && wordEnd - start > MAX_SEGMENT_MS && text.endsWithSentence()
            if (text.isNotEmpty() && (wordSpeaker != speaker || pause || tooLong)) flush()
            if (text.isEmpty()) {
                start = wordStart
                speaker = wordSpeaker
            }
            text.append(value)
            end = wordEnd
        }
        flush()

        if (segments.isNotEmpty()) return SttResult(segments, diarized = segments.any { it.speakerLabel != null })
        val plain = json["text"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (plain.isEmpty()) return SttResult(emptyList(), diarized = false)
        return SttResult(listOf(RawSegment(0, durationHintMs ?: 0, plain)), diarized = false)
    }

    private fun StringBuilder.endsWithSentence(): Boolean = trimEnd().lastOrNull()?.let { it in ".?!" } == true

    private companion object {
        const val PAUSE_SPLIT_MS = 1_500L
        const val MAX_SEGMENT_MS = 20_000L
    }
}
