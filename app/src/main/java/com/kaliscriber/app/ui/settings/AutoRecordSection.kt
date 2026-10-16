package com.kaliscriber.app.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.calendar.AutoRecord
import java.text.DateFormat
import java.util.Date

/** Recording calendar meetings by themselves: on/off, which calendars and events, and what Android must allow. */
@Composable
internal fun AutoRecordSection(settings: AppSettings, vm: SettingsViewModel) {
    val context = LocalContext.current
    val state by vm.autoRecordState.collectAsStateWithLifecycle()
    // Permissions change in the system's settings screens; look again on coming back.
    var resumed by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        resumed++
        onPauseOrDispose { }
    }
    val allowed = remember(resumed, settings) { vm.autoRecordPermissions() }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.update { it.copy(phoneCalendar = true) }
    }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumed++ }

    SectionCard("Record calendar meetings") {
        ToggleRow(
            "Record meetings by themselves",
            "Recording starts when a meeting on your calendar begins and stops when it ends. Meetings shorter than 5 minutes, longer than 8 hours or all day are left out.",
            settings.autoRecord,
        ) { v -> vm.update { it.copy(autoRecord = v) } }

        ToggleRow(
            "Use this phone's calendars",
            "Google, Exchange and any other calendar on the phone, as well as the CalDAV calendar under Export destinations.",
            settings.phoneCalendar && allowed.calendar,
        ) { v ->
            if (v && !allowed.calendar) askCalendar.launch(Manifest.permission.READ_CALENDAR)
            else vm.update { it.copy(phoneCalendar = v) }
        }
        if (!settings.autoRecord) return@SectionCard

        Column(Modifier.selectableGroup()) {
            AutoRecord.Which.entries.forEach { which ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = settings.autoRecordWhich == which, role = Role.RadioButton) {
                            vm.update { it.copy(autoRecordWhich = which) }
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = settings.autoRecordWhich == which, onClick = null)
                    Text(which.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 12.dp))
                }
            }
        }

        if (!allowed.anyCalendar) {
            Warning("No calendar to read yet. Turn on this phone's calendars above, or set up a CalDAV calendar under Export destinations.")
        }

        // The next meeting, so it can be skipped ahead of time.
        val next = vm.upcomingAutoRecord(state)
        if (next != null) {
            HorizontalDivider()
            val time = DateFormat.getTimeInstance(DateFormat.SHORT)
            val day = DateFormat.getDateInstance(DateFormat.MEDIUM)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Next: ${next.title}", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${day.format(Date(next.start))}, ${time.format(Date(next.start))}–${time.format(Date(next.end))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.skipAutoRecord(next) }) { Text("Don't record") }
            }
        }
        val skipped = state.events.filter { AutoRecord.key(it) in state.skipped && it.end > System.currentTimeMillis() }
        skipped.forEach { event ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Won't record: ${event.title}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.unskipAutoRecord(event) }) { Text("Record it") }
            }
        }

        // What Android has to allow, most important first.
        if (!allowed.mic) {
            Permission("KaliScriber isn't allowed to use the microphone.", "Allow") { askMic.launch(Manifest.permission.RECORD_AUDIO) }
        }
        if (!allowed.overlay) {
            Permission(
                "To start recording while you're using the phone, allow KaliScriber to display over other apps. Without it you get a notification to tap.",
                "Allow",
            ) {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
            }
        }
        if (!allowed.fullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Permission("To start recording while the phone is locked, allow full-screen notifications.", "Allow") {
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${context.packageName}")))
            }
        }
        if (!allowed.exactAlarms && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Permission("To start on the minute rather than a few minutes late, allow alarms and reminders.", "Allow") {
                context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
            }
        }
    }
}

@Composable
private fun Warning(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun Permission(text: String, button: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = onClick, modifier = Modifier.padding(start = 8.dp)) { Text(button) }
    }
}
