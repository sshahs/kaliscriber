@file:OptIn(ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.ai.CustomFormat
import com.kaliscriber.core.ai.FormatSection
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.util.Ids

/** The user's own summary formats: list, add, edit and remove. */
@Composable
internal fun FormatsSection(settings: AppSettings, vm: SettingsViewModel) {
    var editing by remember { mutableStateOf<CustomFormat?>(null) }
    var removing by remember { mutableStateOf<CustomFormat?>(null) }
    SectionCard("Your summary formats") {
        Text(
            "Make a format for a kind of meeting you have often, with the sections you want in its notes. " +
                "Pick it on a meeting's Summary tab, or give it title words so matching meetings use it by themselves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        settings.customFormats.forEach { format ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(format.name, style = MaterialTheme.typography.titleSmall)
                    val sections = format.sections.filter { it.title.isNotBlank() }
                    val detail = listOfNotNull(
                        "${sections.size} section${if (sections.size == 1) "" else "s"}",
                        format.titleWords.takeIf { it.isNotBlank() }?.let { "auto for: $it" },
                    ).joinToString(" · ")
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { editing = format }) { Icon(Icons.Filled.Edit, contentDescription = "Edit ${format.name}") }
                IconButton(onClick = { removing = format }) { Icon(Icons.Filled.Delete, contentDescription = "Delete ${format.name}") }
            }
        }
        FilledTonalButton(onClick = { editing = CustomFormat(Ids.new(), "") }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text("New format", Modifier.padding(start = 8.dp))
        }
        Text(
            "Built in: " + MeetingType.entries.joinToString { it.label },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    editing?.let { format ->
        val isNew = settings.customFormats.none { it.id == format.id }
        FormatEditor(
            initial = format,
            isNew = isNew,
            onDismiss = { editing = null },
            onSave = { saved ->
                editing = null
                vm.update { s ->
                    val list = if (isNew) s.customFormats + saved else s.customFormats.map { if (it.id == saved.id) saved else it }
                    s.copy(customFormats = list)
                }
            },
        )
    }
    removing?.let { format ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Delete “${format.name}”?") },
            text = { Text("Meetings that used it keep their notes; written again, they get a format guessed from their title.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    vm.update { s -> s.copy(customFormats = s.customFormats.filterNot { it.id == format.id }) }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun FormatEditor(initial: CustomFormat, isNew: Boolean, onDismiss: () -> Unit, onSave: (CustomFormat) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var focus by remember { mutableStateOf(initial.focus) }
    var words by remember { mutableStateOf(initial.titleWords) }
    val sections = remember {
        mutableStateListOf<FormatSection>().apply { addAll(initial.sections.ifEmpty { listOf(FormatSection("")) }) }
    }
    val canSave = name.isNotBlank() && (sections.any { it.title.isNotBlank() } || focus.isNotBlank())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "New summary format" else "Edit format") },
        text = {
            Column(
                Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (isNew) {
                    Text("Start from", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MeetingType.entries.filter { it.sections.isNotEmpty() }.forEach { type ->
                            SuggestionChip(
                                onClick = {
                                    if (name.isBlank()) name = "My ${type.label}"
                                    focus = type.focus
                                    sections.clear()
                                    sections.addAll(type.sections.map { (title, guidance) -> FormatSection(title, guidance) })
                                },
                                label = { Text(type.label) },
                            )
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    focus, { focus = it },
                    label = { Text("What kind of meeting is it?") },
                    placeholder = { Text("e.g. A weekly review of support tickets") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    words, { words = it },
                    label = { Text("Use for titles with (optional)") },
                    placeholder = { Text("e.g. tickets, support review") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Sections", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                Text(
                    "They come after the usual summary, action items and decisions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sections.forEachIndexed { i, section ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedTextField(
                                section.title, { sections[i] = section.copy(title = it) },
                                label = { Text("Section ${i + 1}") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                section.guidance, { sections[i] = section.copy(guidance = it) },
                                label = { Text("What goes in it (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        IconButton(onClick = { sections.removeAt(i) }) { Icon(Icons.Filled.Close, contentDescription = "Remove section ${i + 1}") }
                    }
                }
                TextButton(onClick = { sections.add(FormatSection("")) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("Add section", Modifier.padding(start = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            focus = focus.trim(),
                            titleWords = words.trim(),
                            sections = sections.filter { it.title.isNotBlank() }.map { FormatSection(it.title.trim(), it.guidance.trim()) },
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
