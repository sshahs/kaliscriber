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
    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?): SttResult {
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
    override val diarizationAvailable = true
    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?): SttResult {
        files += audio
        return respond(audio)
    }
    override suspend fun diarize(audio: File): List<SpeakerTurn> {
        diarizeCalls++
        return turns
    }
}
