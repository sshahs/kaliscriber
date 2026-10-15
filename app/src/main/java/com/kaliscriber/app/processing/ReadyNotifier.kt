package com.kaliscriber.app.processing

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus

/**
 * Says when a meeting's notes are ready (or couldn't be finished) while the app is in the
 * background, so you don't have to keep checking. Tapping it opens the meeting.
 */
object ReadyNotifier {
    private const val CHANNEL = "meeting-ready"
    /** Intent extra with the id of a meeting for [MainActivity] to open. */
    const val EXTRA_OPEN_MEETING = "com.kaliscriber.app.OPEN_MEETING"

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Meeting notes ready", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When a meeting has been transcribed and summarised in the background"
            },
        )
    }

    private val WORKING = setOf(MeetingStatus.TRANSCRIBING, MeetingStatus.ANALYZING)

    /** True when [meeting] has just finished processing, coming from [was]. */
    fun finished(was: MeetingStatus?, meeting: Meeting): Boolean =
        was != null && was in WORKING && meeting.status !in WORKING && meeting.status != MeetingStatus.RECORDING

    fun notify(context: Context, meeting: Meeting) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val failed = meeting.status == MeetingStatus.ERROR
        val title = when {
            failed -> "Couldn't finish: ${meeting.title}"
            meeting.lastError != null -> "Notes ready, with problems: ${meeting.title}"
            else -> "Notes ready: ${meeting.title}"
        }
        val counts = meeting.insights?.let { i ->
            listOfNotNull(
                i.actionItems.size.takeIf { it > 0 }?.let { "$it action item${if (it == 1) "" else "s"}" },
                i.decisions.size.takeIf { it > 0 }?.let { "$it decision${if (it == 1) "" else "s"}" },
            ).joinToString(" · ")
        }.orEmpty()
        val body = when {
            failed || meeting.lastError != null -> meeting.lastError.orEmpty()
            else -> listOf(counts, meeting.insights?.summary.orEmpty().take(300)).filter { it.isNotBlank() }.joinToString("\n")
        }.ifBlank { "Tap to open the meeting." }
        val open = PendingIntent.getActivity(
            context, meeting.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_OPEN_MEETING, meeting.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(body.lineSequence().first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(meeting.id.hashCode(), notification)
        } catch (e: SecurityException) {
            // Notification permission was withdrawn; nothing to show.
        }
    }
}

/** Whether any of the app's screens is showing, so notifications aren't sent for what's on screen. */
class AppVisibility : Application.ActivityLifecycleCallbacks {
    @Volatile private var started = 0

    val foreground: Boolean get() = started > 0

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
