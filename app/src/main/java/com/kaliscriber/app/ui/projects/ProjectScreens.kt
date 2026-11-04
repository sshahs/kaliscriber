@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.core.ask.AskScope
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.projects.Project
import com.kaliscriber.core.projects.Projects
import com.kaliscriber.core.transcript.TranscriptFormatter
import com.kaliscriber.core.util.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

class ProjectsViewModel(private val container: AppContainer) : ViewModel() {
    val projects: StateFlow<List<Project>> = container.settingsStore.value
        .map { it.projects }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), container.settings().projects)

    /** Every project's page, in the order they were made. */
    val overviews: StateFlow<List<Projects.Overview>?> = combine(container.meetings.meetings, projects) { meetings, projects ->
        projects.map { Projects.overview(it, meetings, projects) }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(project: Project) = container.settingsStore.let { store ->
        viewModelScope.launch {
            store.update { s ->
                val exists = s.projects.any { it.id == project.id }
                s.copy(projects = if (exists) s.projects.map { if (it.id == project.id) project else it } else s.projects + project)
            }
        }
    }

    /** Deletes the project; its meetings stay, just without it. */
    fun delete(id: String) = viewModelScope.launch {
        container.settingsStore.update { s ->
            s.copy(
                projects = s.projects.filterNot { it.id == id },
                scheduledRecordings = s.scheduledRecordings.map { if (it.projectId == id) it.copy(projectId = null) else it },
            )
        }
        container.meetings.all().filter { it.projectId == id }.forEach { m -> container.pipeline.setProject(m.id, null) }
    }

    fun newProject(name: String, words: String, about: String) =
        Project(Ids.new(), name.trim(), Projects.words(words), about.trim(), createdAt = System.currentTimeMillis())

    fun setDone(action: Projects.OpenAction, done: Boolean) = viewModelScope.launch {
        container.meetings.setActionItemDone(action.meeting.id, action.item.id, done)
    }

    /** Ask all meetings is limited to [project] until the user clears it. */
    fun askAbout(project: Project) {
        container.askScope.value = AskScope(project = project)
    }
}

private fun date(ms: Long): String = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))

private fun hours(ms: Long): String = when {
    ms < 60_000 -> ""
    ms < 3_600_000 -> "${ms / 60_000} min"
    else -> String.format(java.util.Locale.getDefault(), "%.1f h", ms / 3_600_000.0)
}

/** All projects, with how many meetings and open actions each has. */
@Composable
fun ProjectsScreen(onBack: () -> Unit, onOpenProject: (String) -> Unit) {
    val vm = appViewModel { ProjectsViewModel(it) }
    val overviews by vm.overviews.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        ProjectDialog(null, onDismiss = { creating = false }, onSave = { name, words, about, _ ->
            creating = false
            vm.save(vm.newProject(name, words, about))
        })
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projects") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New project") })
        },
    ) { padding ->
        val list = overviews ?: return@Scaffold
        if (list.isEmpty()) {
            EmptyState(
                Icons.Filled.Folder,
                "No projects yet",
                "Group meetings by project or client. Each project gets a page with its decisions, open actions and meetings, and you can ask about just its meetings.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        val (active, archived) = list.partition { !it.project.archived }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(active, key = { it.project.id }) { o -> ProjectRow(o) { onOpenProject(o.project.id) } }
            if (archived.isNotEmpty()) {
                item { Text("Finished", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp)) }
                items(archived, key = { it.project.id }) { o -> ProjectRow(o) { onOpenProject(o.project.id) } }
            }
        }
    }
}

@Composable
private fun ProjectRow(o: Projects.Overview, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        ListItem(
            headlineContent = { Text(o.project.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Text(
                    listOfNotNull(
                        "${o.meetings.size} meeting${if (o.meetings.size == 1) "" else "s"}",
                        o.openActions.size.takeIf { it > 0 }?.let { "$it open action${if (it == 1) "" else "s"}" },
                        o.lastAt?.let { "last ${date(it)}" },
                    ).joinToString(" · "),
                )
            },
            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
        )
    }
}

