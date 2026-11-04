package com.kaliscriber.app.ui.meeting

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kaliscriber.app.ui.projects.ProjectDialog
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.projects.Project
import com.kaliscriber.core.projects.Projects

/** Which project the meeting is in, with a menu to change it, open it or make a new one. */
@Composable
internal fun ProjectChip(
    meeting: Meeting,
    projects: List<Project>,
    onPick: (String?) -> Unit,
    onCreate: (name: String, words: String, about: String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val current = Projects.of(meeting, projects)
    val auto = Projects.isAutomatic(meeting, projects)
    if (creating) {
        ProjectDialog(null, onDismiss = { creating = false }, onSave = { name, words, about, _ ->
            creating = false
            onCreate(name, words, about)
        })
    }
    Box {
        AssistChip(
            onClick = { open = true },
            label = { Text(current?.let { "Project: ${it.name}" + if (auto) " (by title)" else "" } ?: "Add to project") },
            leadingIcon = { Icon(Icons.Filled.Folder, null, modifier = Modifier.size(18.dp)) },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (current != null) {
                DropdownMenuItem(
                    text = { Text("Open ${current.name}") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                    onClick = { open = false; onOpen(current.id) },
                )
                HorizontalDivider()
            }
            projects.filter { !it.archived || it.id == current?.id }.forEach { p ->
                DropdownMenuItem(
                    text = { Text(p.name) },
                    leadingIcon = if (p.id == current?.id) ({ Icon(Icons.Filled.Check, contentDescription = "Chosen") }) else null,
                    onClick = { open = false; onPick(p.id) },
                )
            }
            if (current != null) {
                DropdownMenuItem(text = { Text("No project") }, onClick = { open = false; onPick(Projects.NONE) })
            }
            if (meeting.projectId != null && projects.any { !it.archived && Projects.matchesTitle(it, meeting.title) }) {
                DropdownMenuItem(text = { Text("Go by title words") }, onClick = { open = false; onPick(null) })
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("New project…") },
                leadingIcon = { Icon(Icons.Filled.Add, null) },
                onClick = { open = false; creating = true },
            )
        }
    }
}
