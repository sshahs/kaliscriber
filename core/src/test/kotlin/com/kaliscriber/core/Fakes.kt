package com.kaliscriber.core

import com.kaliscriber.core.ai.AiServices
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.llm.LlmClient
import com.kaliscriber.core.ai.llm.LlmRequest
import com.kaliscriber.core.ai.stt.SpeakerDiarizer
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.ai.stt.SttClient
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.ai.stt.VoiceEmbedder
import java.io.File

class FakeLlm(private val respond: (LlmRequest) -> String) : LlmClient {
    val requests = mutableListOf<LlmRequest>()
    override val config = AppSettings().configFor(ProviderId.OPENAI, "k")
    override suspend fun complete(request: LlmRequest): String {
        requests += request
        return respond(request)
    }
    override suspend fun listModels() = listOf("fake")
}

class FakeStt(
    override val supportsDiarization: Boolean,
    private val respond: (File) -> SttResult,
) : SttClient {
    val files = mutableListOf<File>()
    override val config = AppSettings().configFor(ProviderId.OPENAI, "k")
    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?, speakers: Int?): SttResult {
        files += audio
        return respond(audio)
    }
}

class FakeAi(var llm: LlmClient, var sttClient: SttClient) : AiServices {
    override fun chat() = llm
    override fun stt() = sttClient
}

/** Stands in for the on-device engine: transcribes without labels, diarizes separately. */
class FakeLocalStt(
    private val turns: List<SpeakerTurn>,
    private val respond: (File) -> SttResult,
) : SttClient, SpeakerDiarizer {
    var diarizeCalls = 0
    val files = mutableListOf<File>()
    override val config = AppSettings().configFor(ProviderId.LOCAL, null)
    override val supportsDiarization = false
    override var diarizationAvailable = true
    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?, speakers: Int?): SttResult {
        files += audio
        return respond(audio)
    }
    var lastSpeakerCount: Int? = null
    override suspend fun diarize(audio: File, speakers: Int?, onProgress: (Float) -> Unit): List<SpeakerTurn> {
        diarizeCalls++
        lastSpeakerCount = speakers
        onProgress(0.5f)
        onProgress(1f)
        return turns
    }
}

/** Fake voiceprints: each speaker's "voice" is decided by the text they spoke. */
class FakeEmbedder(private val voiceOf: (String) -> FloatArray) : VoiceEmbedder {
    var calls = 0
    lateinit var segmentsText: (String) -> String
    override val voiceEmbeddingAvailable = true
    override suspend fun embed(audio: File, spans: Map<String, List<LongRange>>): Map<String, FloatArray> {
        calls++
        return spans.mapValues { (speakerId, _) -> voiceOf(segmentsText(speakerId)) }
    }
}
