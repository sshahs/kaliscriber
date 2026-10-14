package com.kaliscriber.core.ai.tts

import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.executeForBytes
import com.kaliscriber.core.ai.executeForJson
import com.kaliscriber.core.ai.toBody
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class Voice(val id: String, val name: String)

/** ElevenLabs text-to-speech. Returns MP3; long texts are split and the MP3 parts concatenated. */
class ElevenLabsTts(
    val config: ProviderConfig,
    private val http: OkHttpClient,
) {
    suspend fun synthesize(text: String): ByteArray {
        if (config.apiKey.isBlank()) throw AiException("Add your ElevenLabs API key in Settings.")
        val parts = SpeechText.split(text, MAX_CHARS)
        if (parts.isEmpty()) throw AiException("Nothing to read aloud.")
        val out = java.io.ByteArrayOutputStream()
        for (part in parts) out.write(synthesizePart(part))
        return out.toByteArray()
    }

    private suspend fun synthesizePart(text: String): ByteArray {
        val voice = config.voiceId.ifBlank { com.kaliscriber.core.ai.ProviderId.DEFAULT_ELEVENLABS_VOICE }
        val url = "${config.baseUrl}/v1/text-to-speech/$voice".toHttpUrl().newBuilder()
            .addQueryParameter("output_format", "mp3_44100_128")
            .build()
        val body = buildJsonObject {
            put("text", text)
            put("model_id", config.ttsModel.ifBlank { com.kaliscriber.core.ai.ProviderId.DEFAULT_ELEVENLABS_TTS_MODEL })
        }
        val request = Request.Builder()
            .url(url)
            .header("xi-api-key", config.apiKey)
            .header("Accept", "audio/mpeg")
            .post(body.toBody())
            .build()
        return http.executeForBytes(request, config.id)
    }

    suspend fun listVoices(): List<Voice> {
        val request = Request.Builder()
            .url("${config.baseUrl}/v1/voices")
            .header("xi-api-key", config.apiKey)
            .get()
            .build()
        return http.executeForJson(request, config.id)["voices"]?.jsonArray.orEmpty()
            .filterIsInstance<JsonObject>()
            .mapNotNull { v ->
                val id = v["voice_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                Voice(id, v["name"]?.jsonPrimitive?.contentOrNull ?: id)
            }
    }

    companion object {
        const val MAX_CHARS = 2_500
    }
}
