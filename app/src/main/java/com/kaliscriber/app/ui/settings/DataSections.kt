package com.kaliscriber.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.backup.BackupManager
import com.kaliscriber.app.storage.formatBytes
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.store.RecordingRetention

@Composable
internal fun StorageSection(settings: AppSettings, vm: SettingsViewModel) {
    val usage by vm.storage.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.measureStorage() }
    var menuOpen by remember { mutableStateOf(false) }
    // Days chosen in the menu, with what keeping recordings that long would delete now.
    var confirm by remember { mutableStateOf<Triple<Int, Int, Long>?>(null) }

    SectionCard("Storage") {
        val u = usage
        if (u == null) {
            Text("Measuring…", style = MaterialTheme.typography.bodySmall)
        } else {
            Text("KaliScriber uses ${formatBytes(u.total)} on this phone.", style = MaterialTheme.typography.bodyMedium)
            StorageLine("Recordings", u.recordings)
            StorageLine("Meetings, transcripts & notes", u.meetings)
            StorageLine("On-device models", u.models)
            StorageLine("Search index", u.searchIndex)
            if (u.cache > 0) StorageLine("Temporary files", u.cache)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Keep recordings", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Older recordings are deleted to save space. Transcripts, summaries and notes stay; meetings that " +
                        "weren't transcribed keep their audio.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { menuOpen = true }) { Text(RecordingRetention.label(settings.keepRecordingsDays)) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    RecordingRetention.CHOICES.forEach { days ->
                        DropdownMenuItem(
                            text = { Text(RecordingRetention.label(days)) },
                            onClick = {
                                menuOpen = false
                                val (count, bytes) = vm.recordingsDue(days)
                                if (count == 0) vm.keepRecordings(days) else confirm = Triple(days, count, bytes)
                            },
                        )
                    }
                }
            }
        }
    }

    confirm?.let { (days, count, bytes) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete $count recording${if (count == 1) "" else "s"}?") },
            text = {
                Text(
                    "Recordings older than ${RecordingRetention.label(days)} will be deleted now, freeing ${formatBytes(bytes)}, " +
                        "and from now on as they get older. Their transcripts, summaries and notes stay.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    vm.keepRecordings(days)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StorageLine(label: String, bytes: Long) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(formatBytes(bytes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun BackupSection(vm: SettingsViewModel) {
    val state by vm.backupState.collectAsStateWithLifecycle()
    var withRecordings by remember { mutableStateOf(true) }
    var askBackupPassword by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf<CharArray?>(null) }
    var restoreUri by remember { mutableStateOf<Uri?>(null) }

    val createFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = backupPassword
        backupPassword = null
        if (uri != null && password != null) vm.backUp(uri, password, withRecordings) else password?.fill('\u0000')
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> restoreUri = uri }

    SectionCard("Backup & restore") {
        Text(
            "Saves your meetings with their transcripts and summaries, notes, saved voices, settings and AI usage in one " +
                "file encrypted with a password, for example to Google Drive or a computer. API keys and passwords aren't " +
                "included. Restoring adds what this phone doesn't have yet and never overwrites anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth().clickable { withRecordings = !withRecordings }, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = withRecordings, onCheckedChange = { withRecordings = it })
            Text("Include recordings (much larger)", style = MaterialTheme.typography.bodyMedium)
        }
        val working = state is BackupManager.State.Working
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { askBackupPassword = true }, enabled = !working) { Text("Back up") }
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }, enabled = !working) { Text("Restore") }
        }
        when (val s = state) {
            is BackupManager.State.Working -> {
                Text(s.progress?.let { "${s.message} · ${(it * 100).toInt()}%" } ?: "${s.message}…", style = MaterialTheme.typography.bodySmall)
                val progress = s.progress
                if (progress != null) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            is BackupManager.State.Done -> Outcome(s.message, error = false, onDismiss = vm::dismissBackup)
            is BackupManager.State.Failed -> Outcome(s.message, error = true, onDismiss = vm::dismissBackup)
            BackupManager.State.Idle -> Unit
        }
    }

    if (askBackupPassword) {
        PasswordDialog(
            title = "Choose a backup password",
            body = "You'll need it to restore the backup. It can't be recovered if you forget it.",
            confirmLabel = "Choose file",
            confirmTwice = true,
            onDismiss = { askBackupPassword = false },
            onConfirm = { password, _ ->
                askBackupPassword = false
                backupPassword = password
                createFile.launch(vm.backupFileName())
            },
        )
    }
    restoreUri?.let { uri ->
        PasswordDialog(
            title = "Restore backup",
            body = "Enter the password the backup was made with.",
            confirmLabel = "Restore",
            confirmTwice = false,
            settingsChoice = vm.settingsUntouched(),
            onDismiss = { restoreUri = null },
            onConfirm = { password, replaceSettings ->
                restoreUri = null
                vm.restore(uri, password, replaceSettings)
            },
        )
    }
}

@Composable
private fun Outcome(message: String, error: Boolean, onDismiss: () -> Unit) {
    Column {
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onDismiss) { Text("OK") }
    }
}

/**
 * Asks for a password; with [confirmTwice] it must be typed twice and be at least
 * [BackupManager.MIN_PASSWORD] characters. [settingsChoice], when not null, offers to take the
 * backup's settings (checked to start with if it is true).
 */
@Composable
internal fun PasswordDialog(
    title: String,
    body: String,
    confirmLabel: String,
    confirmTwice: Boolean,
    settingsChoice: Boolean? = null,
    onDismiss: () -> Unit,
    onConfirm: (CharArray, Boolean) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var replaceSettings by remember { mutableStateOf(settingsChoice == true) }
    val tooShort = confirmTwice && password.length < BackupManager.MIN_PASSWORD
    val mismatch = confirmTwice && again.isNotEmpty() && again != password
    val ok = password.isNotEmpty() && !tooShort && (!confirmTwice || again == password)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(body, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    supportingText = if (confirmTwice) {
                        { Text("At least ${BackupManager.MIN_PASSWORD} characters") }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (confirmTwice) {
                    OutlinedTextField(
                        value = again,
                        onValueChange = { again = it },
                        label = { Text("Password again") },
                        singleLine = true,
                        isError = mismatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        supportingText = if (mismatch) {
                            { Text("The passwords don't match") }
                        } else {
                            null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (settingsChoice != null) {
                    Row(Modifier.fillMaxWidth().clickable { replaceSettings = !replaceSettings }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = replaceSettings, onCheckedChange = { replaceSettings = it })
                        Text("Use the backup's settings instead of this phone's", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(password.toCharArray(), replaceSettings) }, enabled = ok) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
