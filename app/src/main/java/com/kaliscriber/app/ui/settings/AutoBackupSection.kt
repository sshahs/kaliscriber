@file:OptIn(ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.data.ExportSecret
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.backup.BackupEvery

/** Encrypted backups made by themselves into a folder, keeping the last few. */
@Composable
internal fun AutoBackupSection(settings: AppSettings, vm: SettingsViewModel) {
    val status by vm.autoBackupStatus.collectAsStateWithLifecycle()
    val secrets by vm.exportSecrets.collectAsStateWithLifecycle()
    val hasPassword = ExportSecret.BACKUP_PASSWORD in secrets
    var askPassword by remember { mutableStateOf(false) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseBackupFolder(uri)
    }

    SectionCard("Automatic backups") {
        Text(
            "An encrypted backup made by itself into a folder you choose, such as one synced with Google Drive or Nextcloud. " +
                "Only the newest are kept. Like any backup, it has no API keys.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            BackupEvery.entries.forEach { every ->
                FilterChip(
                    selected = settings.backupEvery == every,
                    onClick = { vm.update { it.copy(backupEvery = every) } },
                    label = { Text(every.label) },
                )
            }
        }
        if (settings.backupEvery == BackupEvery.OFF) return@SectionCard

        // Folder
        val folderName = remember(settings.backupFolder) { settings.backupFolder.takeIf { it.isNotBlank() }?.let(vm::markdownFolderName) }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                if (settings.backupFolder.isBlank()) "No folder chosen" else "Folder: ${folderName ?: "not available"}",
                style = MaterialTheme.typography.bodyMedium,
                color = if (settings.backupFolder.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = androidx.compose.ui.Modifier.weight(1f),
            )
            OutlinedButton(onClick = { pickFolder.launch(null) }) { Text(if (settings.backupFolder.isBlank()) "Choose" else "Change") }
        }

        // Password
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(
                if (hasPassword) "Password set (kept on this phone only)" else "No password set",
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasPassword) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                modifier = androidx.compose.ui.Modifier.weight(1f),
            )
            OutlinedButton(onClick = { askPassword = true }) { Text(if (hasPassword) "Change" else "Set") }
        }

        Text("Keep the newest", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(3, 5, 10).forEach { n ->
                FilterChip(selected = settings.backupKeep == n, onClick = { vm.update { it.copy(backupKeep = n) } }, label = { Text("$n") })
            }
        }
        ToggleRow("Include recordings", "Much larger: each backup holds all the audio.", settings.backupRecordings) { v ->
            vm.update { it.copy(backupRecordings = v) }
        }

        // How it went
        val now = System.currentTimeMillis()
        val error = status.lastError
        when {
            error != null && status.lastErrorAt >= status.lastAt -> Text(
                "Last try failed: $error",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            status.lastAt > 0 -> Text(
                "Last backup " + DateUtils.getRelativeTimeSpanString(status.lastAt, now, DateUtils.MINUTE_IN_MILLIS) + ": ${status.lastFile}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text("No automatic backup yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val ready = settings.backupFolder.isNotBlank() && hasPassword
        FilledTonalButton(onClick = vm::backUpAutomaticallyNow, enabled = ready) { Text("Back up now") }
        if (!ready) {
            Text(
                "Choose a folder and set a password to start.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (askPassword) {
        PasswordDialog(
            title = "Password for automatic backups",
            body = "Backups are encrypted with it. It is stored on this phone (protected by the Android Keystore) so backups can run by themselves, " +
                "but you'll need to type it to restore one on another phone, so keep a copy somewhere safe.",
            confirmLabel = "Save",
            confirmTwice = true,
            onDismiss = { askPassword = false },
            onConfirm = { password, _ ->
                askPassword = false
                vm.setBackupPassword(password)
            },
        )
    }
}

