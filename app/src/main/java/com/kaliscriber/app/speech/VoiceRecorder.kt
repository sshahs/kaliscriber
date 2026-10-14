package com.kaliscriber.app.speech

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Records one spoken question for hands-free Q&A: starts listening, waits for speech, and stops on
 * its own after a pause. Loudness comes from [MediaRecorder.getMaxAmplitude] against an adaptive
 * noise floor, so it works in quiet rooms and noisy ones.
 */
class VoiceRecorder(private val context: Context) {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()

    private var recorder: MediaRecorder? = null

    /** Returns the recorded file, or null if nobody spoke before [noSpeechTimeoutMs]. */
    suspend fun recordUtterance(
        silenceMs: Long = 1_500,
        maxMs: Long = 60_000,
        noSpeechTimeoutMs: Long = 8_000,
    ): File? {
        val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
        val r = start(file)
        var heardSpeech = false
        var quietFor = 0L
        var elapsed = 0L
        var noiseFloor = 0f
        try {
            while (elapsed < maxMs) {
                delay(TICK_MS)
                elapsed += TICK_MS
                val amplitude = r.maxAmplitude.toFloat()
                _level.value = (amplitude / 12_000f).coerceIn(0f, 1f)
                if (elapsed <= CALIBRATION_MS) {
                    noiseFloor = maxOf(noiseFloor, amplitude)
                    continue
                }
                val threshold = maxOf(1_200f, noiseFloor * 2.5f)
                if (amplitude > threshold) {
                    heardSpeech = true
                    quietFor = 0
                } else if (heardSpeech) {
                    quietFor += TICK_MS
                    if (quietFor >= silenceMs) break
                } else if (elapsed >= noSpeechTimeoutMs) {
                    break
                }
            }
        } finally {
            stopRecorder()
        }
        return if (heardSpeech && file.length() > 0) file else file.let { it.delete(); null }
    }

    fun cancel() = stopRecorder()

    private fun start(file: File): MediaRecorder {
        stopRecorder()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        return r
    }

    private fun stopRecorder() {
        val r = recorder ?: return
        recorder = null
        runCatching { r.stop() }
        r.release()
        _level.value = 0f
    }

    private companion object {
        const val TICK_MS = 100L
        const val CALIBRATION_MS = 400L
    }
}
