package com.kaliscriber.app.recording

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.kaliscriber.app.diagnostics.AppLog
import android.view.KeyEvent

/**
 * Turns the play/pause button of wired or Bluetooth headphones into a bookmark button while a
 * meeting is recorded. Android sends media buttons to the app that last played audio, so a moment
 * of silence is played to become that app; the button then reaches this media session.
 */
class HeadsetButtons(private val context: Context, private val onPress: () -> Unit) {
    private var lastPress = 0L
    private val session = MediaSession(context, "KaliScriber recording").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(intent: Intent): Boolean {
                val event = keyEvent(intent) ?: return false
                if (event.keyCode !in KEYS) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) press()
                return true
            }

            // Some headphones send play and pause commands rather than key events.
            override fun onPlay() = press()

            override fun onPause() = press()
        })
        setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                .build(),
        )
        isActive = true
    }

    init {
        claimButtons()
    }

    /** Plays half a second of silence so headphone buttons come here (again, e.g. after a call). */
    fun claimButtons() {
        try {
            val rate = 8_000
            val silence = ShortArray(rate / 2)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(silence.size * 2)
                .build()
            track.write(silence, 0, silence.size)
            track.play()
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { track.release() } }, 1_500)
        } catch (e: Exception) {
            AppLog.w("KaliScriber", "Couldn't claim the headphone buttons: ${e.message}")
        }
    }

    private fun press() {
        // Headphones can send both a key event and a play or pause command for one press.
        val now = SystemClock.elapsedRealtime()
        if (now - lastPress < DEBOUNCE_MS) return
        lastPress = now
        buzz()
        onPress()
    }

    /** A short buzz, so a bookmark can be felt with the phone in a pocket. */
    private fun buzz() {
        runCatching {
            val vibrator = context.getSystemService(Vibrator::class.java) ?: return
            vibrator.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    private fun keyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        }

    private companion object {
        val KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
        )
        const val DEBOUNCE_MS = 700L
    }
}
