package com.kaliscriber.app.ui.recap

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.BulletList
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.common.shareText
import com.kaliscriber.core.ai.AiException
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.recap.WeeklyRecap
import com.kaliscriber.core.recap.WeeklyRecaps
import com.kaliscriber.core.tasks.ActionItems
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.util.Date

/** One week's meetings, what is still open from them and the AI's recap. */
data class WeekView(
    val week: LocalDate,
    val meetings: List<Meeting> = emptyList(),
    val openItems: List<Pair<String?, List<ActionItems.Tracked>>> = emptyList(),
    val dueNextWeek: List<ActionItems.Tracked> = emptyList(),
    val recap: WeeklyRecap? = null,
) {
    /** The recap leaves out meetings recorded (or deleted) since it was written. */
    val recapOutdated: Boolean get() = recap != null && recap.meetingIds.toSet() != meetings.map { it.id }.toSet()
}

class RecapViewModel(private val container: AppContainer) : ViewModel() {
    private val recaps = container.recaps
    private val thisWeek = WeeklyRecaps.weekOf(LocalDate.now())
    private val week = MutableStateFlow(thisWeek)

    val view: StateFlow<WeekView> = combine(week, container.meetings.meetings, recaps.recaps) { w, _, _ ->
        WeekView(w, recaps.meetingsIn(w), recaps.openItems(w), recaps.dueNextWeek(w), recaps.recap(w))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekView(thisWeek))

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val speaking: StateFlow<String?> = container.speech.speaking

    fun isThisWeek(w: LocalDate) = w == thisWeek

    fun previous() {
        week.value = week.value.minusWeeks(1)
        _error.value = null
    }

    fun next() {
        if (week.value < thisWeek) week.value = week.value.plusWeeks(1)
        _error.value = null
    }

    fun write() {
        if (_busy.value) return
        val w = week.value
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            try {
                recaps.write(w)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AiException) {
                _error.value = e.message
            } catch (e: Exception) {
                _error.value = "Couldn't write the recap: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun markdown(recap: WeeklyRecap): String = recaps.markdown(recap)

    /** Reads the recap aloud, or stops if it is being read. */
    fun toggleSpeech(recap: WeeklyRecap) {
        if (speaking.value == SPEECH_KEY) {
            container.speech.stop()
            return
        }
        val text = buildString {
            append(recap.overview)
            if (recap.decisions.isNotEmpty()) append(" Decisions: ").append(recap.decisions.joinToString(". "))
            if (recap.followUps.isNotEmpty()) append(". To follow up: ").append(recap.followUps.joinToString(". "))
        }
        viewModelScope.launch {
            try {
                container.speech.speak(SPEECH_KEY, text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = e.message ?: "The voice didn't work."
            }
        }
    }

    private companion object {
        const val SPEECH_KEY = "weekly-recap"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecapScreen(onBack: () -> Unit, onOpenMeeting: (String) -> Unit) {
    val vm = appViewModel { RecapViewModel(it) }
    val view by vm.view.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val recap = view.recap

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Weekly recap") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (recap != null) {
                        IconButton(onClick = { vm.toggleSpeech(recap) }) {
                            if (speaking == "weekly-recap") {
                                Icon(Icons.Filled.Stop, contentDescription = "Stop reading")
                            } else {
                                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Read aloud")
                            }
                        }
                        IconButton(onClick = { shareText(context, "Week of ${WeeklyRecaps.label(view.week)}", vm.markdown(recap)) }) {
                            Icon(Icons.Filled.Share, contentDescription = "Share")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = vm::previous) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week") }
                    Text(
                        if (vm.isThisWeek(view.week)) "This week" else "Week of ${WeeklyRecaps.label(view.week)}",
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = vm::next, enabled = !vm.isThisWeek(view.week)) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
                    }
                }
                val open = view.openItems.sumOf { it.second.size }
                Text(
                    "${count(view.meetings.size, "meeting")} · ${count(open, "open action item")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (view.meetings.isEmpty()) {
                item { EmptyState(Icons.Filled.DateRange, "No meetings this week", "Recorded and imported meetings appear here by the day they took place.") }
                return@LazyColumn
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (recap == null) {
                            Text(
                                "The AI reads the notes of this week's meetings and writes what happened, what was decided and what to follow up.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        } else {
                            if (recap.overview.isNotBlank()) Text(recap.overview, style = MaterialTheme.typography.bodyLarge)
                            RecapList("Decisions", recap.decisions)
                            RecapList("Themes", recap.themes)
                            RecapList("Risks and blockers", recap.risks)
                            RecapList("Follow up", recap.followUps)
                            Text(
                                "Written ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(recap.createdAt))}" +
                                    if (view.recapOutdated) " · meetings have changed since" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (recap == null) {
                                FilledTonalButton(onClick = vm::write, enabled = !busy) { Text("Write recap") }
                            } else {
                                TextButton(onClick = vm::write, enabled = !busy) { Text("Write again") }
                            }
                            if (busy) {
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            if (view.openItems.isNotEmpty()) {
                item { Heading("Open action items") }
                view.openItems.forEach { (owner, tasks) ->
                    item(key = "owner-${owner ?: ""}") { Text(owner ?: "No owner", style = MaterialTheme.typography.titleSmall) }
                    items(tasks, key = { "open-${it.meetingId}/${it.item.id}" }) { t -> TaskRow(t, onOpenMeeting) }
                }
            }
            if (view.dueNextWeek.isNotEmpty()) {
                item { Heading("Due next week") }
                items(view.dueNextWeek, key = { "due-${it.meetingId}/${it.item.id}" }) { t -> TaskRow(t, onOpenMeeting) }
            }
            item { Heading("Meetings") }
            items(view.meetings, key = { "meeting-${it.id}" }) { m ->
                Card(Modifier.fillMaxWidth().clickable { onOpenMeeting(m.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(m.title, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(m.createdAt)) +
                                (m.insights?.summary?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecapList(title: String, items: List<String>) {
    if (items.isEmpty()) return
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    BulletList(items)
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TaskRow(t: ActionItems.Tracked, onOpenMeeting: (String) -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onOpenMeeting(t.meetingId) }) {
        Column(Modifier.padding(12.dp)) {
            Text(t.item.task, style = MaterialTheme.typography.bodyLarge)
            Text(
                listOfNotNull(t.item.owner, (t.item.dueDateIso ?: t.item.dueDate)?.let { "due $it" }, "from ${t.meetingTitle}").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"
