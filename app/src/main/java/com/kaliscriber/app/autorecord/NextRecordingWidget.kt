package com.kaliscriber.app.autorecord

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.core.calendar.AutoRecord
import com.kaliscriber.core.calendar.CalendarEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Home-screen widget: what KaliScriber records next (or is recording), with Skip and Record now. */
class NextRecordingWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        /** Redraws every placed widget. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, NextRecordingWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) runCatching { manager.updateAppWidget(ids, views(context)) }
        }

        private fun views(context: Context): RemoteViews {
            val auto = (context.applicationContext as KaliScriberApp).container.autoRecorder
            val views = RemoteViews(context.packageName, R.layout.widget_next)
            val open = PendingIntent.getActivity(
                context, 30,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SCHEDULE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.next_root, open)

            val active = auto.state.value.active?.takeIf { auto.isRecording(it) }
            if (active != null) {
                views.setTextViewText(R.id.next_heading, "Recording now")
                views.setTextViewText(R.id.next_title, active.title)
                views.setTextViewText(R.id.next_time, if (active.stopAtEnd) "Stops at ${time(active.end)}" else "Keeps going until you stop it")
                views.setViewVisibility(R.id.next_actions, View.GONE)
                return views
            }
            val next = auto.upcoming()
            if (next == null) {
                views.setTextViewText(R.id.next_heading, context.getString(R.string.widget_next_heading))
                views.setTextViewText(R.id.next_title, "Nothing to record")
                views.setTextViewText(R.id.next_time, "Tap to schedule a recording")
                views.setViewVisibility(R.id.next_actions, View.GONE)
                return views
            }
            views.setTextViewText(R.id.next_heading, context.getString(R.string.widget_next_heading))
            views.setTextViewText(R.id.next_title, next.title)
            views.setTextViewText(R.id.next_time, "${day(next.start)}, ${time(next.start)}–${time(next.end)}")
            views.setViewVisibility(R.id.next_actions, View.VISIBLE)
            views.setOnClickPendingIntent(R.id.next_skip, skip(context, next))
            views.setOnClickPendingIntent(
                R.id.next_record,
                PendingIntent.getActivity(
                    context, 32, AutoRecordActivity.intent(context, AutoRecord.key(next)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            return views
        }

        private fun skip(context: Context, event: CalendarEvent): PendingIntent = PendingIntent.getBroadcast(
            context, 31,
            Intent(context, AutoRecordReceiver::class.java)
                .setAction(AutoRecordReceiver.ACTION_SKIP)
                .putExtra(AutoRecordReceiver.EXTRA_KEY, AutoRecord.key(event))
                .putExtra(AutoRecordReceiver.EXTRA_END, event.end),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

        private fun time(ms: Long) = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)

        private fun day(ms: Long): String {
            val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
            val today = LocalDate.now()
            return when (date) {
                today -> "Today"
                today.plusDays(1) -> "Tomorrow"
                else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
            }
        }
    }
}
