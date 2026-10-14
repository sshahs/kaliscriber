@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.meeting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.ui.common.shareText
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.launch

@Composable
fun MeetingScreen(
    meetingId: String,
    onBack: () -> Unit,
    onOpenNote: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val vm = appViewModel(key = "meeting-$meetingId") { MeetingViewModel(it, meetingId) }
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(meeting?.audioPath, meeting?.status) {
        // Only play the recording once it has been finalized.
        vm.player.load(meeting?.takeIf { it.status != MeetingStatus.RECORDING }?.audioPath)
    }

    val m = meeting
    if (m == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(m.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { shareText(context, m.title, vm.markdown()) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text("Regenerate summary") }, onClick = { menuOpen = false; vm.generateInsights() })
                        DropdownMenuItem(text = { Text("Regenerate gaps & questions") }, onClick = { menuOpen = false; vm.generateGaps() })
                        DropdownMenuItem(text = { Text("Re-detect speakers with AI") }, onClick = { menuOpen = false; vm.separateSpeakers() })
                        DropdownMenuItem(text = { Text("Suggest speaker names") }, onClick = { menuOpen = false; vm.suggestNames() })
                        DropdownMenuItem(text = { Text("Save as note") }, onClick = {
                            menuOpen = false
                            scope.launch { vm.saveAsNote()?.let(onOpenNote) }
                        })
                        DropdownMenuItem(text = { Text("AI settings") }, onClick = { menuOpen = false; onOpenSettings() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; confirmDelete = true })
                    }
                },
            )
        },
        bottomBar = { if (m.audioPath != null && m.status != MeetingStatus.RECORDING) PlayerBar(vm.player) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusBanner(m, busy, onRetry = vm::retryPending)
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("Summary", "Transcript", "Ask AI", "Gaps & questions").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> SummaryTab(m, busy, vm, onOpenTranscriptAt = { ms -> vm.player.seekTo(ms) })
                1 -> TranscriptTab(m, vm, onAskAboutSelection = {
                    vm.askAboutSelection()
                    tab = 2
                })
                2 -> AskTab(m, busy, vm)
                else -> GapsTab(m, busy, vm, onAsk = { question ->
                    vm.ask(question)
                    tab = 2
                })
            }
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(m.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename meeting") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renameMeeting(title); renaming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete meeting?") },
            text = { Text("The recording, transcript and insights will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch { vm.delete() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusBanner(meeting: Meeting, busy: Set<Busy>, onRetry: () -> Unit) {
    val working = when (meeting.status) {
        MeetingStatus.RECORDING -> "Recording in progress…"
        MeetingStatus.TRANSCRIBING -> "Transcribing and detecting speakers…"
        MeetingStatus.ANALYZING -> "Generating summary, insights and questions…"
        else -> null
    }
    if (working != null) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(working, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
    val error = meeting.lastError
    if (error != null && meeting.status != MeetingStatus.TRANSCRIBING && meeting.status != MeetingStatus.ANALYZING) {
        Card(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                if (meeting.pendingChunks.isNotEmpty()) {
                    TextButton(onClick = onRetry, enabled = Busy.RETRY !in busy) {
                        Text("Retry ${meeting.pendingChunks.size} untranscribed part(s)")
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerBar(player: AudioPlayer) {
    val playing by player.playing.collectAsStateWithLifecycle()
    val position by player.position.collectAsStateWithLifecycle()
    val duration by player.duration.collectAsStateWithLifecycle()
    if (duration <= 0) return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = player::toggle) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            Text(TranscriptFormatter.timestamp(position), style = MaterialTheme.typography.labelSmall)
            Slider(
                value = position.coerceAtMost(duration).toFloat(),
                onValueChange = { player.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                modifier = Modifier.weight(1f),
            )
            Text(TranscriptFormatter.timestamp(duration), style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.width(4.dp))
        }
    }
}
