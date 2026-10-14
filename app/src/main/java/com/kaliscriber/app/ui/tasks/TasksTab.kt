@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.core.tasks.ActionItems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class TasksViewModel(private val container: AppContainer) : ViewModel() {
    val items: StateFlow<List<ActionItems.Tracked>> = container.meetings.meetings
        .map { ActionItems.all(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Show only this person's items, or everyone's when null. */
    val owner = MutableStateFlow<String?>(null)
    val showDone = MutableStateFlow(false)

    fun setDone(t: ActionItems.Tracked, done: Boolean) = viewModelScope.launch {
        container.meetings.setActionItemDone(t.meetingId, t.item.id, done)
    }

    fun edit(t: ActionItems.Tracked, task: String, owner: String, due: LocalDate?) = viewModelScope.launch {
        container.meetings.editActionItem(t.meetingId, t.item.id) {
            it.copy(
                task = task.trim().ifBlank { it.task },
                owner = owner.trim().ifBlank { null },
                dueDateIso = due?.toString(),
                dueDate = due?.let(::dueLabel),
            )
        }
    }
}

private fun dueLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

/** Action items from every meeting, grouped by when they are due. */
@Composable
fun TasksTab(onOpenMeeting: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = appViewModel { TasksViewModel(it) }
    val items by vm.items.collectAsStateWithLifecycle()
    val owner by vm.owner.collectAsStateWithLifecycle()
    val showDone by vm.showDone.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ActionItems.Tracked?>(null) }
    val today = LocalDate.now()

    val owners = remember(items) { ActionItems.owners(items) }
    val filtered = items.filter { owner == null || it.item.owner.equals(owner, ignoreCase = true) }
    val groups = ActionItems.grouped(filtered, today).filter { (bucket, _) -> showDone || bucket != ActionItems.Bucket.DONE }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = owner == null, onClick = { vm.owner.value = null }, label = { Text("Everyone") })
                owners.take(12).forEach { name ->
                    FilterChip(selected = owner == name, onClick = { vm.owner.value = if (owner == name) null else name }, label = { Text(name) })
                }
                FilterChip(selected = showDone, onClick = { vm.showDone.value = !showDone }, label = { Text("Show done") })
            }
        }
        if (groups.none { it.first != ActionItems.Bucket.DONE }) {
            item {
                EmptyState(
                    Icons.Filled.TaskAlt,
                    if (items.isEmpty()) "No action items yet" else "All done",
                    if (items.isEmpty()) "Action items from your meeting summaries show up here, grouped by when they're due."
                    else "Nothing open${owner?.let { " for $it" } ?: ""}. Tap \"Show done\" to see finished items.",
                )
            }
        }
        groups.forEach { (bucket, list) ->
            item(key = "header-${bucket.name}") {
                Text(
                    "${bucket.title} · ${list.size}",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (bucket == ActionItems.Bucket.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
                )
            }
            items(list, key = { "${it.meetingId}/${it.item.id}" }) { t ->
                TaskRow(
                    t,
                    overdue = bucket == ActionItems.Bucket.OVERDUE,
                    onToggle = { vm.setDone(t, it) },
                    onOpen = { onOpenMeeting(t.meetingId) },
                    onEdit = { editing = t },
                )
            }
        }
    }

    editing?.let { t ->
        EditTaskDialog(
            t,
            onDismiss = { editing = null },
            onSave = { task, who, due ->
                vm.edit(t, task, who, due)
                editing = null
            },
        )
    }
}

@Composable
private fun TaskRow(t: ActionItems.Tracked, overdue: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit, onEdit: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = t.item.done, onCheckedChange = onToggle)
            Column(Modifier.weight(1f)) {
                Text(
                    t.item.task,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (t.item.done) TextDecoration.LineThrough else null,
                )
                val meta = listOfNotNull(
                    t.item.owner,
                    t.due?.let { "due ${dueLabel(it)}" } ?: t.item.dueDate?.let { "due $it" },
                    t.item.priority?.takeIf { it.isNotBlank() }?.let { "$it priority" },
                    "from ${t.meetingTitle}",
                ).joinToString(" · ")
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = "Edit") }
        }
    }
}

@Composable
private fun EditTaskDialog(t: ActionItems.Tracked, onDismiss: () -> Unit, onSave: (String, String, LocalDate?) -> Unit) {
    var task by remember { mutableStateOf(t.item.task) }
    var owner by remember { mutableStateOf(t.item.owner.orEmpty()) }
    var due by remember { mutableStateOf(t.due) }
    var picking by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit action item") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = task, onValueChange = { task = it }, label = { Text("Task") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = owner, onValueChange = { owner = it }, label = { Text("Owner") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }) { Text(due?.let { "Due ${dueLabel(it)}" } ?: "Set due date") }
                    if (due != null) TextButton(onClick = { due = null }) { Text("Clear") }
                }
                Text(
                    "Your changes are kept if the summary is generated again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(task, owner, due) }, enabled = task.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (due ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DatePicker(state = state)
        }
    }
}
