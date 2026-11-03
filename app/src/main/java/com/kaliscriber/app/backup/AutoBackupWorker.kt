package com.kaliscriber.app.backup

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.backup.AutoBackups
import com.kaliscriber.core.backup.BackupEvery
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/** Makes the automatic backup when one is due; checked about once a day. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as KaliScriberApp).container
        val s = container.settings()
        if (!AutoBackups.due(s.backupEvery, container.backups.auto.value.lastAt, System.currentTimeMillis())) return Result.success()
        container.meetings.load()
        container.notes.load()
        return try {
            container.backups.autoBackUp()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupManager.BusyException) {
            // A meeting is being recorded: try again a little later.
            Result.retry()
        } catch (e: Exception) {
            AutoBackupScheduler.notifyFailed(applicationContext, e.message ?: "The automatic backup failed.")
            // Tried again at the next daily check rather than straight away.
            Result.success()
        }
    }
}

object AutoBackupScheduler {
    private const val WORK = "auto-backup"
    private const val CHANNEL = "backups"
    private const val NOTIFICATION_ID = 81

    /** Turns the daily check on when automatic backups are set up, off otherwise. */
    fun apply(context: Context, settings: AppSettings) {
        val work = WorkManager.getInstance(context)
        if (settings.backupEvery == BackupEvery.OFF || settings.backupFolder.isBlank()) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun notifyFailed(context: Context, message: String) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Backups", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an automatic backup couldn't be made"
            },
        )
        val open = PendingIntent.getActivity(
            context, 40,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Automatic backup failed")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message Check Settings → Data & storage."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications aren't allowed; the failure shows in Settings.
        }
    }
}
