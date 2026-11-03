package com.kaliscriber.app.ui.settings

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.sync.MarkdownFolderSync
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings

/** A folder (an Obsidian vault, say) kept with one Markdown note per meeting. */
@Composable
internal fun MarkdownFolderSection(settings: AppSettings, vm: SettingsViewModel) {
    val e = settings.export
    val status by vm.markdownStatus.collectAsStateWithLifecycle()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.chooseMarkdownFolder(uri)
    }
    SectionCard("Markdown folder") {
        Text(
            "Keep a folder, such as an Obsidian vault, with a Markdown note for every meeting: written when its notes are " +
                "ready and updated when they change. The folder follows the app, so edits made there are replaced the " +
                "next time the meeting changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (e.markdownFolder.isBlank()) {
            FilledTonalButton(onClick = { pick.launch(null) }) { Text("Choose a folder") }
        } else {
            val name = remember(e.markdownFolder) { vm.markdownFolderName(e.markdownFolder) }
            Text("Folder: ${name ?: "not available"}", style = MaterialTheme.typography.bodyMedium)
            ToggleRow("Include transcripts", "Otherwise notes have the summary, action items and decisions.", e.markdownTranscript) { v ->
                vm.updateExport { it.copy(markdownTranscript = v) }
            }
            when (val s = status) {
                MarkdownFolderSync.Status.Working -> LinearProgressIndicator(Modifier.fillMaxWidth())
                is MarkdownFolderSync.Status.Done -> Text(
                    "Up to date · " + DateUtils.getRelativeTimeSpanString(s.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                        if (s.written > 0) " · ${s.written} note${if (s.written == 1) "" else "s"} written" else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                is MarkdownFolderSync.Status.Failed -> Text(s.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                MarkdownFolderSync.Status.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::syncMarkdownNow, enabled = status != MarkdownFolderSync.Status.Working) { Text("Sync now") }
                OutlinedButton(onClick = { pick.launch(null) }) { Text("Change") }
                TextButton(onClick = vm::stopMarkdownSync) { Text("Stop") }
            }
        }
    }
}

/** Keeping exported tasks in step with the calendar both ways, with when it last happened. */
@Composable
internal fun TaskSyncRow(enabled: Boolean, vm: SettingsViewModel) {
    val status by vm.taskSyncStatus.collectAsStateWithLifecycle()
    ToggleRow(
        "Keep tasks in step both ways",
        "For meetings you've exported: ticking or editing an action item here updates its task, and ticking or editing the task in your calendar or tasks app updates the action item.",
        enabled,
    ) { v -> vm.updateExport { it.copy(caldavTaskSync = v) } }
    if (!enabled) return
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            when (val s = status) {
                com.kaliscriber.app.tasks.TaskSyncer.Status.Idle -> "Checks every half hour"
                com.kaliscriber.app.tasks.TaskSyncer.Status.Working -> "Syncing…"
                is com.kaliscriber.app.tasks.TaskSyncer.Status.Done ->
                    "Synced " + DateUtils.getRelativeTimeSpanString(s.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                        if (s.pushed + s.pulled > 0) " · ${s.pushed} sent, ${s.pulled} received" else ""
                is com.kaliscriber.app.tasks.TaskSyncer.Status.Failed -> s.message
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status is com.kaliscriber.app.tasks.TaskSyncer.Status.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = vm::syncTasksNow, enabled = status != com.kaliscriber.app.tasks.TaskSyncer.Status.Working) { Text("Sync now") }
    }
}
