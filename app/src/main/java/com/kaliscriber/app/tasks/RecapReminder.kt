package com.kaliscriber.app.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.recap.WeeklyRecaps
import kotlinx.coroutines.CancellationException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/**
 * The weekly recap notification: on Friday afternoons the week's recap is written and shown, so
 * the week can be closed off before the weekend. Runs as weekly WorkManager work.
 */
object RecapReminder {
    private const val CHANNEL = "weekly-recap"
    /** Intent extra that makes [MainActivity] open the weekly recap. */
    const val EXTRA_OPEN_RECAP = "com.kaliscriber.app.OPEN_RECAP"
    private const val WORK = "weekly-recap"
    private const val PREFS = "weekly-recap"
    private const val NOTIFICATION_ID = 78
    private val TIME: LocalTime = LocalTime.of(16, 0)

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly recap", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A recap of the week's meetings on Friday afternoons"
            },
        )
    }

    /** Starts or stops the weekly recap to match [settings]. */
    fun schedule(context: Context, settings: AppSettings) {
        val work = WorkManager.getInstance(context)
        if (!settings.weeklyRecap) {
            work.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<RecapWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(delayUntilFriday().toMinutes(), TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        work.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun delayUntilFriday(): Duration {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)).atTime(TIME)
        if (!next.isAfter(now)) next = next.plusWeeks(1)
        return Duration.between(now, next)
    }

    /** Writes this week's recap and shows it, once a week, if there were meetings. */
    internal suspend fun showRecap(context: Context) {
        val container = (context.applicationContext as KaliScriberApp).container
        if (!container.settings().weeklyRecap) return
        val week = WeeklyRecaps.weekOf(LocalDate.now())
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString("shown", null) == week.toString()) return
        container.meetings.load()
        val meetings = container.recaps.meetingsIn(week)
        if (meetings.isEmpty()) return
        val recap = try {
            container.recaps.write(week)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still say how the week went; the recap can be written from the app.
            Log.w("KaliScriber", "Weekly recap couldn't be written", e)
            null
        }
        prefs.edit().putString("shown", week.toString()).apply()
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val open = container.recaps.openItems(week).sumOf { it.second.size }
        val title = "Your week: ${count(meetings.size, "meeting")}" +
            (recap?.decisions?.size?.takeIf { it > 0 }?.let { ", ${count(it, "decision")}" } ?: "") +
            ", ${count(open, "open action item")}"
        val body = recap?.overview?.takeIf { it.isNotBlank() } ?: "Tap to see the week and write its recap."
        val intent = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_RECAP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notification permission was withdrawn; nothing to show.
        }
    }

    private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
}

class RecapWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        RecapReminder.showRecap(applicationContext)
        return Result.success()
    }
}
