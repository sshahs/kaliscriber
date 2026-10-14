package com.kaliscriber.app.recording

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the microphone (and later the AI processing) alive while the
 * screen is off or the user is in another app.
 */
class RecordingService : LifecycleService() {

    private val container get() = (application as KaliScriberApp).container
    private val manager: RecordingManager get() = container.recorder
    private var observer: Job? = null
    private var stepObserver: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Without a wake lock the CPU can sleep once the screen is off, which stalls on-device
     * transcription, speaker detection and the AI requests after the meeting ends.
     */
    private fun holdWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KaliScriber:meeting")
            .apply {
                setReferenceCounted(false)
                acquire(MAX_WAKE_MS)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START -> {
                startInForeground(buildNotification(RecordingState.Recording(meetingId = "")))
                holdWakeLock()
                if (!manager.beginCapture()) {
                    stopSelfCleanly()
                    return START_NOT_STICKY
                }
                observeState()
            }
            ACTION_PAUSE -> manager.setPaused(true)
            ACTION_RESUME -> manager.setPaused(false)
            ACTION_STOP -> manager.stop()
            ACTION_BOOKMARK -> lifecycleScope.launch {
                val mark = manager.bookmark() ?: return@launch
                (manager.state.value as? RecordingState.Recording)?.let { state ->
                    notify(buildNotification(state, step = "Bookmarked ${TranscriptFormatter.timestamp(mark.atMs)}"))
                }
            }
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
                    stepObserver?.cancel()
                    when (state) {
                        RecordingState.Idle -> stopSelfCleanly()
                        is RecordingState.Finishing -> {
                            notify(buildNotification(state))
                            // Mirror the processing step ("Detecting speakers… 40%") in the notification.
                            stepObserver = lifecycleScope.launch {
                                container.meetings.observe(state.meetingId)
                                    .map { m -> m?.processingStep to m?.processingProgress?.let { (it * 100).toInt() } }
                                    .distinctUntilChanged()
                                    .collect { (step, percent) -> notify(buildNotification(state, step, percent)) }
                            }
                        }
                        is RecordingState.Recording -> notify(buildNotification(state))
                    }
                }
        }
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun stopSelfCleanly() {
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(state: RecordingState, step: String? = null, percent: Int? = null): Notification {
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
                    .setContentText(step ?: "Live transcription is running")
                    .setUsesChronometer(!state.paused)
                    .setWhen(System.currentTimeMillis() - state.elapsedMs)
                if (state.paused) {
                    builder.addAction(0, "Resume", serviceIntent(ACTION_RESUME, 1))
                } else {
                    builder.addAction(0, "Pause", serviceIntent(ACTION_PAUSE, 2))
                    builder.addAction(0, "Bookmark", serviceIntent(ACTION_BOOKMARK, 4))
                }
                builder.addAction(0, "Stop", serviceIntent(ACTION_STOP, 3))
            }
            is RecordingState.Finishing -> builder
                .setContentTitle("Processing meeting")
                .setContentText(step?.let { s -> percent?.let { "$s · $it%" } ?: "$s…" } ?: "Finishing the transcript…")
                .setProgress(100, percent ?: 0, percent == null)
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
        const val ACTION_BOOKMARK = "com.kaliscriber.app.BOOKMARK"
        private const val NOTIFICATION_ID = 42
        /** Safety net so a stuck job can never keep the phone awake forever. */
        private const val MAX_WAKE_MS = 6L * 60 * 60 * 1000
    }
}
