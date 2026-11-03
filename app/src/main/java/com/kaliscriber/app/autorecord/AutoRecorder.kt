package com.kaliscriber.app.autorecord

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.kaliscriber.app.R
import com.kaliscriber.app.calendar.CalendarService
import com.kaliscriber.app.processing.AppVisibility
import com.kaliscriber.app.recording.RecordingManager
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.calendar.AutoRecord
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.calendar.ScheduledRecordings
import com.kaliscriber.core.pipeline.MeetingPipeline
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.calendar.ScheduledRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Records calendar meetings by themselves: an exact alarm wakes the app when a meeting starts and
 * when it ends, and [tick] starts or stops the recording ([AutoRecord] decides which).
 *
 * Android only lets an app record from the microphone if it is on screen when recording starts.
 * So when the app is in the background, recording starts from [AutoRecordActivity], an invisible
 * screen opened directly (with "display over other apps" allowed) or by a full-screen
 * notification (on the lock screen). Otherwise the notification asks for a tap.
 */
class AutoRecorder(
    private val context: Context,
    private val calendar: CalendarService,
    private val recorder: RecordingManager,
    private val pipeline: MeetingPipeline,
    private val settings: () -> AppSettings,
    private val visibility: AppVisibility,
    private val store: JsonValueStore<AutoRecord.State>,
) {
    val state: StateFlow<AutoRecord.State> = store.value
    private val mutex = Mutex()

    /** The event a recording is being started for, so the new meeting gets it rather than a guess. */
    @Volatile var starting: CalendarEvent? = null
        private set

    /** Looks at the calendar and the recorder, starts or stops a recording when it's time, and sets the next alarm. */
    suspend fun tick(refresh: Boolean = false) {
        val wake = mutex.withLock { tickLocked(refresh) }
        schedule(wake)
    }

    private suspend fun tickLocked(refresh: Boolean): Long? {
        val s = settings()
        if (!s.autoRecord && s.scheduledRecordings.isEmpty()) return null
        val now = System.currentTimeMillis()
        var st = store.value.value.pruned(now)
        // A slow or unreachable calendar mustn't hold up starting the meeting: use what was read last.
        val read = if (!s.autoRecord) null else withTimeoutOrNull(READ_TIMEOUT_MS) {
            try {
                calendar.events(force = refresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        st = when {
            !s.autoRecord || !calendar.enabled -> st.copy(events = emptyList())
            read != null && calendar.lastReadOk -> st.copy(events = read)
            // Unreachable just now: keep what was read before (phone calendar events are added in fresh).
            read != null -> st.copy(events = (st.events + read).distinctBy { AutoRecord.key(it) })
            else -> st
        }
        // Stopped by hand (or it failed): it's no longer ours to stop.
        st.active?.let { if (!recorder.isCapturing(it.meetingId)) st = st.copy(active = null) }

        val events = allEvents(st, now)
        st = remind(events, now, st)
        var decision = AutoRecord.decide(events, now, s.autoRecordWhich, st, recorderState(st))
        if (decision == AutoRecord.Decision.Stop) {
            val active = st.active!!
            recorder.stop()
            // Stopping happens in the background; the next meeting can only start once it has.
            withTimeoutOrNull(STOP_WAIT_MS) {
                recorder.state.first { (it as? RecordingState.Recording)?.meetingId != active.meetingId }
            }
            notifyStopped(active)
            st = st.copy(active = null)
            // The next meeting may be starting right now (back-to-back meetings).
            decision = AutoRecord.decide(events, now, s.autoRecordWhich, st, AutoRecord.Recorder.IDLE)
        }
        if (decision is AutoRecord.Decision.Start) {
            val event = decision.event
            st = st.copy(handled = st.handled + (AutoRecord.key(event) to event.end))
            store.update { st }
            st = begin(event) ?: st
        }
        store.update { st }
        return listOfNotNull(AutoRecord.nextWake(events, now, s.autoRecordWhich, st, recorderState(st)), nextReminder(events, now, st)).minOrNull()
    }

    /** Announces scheduled recordings whose reminder time has come; returns the state with them marked. */
    private fun remind(events: List<CalendarEvent>, now: Long, start: AutoRecord.State): AutoRecord.State {
        var st = start
        for (event in pendingReminders(events, st)) {
            val at = ScheduledRecordings.reminderAt(event, settings().scheduledRecordings) ?: continue
            if (now < at || now >= event.start) continue
            val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(event.start))
            notify(event, "${event.title} at $time", "KaliScriber will record it from $time.", ask = false, skippable = true)
            st = st.copy(reminded = st.reminded + (AutoRecord.key(event) to event.end))
        }
        return st
    }

    private fun nextReminder(events: List<CalendarEvent>, now: Long, st: AutoRecord.State): Long? =
        pendingReminders(events, st).mapNotNull { ScheduledRecordings.reminderAt(it, settings().scheduledRecordings) }.filter { it > now }.minOrNull()

    private fun pendingReminders(events: List<CalendarEvent>, st: AutoRecord.State): List<CalendarEvent> =
        events.filter { e ->
            val key = AutoRecord.key(e)
            ScheduledRecordings.isScheduled(e) && key !in st.reminded && key !in st.handled && key !in st.skipped
        }

    /**
     * Calendar events (when auto-record is on) and the recordings scheduled in the app. Only the
     * calendar's are kept in the state; scheduled ones are worked out from the settings each time.
     */
    private fun allEvents(st: AutoRecord.State, now: Long): List<CalendarEvent> {
        val scheduled = ScheduledRecordings.occurrences(settings().scheduledRecordings, now - 12 * HOUR_MS, now + 8 * 24 * HOUR_MS)
        return st.events + scheduled
    }

    private fun recorderState(st: AutoRecord.State): AutoRecord.Recorder {
        val active = st.active
        if (active != null && recorder.isCapturing(active.meetingId)) return AutoRecord.Recorder.AUTO
        return if (recorder.state.value is RecordingState.Recording) AutoRecord.Recorder.MANUAL else AutoRecord.Recorder.IDLE
    }

    /** Starts recording [event], or arranges for it to start; returns the new state if it started. */
    private suspend fun begin(event: CalendarEvent): AutoRecord.State? {
        if (!micAllowed()) {
            notify(event, "Couldn't record ${event.title}", "KaliScriber isn't allowed to use the microphone. Open the app to allow it.", ask = false)
            return null
        }
        // On screen, or on Android 10 and older, recording can start straight away.
        if (visibility.foreground || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return startLocked(event)
        val key = AutoRecord.key(event)
        notify(event, event.title, "Starting now. Tap Record if it doesn't start by itself.", ask = true)
        if (Settings.canDrawOverlays(context)) {
            runCatching { context.startActivity(AutoRecordActivity.intent(context, key)) }
                .onFailure { Log.w(TAG, "Couldn't open the recording screen", it) }
        }
        return null
    }

    /** Starts recording the occurrence [key] now; called from [AutoRecordActivity] while it is on screen. */
    suspend fun startFromScreen(key: String) {
        val wake = mutex.withLock {
            val st = store.value.value
            val event = allEvents(st, System.currentTimeMillis()).firstOrNull { AutoRecord.key(it) == key }
            if (event != null && st.active == null && recorder.state.value !is RecordingState.Recording &&
                System.currentTimeMillis() < event.end - AutoRecord.MIN_LEFT_MS
            ) {
                startLocked(event)?.let { store.update { _ -> it } }
            }
            val now = System.currentTimeMillis()
            AutoRecord.nextWake(allEvents(store.value.value, now), now, settings().autoRecordWhich, store.value.value, recorderState(store.value.value))
        }
        schedule(wake)
    }

    private suspend fun startLocked(event: CalendarEvent): AutoRecord.State? {
        val id = try {
            starting = event
            recorder.start(event.title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start recording ${event.title}", e)
            notify(event, "Couldn't record ${event.title}", e.message ?: "Recording didn't start.", ask = false)
            return null
        } finally {
            starting = null
        }
        cancelNotification()
        ScheduledRecordings.scheduleFor(event, settings().scheduledRecordings)?.let { applySchedule(id, it) }
        val active = AutoRecord.Active(AutoRecord.key(event), id, event.title, event.end)
        return store.value.value.copy(active = active, handled = store.value.value.handled + (active.key to event.end))
    }

    /** Gives a scheduled recording's meeting the format, number of people and agenda the user set. */
    private suspend fun applySchedule(meetingId: String, schedule: ScheduledRecording) {
        try {
            val custom = schedule.formatId?.takeIf { id -> settings().customFormats.any { it.id == id } }
            when {
                custom != null -> pipeline.setCustomFormat(meetingId, custom)
                schedule.type != null -> pipeline.setMeetingType(meetingId, schedule.type)
            }
            schedule.expectedSpeakers?.let { pipeline.setExpectedSpeakers(meetingId, it) }
            // The notes then say which agenda items the meeting covered.
            if (schedule.agenda.isNotBlank()) pipeline.setAgenda(meetingId, schedule.agenda)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't apply the schedule's settings", e)
        }
    }

    /** Doesn't record the occurrence [key]. */
    suspend fun skip(key: String, end: Long) {
        mutex.withLock { store.update { it.copy(skipped = it.skipped + (key to end)) } }
        cancelNotification()
        tick()
    }

    suspend fun unskip(key: String) {
        mutex.withLock { store.update { it.copy(skipped = it.skipped - key) } }
        tick()
    }

    /** Keeps the current automatic recording going past its meeting's end. */
    suspend fun keepRecording() {
        mutex.withLock { store.update { st -> st.copy(active = st.active?.copy(stopAtEnd = false)) } }
        tick()
    }

    /** The next meeting that will be recorded, for showing ahead of time. */
    fun upcoming(st: AutoRecord.State = state.value): CalendarEvent? {
        val now = System.currentTimeMillis()
        return AutoRecord.upcoming(allEvents(st, now), now, settings().autoRecordWhich, st)
    }

    /** Turns the periodic calendar check on or off to match the settings. */
    fun applySettings() {
        val work = WorkManager.getInstance(context)
        if (settings().autoRecord || settings().scheduledRecordings.isNotEmpty()) {
            work.enqueueUniquePeriodicWork(
                WORK, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoRecordWorker>(CHECK_MINUTES, TimeUnit.MINUTES).build(),
            )
        } else {
            work.cancelUniqueWork(WORK)
            schedule(null)
        }
    }

    // --- What Android allows ----------------------------------------------------------------------

    fun micAllowed(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Exact alarms, so recording starts on the minute rather than some minutes late. */
    fun exactAlarmsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /** Full-screen notifications, which start recording on the lock screen (Android 14 asks the user). */
    fun fullScreenAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** "Display over other apps", which lets recording start while the phone is in use. */
    fun overlayAllowed(): Boolean = Settings.canDrawOverlays(context)

    // --- Alarms and notifications --------------------------------------------------------------------

    private fun schedule(at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, AutoRecordReceiver::class.java).setAction(AutoRecordReceiver.ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (at == null) {
            alarms.cancel(pending)
            return
        }
        try {
            if (exactAlarmsAllowed()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    private fun notify(event: CalendarEvent, title: String, text: String, ask: Boolean, skippable: Boolean = ask) {
        val key = AutoRecord.key(event)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter((event.end - System.currentTimeMillis()).coerceAtLeast(60_000))
        val skip = PendingIntent.getBroadcast(
            context, 11,
            Intent(context, AutoRecordReceiver::class.java)
                .setAction(AutoRecordReceiver.ACTION_SKIP)
                .putExtra(AutoRecordReceiver.EXTRA_KEY, key)
                .putExtra(AutoRecordReceiver.EXTRA_END, event.end),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        if (skippable && !ask) builder.addAction(0, "Don't record", skip)
        if (ask) {
            val record = PendingIntent.getActivity(
                context, 10, AutoRecordActivity.intent(context, key),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(record)
                // On the lock screen this opens the recording screen by itself.
                .setFullScreenIntent(record, true)
                .addAction(0, "Record", record)
                .addAction(0, "Don't record", skip)
        }
        post(builder.build())
    }

    private fun notifyStopped(active: AutoRecord.Active) {
        val until = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(active.end))
        post(
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_mic)
                .setContentTitle("Stopped recording ${active.title}")
                .setContentText("It was due to end at $until. Its notes are being written.")
                .setSilent(true)
                .setAutoCancel(true)
                .build(),
        )
    }

    private fun post(notification: android.app.Notification) {
        createChannel(context)
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Notifications aren't allowed; recording still starts when the app can start it.
        }
    }

    private fun cancelNotification() = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)

    companion object {
        private const val TAG = "AutoRecord"
        private const val CHANNEL = "auto-record"
        private const val NOTIFICATION_ID = 79
        private const val WORK = "auto-record-check"
        /** How often the calendar is read again, so moved or new meetings are picked up. */
        private const val CHECK_MINUTES = 30L
        private const val READ_TIMEOUT_MS = 6_000L
        private const val STOP_WAIT_MS = 15_000L
        private const val HOUR_MS = 3_600_000L

        fun createChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Recording calendar meetings", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "When a meeting on your calendar starts or ends and is recorded by itself"
                },
            )
        }
    }
}
