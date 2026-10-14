package com.kaliscriber.app.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.tasks.ActionItems
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * The daily action-item reminder: one notification at the chosen hour listing open items that are
 * overdue or due that day. Runs as periodic WorkManager work, so it survives reboots.
 */
object TaskReminders {
    const val CHANNEL = "reminders"
    /** Intent extra that makes [MainActivity] open the task list. */
    const val EXTRA_OPEN_TASKS = "com.kaliscriber.app.OPEN_TASKS"
    private const val WORK = "task-digest"
    private const val PREFS = "task-reminders"
    private const val NOTIFICATION_ID = 77

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Action item reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A daily list of action items that are overdue or due today"
            },
        )
    }

    /** Starts, moves or stops the daily reminder to match [settings]. */
    fun schedule(context: Context, settings: AppSettings) {
        val work = WorkManager.getInstance(context)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!settings.taskReminders) {
            work.cancelUniqueWork(WORK)
            prefs.edit().remove("hour").apply()
            return
        }
        val hour = settings.reminderHour.coerceIn(0, 23)
        val request = PeriodicWorkRequestBuilder<TaskDigestWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delayUntil(hour).toMinutes(), TimeUnit.MINUTES)
            .build()
        // Keep the existing schedule unless the hour changed, so app starts don't push it back.
        val policy = if (prefs.getInt("hour", -1) == hour) ExistingPeriodicWorkPolicy.KEEP else ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        work.enqueueUniquePeriodicWork(WORK, policy, request)
        prefs.edit().putInt("hour", hour).apply()
    }

    private fun delayUntil(hour: Int): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(LocalTime.of(hour, 0))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next)
    }

    /** Posts today's reminder unless it was already shown today or nothing is due. */
    internal suspend fun showDigest(context: Context) {
        val container = (context.applicationContext as KaliScriberApp).container
        if (!container.settings().taskReminders) return
        val today = LocalDate.now()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("shown", null) == today.toString()) return
        container.meetings.load()
        val digest = ActionItems.digest(container.meetings.all(), today)
        prefs.edit().putString("shown", today.toString()).apply()
        if (digest.isEmpty || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val title = listOfNotNull(
            digest.overdue.size.takeIf { it > 0 }?.let { "$it overdue" },
            digest.dueToday.size.takeIf { it > 0 }?.let { "$it due today" },
        ).joinToString(", ").replaceFirstChar { it.uppercase() } + if (digest.overdue.size + digest.dueToday.size == 1) " action item" else " action items"
        val lines = (digest.overdue + digest.dueToday).map { t ->
            t.item.task + (t.item.owner?.let { " — $it" } ?: "")
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.take(6).forEach(style::addLine)
        if (lines.size > 6) style.setSummaryText("+${lines.size - 6} more")
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_TASKS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission was withdrawn; nothing to show.
        }
    }
}

class TaskDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        TaskReminders.showDigest(applicationContext)
        return Result.success()
    }
}
