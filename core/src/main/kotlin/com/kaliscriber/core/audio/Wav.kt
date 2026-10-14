package com.kaliscriber.core.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Minimal PCM WAV writing, used for the short chunks uploaded during live transcription. */
object Wav {

    fun header(dataBytes: Int, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataBytes)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1) // PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * bitsPerSample / 8).toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataBytes)
        }.array()
    }

    fun write(file: File, pcm: ByteArray, length: Int, sampleRate: Int, channels: Int = 1) {
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use {
            it.write(header(length, sampleRate, channels))
            it.write(pcm, 0, length)
        }
    }

    fun durationMs(pcmBytes: Long, sampleRate: Int, channels: Int = 1, bitsPerSample: Int = 16): Long =
        pcmBytes * 1000 / (sampleRate.toLong() * channels * bitsPerSample / 8)
}
