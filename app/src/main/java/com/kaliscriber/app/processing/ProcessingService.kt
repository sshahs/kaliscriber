package com.kaliscriber.app.processing

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import com.kaliscriber.app.diagnostics.AppLog
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the app running, with the CPU awake, while [MeetingJobs] has work, and shows its progress
 * in a notification. Stops itself when the last job finishes.
 */
class ProcessingService : LifecycleService() {

    private val container get() = (application as KaliScriberApp).container
    private var observer: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!enterForeground()) {
            // The job still runs while the app is open; it just isn't protected in the background.
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KaliScriber:processing")
                .apply {
                    setReferenceCounted(false)
                    acquire(MAX_WAKE_MS)
                }
        }
        observe()
        return START_NOT_STICKY
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observe() {
        if (observer != null) return
        observer = lifecycleScope.launch {
            container.jobs.active
                .flatMapLatest { active ->
                    val meetingId = active.firstOrNull()
                    if (meetingId == null) {
                        stop()
                        emptyFlow<Pair<String?, Int?>>()
                    } else if (meetingId.startsWith(MeetingJobs.APP_JOB)) {
                        container.jobs.appStep
                    } else {
                        container.meetings.observe(meetingId)
                            .map { m -> m?.processingStep to m?.processingProgress?.let { (it * 100).toInt() } }
                    }
                }
                .distinctUntilChanged()
                .collect { (step, percent) -> notify(buildNotification(step, percent)) }
        }
    }

    /**
     * Calls the platform directly: ServiceCompat drops service types newer than the AndroidX version
     * knows (mediaProcessing), which Android then rejects as "type none".
     */
    private fun enterForeground(): Boolean {
        val notification = buildNotification(null, null)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return true
        }
        // Prefer the media-processing type; fall back to data sync if this Android rejects it.
        for (type in listOf(foregroundType(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC).distinct()) {
            try {
                startForeground(NOTIFICATION_ID, notification, type)
                return true
            } catch (e: Exception) {
                AppLog.w("KaliScriber", "Could not run meeting processing in the foreground (type $type): ${e.message}")
            }
        }
        return false
    }

    private fun foregroundType(): Int = when {
        Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else -> 0
    }

    /** Android 15 limits how long this kind of service may run; stop cleanly when told to. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stop()
    }

    private fun stop() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun notify(notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(step: String?, percent: Int?): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, KaliScriberApp.RECORDING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentTitle("Processing meeting")
            .setContentText(step?.let { s -> percent?.let { "$s · $it%" } ?: "$s…" } ?: "Working…")
            .setProgress(100, percent ?: 0, percent == null)
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 43
        /** Safety net so a stuck job can never keep the phone awake forever. */
        const val MAX_WAKE_MS = 3L * 60 * 60 * 1000
    }
}
