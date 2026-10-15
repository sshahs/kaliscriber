package com.kaliscriber.app.speech

/** What a downloadable on-device model is used for. */
enum class ModelRole { ASR, VAD, SEGMENTATION, EMBEDDING, SEARCH }

enum class AsrFamily { WHISPER, PARAKEET, SENSE_VOICE }

data class LocalModel(
    val id: String,
    val name: String,
    val description: String,
    val role: ModelRole,
    val url: String,
    val downloadMb: Int,
    val languages: String = "",
    val family: AsrFamily? = null,
    /** Whisper models with a fixed language ("*.en"). */
    val englishOnly: Boolean = false,
    /** Small files downloaded next to the main one (e.g. a tokenizer and config). */
    val extraUrls: List<String> = emptyList(),
) {
    val isArchive: Boolean get() = url.endsWith(".tar.bz2")
}

/** Pretrained models published by the sherpa-onnx project (https://github.com/k2-fsa/sherpa-onnx/releases). */
object ModelCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download"

    val asrModels = listOf(
        LocalModel(
            "parakeet-v3", "Parakeet TDT 0.6B v3",
            "Most accurate for English and 24 other European languages, with punctuation. Recommended for meetings on recent phones.",
            ModelRole.ASR, "$BASE/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2", 465,
            languages = "25 European languages", family = AsrFamily.PARAKEET,
        ),
        LocalModel(
            "parakeet-v2", "Parakeet TDT 0.6B v2 (English)",
            "Excellent English accuracy with punctuation and capitalisation.",
            ModelRole.ASR, "$BASE/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2", 460,
            languages = "English", family = AsrFamily.PARAKEET,
        ),
        LocalModel(
            "whisper-base", "Whisper Base",
            "Good all-rounder for 99 languages. Balanced speed and accuracy.",
            ModelRole.ASR, "$BASE/asr-models/sherpa-onnx-whisper-base.tar.bz2", 198,
            languages = "99 languages", family = AsrFamily.WHISPER,
        ),
        LocalModel(
            "whisper-tiny", "Whisper Tiny",
            "Smallest and fastest, for older phones. Less accurate.",
            ModelRole.ASR, "$BASE/asr-models/sherpa-onnx-whisper-tiny.tar.bz2", 111,
            languages = "99 languages", family = AsrFamily.WHISPER,
        ),
        LocalModel(
            "whisper-small", "Whisper Small",
            "Most accurate Whisper that is practical on a phone. Large and slow on older devices.",
            ModelRole.ASR, "$BASE/asr-models/sherpa-onnx-whisper-small.tar.bz2", 610,
            languages = "99 languages", family = AsrFamily.WHISPER,
        ),
        LocalModel(
            "sense-voice", "SenseVoice",
            "Fast and accurate for Chinese, Cantonese, English, Japanese and Korean.",
            ModelRole.ASR, "$BASE/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2", 155,
            languages = "zh, yue, en, ja, ko", family = AsrFamily.SENSE_VOICE,
        ),
    )

    /** Silero voice activity detection: splits audio into utterances before recognition. */
    val vad = LocalModel(
        "silero-vad", "Silero VAD", "Finds where people are speaking.",
        ModelRole.VAD, "$BASE/asr-models/silero_vad.onnx", 1,
    )

    val segmentation = LocalModel(
        "pyannote-segmentation-3", "Pyannote segmentation 3.0", "Detects speaker changes.",
        ModelRole.SEGMENTATION, "$BASE/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2", 7,
    )

    /** NeMo TitaNet small: as accurate as ERes2Net at telling voices apart, and about a third faster. */
    val embedding = LocalModel(
        "titanet-small-speaker", "NeMo TitaNet small", "Recognises the same voice across the meeting.",
        ModelRole.EMBEDDING, "$BASE/speaker-recongition-models/nemo_en_titanet_small.onnx", 40,
    )

    /** Models the app used before, deleted from the phone to free the space. */
    val retired = listOf("eres2net-speaker")

    val diarization = listOf(segmentation, embedding)

    private const val POTION = "https://huggingface.co/minishlab/potion-base-8M/resolve/main"

    /** Model2Vec potion-base-8M: finds meeting passages by meaning for Ask all meetings (MIT licence). */
    val searchModel = LocalModel(
        "potion-base-8m", "Smart search (potion-base-8M)",
        "Finds passages by meaning, not just matching words, when you ask across all meetings. English.",
        ModelRole.SEARCH, "$POTION/model.safetensors", 30,
        extraUrls = listOf("$POTION/tokenizer.json", "$POTION/config.json"),
    )

    val all: List<LocalModel> = asrModels + vad + diarization + searchModel

    fun byId(id: String): LocalModel? = all.firstOrNull { it.id == id }

    val recommended: LocalModel get() = asrModels.first()
}
