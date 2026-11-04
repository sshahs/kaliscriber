@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.schedule

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.recording.ExpectedSpeakersRow
import com.kaliscriber.core.ai.CustomFormat
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.calendar.ScheduledRecording
import com.kaliscriber.core.calendar.ScheduledRecording.Repeat
import com.kaliscriber.core.calendar.ScheduledRecordings
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

class ScheduleEditViewModel(private val container: AppContainer, id: String?) : ViewModel() {
    /** The schedule being edited, or a new one starting at the next half hour. */
    val initial: ScheduledRecording = container.settings().scheduledRecordings.firstOrNull { it.id == id } ?: newSchedule()
    val isNew: Boolean = container.settings().scheduledRecordings.none { it.id == id }
    val customFormats: List<CustomFormat> = container.settings().customFormats
    val projects: List<com.kaliscriber.core.projects.Project> = container.settings().projects.filter { !it.archived || it.id == initial.projectId }

    fun save(schedule: ScheduledRecording, done: () -> Unit) = viewModelScope.launch {
        container.settingsStore.update { s ->
            val others = ScheduledRecordings.pruned(s.scheduledRecordings, System.currentTimeMillis()).filterNot { it.id == schedule.id }
            s.copy(scheduledRecordings = others + schedule)
        }
        done()
    }

    fun delete(done: () -> Unit) = viewModelScope.launch {
        container.settingsStore.update { s -> s.copy(scheduledRecordings = s.scheduledRecordings.filterNot { it.id == initial.id }) }
        done()
    }

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
}

private val REMINDERS = listOf(0, 5, 10, 15, 30)

