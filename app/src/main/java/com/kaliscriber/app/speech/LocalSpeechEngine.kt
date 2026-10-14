package com.kaliscriber.app.speech

import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.ProviderConfig
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.ai.stt.RawSegment
import com.kaliscriber.core.ai.stt.SpeakerDiarizer
import com.kaliscriber.core.ai.stt.SpeakerTurn
import com.kaliscriber.core.ai.stt.SttClient
import com.kaliscriber.core.ai.stt.SttResult
import com.kaliscriber.core.ai.stt.VoiceEmbedder
import com.kaliscriber.core.model.WordTiming
import com.kaliscriber.core.speakers.SpeakerClusters
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Speech-to-text and speaker diarization that run entirely on the phone with sherpa-onnx.
 *
 * Transcription: audio is decoded to 16 kHz, split into utterances by Silero VAD, and each utterance
 * is recognized offline, which gives naturally timed transcript segments.
 * Diarization: when a meeting ends, pyannote segmentation plus speaker embeddings cluster the voices
 * of the whole recording; the pipeline then labels the live transcript with those turns.
 */
class LocalSpeechEngine(
    private val models: ModelManager,
    private val settings: () -> AppSettings,
) : SttClient, SpeakerDiarizer, VoiceEmbedder {

    override val config: ProviderConfig get() = settings().configFor(ProviderId.LOCAL, null)
    override val supportsDiarization: Boolean = false
    override val maxUploadBytes: Long = Long.MAX_VALUE
    override val diarizationAvailable: Boolean get() = models.diarizationReady
    override val voiceEmbeddingAvailable: Boolean get() = models.isReady(ModelCatalog.embedding)

    // Native objects are not thread-safe; one recognition at a time.
    private val lock = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var recognizerKey: String? = null
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)

    fun selectedModel(): LocalModel? {
        val chosen = ModelCatalog.asrModels.firstOrNull { it.id == settings().localAsrModel }
        return chosen?.takeIf(models::isReady) ?: models.readyAsrModels().firstOrNull()
    }

    override suspend fun transcribe(audio: File, language: String?, durationHintMs: Long?, speakers: Int?): SttResult =
        withContext(Dispatchers.Default) {
            lock.withLock {
                val model = selectedModel()
                    ?: throw AiException("Download an on-device speech model in Settings → On-device speech first.")
                val asr = ensureRecognizer(model, language)
                val segments = mutableListOf<RawSegment>()

                fun recognize(startSample: Long, samples: FloatArray) {
                    if (samples.size < AudioDecoder.SAMPLE_RATE / 4) return
                    val stream = asr.createStream()
                    try {
                        stream.acceptWaveform(samples, AudioDecoder.SAMPLE_RATE)
                        asr.decode(stream)
                        val result = asr.getResult(stream)
                        val text = clean(result.text)
                        if (text.isNotEmpty()) {
                            val startMs = startSample * 1000 / AudioDecoder.SAMPLE_RATE
                            val words = wordTimings(result.tokens, result.timestamps, startMs)?.takeIf { w ->
                                // Only keep timings that spell out exactly the text we show.
                                w.joinToString("") { it.text }.trim() == text
                            }
                            segments += RawSegment(startMs, startMs + samples.size * 1000L / AudioDecoder.SAMPLE_RATE, text, words = words)
                        }
                    } finally {
                        stream.release()
                    }
                }

                val started = SystemClock.elapsedRealtime()
                if (models.isReady(ModelCatalog.vad)) {
                    transcribeWithVad(audio, ::recognize)
                } else {
                    transcribeInWindows(audio, ::recognize)
                }
                val audioMs = segments.lastOrNull()?.endMs ?: 0
                Log.i(TAG, "${model.id}: transcribed ${audio.name} (${audioMs / 1000}s of speech) in ${SystemClock.elapsedRealtime() - started} ms")
                SttResult(segments, diarized = false)
            }
        }

    /** Feeds the decoder output through Silero VAD and recognizes each detected utterance. */
    private fun transcribeWithVad(audio: File, recognize: (Long, FloatArray) -> Unit) {
        val vad = Vad(
            null,
            VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = models.file(ModelCatalog.vad, "silero_vad.onnx").absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.4f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                    // Whisper handles at most 30 s per call; long monologues are split.
                    maxSpeechDuration = 20f,
                ),
                sampleRate = AudioDecoder.SAMPLE_RATE,
                numThreads = 1,
            ),
        )
        try {
            val pending = FloatArray(VAD_WINDOW)
            var filled = 0
            fun drain() {
                while (!vad.empty()) {
                    val segment = vad.front()
                    recognize(segment.start.toLong(), segment.samples)
                    vad.pop()
                }
            }
            AudioDecoder.decode(audio) { block ->
                var i = 0
                while (i < block.size) {
                    val n = minOf(VAD_WINDOW - filled, block.size - i)
                    System.arraycopy(block, i, pending, filled, n)
                    filled += n
                    i += n
                    if (filled == VAD_WINDOW) {
                        vad.acceptWaveform(pending.copyOf())
                        filled = 0
                        drain()
                    }
                }
            }
            if (filled > 0) vad.acceptWaveform(pending.copyOf(filled))
            vad.flush()
            drain()
        } finally {
            vad.release()
        }
    }

    /** Fallback without the VAD model: fixed 20 s windows. */
    private fun transcribeInWindows(audio: File, recognize: (Long, FloatArray) -> Unit) {
        val window = AudioDecoder.SAMPLE_RATE * 20
        val buffer = FloatArray(window)
        var filled = 0
        var start = 0L
        AudioDecoder.decode(audio) { block ->
            var i = 0
            while (i < block.size) {
                val n = minOf(window - filled, block.size - i)
                System.arraycopy(block, i, buffer, filled, n)
                filled += n
                i += n
                if (filled == window) {
                    recognize(start, buffer.copyOf())
                    start += window
                    filled = 0
                }
            }
        }
        if (filled > 0) recognize(start, buffer.copyOf(filled))
    }

    /**
     * Groups recognizer tokens into words with start times. Subword tokens mark a new word with a
     * leading space (or "▁"); CJK characters are words of their own. Null when the model gives no timestamps.
     */
    private fun wordTimings(tokens: Array<String>, timestamps: FloatArray, offsetMs: Long): List<WordTiming>? {
        if (tokens.isEmpty() || tokens.size != timestamps.size) return null
        val words = mutableListOf<WordTiming>()
        val current = StringBuilder()
        var currentStart = 0L
        tokens.forEachIndexed { i, raw ->
            val token = raw.replace('\u2581', ' ')
            if (token.isEmpty()) return@forEachIndexed
            val startsWord = current.isEmpty() || token.first().isWhitespace() || isCjk(token.first()) ||
                current.lastOrNull()?.let(::isCjk) == true
            if (startsWord && current.isNotEmpty()) {
                words += WordTiming(currentStart, current.toString())
                current.clear()
            }
            if (current.isEmpty()) currentStart = offsetMs + (timestamps[i] * 1000).toLong()
            current.append(token)
        }
        if (current.isNotEmpty()) words += WordTiming(currentStart, current.toString())
        return words.takeIf { it.isNotEmpty() }
    }

    private fun isCjk(c: Char): Boolean = Character.UnicodeScript.of(c.code).let {
        it == Character.UnicodeScript.HAN || it == Character.UnicodeScript.HIRAGANA ||
            it == Character.UnicodeScript.KATAKANA || it == Character.UnicodeScript.HANGUL
    }

    private fun ensureRecognizer(model: LocalModel, language: String?): OfflineRecognizer {
        val lang = language?.trim()?.lowercase().orEmpty()
        val key = "${model.id}|$lang"
        recognizer?.let { if (recognizerKey == key) return it }
        recognizer?.release()
        recognizer = null
        val modelConfig = when (model.family) {
            AsrFamily.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = models.file(model, "encoder.int8.onnx").absolutePath,
                    decoder = models.file(model, "decoder.int8.onnx").absolutePath,
                    // Empty lets multilingual Whisper detect the language itself.
                    language = if (model.englishOnly) "en" else lang,
                    task = "transcribe",
                ),
                tokens = models.file(model, "tokens.txt").absolutePath,
                modelType = "whisper",
                numThreads = threads,
            )
            AsrFamily.PARAKEET -> OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.file(model, "encoder.int8.onnx").absolutePath,
                    decoder = models.file(model, "decoder.int8.onnx").absolutePath,
                    joiner = models.file(model, "joiner.int8.onnx").absolutePath,
                ),
                tokens = models.file(model, "tokens.txt").absolutePath,
                modelType = "nemo_transducer",
                numThreads = threads,
            )
            AsrFamily.SENSE_VOICE -> OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = models.file(model, "model.int8.onnx").absolutePath,
                    language = lang,
                    useInverseTextNormalization = true,
                ),
                tokens = models.file(model, "tokens.txt").absolutePath,
                numThreads = threads,
            )
            null -> throw AiException("${model.name} is not a speech recognition model")
        }
        val created = try {
            OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = modelConfig))
        } catch (e: Exception) {
            throw AiException("Could not load ${model.name}: ${e.message}. Try deleting and downloading it again.", cause = e)
        }
        recognizer = created
        recognizerKey = key
        return created
    }

    /**
     * Diarizes the recording on the phone in blocks of up to [BLOCK_SECONDS], so only one block of
     * audio is in memory at a time. Each block's speakers get a voiceprint, and the voiceprints are
     * then merged across the meeting: this matches people between blocks and also rejoins one
     * person the diarizer split in two. A known speaker count is applied in that merge.
     */
    override suspend fun diarize(audio: File, speakers: Int?, onProgress: (Float) -> Unit): List<SpeakerTurn> = withContext(Dispatchers.Default) {
        lock.withLock {
            if (!models.diarizationReady) return@withLock emptyList()
            val rate = AudioDecoder.SAMPLE_RATE
            val totalSamples = AudioDecoder.estimateSamples(audio)
            val blocks = totalSamples?.let { ((it + BLOCK_SECONDS * rate - 1) / (BLOCK_SECONDS * rate)).toInt().coerceAtLeast(1) } ?: 1
            val blockSamples = totalSamples?.let { ((it + blocks - 1) / blocks).toInt() + rate } ?: (BLOCK_SECONDS * rate)

            val embeddingConfig = SpeakerEmbeddingExtractorConfig(
                model = models.file(ModelCatalog.embedding, ".onnx").absolutePath,
                numThreads = threads,
            )
            val diarizer = OfflineSpeakerDiarization(
                null,
                OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(
                            model = models.file(ModelCatalog.segmentation, "model.int8.onnx").absolutePath,
                        ),
                        numThreads = threads,
                    ),
                    embedding = embeddingConfig,
                    // Threshold clustering per block; the speaker count is applied when voiceprints are merged.
                    clustering = FastClusteringConfig(numClusters = -1, threshold = 0.5f),
                    minDurationOn = 0.3f,
                    minDurationOff = 0.5f,
                ),
            )
            val extractor = SpeakerEmbeddingExtractor(null, embeddingConfig)
            val started = SystemClock.elapsedRealtime()
            Log.i(TAG, "Diarizing ${audio.name} in $blocks block(s) (speakers=${speakers ?: "auto"})")
            // Turns with their block and block-local speaker number, and a voiceprint per block speaker.
            val blockTurns = mutableListOf<Triple<Int, Int, SpeakerTurn>>()
            val clusters = mutableListOf<Pair<Pair<Int, Int>, SpeakerClusters.Item>>()
            try {
                var blockIndex = 0
                var offset = 0L

                fun process(samples: FloatArray) {
                    val index = blockIndex
                    val blockStarted = SystemClock.elapsedRealtime()
                    onProgress((index.toFloat() / blocks).coerceAtMost(1f))
                    val segments = diarizer.processWithCallback(
                        samples,
                        callback = DiarizationCallback { processed, total ->
                            if (total > 0) onProgress(((index + processed.toFloat() / total) / blocks).coerceAtMost(1f))
                        },
                    )
                    val offsetMs = offset * 1000 / rate
                    segments.forEach {
                        blockTurns += Triple(index, it.speaker, SpeakerTurn(offsetMs + (it.start * 1000).toLong(), offsetMs + (it.end * 1000).toLong(), ""))
                    }
                    for ((speaker, turns) in segments.groupBy { it.speaker }) {
                        val voice = FloatArrayBuilder()
                        for (t in turns.sortedByDescending { it.end - it.start }) {
                            if (voice.size >= VOICEPRINT_SECONDS * rate) break
                            val from = (t.start * rate).toInt().coerceIn(0, samples.size)
                            val to = (t.end * rate).toInt().coerceIn(from, samples.size)
                            voice.append(samples, from, to)
                        }
                        val print = voiceprint(extractor, voice) ?: continue
                        clusters += (index to speaker) to SpeakerClusters.Item(print, turns.sumOf { (it.end - it.start).toDouble() })
                    }
                    Log.i(
                        TAG,
                        "Block ${index + 1}/$blocks (${samples.size / rate}s): ${segments.map { it.speaker }.distinct().size} speakers, " +
                            "${segments.size} turns in ${SystemClock.elapsedRealtime() - blockStarted} ms",
                    )
                    offset += samples.size
                    blockIndex++
                }

                val buffer = FloatArray(blockSamples)
                var filled = 0
                AudioDecoder.decode(audio) { chunk ->
                    var i = 0
                    while (i < chunk.size) {
                        val n = minOf(buffer.size - filled, chunk.size - i)
                        System.arraycopy(chunk, i, buffer, filled, n)
                        filled += n
                        i += n
                        if (filled == buffer.size) {
                            process(buffer)
                            filled = 0
                        }
                    }
                }
                if (filled > 0) process(buffer.copyOf(filled))
            } catch (e: OutOfMemoryError) {
                throw AiException("This phone ran out of memory detecting speakers in this recording.", cause = e)
            } finally {
                diarizer.release()
                extractor.release()
            }

            // Merge block speakers into meeting-wide speakers by voice.
            val global = if (clusters.isEmpty()) {
                emptyMap()
            } else {
                val ids = SpeakerClusters.merge(clusters.map { it.second }, target = speakers)
                clusters.mapIndexed { i, (key, _) -> key to ids[i] }.toMap()
            }
            val turns = blockTurns.mapNotNull { (block, speaker, turn) ->
                val id = when {
                    // No usable voiceprints at all: keep the diarizer's own labels.
                    global.isEmpty() -> "$block-$speaker"
                    // Too little speech for a voiceprint: its lines go to a neighbouring turn.
                    else -> global[block to speaker]?.toString() ?: return@mapNotNull null
                }
                turn.copy(speaker = id)
            }
            Log.i(TAG, "Diarization found ${turns.map { it.speaker }.distinct().size} speakers in ${SystemClock.elapsedRealtime() - started} ms")
            turns
        }
    }

    private fun voiceprint(extractor: SpeakerEmbeddingExtractor, voice: FloatArrayBuilder): FloatArray? {
        if (voice.size < AudioDecoder.SAMPLE_RATE) return null
        val stream = extractor.createStream()
        return try {
            stream.acceptWaveform(voice.toArray(), AudioDecoder.SAMPLE_RATE)
            stream.inputFinished()
            if (extractor.isReady(stream)) extractor.compute(stream) else null
        } finally {
            stream.release()
        }
    }

    /**
     * Voiceprints for each speaker from the given time spans. Only those stretches of audio are kept
     * in memory while the file is decoded.
     */
    override suspend fun embed(audio: File, spans: Map<String, List<LongRange>>): Map<String, FloatArray> =
        withContext(Dispatchers.Default) {
            lock.withLock {
                if (!voiceEmbeddingAvailable || spans.isEmpty()) return@withLock emptyMap()
                val rate = AudioDecoder.SAMPLE_RATE.toLong()
                // (startSample, endSample, speakerId), sorted, so the decoder can be streamed once.
                val ranges = spans.flatMap { (speaker, list) ->
                    list.map { Triple(it.first * rate / 1000, it.last * rate / 1000, speaker) }
                }.sortedBy { it.first }
                val collected = spans.keys.associateWith { FloatArrayBuilder() }
                var offset = 0L
                AudioDecoder.decode(audio) { block ->
                    val blockEnd = offset + block.size
                    for ((start, end, speaker) in ranges) {
                        if (start >= blockEnd) break
                        if (end <= offset) continue
                        val from = (maxOf(start, offset) - offset).toInt()
                        val to = (minOf(end, blockEnd) - offset).toInt()
                        collected.getValue(speaker).append(block, from, to)
                    }
                    offset = blockEnd
                }
                val extractor = SpeakerEmbeddingExtractor(
                    null,
                    SpeakerEmbeddingExtractorConfig(
                        model = models.file(ModelCatalog.embedding, ".onnx").absolutePath,
                        numThreads = threads,
                    ),
                )
                try {
                    collected.mapNotNull { (speaker, samples) ->
                        if (samples.size < AudioDecoder.SAMPLE_RATE) return@mapNotNull null
                        val stream = extractor.createStream()
                        try {
                            stream.acceptWaveform(samples.toArray(), AudioDecoder.SAMPLE_RATE)
                            stream.inputFinished()
                            if (!extractor.isReady(stream)) return@mapNotNull null
                            speaker to extractor.compute(stream)
                        } finally {
                            stream.release()
                        }
                    }.toMap()
                } finally {
                    extractor.release()
                }
            }
        }

    /**
     * Progress callback for sherpa-onnx diarization. The native code looks up
     * `invoke(IIJ)Ljava/lang/Integer;` by name, which a Kotlin lambda lacks (it is compiled to a
     * class with only the generic `invoke(Object, Object, Object)`), and the app aborts. A named
     * class gets that exact method. Returning 0 lets diarization continue.
     */
    private class DiarizationCallback(private val report: (processed: Int, total: Int) -> Unit) : (Int, Int, Long) -> Int {
        override fun invoke(processed: Int, total: Int, arg: Long): Int {
            runCatching { report(processed, total) }
            return 0
        }
    }

    private class FloatArrayBuilder {
        private var data = FloatArray(AudioDecoder.SAMPLE_RATE * 10)
        var size = 0
            private set

        fun append(source: FloatArray, from: Int, to: Int) {
            val n = to - from
            if (n <= 0) return
            if (size + n > data.size) data = data.copyOf(maxOf(data.size * 2, size + n))
            System.arraycopy(source, from, data, size, n)
            size += n
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    /** Frees the loaded recognizer, e.g. after the model was changed or deleted. */
    suspend fun unload() = lock.withLock {
        recognizer?.release()
        recognizer = null
        recognizerKey = null
    }

    private fun clean(text: String): String {
        val t = text.trim()
        // Whisper marks silence and noise like "[BLANK_AUDIO]" or "(music)".
        if (t.matches(Regex("""^[\[(][^\])]*[\])]$"""))) return ""
        return t
    }

    private companion object {
        const val TAG = "KaliScriber"
        const val VAD_WINDOW = 512
        /** Longest stretch diarized in one go (about 38 MB of audio in memory). */
        const val BLOCK_SECONDS = 10 * 60
        const val VOICEPRINT_SECONDS = 30
    }
}
