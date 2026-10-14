package com.kaliscriber.app.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the microphone (and later the AI processing) alive while the
 * screen is off or the user is in another app.
 */
class RecordingService : LifecycleService() {

    private val manager: RecordingManager get() = (application as KaliScriberApp).container.recorder
    private var observer: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startInForeground(buildNotification(RecordingState.Recording(meetingId = "")))
                if (!manager.beginCapture()) {
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                observeState()
            }
            ACTION_PAUSE -> manager.setPaused(true)
            ACTION_RESUME -> manager.setPaused(false)
            ACTION_STOP -> manager.stop()
            else -> if (manager.state.value == RecordingState.Idle) stopSelfCleanly()
        }
        return START_NOT_STICKY
    }

    private fun observeState() {
        if (observer != null) return
        observer = lifecycleScope.launch {
            manager.state
                .distinctUntilChangedBy { state ->
                    when (state) {
                        is RecordingState.Recording -> "rec-${state.paused}"
                        is RecordingState.Finishing -> "finishing"
                        RecordingState.Idle -> "idle"
                    }
                }
                .collect { state ->
                    if (state == RecordingState.Idle) {
                        stopSelfCleanly()
                    } else {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(state))
                    }
                }
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopSelfCleanly() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: RecordingState): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, KaliScriberApp.RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        when (state) {
            is RecordingState.Recording -> {
                builder.setContentTitle(if (state.paused) "Recording paused" else "Recording meeting")
                    .setContentText("Live transcription is running")
                    .setUsesChronometer(!state.paused)
                    .setWhen(System.currentTimeMillis() - state.elapsedMs)
                if (state.paused) {
                    builder.addAction(0, "Resume", serviceIntent(ACTION_RESUME, 1))
                } else {
                    builder.addAction(0, "Pause", serviceIntent(ACTION_PAUSE, 2))
                }
                builder.addAction(0, "Stop", serviceIntent(ACTION_STOP, 3))
            }
            is RecordingState.Finishing -> builder
                .setContentTitle("Processing meeting")
                .setContentText("Transcribing and generating insights…")
                .setProgress(0, 0, true)
            RecordingState.Idle -> builder.setContentTitle("KaliScriber")
        }
        return builder.build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this, requestCode,
        Intent(this, RecordingService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_START = "com.kaliscriber.app.START"
        const val ACTION_PAUSE = "com.kaliscriber.app.PAUSE"
        const val ACTION_RESUME = "com.kaliscriber.app.RESUME"
        const val ACTION_STOP = "com.kaliscriber.app.STOP"
        private const val NOTIFICATION_ID = 42
    }
}