/** Creating or changing a scheduled recording: when, how often, and how its notes are written. */
@Composable
fun ScheduleEditScreen(id: String?, onBack: () -> Unit) {
    val vm = appViewModel(key = "schedule-${id ?: "new"}") { ScheduleEditViewModel(it, id) }
    val context = LocalContext.current
    val initial = vm.initial
    val zone = ZoneId.systemDefault()
    val firstStart = remember { Instant.ofEpochMilli(initial.start).atZone(zone).toLocalDateTime() }
    val firstEnd = remember { Instant.ofEpochMilli(initial.end).atZone(zone).toLocalDateTime() }
    var title by remember { mutableStateOf(initial.title) }
    var date by remember { mutableStateOf(firstStart.toLocalDate()) }
    var from by remember { mutableStateOf(firstStart.toLocalTime()) }
    var to by remember { mutableStateOf(firstEnd.toLocalTime()) }
    var repeat by remember { mutableStateOf(initial.repeat) }
    var days by remember { mutableStateOf(initial.days.toSet()) }
    var type by remember { mutableStateOf(initial.type) }
    var formatId by remember { mutableStateOf(initial.formatId) }
    var projectId by remember { mutableStateOf(initial.projectId?.takeIf { id -> vm.projects.any { it.id == id } }) }
    var people by remember { mutableStateOf(initial.expectedSpeakers) }
    var remind by remember { mutableStateOf(initial.remindMinutes) }
    var agenda by remember { mutableStateOf(initial.agenda) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var until by remember { mutableStateOf(initial.untilDate) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val start = date.atTime(from)
    // An end before the start means the meeting runs past midnight.
    val end = date.atTime(to).let { if (!it.isAfter(start)) it.plusDays(1) else it }
    val startMs = start.atZone(zone).toInstant().toEpochMilli()
    val endMs = end.atZone(zone).toInstant().toEpochMilli()
    val past = repeat == Repeat.NONE && endMs <= System.currentTimeMillis()
    val noDays = repeat == Repeat.CUSTOM && days.isEmpty()
    val endsBeforeStart = repeat != Repeat.NONE && until?.isBefore(date) == true
    val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    val askPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onBack() }
    fun built() = initial.copy(
        title = title.trim(), start = startMs, end = endMs, repeat = repeat,
        days = if (repeat == Repeat.CUSTOM) days.sorted() else emptyList(),
        type = type, formatId = formatId, expectedSpeakers = people, remindMinutes = remind,
        agenda = agenda.trim(), enabled = enabled, projectId = projectId,
        until = if (repeat == Repeat.NONE) null else until?.toString(),
    )
    fun save() = vm.save(built()) {
        // Recording later needs the microphone, and reminders need notifications.
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) onBack() else askPermissions.launch(needed.toTypedArray())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.isNew) "Schedule a recording" else "Edit schedule") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { save() }, enabled = !past && !noDays && !endsBeforeStart) { Text("Save") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                title, { title = it },
                label = { Text("Meeting name") },
                placeholder = { Text("e.g. Weekly sync") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Label("When")
            OutlinedButton(onClick = { picking = "date" }, modifier = Modifier.fillMaxWidth()) {
                Text((if (repeat == Repeat.NONE) "" else "Starting ") + date.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picking = "from" }, modifier = Modifier.weight(1f)) { Text("From ${from.format(timeFormat)}") }
                OutlinedButton(onClick = { picking = "to" }, modifier = Modifier.weight(1f)) { Text("To ${to.format(timeFormat)}") }
            }
            val minutes = Duration.between(start, end).toMinutes()
            Hint("${if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"} long")
            if (past) Text("That time has already passed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)

            Label("Repeat")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Repeat.entries.forEach { r ->
                    FilterChip(
                        selected = repeat == r,
                        onClick = {
                            repeat = r
                            if (r == Repeat.CUSTOM && days.isEmpty()) days = setOf(date.dayOfWeek.value)
                        },
                        label = {
                            Text(
                                when (r) {
                                    Repeat.WEEKLY -> "Every ${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}"
                                    Repeat.CUSTOM -> "Choose days"
                                    else -> r.label
                                },
                            )
                        },
                    )
                }
            }
            if (repeat == Repeat.CUSTOM) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { day ->
                        FilterChip(
                            selected = day.value in days,
                            onClick = { days = if (day.value in days) days - day.value else days + day.value },
                            label = { Text(day.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
                if (noDays) Text("Choose at least one day.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (repeat != Repeat.NONE) {
                Label("Ends")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = until == null, onClick = { until = null }, label = { Text("Never") })
                    FilterChip(
                        selected = until != null,
                        onClick = { picking = "until" },
                        label = { Text(until?.let { "After ${it.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy"))}" } ?: "On a date…") },
                    )
                }
                if (endsBeforeStart) Text("The end date is before the first day.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            HorizontalDivider()
            Label("Notes format")
            FormatPicker(type, formatId, vm.customFormats) { t, f ->
                type = t
                formatId = f
            }
            Hint("The sections the summary is written with. Auto guesses from the meeting's name.")

            if (vm.projects.isNotEmpty()) {
                Label("Project")
                ProjectPicker(projectId, vm.projects) { projectId = it }
                Hint("The recordings go in this project, with its decisions and open actions.")
            }

            ExpectedSpeakersRow(people, onSelect = { people = it })
            Hint("Knowing how many people take part helps tell their voices apart.")

            Label("Remind me")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                REMINDERS.forEach { m ->
                    FilterChip(selected = remind == m, onClick = { remind = m }, label = { Text(if (m == 0) "No reminder" else "$m min before") })
                }
            }

            OutlinedTextField(
                agenda, { agenda = it },
                label = { Text("Agenda (optional)") },
                placeholder = { Text("One item per line; the notes will say which were covered") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )

            Row(
                Modifier.fillMaxWidth().toggleable(value = enabled, role = Role.Switch, onValueChange = { enabled = it }).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Record", style = MaterialTheme.typography.bodyMedium)
                    Text("Turn off to pause this schedule without deleting it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = null)
            }

            if (!vm.isNew) {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete schedule", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this schedule?") },
            text = { Text("It won't record again. Meetings it already recorded are kept.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    when (picking) {
        "until" -> {
            val first = date
            val state = rememberDatePickerState(
                initialSelectedDateMillis = (until ?: first.plusMonths(1)).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                selectableDates = object : SelectableDates {
                    override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                        !Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate().isBefore(first)
                },
            )
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { until = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            ) { DatePicker(state, title = { Text("Last day it records", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) }) }
        }
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
                            val length = Duration.between(start, end)
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

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** No project, or one of the user's projects. */
@Composable
private fun ProjectPicker(projectId: String?, projects: List<com.kaliscriber.core.projects.Project>, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = projects.firstOrNull { it.id == projectId }?.name ?: "None (or by title words)"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose the project")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("None (or by title words)") }, onClick = { open = false; onPick(null) })
            projects.forEach { p ->
                DropdownMenuItem(text = { Text(p.name) }, onClick = { open = false; onPick(p.id) })
            }
        }
    }
}

/** Auto, a built-in format, or one of the user's own. */
@Composable
private fun FormatPicker(type: MeetingType?, formatId: String?, custom: List<CustomFormat>, onPick: (MeetingType?, String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = formatId?.let { id -> custom.firstOrNull { it.id == id }?.name } ?: type?.label ?: "Auto"
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose the notes format")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Auto") }, onClick = { open = false; onPick(null, null) })
            MeetingType.entries.forEach { t ->
                DropdownMenuItem(text = { Text(t.label) }, onClick = { open = false; onPick(t, null) })
            }
            if (custom.isNotEmpty()) HorizontalDivider()
            custom.forEach { f ->
                DropdownMenuItem(text = { Text(f.name) }, onClick = { open = false; onPick(null, f.id) })
            }
        }
    }
}
