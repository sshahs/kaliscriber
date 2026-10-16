package com.kaliscriber.app.autorecord

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.recording.RecordingState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * An invisible screen that starts recording a calendar meeting. Android lets an app use the
 * microphone from the background only if recording began while one of its screens was showing,
 * so this stays open (over the lock screen too) until recording has started, then closes.
 */
class AutoRecordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        val key = intent.getStringExtra(EXTRA_KEY)
        if (key == null) {
            finish()
            return
        }
        val container = (application as KaliScriberApp).container
        lifecycleScope.launch {
            container.meetings.load()
            container.autoRecorder.startFromScreen(key)
            withTimeoutOrNull(WAIT_MS) {
                container.recorder.state.first { it is RecordingState.Recording && it.meetingId.isNotEmpty() }
            }
            finish()
        }
    }

    companion object {
        private const val EXTRA_KEY = "key"
        private const val WAIT_MS = 10_000L

        fun intent(context: Context, key: String): Intent =
            Intent(context, AutoRecordActivity::class.java)
                .putExtra(EXTRA_KEY, key)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}
