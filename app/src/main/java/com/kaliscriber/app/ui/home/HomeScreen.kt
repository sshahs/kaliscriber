@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.input.ImeAction
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.library.MeetingList
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.kaliscriber.core.transcript.TranscriptFiles
import com.kaliscriber.app.LaunchRequest
import com.kaliscriber.app.data.Deletions
import com.kaliscriber.app.appContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.people.PeopleTab
import com.kaliscriber.app.ui.tasks.TasksTab
import com.kaliscriber.core.calendar.MeetingPrep
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.search.MeetingSearch
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** What the import button offers: recordings, videos of calls, and transcripts from Zoom, Teams or Meet. */
private val IMPORT_TYPES = arrayOf(
    "audio/*", "video/*", "text/*", "application/x-subrip", TranscriptFiles.DOCX_MIME, "application/octet-stream",
)

private const val TAB_MEETINGS = 0
private const val TAB_NOTES = 1
private const val TAB_TASKS = 2
private const val TAB_PEOPLE = 3

/** Words that start a question, so pressing Search on one asks across meetings instead. */
private val QUESTION = Regex("""(?i)^(what|who|when|where|why|how|which|did|do|does|is|are|was|were|can|could|should|will|has|have)\b.*|.*\?$""")

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
    onOpenRecap: () -> Unit = {},
    /** Opens first-run setup. */
    onOpenSetup: () -> Unit = onOpenSettings,
    startTab: Int = 0,
) {
    val vm = appViewModel { HomeViewModel(it) }
    val meetings by vm.meetings.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val searchResults by vm.searchResults.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val people by vm.people.collectAsStateWithLifecycle()
    val types by vm.types.collectAsStateWithLifecycle()
    val hasMeetings by vm.hasMeetings.collectAsStateWithLifecycle()
    val dueTasks by vm.dueTasks.collectAsStateWithLifecycle()
    val recording by vm.recording.collectAsStateWithLifecycle()
    val needsSetup by vm.needsSetup.collectAsStateWithLifecycle()
    val firstRun by vm.firstRun.collectAsStateWithLifecycle()
    LaunchedEffect(firstRun) { if (firstRun) onOpenSetup() }
    val prep by vm.prep.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(startTab.coerceIn(0, 3)) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current

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

    /** Deletes after a moment; the snackbar below offers Undo meanwhile. */
    fun delete(id: String, title: String, isNote: Boolean) = vm.deleteLater(id, title, isNote)

    // Undo for meetings and notes deleted here or from their own screen.
    LaunchedEffect(Unit) {
        val effect = this
        val announced = mutableSetOf<String>()
        vm.pendingDeletions.collect { list ->
            val fresh = list.lastOrNull { it.kind != Deletions.Kind.RECORDING && it.key !in announced } ?: return@collect
            announced += fresh.key
            effect.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar("Deleted “${fresh.label.take(40)}”", actionLabel = "Undo", duration = SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) vm.undoDelete(fresh)
            }
        }
    }

    // The Quick Settings tile and the widget open the app to start recording.
    val launches = appContainer().launches
    val launchRequest by launches.pending.collectAsStateWithLifecycle()
    LaunchedEffect(launchRequest) {
        if (launchRequest == LaunchRequest.Record) {
            launches.consume(LaunchRequest.Record)
            requestRecording()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            snackbar.showSnackbar("Importing…")
            vm.importAudio(uri)
                .onSuccess(onOpenMeeting)
                .onFailure { snackbar.showSnackbar(it.message ?: "Import failed") }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(listOf("Meetings", "Notes", "Tasks", "People")[tab]) },
                actions = {
                    IconButton(onClick = { importer.launch(IMPORT_TYPES) }) {
                        Icon(Icons.Filled.AudioFile, contentDescription = "Import a recording or transcript")
                    }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_MEETINGS, onClick = { tab = TAB_MEETINGS },
                    icon = { Icon(Icons.Filled.Groups, null) }, label = { Text("Meetings") },
                )
                NavigationBarItem(
                    selected = tab == TAB_NOTES, onClick = { tab = TAB_NOTES },
                    icon = { Icon(Icons.Filled.EditNote, null) }, label = { Text("Notes") },
                )
                NavigationBarItem(
                    selected = tab == TAB_TASKS, onClick = { tab = TAB_TASKS },
                    icon = {
                        BadgedBox(badge = { if (dueTasks > 0) Badge { Text("$dueTasks") } }) { Icon(Icons.Filled.TaskAlt, null) }
                    },
                    label = { Text("Tasks") },
                )
                NavigationBarItem(
                    selected = tab == TAB_PEOPLE, onClick = { tab = TAB_PEOPLE },
                    icon = { Icon(Icons.Filled.Person, null) }, label = { Text("People") },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == TAB_MEETINGS) {
                ExtendedFloatingActionButton(
                    onClick = ::requestRecording,
                    icon = { Icon(if (recording is RecordingState.Idle) Icons.Filled.Mic else Icons.Filled.FiberManualRecord, null) },
                    text = { Text(if (recording is RecordingState.Idle) "Record meeting" else "Recording…") },
                )
            } else if (tab == TAB_NOTES) {
                ExtendedFloatingActionButton(
                    onClick = { scope.launch { onOpenNote(vm.createNote()) } },
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New note") },
                )
            }
            // Tasks and People have no create action; they come from meeting summaries.
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tab == TAB_TASKS) {
                TasksTab(onOpenMeeting = onOpenMeeting, modifier = Modifier.weight(1f))
                return@Column
            }
            if (tab == TAB_PEOPLE) {
                PeopleTab(onOpenPerson = onOpenPerson, modifier = Modifier.weight(1f))
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text(if (tab == TAB_MEETINGS) "Search or ask about your meetings" else "Search notes") },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, contentDescription = "Clear search") }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    // A question goes to the AI; anything else just shows the matches below.
                    if (tab == TAB_MEETINGS && QUESTION.matches(query.trim())) onAskAll(query.trim())
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (tab == TAB_MEETINGS && query.isBlank()) {
                MeetingChips(
                    filter = filter,
                    people = people,
                    types = types,
                    showFilters = hasMeetings,
                    onAskAll = { onAskAll(null) },
                    onOpenRecap = onOpenRecap,
                    onFilter = { vm.filter.value = it },
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (needsSetup) {
                    item {
                        BannerCard(
                            icon = { Icon(Icons.Filled.Key, null) },
                            title = "Finish setting up",
                            body = "Choose how meetings are transcribed and which AI writes your notes. It takes about two minutes.",
                            onClick = onOpenSetup,
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
                if (tab == TAB_MEETINGS && query.isNotBlank()) {
                    item {
                        BannerCard(
                            icon = { Icon(Icons.Filled.QuestionAnswer, null) },
                            title = "Ask all meetings",
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
                } else if (tab == TAB_MEETINGS) {
                    val upcoming = prep
                    if (upcoming != null && active !is RecordingState.Recording) {
                        item {
                            PrepCard(
                                upcoming,
                                onPrepare = { onAskAll(MeetingPrep.question(upcoming.event)) },
                                onRecord = ::requestRecording,
                                onOpenMeeting = onOpenMeeting,
                                onOpenPerson = onOpenPerson,
                            )
                        }
                    }
                    if (meetings.isEmpty()) {
                        item {
                            if (filter.active) {
                                EmptyState(Icons.Filled.FilterList, "No meetings match", "Try other filters.") {
                                    TextButton(onClick = { vm.filter.value = MeetingList.Filter() }) { Text("Clear filters") }
                                }
                            } else {
                                EmptyState(
                                    Icons.Filled.Groups,
                                    "No meetings yet",
                                    "Record a meeting to get a live transcript, summary, action items and answers to your questions. " +
                                        "You can also import a recording or a Zoom, Teams or Meet transcript.",
                                )
                            }
                        }
                    }
                    MeetingList.grouped(meetings, System.currentTimeMillis()).forEach { (heading, group) ->
                        item(key = "heading-$heading") {
                            Text(
                                heading,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, start = 4.dp).semantics { heading() },
                            )
                        }
                        items(group, key = { it.id }) { meeting ->
                            SwipeToDelete(onDelete = { delete(meeting.id, meeting.title, isNote = false) }) {
                                MeetingRow(meeting, onClick = { onOpenMeeting(meeting.id) }, onDelete = { delete(meeting.id, meeting.title, isNote = false) })
                            }
                        }
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
                        val title = note.title.ifBlank { "Untitled note" }
                        SwipeToDelete(onDelete = { delete(note.id, title, isNote = true) }) {
                            NoteRow(note, onClick = { onOpenNote(note.id) }, onDelete = { delete(note.id, title, isNote = true) })
                        }
                    }
                }
            }
        }
    }
}

/** Ask all meetings, the weekly recap, and filters for the meeting list. */
@Composable
private fun MeetingChips(
    filter: MeetingList.Filter,
    people: List<String>,
    types: List<MeetingType>,
    showFilters: Boolean,
    onAskAll: () -> Unit,
    onOpenRecap: () -> Unit,
    onFilter: (MeetingList.Filter) -> Unit,
) {
    var personMenu by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(onClick = onAskAll, label = { Text("Ask all meetings") }, leadingIcon = { Icon(Icons.Filled.QuestionAnswer, null, Modifier.size(18.dp)) })
        AssistChip(onClick = onOpenRecap, label = { Text("Weekly recap") }, leadingIcon = { Icon(Icons.Filled.DateRange, null, Modifier.size(18.dp)) })
        if (!showFilters) return@Row
        FilterChip(
            selected = filter.needsAttention,
            onClick = { onFilter(filter.copy(needsAttention = !filter.needsAttention)) },
            label = { Text("Needs attention") },
        )
        if (people.isNotEmpty()) {
            Box {
                FilterChip(
                    selected = filter.person != null,
                    onClick = { personMenu = true },
                    label = { Text(filter.person ?: "Person") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = personMenu, onDismissRequest = { personMenu = false }) {
                    DropdownMenuItem(text = { Text("Anyone") }, onClick = { personMenu = false; onFilter(filter.copy(person = null)) })
                    people.take(30).forEach { name ->
                        DropdownMenuItem(text = { Text(name) }, onClick = { personMenu = false; onFilter(filter.copy(person = name)) })
                    }
                }
            }
        }
        if (types.size > 1) {
            Box {
                FilterChip(
                    selected = filter.type != null,
                    onClick = { typeMenu = true },
                    label = { Text(filter.type?.label ?: "Format") },
                    trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp)) },
                )
                DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    DropdownMenuItem(text = { Text("Any format") }, onClick = { typeMenu = false; onFilter(filter.copy(type = null)) })
                    types.forEach { type ->
                        DropdownMenuItem(text = { Text(type.label) }, onClick = { typeMenu = false; onFilter(filter.copy(type = type)) })
                    }
                }
            }
        }
    }
}

