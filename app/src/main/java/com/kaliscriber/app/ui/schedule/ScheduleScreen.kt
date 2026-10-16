@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.core.calendar.AutoRecord
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
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ScheduleViewModel(private val container: AppContainer) : ViewModel() {
    /** A schedule with its next occurrence (null once a one-off is over) and whether that one is skipped. */
    data class Entry(val schedule: ScheduledRecording, val nextStart: Long?, val nextEnd: Long?, val nextSkipped: Boolean)

    val rows: StateFlow<List<Entry>> = combine(container.settingsStore.value, container.autoRecorder.state) { settings, auto ->
        val now = System.currentTimeMillis()
        ScheduledRecordings.pruned(settings.scheduledRecordings, now).map { s ->
            val next = ScheduledRecordings.next(s, now)
            Entry(s, next?.start, next?.end, next != null && AutoRecord.key(next) in auto.skipped)
        }.sortedBy { it.nextStart ?: Long.MAX_VALUE }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(schedule: ScheduledRecording) = viewModelScope.launch {
        container.settingsStore.update { s ->
            val others = ScheduledRecordings.pruned(s.scheduledRecordings, System.currentTimeMillis()).filterNot { it.id == schedule.id }
            s.copy(scheduledRecordings = others + schedule)
        }
    }

    fun delete(id: String) = viewModelScope.launch {
        container.settingsStore.update { s -> s.copy(scheduledRecordings = s.scheduledRecordings.filterNot { it.id == id }) }
    }

    /** Skips (or un-skips) just the next time a repeating schedule records. */
    fun toggleSkipNext(row: Entry) {
        val start = row.nextStart ?: return
        val key = ScheduledRecordings.UID_PREFIX + row.schedule.id + "@" + start
        container.scope.launch {
            if (row.nextSkipped) container.autoRecorder.unskip(key) else container.autoRecorder.skip(key, row.nextEnd ?: start)
        }
    }

    fun overlayAllowed() = container.autoRecorder.overlayAllowed()
    fun fullScreenAllowed() = container.autoRecorder.fullScreenAllowed()
    fun exactAlarmsAllowed() = container.autoRecorder.exactAlarmsAllowed()
}

/** Recordings scheduled in the app: each starts and stops at its time, once or repeating. */
@Composable
fun ScheduleScreen(onBack: () -> Unit) {
    val vm = appViewModel { ScheduleViewModel(it) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var editing by remember { mutableStateOf<ScheduledRecording?>(null) }
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { resumed++ }
    fun askBasics() {
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scheduled recordings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = newSchedule() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Schedule") },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Recording starts and stops by itself at the times you set, once or every week. Meetings on your calendar can be recorded too: Settings → Recording & notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item(key = "allow-$resumed") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rows.isNotEmpty() && !vm.overlayAllowed()) {
                        Allow("To start while you're using the phone, allow KaliScriber to display over other apps. Without it you get a notification to tap.") {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                    }
                    if (rows.isNotEmpty() && !vm.fullScreenAllowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        Allow("To start while the phone is locked, allow full-screen notifications.") {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
                        }
                    }
                    if (rows.isNotEmpty() && !vm.exactAlarmsAllowed() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Allow("To start on the minute, allow alarms and reminders.") {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
            }
            if (rows.isEmpty()) {
                item { EmptyState(Icons.Filled.Schedule, "Nothing scheduled", "Tap Schedule to record a meeting at a set time.") }
            }
            items(rows, key = { it.schedule.id }) { row ->
                ScheduleCard(
                    row,
                    onEdit = { editing = row.schedule },
                    onDelete = { vm.delete(row.schedule.id) },
                    onSkip = { vm.toggleSkipNext(row) },
                )
            }
        }
    }

    editing?.let { schedule ->
        ScheduleEditor(
            initial = schedule,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                vm.save(it)
                askBasics()
            },
        )
    }
}

@Composable
private fun Allow(text: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp)) { Text("Allow") }
    }
}

@Composable
private fun ScheduleCard(row: ScheduleViewModel.Entry, onEdit: () -> Unit, onDelete: () -> Unit, onSkip: () -> Unit) {
    val s = row.schedule
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.title.ifBlank { "Scheduled recording" }, style = MaterialTheme.typography.titleMedium)
                val next = row.nextStart
                Text(
                    when {
                        next == null -> "Done"
                        row.nextSkipped -> "Skipping ${timeLabel(next, row.nextEnd)}"
                        else -> timeLabel(next, row.nextEnd)
                    } + if (s.repeat != ScheduledRecording.Repeat.NONE) " · ${s.repeat.label.lowercase()}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.repeat != ScheduledRecording.Repeat.NONE && next != null) {
                    TextButton(onClick = onSkip, contentPadding = PaddingValues(0.dp)) {
                        Text(if (row.nextSkipped) "Record the next one" else "Skip the next one")
                    }
                }
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${s.title}") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${s.title}") }
        }
    }
}

