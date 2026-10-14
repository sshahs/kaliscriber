@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.people.PeopleTab
import com.kaliscriber.app.ui.tasks.TasksTab
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.search.MeetingSearch
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    onOpenMeeting: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenRecording: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Opens a meeting's transcript at a line (from a search result). */
    onOpenMeetingAt: (meetingId: String, segmentId: String?) -> Unit = { id, _ -> onOpenMeeting(id) },
    /** Opens "Ask all meetings", optionally asking [question] straight away. */
    onAskAll: (question: String?) -> Unit = {},
    onOpenPerson: (String) -> Unit = {},
    startTab: Int = 0,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val needsSetup by vm.needsSetup.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(startTab.coerceIn(0, 3)) }
    var confirmDelete by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun startRecording() = scope.launch {
        vm.startRecording(null)
            .onSuccess { onOpenRecording() }
            .onFailure { snackbar.showSnackbar(it.message ?: "Could not start recording") }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.RECORD_AUDIO] == true) startRecording()
        else scope.launch { snackbar.showSnackbar("Microphone permission is needed to record meetings") }
    }
    fun requestRecording() {
        if (recording !is RecordingState.Idle) {
            onOpenRecording()
            return
        }
        val needed = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startRecording() else permissions.launch(needed.toTypedArray())
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            snackbar.showSnackbar("Importing audio…")
            vm.importAudio(uri)
                .onSuccess(onOpenMeeting)
                .onFailure { snackbar.showSnackbar(it.message ?: "Import failed") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("KaliScriber") },
                actions = {
                    IconButton(onClick = { onAskAll(null) }) {
                        Icon(Icons.Filled.QuestionAnswer, contentDescription = "Ask all meetings")
                    }
                    IconButton(onClick = { importer.launch(arrayOf("audio/*", "video/mp4")) }) {
                        Icon(Icons.Filled.AudioFile, contentDescription = "Import audio")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab >= 2) {
                // Tasks and People have no create action; they come from meeting summaries.
            } else if (tab == 0) {
                ExtendedFloatingActionButton(
                    onClick = ::requestRecording,
                    icon = { Icon(if (recording is RecordingState.Idle) Icons.Filled.Mic else Icons.Filled.FiberManualRecord, null) },
                    text = { Text(if (recording is RecordingState.Idle) "Record meeting" else "Recording…") },
                )
            } else {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { onOpenNote(vm.createNote()) } },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New note") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Meetings") }, icon = { Icon(Icons.Filled.Groups, null) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Notes") }, icon = { Icon(Icons.Filled.EditNote, null) })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Tasks") }, icon = { Icon(Icons.Filled.TaskAlt, null) })
                Tab(selected = tab == 3, onClick = { tab = 3 }, text = { Text("People") }, icon = { Icon(Icons.Filled.Person, null) })
            }
            if (tab == 2) {
                TasksTab(onOpenMeeting = onOpenMeeting, modifier = Modifier.weight(1f))
            } else if (tab == 3) {
                PeopleTab(onOpenPerson = onOpenPerson, modifier = Modifier.weight(1f))
            } else {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = { Text(if (tab == 0) "Search meetings, people, transcripts" else "Search notes") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (needsSetup) {
                        item {
                            BannerCard(
                                icon = { Icon(Icons.Filled.Key, null) },
                                title = "Finish setting up",
                                body = "Download an on-device speech model for private transcription, and add a key for your AI model " +
                                    "(Command Code, OpenAI, Claude, Gemini, Ollama Cloud…). Tap to open Settings.",
                                onClick = onOpenSettings,
                            )
                        }
                    }
                    val active = recording
                    if (active is RecordingState.Recording) {
                        item {
                            BannerCard(
                                icon = { Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error) },
                                title = if (active.paused) "Recording paused" else "Recording in progress",
                                body = "${TranscriptFormatter.timestamp(active.elapsedMs)} · tap to view the live transcript",
                                onClick = onOpenRecording,
                            )
                        }
                    }
                    if (tab == 0 && query.isNotBlank()) {
                        item {
                            BannerCard(
                                icon = { Icon(Icons.Filled.QuestionAnswer, null) },
                                title = "Ask AI across all meetings",
                                body = "\"${query.trim()}\"",
                                onClick = { onAskAll(query.trim()) },
                            )
                        }
                        if (searchResults.isEmpty()) {
                            item { EmptyState(Icons.Filled.Search, "No matches", "Try other words, or ask the AI above.") }
                        }
                        items(searchResults, key = { it.meeting.id }) { result ->
                            SearchResultRow(result, onOpen = { segmentId -> onOpenMeetingAt(result.meeting.id, segmentId) })
                        }
                    } else if (tab == 0) {
                        if (meetings.isEmpty()) {
                            item {
                                EmptyState(
                                    Icons.Filled.Groups,
                                    if (query.isBlank()) "No meetings yet" else "No matches",
                                    if (query.isBlank()) "Record a meeting to get a live transcript, summary, action items and answers to your questions." else "Try a different search.",
                                )
                            }
                        }
                        items(meetings, key = { it.id }) { meeting ->
                            MeetingRow(meeting, onClick = { onOpenMeeting(meeting.id) }, onDelete = {
                                confirmDelete = { vm.deleteMeeting(meeting.id) }
                            })
                        }
                    } else {
                        if (notes.isEmpty()) {
                            item {
                                EmptyState(
                                    Icons.Filled.EditNote,
                                    if (query.isBlank()) "No notes yet" else "No matches",
                                    if (query.isBlank()) "Type or dictate notes and let AI tidy, summarise and pull out action items." else "Try a different search.",
                                )
                            }
                        }
                        items(notes, key = { it.id }) { note ->
                            NoteRow(note, onClick = { onOpenNote(note.id) }, onDelete = { confirmDelete = { vm.deleteNote(note.id) } })
                        }
                    }
                }
            }
        }
    }

    confirmDelete?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete?") },
            text = { Text("This permanently deletes it, including any recording.") },
            confirmButton = {
                TextButton(onClick = {
                    action()
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BannerCard(icon: @Composable () -> Unit, title: String, body: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            icon()
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun MeetingRow(meeting: Meeting, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(meeting.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    add(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.createdAt)))
                    if (meeting.durationMs > 0) add(TranscriptFormatter.timestamp(meeting.durationMs))
                    if (meeting.speakers.isNotEmpty()) add("${meeting.speakers.size} speakers")
                    meeting.insights?.actionItems?.count { !it.done }?.takeIf { it > 0 }?.let { add("$it open tasks") }
                }.joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val preview = meeting.insights?.summary?.takeIf { it.isNotBlank() } ?: meeting.transcriptPreview()
                if (preview.isNotBlank()) {
                    Text(preview, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                StatusLabel(meeting.status)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun StatusLabel(status: MeetingStatus) {
    val text = when (status) {
        MeetingStatus.RECORDING -> "Recording"
        MeetingStatus.TRANSCRIBING -> "Transcribing…"
        MeetingStatus.ANALYZING -> "Generating insights…"
        MeetingStatus.ERROR -> "Needs attention"
        MeetingStatus.READY -> return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.ANALYZING) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (status == MeetingStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(note.title.ifBlank { "Untitled note" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (note.content.isNotBlank()) {
                    Text(note.content, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete") }
        }
    }
}

@Composable
private fun SearchResultRow(result: MeetingSearch.Result, onOpen: (segmentId: String?) -> Unit) {
    val meeting = result.meeting
    Card(Modifier.fillMaxWidth().clickable { onOpen(null) }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(meeting.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(meeting.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result.hits.forEach { hit ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(hit.segmentId) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        when (hit.field) {
                            MeetingSearch.Field.TRANSCRIPT -> TranscriptFormatter.timestamp(hit.startMs ?: 0)
                            MeetingSearch.Field.TITLE -> "Title"
                            MeetingSearch.Field.PEOPLE -> "People"
                            MeetingSearch.Field.SUMMARY -> "Summary"
                            MeetingSearch.Field.ACTION_ITEM -> "Task"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