/** Swipe left to delete; the row's own delete action is offered to screen readers. */
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, CardDefaults.shape)
                    .padding(horizontal = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        content()
    }
}

/** The meeting in progress or coming up next on the calendar, with what is open with its invitees. */
@Composable
private fun PrepCard(
    prep: MeetingPrep.Prep,
    onPrepare: () -> Unit,
    onRecord: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
) {
    val event = prep.event
    val now = System.currentTimeMillis()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    val whenText = when {
        event.start <= now -> "Now, until ${time.format(Date(event.end))}"
        event.start - now < 60 * 60_000L -> "In ${((event.start - now) / 60_000).coerceAtLeast(1)} min · ${time.format(Date(event.start))}"
        else -> "${time.format(Date(event.start))}–${time.format(Date(event.end))}"
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Event, null)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(whenText, event.location).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (event.attendees.isNotEmpty()) {
                Text("With ${event.attendees.joinToString()}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            prep.people.filter { it.openItems.isNotEmpty() }.take(3).forEach { person ->
                Text(
                    "${person.name}: " + person.openItems.take(2).joinToString("; ") { it.item.task } +
                        if (person.openItems.size > 2) " (+${person.openItems.size - 2} more)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenPerson(person.name) },
                )
            }
            prep.lastMeeting?.let { last ->
                Text(
                    "Last time: ${last.title} (${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(last.createdAt))})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onOpenMeeting(last.id) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onPrepare) { Text("Prepare") }
                OutlinedButton(onClick = onRecord) {
                    Icon(Icons.Filled.FiberManualRecord, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Record")
                }
            }
        }
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
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).deleteAction(onDelete)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                StatusLabel(meeting)
            }
        }
    }
}

/** Lets screen readers delete a row, since swiping isn't available to them. */
private fun Modifier.deleteAction(onDelete: () -> Unit): Modifier = semantics {
    customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
}

@Composable
private fun StatusLabel(meeting: Meeting) {
    val status = meeting.status
    // While processing, say which step it is on ("Detecting speakers · 40%").
    val step = meeting.processingStep?.takeIf { status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.ANALYZING }
        ?.let { s -> meeting.processingProgress?.let { "$s · ${(it * 100).toInt()}%" } ?: "$s…" }
    val text = step ?: when (status) {
        MeetingStatus.RECORDING -> "Recording"
        MeetingStatus.TRANSCRIBING -> "Transcribing…"
        MeetingStatus.ANALYZING -> "Generating insights…"
        MeetingStatus.ERROR -> "Needs attention"
        MeetingStatus.READY -> if (MeetingList.needsAttention(meeting)) "Ready, with problems" else return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.ANALYZING) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (MeetingList.needsAttention(meeting)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun NoteRow(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).deleteAction(onDelete)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