/** One project: what was decided, what is open, who is involved and every meeting. */
@Composable
fun ProjectScreen(
    id: String,
    onBack: () -> Unit,
    onOpenMeeting: (String) -> Unit,
    onOpenPerson: (String) -> Unit,
    onAsk: () -> Unit,
) {
    val vm = appViewModel { ProjectsViewModel(it) }
    val overviews by vm.overviews.collectAsStateWithLifecycle()
    val o = overviews?.firstOrNull { it.project.id == id }
    var editing by rememberSaveable { mutableStateOf(false) }
    var allDecisions by rememberSaveable { mutableStateOf(false) }
    if (editing && o != null) {
        ProjectDialog(
            o.project,
            onDismiss = { editing = false },
            onSave = { name, words, about, archived ->
                editing = false
                vm.save(o.project.copy(name = name.trim(), titleWords = Projects.words(words), about = about.trim(), archived = archived))
            },
            onDelete = {
                editing = false
                vm.delete(o.project.id)
                onBack()
            },
        )
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(o?.project?.name ?: "Project", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (o != null) IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, contentDescription = "Edit project") }
                },
            )
        },
    ) { padding ->
        if (o == null) {
            EmptyState(Icons.Filled.Folder, if (overviews == null) "Loading…" else "This project was deleted", "", Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (o.project.about.isNotBlank()) Text(o.project.about, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            "${o.meetings.size} meeting${if (o.meetings.size == 1) "" else "s"}",
                            hours(o.totalMs).ifEmpty { null },
                            o.firstAt?.let { first -> if (first == o.lastAt) date(first) else "${date(first)} – ${date(o.lastAt!!)}" },
                            "${o.doneActions} of ${o.doneActions + o.openActions.size} actions done".takeIf { o.doneActions + o.openActions.size > 0 },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (o.project.titleWords.isNotEmpty()) {
                        Text(
                            "Meetings with ${o.project.titleWords.joinToString(" or ") { "\"$it\"" }} in the title join by themselves.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FilledTonalButton(onClick = { vm.askAbout(o.project); onAsk() }, enabled = o.meetings.isNotEmpty()) {
                        Icon(Icons.Filled.QuestionAnswer, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Ask about this project")
                    }
                    if (o.people.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            o.people.take(12).forEach { name -> AssistChip(onClick = { onOpenPerson(name) }, label = { Text(name) }) }
                        }
                    }
                }
            }
            if (o.meetings.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No meetings yet. Put a meeting in this project from its summary, give a schedule this project, or add title words.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (o.nextSteps.isNotEmpty()) {
                item(key = "next-title") { SectionTitle("Next steps (from ${o.nextSteps.first().meeting.title})") }
                itemsIndexed(o.nextSteps, key = { i, _ -> "next-$i" }) { _, step -> Bullet(step.text) }
            }
            if (o.openActions.isNotEmpty()) {
                item(key = "open-title") { SectionTitle("Open actions · ${o.openActions.size}") }
                items(o.openActions, key = { "open-${it.meeting.id}/${it.item.id}" }) { a ->
                    Card(Modifier.fillMaxWidth().clickable { onOpenMeeting(a.meeting.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = a.item.done, onCheckedChange = { vm.setDone(a, it) })
                            Column(Modifier.weight(1f).padding(end = 12.dp, top = 8.dp, bottom = 8.dp)) {
                                Text(
                                    a.item.task,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (a.item.done) TextDecoration.LineThrough else null,
                                )
                                Text(
                                    listOfNotNull(a.item.owner, a.item.dueDate?.let { "due $it" }, "from ${a.meeting.title}, ${date(a.meeting.createdAt)}").joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            if (o.decisions.isNotEmpty()) {
                item(key = "dec-title") { SectionTitle("Decisions · ${o.decisions.size}") }
                val shown = if (allDecisions) o.decisions else o.decisions.take(DECISIONS_SHOWN)
                itemsIndexed(shown, key = { i, _ -> "dec-$i" }) { _, d -> Dated(d, onOpenMeeting) }
                if (o.decisions.size > DECISIONS_SHOWN && !allDecisions) {
                    item(key = "dec-more") { TextButton(onClick = { allDecisions = true }) { Text("Show all ${o.decisions.size}") } }
                }
            }
            if (o.risks.isNotEmpty()) {
                item(key = "risk-title") { SectionTitle("Risks raised lately") }
                itemsIndexed(o.risks, key = { i, _ -> "risk-$i" }) { _, r -> Dated(r, onOpenMeeting) }
            }
            if (o.meetings.isNotEmpty()) {
                item(key = "meetings-title") { SectionTitle("Meetings") }
                items(o.meetings, key = { "m-${it.id}" }) { m -> MeetingCard(m) { onOpenMeeting(m.id) } }
            }
        }
    }
}

private const val DECISIONS_SHOWN = 8

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Bullet(text: String) {
    Text("• $text", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun Dated(item: Projects.Item, onOpenMeeting: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().clickable { onOpenMeeting(item.meeting.id) }.padding(vertical = 4.dp)) {
        Text(item.text, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${item.meeting.title} · ${date(item.meeting.createdAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MeetingCard(m: Meeting, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Text(m.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(date(m.createdAt), m.durationMs.takeIf { it > 0 }?.let { TranscriptFormatter.timestamp(it) }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            m.insights?.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Making or changing a project. [onSave] gets the name, title words, description and whether it's finished. */
@Composable
internal fun ProjectDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (name: String, words: String, about: String, archived: Boolean) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(project?.name.orEmpty()) }
    var words by rememberSaveable { mutableStateOf(project?.titleWords?.joinToString(", ").orEmpty()) }
    var about by rememberSaveable { mutableStateOf(project?.about.orEmpty()) }
    var archived by rememberSaveable { mutableStateOf(project?.archived ?: false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (project == null) "New project" else "Edit project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    words, { words = it },
                    label = { Text("Title words (optional)") },
                    placeholder = { Text("Apollo, launch sync") },
                    supportingText = { Text("Meetings with one of these in the title join by themselves. Separate with commas.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(about, { about = it }, label = { Text("About (optional)") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
                if (project != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Finished", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Switch(checked = archived, onCheckedChange = { archived = it })
                    }
                    if (onDelete != null) {
                        TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                            Text(
                                if (confirmDelete) "Tap again to delete (meetings are kept)" else "Delete project",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, words, about, archived) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
