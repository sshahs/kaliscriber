package com.kaliscriber.app.speech

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.DefaultAiServices
import com.kaliscriber.core.ai.TtsEngine
import com.kaliscriber.core.ai.tts.SentenceStream
import com.kaliscriber.core.ai.tts.SpeechText
import com.kaliscriber.core.audio.Wav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads text aloud with either the phone's own offline voice (Android TextToSpeech) or ElevenLabs,
 * and renders audio briefings to files. Only one thing speaks at a time; [speaking] holds its key so
 * the UI can show a stop button next to the right item.
 */
class SpeechOutput(
    private val context: Context,
    private val ai: DefaultAiServices,
    private val settings: () -> AppSettings,
) {
    private val _speaking = MutableStateFlow<String?>(null)
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ttsEnginePackage: String? = null
    private var ttsReady: CompletableDeferred<Boolean>? = null
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private var player: MediaPlayer? = null
    private var playback: CompletableDeferred<Unit>? = null

    /** Speaks [text] (Markdown is cleaned up first) and suspends until it finishes or is stopped. */
    suspend fun speak(key: String, text: String) {
        stop()
        val spoken = SpeechText.fromMarkdown(text)
        if (spoken.isBlank()) return
        _speaking.value = key
        try {
            when (settings().ttsEngine) {
                TtsEngine.DEVICE -> speakOnDevice(key, spoken)
                TtsEngine.ELEVENLABS -> {
                    val audio = withContext(Dispatchers.IO) {
                        val bytes = ai.elevenLabsTts().synthesize(spoken)
                        File(context.cacheDir, "speech.mp3").apply { writeBytes(bytes) }
                    }
                    if (_speaking.value == key) playFile(key, audio)
                }
            }
        } finally {
            if (_speaking.value == key) _speaking.value = null
        }
    }

    /** Plays an audio file (e.g. a saved briefing) under [key]; suspends until done or stopped. */
    suspend fun playFile(key: String, file: File) {
        if (_speaking.value != key) stop()
        _speaking.value = key
        val done = CompletableDeferred<Unit>()
        playback = done
        try {
            withContext(Dispatchers.Main) {
                player = MediaPlayer().apply {
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { done.complete(Unit) }
                    setOnErrorListener { _, _, _ -> done.complete(Unit); true }
                    prepare()
                    start()
                }
            }
            done.await()
        } finally {
            player?.release()
            player = null
            if (_speaking.value == key) _speaking.value = null
        }
    }

    /** Bumped by [stop], so speech that is still being queued knows it was stopped. */
    @Volatile private var generation = 0

    /**
     * Speaks an answer while it is still being written: each sentence is spoken as soon as it is
     * complete, so speech starts after the first sentence rather than after the whole answer.
     */
    fun live(key: String, scope: CoroutineScope): LiveSpeech {
        stop()
        return LiveSpeech(key, scope, generation)
    }

    inner class LiveSpeech internal constructor(private val key: String, scope: CoroutineScope, private val gen: Int) {
        private val sentences = SentenceStream()
        private val queue = Channel<String>(Channel.UNLIMITED)
        private val job = scope.launch { speakQueue() }

        /** The answer so far; safe to call from any thread. */
        fun feed(partial: String) {
            sentences.feed(partial).forEach { queue.trySend(it) }
        }

        /** The finished answer: speaks what is left and waits until all of it has been said. */
        suspend fun finish(full: String) {
            sentences.finish(full).forEach { queue.trySend(it) }
            queue.close()
            job.join()
        }

        fun cancel() {
            queue.close()
            job.cancel()
            if (generation == gen) stop()
        }

        private suspend fun speakQueue() {
            try {
                when (settings().ttsEngine) {
                    TtsEngine.DEVICE -> {
                        val engine = deviceTts()
                        val waits = mutableListOf<CompletableDeferred<Boolean>>()
                        var n = 0
                        for (sentence in queue) {
                            if (generation != gen) return
                            _speaking.value = key
                            for (part in SpeechText.split(sentence, maxDeviceChars())) {
                                val id = "$key#live${n++}"
                                val done = CompletableDeferred<Boolean>().also { pending[id] = it }
                                // Queued behind the sentences before it, so speech runs on without gaps.
                                withContext(Dispatchers.Main) { engine.speak(part, TextToSpeech.QUEUE_ADD, null, id) }
                                waits += done
                            }
                        }
                        for (wait in waits) if (!wait.await()) break
                    }
                    TtsEngine.ELEVENLABS -> for (sentence in queue) {
                        if (generation != gen) return
                        _speaking.value = key
                        val audio = withContext(Dispatchers.IO) {
                            File(context.cacheDir, "speech-live.mp3").apply { writeBytes(ai.elevenLabsTts().synthesize(sentence)) }
                        }
                        if (generation != gen) return
                        playFile(key, audio)
                    }
                }
            } finally {
                if (_speaking.value == key && generation == gen) _speaking.value = null
            }
        }
    }

    fun stop() {
        generation++
        _speaking.value = null
        tts?.stop()
        pending.values.forEach { it.complete(false) }
        pending.clear()
        runCatching { player?.stop() }
        playback?.complete(Unit)
    }

    /**
     * Renders [text] to an audio file in [dir] ("briefing.mp3" with ElevenLabs, "briefing.wav" with the
     * phone voice) and returns it.
     */
    suspend fun renderToFile(text: String, dir: File): File {
        val spoken = SpeechText.fromMarkdown(text)
        if (spoken.isBlank()) throw AiException("Nothing to read aloud.")
        dir.mkdirs()
        return when (settings().ttsEngine) {
            TtsEngine.ELEVENLABS -> withContext(Dispatchers.IO) {
                val bytes = ai.elevenLabsTts().synthesize(spoken)
                File(dir, "briefing.mp3").apply {
                    writeBytes(bytes)
                    File(dir, "briefing.wav").delete()
                }
            }
            TtsEngine.DEVICE -> {
                val engine = deviceTts()
                val parts = SpeechText.split(spoken, maxDeviceChars())
                val files = parts.mapIndexed { i, part ->
                    val file = File(context.cacheDir, "briefing_part_$i.wav")
                    val id = "render-$i-${System.nanoTime()}"
                    val done = CompletableDeferred<Boolean>().also { pending[id] = it }
                    val result = withContext(Dispatchers.Main) { engine.synthesizeToFile(part, Bundle(), file, id) }
                    if (result != TextToSpeech.SUCCESS) {
                        pending.remove(id)
                        throw AiException("The phone's voice could not create audio.")
                    }
                    if (!withTimeout(120_000) { done.await() }) throw AiException("Creating the audio briefing was interrupted.")
                    file
                }
                withContext(Dispatchers.IO) {
                    val out = File(dir, "briefing.wav")
                    mergeWavs(files, out)
                    files.forEach { it.delete() }
                    File(dir, "briefing.mp3").delete()
                    out
                }
            }
        }
    }

    private suspend fun speakOnDevice(key: String, text: String) {
        val engine = deviceTts()
        val parts = SpeechText.split(text, maxDeviceChars())
        val waits = parts.mapIndexed { i, part ->
            val id = "$key#$i"
            val done = CompletableDeferred<Boolean>().also { pending[id] = it }
            withContext(Dispatchers.Main) {
                engine.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            }
            done
        }
        for (wait in waits) if (!wait.await()) break
    }

    private fun maxDeviceChars(): Int = (TextToSpeech.getMaxSpeechInputLength() - 100).coerceIn(500, 3_900)

    /** Text-to-speech engines installed on the phone, as (package, name), e.g. NekoSpeak or RHVoice. */
    fun installedEngines(): List<Pair<String, String>> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
            .mapNotNull { info ->
                val service = info.serviceInfo ?: return@mapNotNull null
                service.packageName to (service.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: service.packageName)
            }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
    }

    /**
     * The phone's speech engine: the one chosen in settings, else the system default, else any
     * installed engine that starts (GrapheneOS and other phones without Google services may have no
     * default set).
     */
    private suspend fun deviceTts(): TextToSpeech {
        val wanted = settings().deviceTtsEngine
        tts?.let { existing ->
            if (ttsEnginePackage == wanted && ttsReady?.await() == true) return existing
            existing.shutdown()
            tts = null
        }
        val installed = installedEngines().map { it.first }
        val candidates = (listOf(wanted) + listOf("") + installed).distinct()
            .filter { it.isEmpty() || it in installed }
        for (pkg in candidates) {
            startEngine(pkg)?.let { engine ->
                ttsEnginePackage = wanted
                return engine
            }
        }
        throw AiException(
            if (installed.isEmpty()) {
                "No text-to-speech engine is installed. Install one (for example NekoSpeak or RHVoice), or choose ElevenLabs in Settings."
            } else {
                "The phone's text-to-speech engine didn't start. Pick another engine in Settings, or choose ElevenLabs."
            },
        )
    }

    /** Starts the engine in [pkg] (blank: system default); null if it doesn't come up. */
    private suspend fun startEngine(pkg: String): TextToSpeech? {
        val ready = CompletableDeferred<Boolean>()
        ttsReady = ready
        val engine = withContext(Dispatchers.Main) {
            val onInit = TextToSpeech.OnInitListener { status -> ready.complete(status == TextToSpeech.SUCCESS) }
            if (pkg.isEmpty()) TextToSpeech(context, onInit) else TextToSpeech(context, onInit, pkg)
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(true) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let { pending.remove(it)?.complete(false) }
            }
        })
        // Neural engines can take a while to load their voice the first time.
        if (withTimeoutOrNull(20_000) { ready.await() } != true) {
            engine.shutdown()
            return null
        }
        tts = engine
        return engine
    }

    fun shutdown() {
        stop()
        tts?.shutdown()
        tts = null
    }

    /** Concatenates PCM WAV files that share one format into [out]. */
    private fun mergeWavs(files: List<File>, out: File) {
        var sampleRate = 0
        var channels = 1
        val pcm = ByteArrayOutputStream()
        for (file in files) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(12)
                val header = ByteArray(8)
                while (raf.filePointer + 8 <= raf.length()) {
                    raf.readFully(header)
                    val id = String(header, 0, 4, Charsets.US_ASCII)
                    val size = (ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL)
                        .let { if (it == 0xFFFFFFFFL || it == 0L) raf.length() - raf.filePointer else it }
                    if (id == "fmt ") {
                        val fmt = ByteArray(size.toInt())
                        raf.readFully(fmt)
                        val b = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        channels = b.getShort(2).toInt()
                        sampleRate = b.getInt(4)
                    } else if (id == "data") {
                        val data = ByteArray(minOf(size, raf.length() - raf.filePointer).toInt())
                        raf.readFully(data)
                        pcm.write(data)
                        break
                    } else {
                        raf.seek(raf.filePointer + size + (size and 1))
                    }
                }
            }
        }
        if (sampleRate == 0) throw AiException("The phone's voice produced no audio.")
        val bytes = pcm.toByteArray()
        Wav.write(out, bytes, bytes.size, sampleRate, channels)
    }
}
