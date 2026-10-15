@file:OptIn(ExperimentalMaterial3Api::class)

package com.kaliscriber.app.ui.meeting

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.kaliscriber.app.export.ExportTarget
import com.kaliscriber.app.export.GoogleAuth
import com.kaliscriber.app.ui.common.shareText
import com.kaliscriber.core.ai.usage.TokenUsage
import com.kaliscriber.core.ai.usage.UsageRecord
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
fun MeetingScreen(
    meetingId: String,
    initialTab: Int = 0,
    /** A transcript line to scroll to and highlight, e.g. from search. */
    focusSegmentId: String? = null,
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
    var tab by rememberSaveable { mutableIntStateOf(initialTab.coerceIn(0, 3)) }
    var exportOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDeleteRecording by remember { mutableStateOf(false) }
    var showTimeline by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) {
        vm.exportResults.collect { result ->
            val url = result.url
            val action = snackbar.showSnackbar(result.message, actionLabel = if (url != null) "Open" else null, withDismissAction = url == null)
            if (action == SnackbarResult.ActionPerformed && url != null) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
    }

    suspend fun openDocxInGoogleDocs(reason: String?) {
        if (reason != null) snackbar.showSnackbar(reason)
        vm.googleDocsFileIntent()?.let { runCatching { context.startActivity(it) } }
    }
    val googleConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val token = GoogleAuth.tokenFromResult(context, result.data)
        if (token != null) vm.exportGoogleDocs(token) else vm.showMessage("Google sign-in was cancelled")
    }
    fun exportTo(target: ExportTarget) {
        exportOpen = false
        when (target) {
            ExportTarget.EMAIL -> vm.emailIntent()?.let { runCatching { context.startActivity(it) } }
            ExportTarget.GOOGLE_DOCS -> if (vm.googleDirectUpload) {
                GoogleAuth.authorize(context) { outcome ->
                    when (outcome) {
                        is GoogleAuth.Outcome.Token -> vm.exportGoogleDocs(outcome.accessToken)
                        is GoogleAuth.Outcome.NeedsConsent ->
                            googleConsent.launch(IntentSenderRequest.Builder(outcome.intent.intentSender).build())
                        is GoogleAuth.Outcome.Failed -> scope.launch {
                            openDocxInGoogleDocs("Google sign-in isn't set up for this build (${outcome.message}). Opening the notes in Google Docs as a Word file instead.")
                        }
                    }
                }
            } else {
                scope.launch { openDocxInGoogleDocs(null) }
            }
            else -> vm.export(target)
        }
    }
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
                    if (Busy.EXPORT in busy) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { exportOpen = true }) {
                            Icon(Icons.Filled.IosShare, contentDescription = "Export")
                        }
                    }
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text("Regenerate summary") }, onClick = { menuOpen = false; vm.generateInsights() })
                        DropdownMenuItem(text = { Text("Regenerate gaps & questions") }, onClick = { menuOpen = false; vm.generateGaps() })
                        DropdownMenuItem(text = { Text("Re-detect speakers with AI") }, onClick = { menuOpen = false; vm.separateSpeakers() })
                        DropdownMenuItem(text = { Text("Fix words from glossary") }, onClick = { menuOpen = false; vm.applyGlossary() })
                        DropdownMenuItem(text = { Text("Suggest speaker names") }, onClick = { menuOpen = false; vm.suggestNames() })
                        DropdownMenuItem(text = { Text("Save as note") }, onClick = {
                            menuOpen = false
                            scope.launch { vm.saveAsNote()?.let(onOpenNote) }
                        })
                        DropdownMenuItem(text = { Text("Processing details") }, onClick = { menuOpen = false; showTimeline = true })
                        DropdownMenuItem(text = { Text("AI settings") }, onClick = { menuOpen = false; onOpenSettings() })
                        if (m.audioPath != null && m.status == MeetingStatus.READY) {
                            DropdownMenuItem(text = { Text("Delete recording only") }, onClick = { menuOpen = false; confirmDeleteRecording = true })
                        }
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
            if (m.status == MeetingStatus.RECORDING) {
                Text(
                    "Recording in progress. Ask AI works on the transcript so far.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                listOf("Summary", "Transcript", "Ask AI", "Gaps & questions").forEachIndexed { i, label ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(label) })
                }
            }
            when (tab) {
                0 -> SummaryTab(m, busy, vm, onOpenTranscriptAt = { ms -> vm.player.seekTo(ms) })
                1 -> TranscriptTab(m, vm, focusSegmentId = focusSegmentId, onAskAboutSelection = {
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

    if (exportOpen) {
        ExportSheet(
            missing = { vm.missingSetup(it) },
            onDismiss = { exportOpen = false },
            onExport = ::exportTo,
            onShareText = {
                exportOpen = false
                shareText(context, m.title, vm.markdown())
            },
            onOpenSettings = {
                exportOpen = false
                onOpenSettings()
            },
        )
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
    if (showTimeline) {
        AlertDialog(
            onDismissRequest = { showTimeline = false },
            title = { Text("Processing details") },
            text = {
                val usage by vm.usage.collectAsStateWithLifecycle()
                TimelineList(m, usage, vm.usageCost(usage))
            },
            confirmButton = { TextButton(onClick = { showTimeline = false }) { Text("Close") } },
        )
    }
    if (confirmDeleteRecording) {
        AlertDialog(
            onDismissRequest = { confirmDeleteRecording = false },
            title = { Text("Delete the recording?") },
            text = {
                Text(
                    "The audio is deleted to save space. The transcript, summary, notes and chat stay. " +
                        "Without the audio you can't play the meeting, detect speakers again or transcribe it again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteRecording = false
                    vm.deleteRecording()
                }) { Text("Delete recording") }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteRecording = false }) { Text("Cancel") } },
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
private fun ExportSheet(
    missing: (ExportTarget) -> String?,
    onDismiss: () -> Unit,
    onExport: (ExportTarget) -> Unit,
    onShareText: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Text("Export meeting notes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ExportTarget.entries.forEach { target ->
                val needs = missing(target)
                ListItem(
                    headlineContent = { Text(target.label) },
                    supportingContent = {
                        Text(
                            needs?.let { "Set up first: $it" } ?: target.description,
                            color = if (needs != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    leadingContent = {
                        Icon(
                            when (target) {
                                ExportTarget.EMAIL -> Icons.Filled.Email
                                ExportTarget.GOOGLE_DOCS -> Icons.Filled.Description
                                ExportTarget.NOTION -> Icons.Filled.Article
                                ExportTarget.AFFINE -> Icons.Filled.Dashboard
                                ExportTarget.CALDAV -> Icons.Filled.Event
                            },
                            contentDescription = null,
                        )
                    },
                    modifier = Modifier.clickable { if (needs != null) onOpenSettings() else onExport(target) },
                )
            }
            ListItem(
                headlineContent = { Text("Share as text") },
                supportingContent = { Text("Send Markdown to any app") },
                leadingContent = { Icon(Icons.Filled.Share, contentDescription = null) },
                modifier = Modifier.clickable(onClick = onShareText),
            )
        }
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
        val step = meeting.processingStep.takeIf { meeting.status != MeetingStatus.RECORDING }
        val fraction = meeting.processingProgress
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                when {
                    step != null && fraction != null -> "$step · ${(fraction * 100).toInt()}%"
                    step != null -> "$step…"
                    else -> working
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (fraction != null && step != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            if (meeting.status != MeetingStatus.RECORDING) {
                Text(
                    "You can leave the app; processing continues in the background.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

/** Each processing step with its duration, and how long the meeting took to be ready after stopping. */
@Composable
private fun TimelineList(meeting: Meeting, usage: List<UsageRecord>, cost: Double?) {
    val entries = meeting.timeline
    if (entries.isEmpty() && usage.isEmpty()) {
        Text("Nothing recorded yet. Steps appear here as the meeting is processed.")
        return
    }
    val time = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    val stopped = entries.lastOrNull { it.label == "Recording stopped" }
    val ready = entries.lastOrNull { it.label.startsWith("Ready") }
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (stopped != null && ready != null && ready.at >= stopped.at) {
            Text(
                "Ready ${duration(ready.at - stopped.at)} after you stopped recording",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        meeting.audioDeletedAt?.let { at ->
            Text(
                "Recording deleted ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(java.util.Date(at))} to save space; the transcript was kept.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (usage.isNotEmpty()) {
            val total = usage.map { it.usage }.reduce(TokenUsage::plus)
            Text(
                "AI: ${usage.size} requests, ${total.input} tokens in (${total.cachedInput} from cache), ${total.output} out" +
                    (cost?.let { String.format(java.util.Locale.US, " · about $%.3f", it) } ?: "") +
                    if (total.estimated) " (partly estimated)" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            usage.groupBy { it.purpose.ifBlank { "Other" } }.forEach { (purpose, list) ->
                val u = list.map { it.usage }.reduce(TokenUsage::plus)
                Text(
                    "  $purpose: ${list.size} × · ${u.input} in · ${u.output} out",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        entries.forEach { e ->
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    time.format(Date(e.at)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    e.label + if (e.durationMs >= 1_000) " · ${duration(e.durationMs)}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (e.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }
}