/** "Thu 15 Oct, 09:30–10:00". */
private fun timeLabel(start: Long, end: Long?): String {
    val zone = ZoneId.systemDefault()
    val day = DateTimeFormatter.ofPattern("EEE d MMM")
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    val s = Instant.ofEpochMilli(start).atZone(zone)
    val e = end?.let { Instant.ofEpochMilli(it).atZone(zone) }
    return "${s.format(day)}, ${s.format(time)}" + (e?.let { "–${it.format(time)}" } ?: "")
}

/** A new schedule: the next half hour, for half an hour. */
private fun newSchedule(): ScheduledRecording {
    val now = LocalDateTime.now().withSecond(0).withNano(0)
    val start = now.plusMinutes((30 - now.minute % 30).toLong())
    val zone = ZoneId.systemDefault()
    return ScheduledRecording(
        id = Ids.new(),
        title = "",
        start = start.atZone(zone).toInstant().toEpochMilli(),
        end = start.plusMinutes(30).atZone(zone).toInstant().toEpochMilli(),
    )
}

@Composable
private fun ScheduleEditor(initial: ScheduledRecording, onDismiss: () -> Unit, onSave: (ScheduledRecording) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val firstStart = remember { Instant.ofEpochMilli(initial.start).atZone(zone).toLocalDateTime() }
    val firstEnd = remember { Instant.ofEpochMilli(initial.end).atZone(zone).toLocalDateTime() }
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(firstStart.toLocalDate()) }
    var from by remember { mutableStateOf(firstStart.toLocalTime()) }
    var to by remember { mutableStateOf(firstEnd.toLocalTime()) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var picking by remember { mutableStateOf<String?>(null) }

    val start = date.atTime(from)
    // An end before the start means the meeting runs past midnight.
    val end = date.atTime(to).let { if (!it.isAfter(start)) it.plusDays(1) else it }
    val startMs = start.atZone(zone).toInstant().toEpochMilli()
    val endMs = end.atZone(zone).toInstant().toEpochMilli()
    val past = repeat == ScheduledRecording.Repeat.NONE && endMs <= System.currentTimeMillis()
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.title.isEmpty() && initial.repeat == ScheduledRecording.Repeat.NONE) "Schedule a recording" else "Edit schedule") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    title, { title = it },
                    label = { Text("Meeting name") },
                    placeholder = { Text("e.g. Weekly sync") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { picking = "date" }, modifier = Modifier.fillMaxWidth()) {
                    Text(date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) { Text("From ${from.format(timeFormat)}") }
                    OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) { Text("To ${to.format(timeFormat)}") }
                }
                Column(Modifier.selectableGroup()) {
                    ScheduledRecording.Repeat.entries.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = repeat == r, role = Role.RadioButton) { repeat = r }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = repeat == r, onClick = null)
                            Text(
                                when (r) {
                                    ScheduledRecording.Repeat.WEEKLY -> "Every ${date.dayOfWeek.name.lowercase().replaceFirstChar(Char::uppercase)}"
                                    else -> r.label
                                },
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                if (past) Text("That time has already passed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !past,
                onClick = { onSave(initial.copy(title = title.trim(), start = startMs, end = endMs, repeat = repeat)) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    when (picking) {
        "date" -> {
            val today = LocalDate.now()
            val state = rememberDatePickerState(
                initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(today)
                },
            )
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            ) { DatePicker(state) }
        }
        "from", "to" -> {
            val initialTime = if (picking == "from") from else to
            val state = rememberTimePickerState(initialTime.hour, initialTime.minute, android.text.format.DateFormat.is24HourFormat(context))
            AlertDialog(
                onDismissRequest = { picking = null },
                title = { Text(if (picking == "from") "Starts at" else "Ends at") },
                text = { TimePicker(state) },
                confirmButton = {
                    TextButton(onClick = {
                        val picked = LocalTime.of(state.hour, state.minute)
                        if (picking == "from") {
                            // Moving the start keeps the length the same.
                            val length = java.time.Duration.between(start, end)
                            from = picked
                            to = picked.plus(length)
                        } else {
                            to = picked
                        }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            )
        }
    }
}
