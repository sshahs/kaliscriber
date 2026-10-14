package com.kaliscriber.app.shortcuts

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.kaliscriber.app.KaliScriberApp
import com.kaliscriber.app.MainActivity
import com.kaliscriber.app.R
import com.kaliscriber.app.recording.RecordingState

/** Home-screen widget: a record button that also shows the running time while a meeting is recorded. */
class RecordWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val state = (context.applicationContext as KaliScriberApp).container.recorder.state.value
        manager.updateAppWidget(ids, views(context, state))
    }

    companion object {
        /** Redraws every placed widget for [state]. */
        fun update(context: Context, state: RecordingState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = runCatching { manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java)) }.getOrNull() ?: return
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context, state))
        }

        private fun views(context: Context, state: RecordingState): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_record)
            val open = PendingIntent.getActivity(
                context, 10, MainActivity.recordIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            views.setOnClickPendingIntent(R.id.widget_root, open)
            when (state) {
                is RecordingState.Recording -> {
                    views.setTextViewText(R.id.widget_label, if (state.paused) "Paused" else "Recording")
                    views.setViewVisibility(R.id.widget_timer, View.VISIBLE)
                    views.setChronometer(R.id.widget_timer, SystemClock.elapsedRealtime() - state.elapsedMs, null, !state.paused)
                }
                is RecordingState.Finishing -> {
                    views.setTextViewText(R.id.widget_label, "Processing…")
                    views.setViewVisibility(R.id.widget_timer, View.GONE)
                }
                RecordingState.Idle -> {
                    views.setTextViewText(R.id.widget_label, "Record meeting")
                    views.setViewVisibility(R.id.widget_timer, View.GONE)
                }
            }
            return views
        }
    }
}
