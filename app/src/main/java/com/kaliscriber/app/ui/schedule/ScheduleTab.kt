package com.kaliscriber.app.ui.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.settings.SettingsPage
import com.kaliscriber.core.calendar.AutoRecord
import com.kaliscriber.core.calendar.CalendarEvent
import com.kaliscriber.core.calendar.ScheduledRecording
import com.kaliscriber.core.calendar.ScheduledRecordings
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ScheduleViewModel(private val container: AppContainer) : ViewModel() {
    /** One time something will be recorded: a scheduled recording or a calendar meeting. */
    data class Upcoming(
        val event: CalendarEvent,
        val schedule: ScheduledRecording?,
        val skipped: Boolean,
        val recordingNow: Boolean,
        val format: String?,
        /** On a day recording is paused. */
        val paused: Boolean = false,
    ) {
        val key: String get() = AutoRecord.key(event)
    }

    /** A schedule with its next time (null once a one-off is over). */
    data class Entry(val schedule: ScheduledRecording, val next: CalendarEvent?, val repeat: String, val format: String?)

    data class State(
        val days: List<Pair<String, List<Upcoming>>> = emptyList(),
        val schedules: List<Entry> = emptyList(),
        val calendarOn: Boolean = false,
        /** Nothing is recorded by itself up to the end of this day. */
        val pausedUntil: LocalDate? = null,
    )

    val state: StateFlow<State> = combine(container.settingsStore.value, container.autoRecorder.state, container.recorder.state) { settings, auto, recording ->
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val custom = settings.customFormats
        val activeKey = auto.active?.key?.takeIf { recording is RecordingState.Recording }
        val scheduled = ScheduledRecordings.occurrences(settings.scheduledRecordings, now, now + WEEK_MS, zone).map { e ->
            val s = ScheduledRecordings.scheduleFor(e, settings.scheduledRecordings)
            Upcoming(e, s, AutoRecord.key(e) in auto.skipped, AutoRecord.key(e) == activeKey, s?.let { ScheduledRecordings.formatLabel(it, custom) })
        }
        // Calendar meetings are read a day ahead; they are listed when calendar auto-record is on.
        val calendar = if (!settings.autoRecord) emptyList() else auto.events
            .filter { it.end > now && AutoRecord.wanted(it, settings.autoRecordWhich, auto.copy(skipped = emptyMap())) }
            .map { e -> Upcoming(e, null, AutoRecord.key(e) in auto.skipped, AutoRecord.key(e) == activeKey, null) }
        val today = LocalDate.now(zone)
        val pausedUntil = settings.recordingPausedUntil?.let { runCatching { LocalDate.parse(it) }.getOrNull() }?.takeIf { !it.isBefore(today) }
        val days = (scheduled + calendar)
            .map { it.copy(paused = ScheduledRecordings.paused(it.event, settings.recordingPausedUntil, zone)) }
            .sortedBy { it.event.start }
            .groupBy { Instant.ofEpochMilli(it.event.start).atZone(zone).toLocalDate() }
            .map { (day, list) -> dayLabel(day, today) to list }
        val schedules = ScheduledRecordings.pruned(settings.scheduledRecordings, now).map { s ->
            Entry(s, ScheduledRecordings.next(s, now, zone), ScheduledRecordings.repeatLabel(s, zone), ScheduledRecordings.formatLabel(s, custom))
        }.sortedWith(compareBy<Entry> { !it.schedule.enabled }.thenBy { it.next?.start ?: Long.MAX_VALUE })
        State(days, schedules, settings.autoRecord, pausedUntil)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), State())

    fun toggleSkip(item: Upcoming) {
        container.scope.launch {
            if (item.skipped) container.autoRecorder.unskip(item.key) else container.autoRecorder.skip(item.key, item.event.end)
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch {
        container.settingsStore.update { s ->
            s.copy(scheduledRecordings = s.scheduledRecordings.map { if (it.id == id) it.copy(enabled = enabled) else it })
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        container.settingsStore.update { s -> s.copy(scheduledRecordings = s.scheduledRecordings.filterNot { it.id == id }) }
    }

    /** Records nothing by itself up to the end of [day]; null resumes. */
    fun pauseUntil(day: LocalDate?) = viewModelScope.launch {
        container.settingsStore.update { it.copy(recordingPausedUntil = day?.toString()) }
    }

    /** Saves a copy of [schedule] and hands its id to [then], to edit it. */
    fun duplicate(schedule: ScheduledRecording, then: (String) -> Unit) = viewModelScope.launch {
        val copy = ScheduledRecordings.duplicate(schedule, Ids.new())
        container.settingsStore.update { s -> s.copy(scheduledRecordings = s.scheduledRecordings + copy) }
        then(copy.id)
    }

    fun overlayAllowed() = container.autoRecorder.overlayAllowed()
    fun fullScreenAllowed() = container.autoRecorder.fullScreenAllowed()
    fun exactAlarmsAllowed() = container.autoRecorder.exactAlarmsAllowed()
    fun micAllowed() = container.autoRecorder.micAllowed()

    private companion object {
        const val WEEK_MS = 7L * 24 * 3_600_000
    }
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    else -> day.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

private fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(TIME)

/**
 * The Schedule tab: what will be recorded in the coming week (scheduled recordings and calendar
 * meetings), the user's schedules, and what Android still has to allow.
 */
@Composable
fun ScheduleTab(
    onEdit: (String?) -> Unit,
    onOpenSettingsPage: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = appViewModel { ScheduleViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<ScheduledRecording?>(null) }
    var pickingPause by remember { mutableStateOf(false) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumed++ }
    val anything = state.schedules.any { it.schedule.enabled } || state.calendarOn

    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (anything) {
            item(key = "allow-$resumed") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (!vm.micAllowed()) {
                        Allow("KaliScriber isn't allowed to use the microphone, so nothing can be recorded.") {
                            val needed = buildList {
                                add(Manifest.permission.RECORD_AUDIO)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
                            }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                            askPermissions.launch(needed.toTypedArray())
                        }
                    }
                    if (!vm.overlayAllowed()) {
                        Allow("To start while you're using the phone, allow KaliScriber to display over other apps. Without it you get a notification to tap.") {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    }
                    if (!vm.fullScreenAllowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Allow("To start while the phone is locked, allow full-screen notifications.") {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                        }
                    }
                    if (!vm.exactAlarmsAllowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Allow("To start on the minute, allow alarms and reminders.") {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
            }
        }

        item(key = "pause") {
            PauseCard(state.pausedUntil, onPause = { pickingPause = true }, onResume = { vm.pauseUntil(null) })
        }

        item(key = "coming-up") { Heading("Coming up") }
        if (state.days.isEmpty()) {
            item(key = "nothing") {
                EmptyState(
                    Icons.Filled.Schedule,
                    "Nothing to record this week",
                    "Tap Schedule to record a meeting at a set time, once or on repeat.",
                )
            }
        }
        state.days.forEach { (day, list) ->
            item(key = "day-$day") {
                Text(day, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
            }
            items(list, key = { "up-${it.key}" }) { item ->
                UpcomingRow(item, onSkip = { vm.toggleSkip(item) }, onEdit = { item.schedule?.let { onEdit(it.id) } })
            }
        }

        item(key = "calendar") {
            Card(
                Modifier.fillMaxWidth().clickable { onOpenSettingsPage(SettingsPage.RECORDING) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Calendar meetings", style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (state.calendarOn) "Recorded by themselves, and listed above" else "Off: turn on to record meetings from your calendar too",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        }

        item(key = "yours") { Heading("Your schedules") }
        if (state.schedules.isEmpty()) {
            item(key = "none") {
                Text("None yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(state.schedules, key = { "s-${it.schedule.id}" }) { entry ->
            ScheduleCard(
                entry,
                onEdit = { onEdit(entry.schedule.id) },
                onToggle = { vm.setEnabled(entry.schedule.id, it) },
                onDelete = { deleting = entry.schedule },
                onDuplicate = { vm.duplicate(entry.schedule) { id -> onEdit(id) } },
            )
        }
    }

    if (pickingPause) {
        PauseUntilDialog(onDismiss = { pickingPause = false }, onPick = { day ->
            pickingPause = false
            vm.pauseUntil(day)
        })
    }

    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${s.title.ifBlank { "Scheduled recording" }}”?") },
            text = { Text("It won't record again. Meetings it already recorded are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(s.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
}

@Composable
private fun Allow(text: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp)) { Text("Allow") }
    }
}

@Composable
private fun UpcomingRow(item: ScheduleViewModel.Upcoming, onSkip: () -> Unit, onEdit: () -> Unit) {
    val fromSchedule = item.schedule != null
    Card(Modifier.fillMaxWidth().then(if (fromSchedule) Modifier.clickable(onClickLabel = "Edit", onClick = onEdit) else Modifier)) {
        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when {
                    item.recordingNow -> Icons.Filled.FiberManualRecord
                    item.skipped -> Icons.Filled.EventBusy
                    fromSchedule -> Icons.Filled.Schedule
                    else -> Icons.Filled.Event
                },
                contentDescription = when {
                    item.recordingNow -> "Recording now"
                    item.skipped -> "Won't be recorded"
                    fromSchedule -> "Scheduled recording"
                    else -> "Calendar meeting"
                },
                tint = if (item.recordingNow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.event.title,
                    style = MaterialTheme.typography.titleSmall,
                    textDecoration = if (item.skipped || item.paused) TextDecoration.LineThrough else null,
                )
                val details = listOfNotNull(
                    "${time(item.event.start)}–${time(item.event.end)}",
                    if (fromSchedule) null else "from your calendar",
                    item.format,
                    "recording now".takeIf { item.recordingNow },
                    "won't record".takeIf { item.skipped },
                    "paused".takeIf { item.paused && !item.skipped },
                ).joinToString(" · ")
                Text(details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!item.recordingNow && !item.paused) {
                TextButton(onClick = onSkip) { Text(if (item.skipped) "Record" else "Skip") }
            }
        }
    }
}

@Composable
private fun ScheduleCard(
    entry: ScheduleViewModel.Entry,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    val s = entry.schedule
    Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Edit", onClick = onEdit)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { "Scheduled recording" }, style = MaterialTheme.typography.titleMedium)
                val next = entry.next
                val whenText = when {
                    next == null -> "Done"
                    else -> "${dayLabel(Instant.ofEpochMilli(next.start).atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now())}, " +
                        "${time(next.start)}–${time(next.end)}"
                }
                Text(
                    listOfNotNull(if (s.repeat == ScheduledRecording.Repeat.NONE) whenText else "Next: $whenText", entry.format).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.repeat.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Repeat, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(4.dp))
                        Text(entry.repeat, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                val until = s.untilDate?.takeIf { s.repeat != ScheduledRecording.Repeat.NONE }
                if (until != null) {
                    Text("Until ${until.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row {
                    TextButton(onClick = onDuplicate, contentPadding = PaddingValues(0.dp)) { Text("Duplicate") }
                    Spacer(Modifier.width(16.dp))
                    TextButton(onClick = onDelete, contentPadding = PaddingValues(0.dp)) { Text("Delete") }
                }
            }
            Switch(
                checked = s.enabled,
                onCheckedChange = onToggle,
                modifier = Modifier.semantics { contentDescription = "Record ${s.title.ifBlank { "this schedule" }}" },
            )
        }
    }
}

/** Pausing everything that records by itself for a while, such as a holiday. */
@Composable
private fun PauseCard(pausedUntil: LocalDate?, onPause: () -> Unit, onResume: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (pausedUntil != null) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PauseCircle, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (pausedUntil != null) {
                    Text("Paused until ${pausedUntil.format(DateTimeFormatter.ofPattern("EEEE d MMMM"))}", style = MaterialTheme.typography.titleSmall)
                    Text("Nothing is recorded by itself until then.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Away for a while?", style = MaterialTheme.typography.titleSmall)
                    Text("Pause scheduled and calendar recordings up to a date.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (pausedUntil != null) TextButton(onClick = onResume) { Text("Resume") } else TextButton(onClick = onPause) { Text("Pause") }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PauseUntilDialog(onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val state = androidx.compose.material3.rememberDatePickerState(
        initialSelectedDateMillis = today.plusDays(7).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : androidx.compose.material3.SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                !Instant.ofEpochMilli(utcTimeMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate().isBefore(today)
        },
    )
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
            }) { Text("Pause") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        androidx.compose.material3.DatePicker(state, title = { Text("Pause until the end of", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}
