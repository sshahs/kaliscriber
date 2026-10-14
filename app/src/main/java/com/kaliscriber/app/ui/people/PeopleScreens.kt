@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.people

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.core.people.People
import com.kaliscriber.core.tasks.ActionItems
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class PeopleViewModel(private val container: AppContainer) : ViewModel() {
    val people: StateFlow<List<People.Person>> = combine(container.meetings.meetings, container.voices.profiles) { meetings, voices ->
        People.directory(meetings, voices)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setDone(t: ActionItems.Tracked, done: Boolean) = viewModelScope.launch {
        container.meetings.setActionItemDone(t.meetingId, t.item.id, done)
    }
}

private fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun minutes(ms: Long): String = if (ms < 60_000) "<1 min" else "${ms / 60_000} min"

/** Everyone from your meetings, most recently seen first. */
@Composable
fun PeopleTab(onOpenPerson: (String) -> Unit, modifier: Modifier = Modifier) {
    val vm = appViewModel { PeopleViewModel(it) }
    val people by vm.people.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val shown = people.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Find a person") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        if (shown.isEmpty()) {
            item {
                EmptyState(
                    Icons.Filled.Groups,
                    if (people.isEmpty()) "No people yet" else "No matches",
                    "Name speakers in your meetings (or let the AI suggest names) and they appear here with their meetings and commitments.",
                )
            }
        }
        items(shown, key = { it.name }) { person ->
            Card(Modifier.fillMaxWidth().clickable { onOpenPerson(person.name) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (person.voiceProfileId != null) Icons.Filled.RecordVoiceOver else Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(person.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            listOfNotNull(
                                "${person.meetings.size} meeting${if (person.meetings.size == 1) "" else "s"}",
                                person.lastSeen?.let { "last ${date(it)}" },
                                person.openItems.size.takeIf { it > 0 }?.let { "$it open task${if (it == 1) "" else "s"}" },
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One person: their open tasks, notable quotes and the meetings they were in. */
@Composable
fun PersonScreen(
    name: String,
    onBack: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    onAsk: (String) -> Unit,
) {
    val vm = appViewModel { PeopleViewModel(it) }
    val people by vm.people.collectAsStateWithLifecycle()
    val person = people.firstOrNull { it.name.equals(name, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(person?.name ?: name) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        if (person == null) {
            EmptyState(Icons.Filled.Person, "Loading…", "", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    listOfNotNull(
                        "${person.meetings.size} meeting${if (person.meetings.size == 1) "" else "s"}",
                        person.speakingMs.takeIf { it > 0 }?.let { "spoke for ${minutes(it)}" },
                        if (person.voiceProfileId != null) "voice saved, recognised automatically" else "voice not saved yet",
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(8.dp))
                FilledTonalButton(onClick = {
                    onAsk("What has ${person.name} discussed, decided and committed to in recent meetings, and what is still open with them?")
                }) {
                    Icon(Icons.Filled.QuestionAnswer, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ask AI about ${person.name.substringBefore(' ')}")
                }
            }
            if (person.openItems.isNotEmpty()) {
                item { SectionTitle("Open tasks · ${person.openItems.size}") }
                items(person.openItems, key = { "open-${it.meetingId}/${it.item.id}" }) { t ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenMeeting(t.meetingId) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = false, onCheckedChange = { vm.setDone(t, it) })
                            Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                Text(t.item.task, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(t.item.dueDate?.let { "due $it" }, "from ${t.meetingTitle}").joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (person.highlights.isNotEmpty()) {
                item { SectionTitle("Notable moments") }
                items(person.highlights.take(10), key = { "h-${it.first.meetingId}-${it.second.text.hashCode()}" }) { (appearance, h) ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenMeeting(appearance.meetingId) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("“${h.text}”", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull(appearance.title, h.timestamp, date(appearance.date)).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (person.meetings.isNotEmpty()) {
                item { SectionTitle("Meetings") }
                items(person.meetings, key = { "m-${it.meetingId}" }) { m ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenMeeting(m.meetingId) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(date(m.date), m.speakingMs.takeIf { it > 0 }?.let { "spoke ${TranscriptFormatter.timestamp(it)}" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (person.doneItems.isNotEmpty()) {
                item {
                    Text(
                        "${person.doneItems.size} completed task${if (person.doneItems.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}
