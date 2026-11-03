package com.kaliscriber.app.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.core.ai.Agenda
import com.kaliscriber.core.model.Meeting

/** The agenda: each item ticked or crossed by the notes, or as planned while there are none yet. */
@Composable
internal fun AgendaCard(meeting: Meeting, onEdit: () -> Unit) {
    val assessed = meeting.insights?.agenda.orEmpty()
    SectionCard("Agenda", Icons.Filled.Checklist) {
        if (assessed.isNotEmpty()) {
            val covered = assessed.count { it.covered }
            Text(
                "$covered of ${assessed.size} covered",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            assessed.forEach { a ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clearAndSetSemantics {
                            contentDescription = "${a.item}: ${if (a.covered) "covered" else "not covered"}. ${a.notes}"
                        },
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        if (a.covered) Icons.Filled.CheckCircle else Icons.Filled.RemoveCircleOutline,
                        contentDescription = null,
                        tint = if (a.covered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(a.item, style = MaterialTheme.typography.bodyMedium)
                        if (a.notes.isNotBlank()) {
                            Text(a.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        } else {
            Text(
                "Write the notes again to see which items were covered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Agenda.items(meeting.agenda).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.RadioButtonUnchecked, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.width(10.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        TextButton(onClick = onEdit) { Text("Edit agenda") }
    }
}

/** Editing the agenda; saving writes the notes again so they follow it. */
@Composable
internal fun AgendaDialog(initial: String, hasNotes: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.isBlank()) "Add an agenda" else "Edit agenda") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "One item per line." + if (hasNotes) " The notes are written again to say which items the meeting covered." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    text, { text = it },
                    placeholder = { Text("Budget\nHiring plan\nLaunch date") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
