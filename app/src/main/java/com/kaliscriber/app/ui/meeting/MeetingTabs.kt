@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)

package com.kaliscriber.app.ui.meeting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material.icons.filled.Subject
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Topic
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaliscriber.app.ui.common.BookmarkMarker
import com.kaliscriber.app.ui.common.GapMarker
import com.kaliscriber.app.ui.common.gapsByLine
import com.kaliscriber.app.ui.common.ErrorFixButton
import com.kaliscriber.app.ui.common.page
import com.kaliscriber.app.ui.settings.SettingsPage
import com.kaliscriber.core.ai.ErrorFixes
import com.kaliscriber.app.ui.common.BulletList
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.common.MarkdownText
import com.kaliscriber.app.ui.common.SectionCard
import com.kaliscriber.app.ui.common.SpeakerBadge
import com.kaliscriber.app.ui.common.StoppedLabel
import com.kaliscriber.app.ui.common.StreamingAnswer
import com.kaliscriber.app.ui.common.bookmarksByLine
import com.kaliscriber.app.ui.recording.ExpectedSpeakersRow
import com.kaliscriber.core.ai.MeetingType
import com.kaliscriber.core.model.ChatRole
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.model.Speaker
import com.kaliscriber.core.model.TranscriptSegment
import com.kaliscriber.core.transcript.TranscriptFormatter
import java.io.File

/** Parses "mm:ss" or "h:mm:ss" (optionally in brackets) into milliseconds. */
/** The summary format, which the user can change; the summary is then written again. */
@Composable
private fun FormatRow(meeting: Meeting, enabled: Boolean, onPick: (MeetingType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = MeetingType.of(meeting)
    Box {
        AssistChip(
            onClick = { open = true },
            enabled = enabled,
            label = { Text("Format: ${current.label}" + if (meeting.type == null) " (auto)" else "") },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Auto (${MeetingType.guess(meeting).label})") },
                onClick = { open = false; onPick(null) },
            )
            MeetingType.entries.forEach { type ->
                DropdownMenuItem(text = { Text(type.label) }, onClick = { open = false; onPick(type) })
            }
        }
    }
}

/** How far before a bookmark playback and the transcript start: the mark is about what was just said. */
private const val BOOKMARK_LEAD_MS = 20_000L

private fun parseTimestamp(text: String?): Long? {
    val parts = text?.trim()?.trim('[', ']')?.split(":")?.map { it.trim().toLongOrNull() ?: return null } ?: return null
    return when (parts.size) {
        2 -> (parts[0] * 60 + parts[1]) * 1000
        3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000
        else -> null
    }
}

private val Meeting.isProcessing get() = status == MeetingStatus.TRANSCRIBING || status == MeetingStatus.ANALYZING

// ---------------------------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------------------------

/** A part of the summary, with what its jump chip says (null for none). */
private class SummaryPart(val key: String, val chip: String?, val content: @Composable () -> Unit)

@Composable
fun SummaryTab(meeting: Meeting, busy: Set<Busy>, vm: MeetingViewModel, onOpenTranscriptAt: (Long) -> Unit) {
    val insights = meeting.insights
    if (insights == null) {
        if (meeting.isProcessing || Busy.INSIGHTS in busy) {
            EmptyState(Icons.Filled.AutoAwesome, "Working on it", "The summary, action items and highlights will appear here.") {
                CircularProgressIndicator()
            }
        } else {
            EmptyState(
                Icons.Filled.AutoAwesome,
                "No summary yet",
                if (meeting.segments.isEmpty()) "There is no transcript to summarise yet." else "Generate a summary, action items, highlights and insights.",
            ) {
                Button(onClick = vm::generateInsights, enabled = meeting.segments.isNotEmpty()) { Text("Generate summary") }
            }
        }
        return
    }
    // What needs doing first, then what was decided and said; background last and folded.
    val parts = buildList {
        if (insights.summary.isNotBlank()) {
            add(SummaryPart("summary", null) { SectionCard("Summary", Icons.Filled.Subject) { MarkdownText(insights.summary) } })
        }
        if (insights.actionItems.isNotEmpty()) {
            val open = insights.actionItems.count { !it.done }
            add(SummaryPart("actions", "Actions ${if (open > 0) open else insights.actionItems.size}") {
                SectionCard("Action items", Icons.Filled.TaskAlt) {
                    insights.actionItems.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(checked = item.done, onCheckedChange = { vm.setActionDone(item.id, it) })
                            Column(Modifier.padding(top = 12.dp)) {
                                Text(
                                    item.task,
                                    style = MaterialTheme.typography.bodyMedium,
                                    textDecoration = if (item.done) TextDecoration.LineThrough else null,
                                )
                                val meta = listOfNotNull(
                                    item.owner?.let { "👤 $it" },
                                    item.dueDate?.let { "📅 $it" },
                                    item.priority?.let { "⚑ $it" },
                                ).joinToString("   ")
                                if (meta.isNotEmpty()) {
                                    Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            })
        }
        if (insights.decisions.isNotEmpty()) {
            add(SummaryPart("decisions", "Decisions ${insights.decisions.size}") {
                SectionCard("Decisions", Icons.Filled.Gavel) { BulletList(insights.decisions) }
            })
        }
        insights.sections.forEachIndexed { i, section ->
            add(SummaryPart("section-$i", "${section.title} ${section.items.size}") {
                SectionCard(section.title, Icons.Filled.ViewAgenda) { BulletList(section.items) }
            })
        }
        if (meeting.bookmarks.isNotEmpty()) {
            add(SummaryPart("bookmarks", "Bookmarks ${meeting.bookmarks.size}") {
                SectionCard("Your bookmarks", Icons.Filled.Bookmark) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        meeting.bookmarks.forEach { mark ->
                            BookmarkMarker(mark, onClick = { onOpenTranscriptAt((mark.atMs - BOOKMARK_LEAD_MS).coerceAtLeast(0)) })
                        }
                    }
                }
            })
        }
        if (insights.keyPoints.isNotEmpty()) {
            add(SummaryPart("points", "Key points ${insights.keyPoints.size}") {
                SectionCard("Key points", Icons.Filled.Lightbulb) { BulletList(insights.keyPoints) }
            })
        }
        if (insights.highlights.isNotEmpty()) {
            add(SummaryPart("highlights", "Highlights ${insights.highlights.size}") {
                SectionCard("Highlights", Icons.Filled.Star) {
                    insights.highlights.forEach { h ->
                        val at = parseTimestamp(h.timestamp)
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .then(if (at != null) Modifier.clickable { onOpenTranscriptAt(at) } else Modifier)
                                .padding(vertical = 4.dp),
                        ) {
                            Text("“${h.text}”", style = MaterialTheme.typography.bodyMedium)
                            val who = listOfNotNull(h.speaker, h.timestamp?.let { "at $it" }).joinToString(" ")
                            if (who.isNotEmpty()) Text("— $who", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            if (!h.why.isNullOrBlank()) Text(h.why!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            })
        }
        if (insights.nextSteps.isNotEmpty()) {
            add(SummaryPart("next", "Next steps ${insights.nextSteps.size}") {
                SectionCard("Next steps", Icons.Filled.TrendingUp) { BulletList(insights.nextSteps) }
            })
        }
        if (insights.risks.isNotEmpty()) {
            add(SummaryPart("risks", "Risks ${insights.risks.size}") {
                SectionCard("Risks & concerns", Icons.Filled.Warning) { BulletList(insights.risks) }
            })
        }
        if (insights.topics.isNotEmpty()) {
            add(SummaryPart("topics", "Topics ${insights.topics.size}") {
                FoldedCard("Topics", Icons.Filled.Topic, meeting.id) {
                    insights.topics.forEach { t ->
                        Text(t.name, style = MaterialTheme.typography.titleSmall)
                        if (t.summary.isNotBlank()) Text(t.summary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            })
        }
        if (insights.sentiment.isNotBlank()) {
            add(SummaryPart("tone", null) {
                FoldedCard("Tone", Icons.Filled.Forum, meeting.id) { Text(insights.sentiment, style = MaterialTheme.typography.bodyMedium) }
            })
        }
    }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Items before the parts: the format row and the jump chips.
    val lead = 2
    LazyColumn(state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(key = "format") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FormatRow(meeting, enabled = Busy.INSIGHTS !in busy, onPick = vm::setMeetingType)
                if (Busy.INSIGHTS in busy) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Writing again…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item(key = "jump") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                parts.forEachIndexed { i, part ->
                    val chip = part.chip ?: return@forEachIndexed
                    SuggestionChip(onClick = { scope.launch { listState.animateScrollToItem(lead + i) } }, label = { Text(chip) })
                }
            }
        }
        items(parts, key = { it.key }) { part -> part.content() }
    }
}

/** A card that starts folded, for background like topics and tone. */
@Composable
private fun FoldedCard(title: String, icon: ImageVector, meetingId: String, content: @Composable () -> Unit) {
    var open by rememberSaveable(meetingId, title) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (open) "Fold" else "Unfold")
            }
            if (open) content()
        }
    }
}

/**
 * Listening in the top bar: read the summary aloud, or play, share or create the audio briefing.
 * While something is being read it becomes a Stop button.
 */
@Composable
internal fun ListenAction(meeting: Meeting, busy: Set<Busy>, vm: MeetingViewModel) {
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    if (speaking != null) {
        IconButton(onClick = vm::stopSpeaking) { Icon(Icons.Filled.Stop, contentDescription = "Stop listening") }
        return
    }
    if (Busy.BRIEFING in busy) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        return
    }
    val briefing = meeting.briefing
    val hasSummary = meeting.insights != null
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Headphones, contentDescription = "Listen") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Read the summary aloud") },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, null) },
                enabled = hasSummary,
                onClick = { open = false; vm.speak(MeetingViewModel.SUMMARY_KEY, vm.summaryForSpeech()) },
            )
            if (briefing?.audioPath != null) {
                DropdownMenuItem(
                    text = { Text("Play the audio briefing") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    onClick = { open = false; vm.playBriefing() },
                )
                DropdownMenuItem(
                    text = { Text("Share the audio briefing") },
                    leadingIcon = { Icon(Icons.Filled.Share, null) },
                    onClick = { open = false; shareAudio(context, File(briefing.audioPath!!), meeting.title) },
                )
                DropdownMenuItem(
                    text = { Text("Create the briefing again") },
                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                    onClick = { open = false; vm.regenerateBriefing() },
                )
            } else {
                DropdownMenuItem(
                    text = { Text("Create an audio briefing") },
                    leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                    enabled = hasSummary,
                    onClick = { open = false; vm.createBriefing() },
                )
            }
        }
    }
}

private fun shareAudio(context: Context, file: File, title: String) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = if (file.extension == "mp3") "audio/mpeg" else "audio/wav"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "Audio briefing: $title")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share briefing").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ---------------------------------------------------------------------------------------------
// Transcript
// ---------------------------------------------------------------------------------------------

@Composable
fun TranscriptTab(meeting: Meeting, vm: MeetingViewModel, focusSegmentId: String? = null, onAskAboutSelection: () -> Unit) {
    val selection by vm.selection.collectAsStateWithLifecycle()
    val position by vm.player.position.collectAsStateWithLifecycle()
    val playing by vm.player.playing.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var speakerDialog by remember { mutableStateOf<String?>(null) }
    var assigning by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TranscriptSegment?>(null) }
    val listState = rememberLazyListState()
    // Find in transcript, and showing one speaker's lines.
    var finding by rememberSaveable { mutableStateOf(false) }
    var find by rememberSaveable { mutableStateOf("") }
    var only by rememberSaveable { mutableStateOf<String?>(null) }

    if (meeting.segments.isEmpty()) {
        EmptyState(
            Icons.Filled.Forum,
            if (meeting.isProcessing || meeting.status == MeetingStatus.RECORDING) "Transcribing…" else "No transcript",
            "Transcribed speech will appear here.",
        )
        return
    }

    val shown = remember(meeting.segments, only) { if (only == null) meeting.segments else meeting.segments.filter { it.speakerId == only } }
    val matches = remember(shown, find) {
        val q = find.trim()
        if (q.length < 2) emptyList() else shown.indices.filter { shown[it].text.contains(q, ignoreCase = true) }
    }
    var current by remember(matches) { mutableIntStateOf(0) }
    LaunchedEffect(current, matches) {
        matches.getOrNull(current)?.let { listState.animateScrollToItem((it - 1).coerceAtLeast(0)) }
    }
    val currentMatchId = matches.getOrNull(current)?.let { shown[it].id }

    val activeId = if (playing) meeting.segments.lastOrNull { it.startMs <= position }?.id else null
    LaunchedEffect(activeId) {
        val index = shown.indexOfFirst { it.id == activeId }
        if (index >= 0) listState.animateScrollToItem(index)
    }
    // Jump once to the line a search result or answer pointed at.
    var focused by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusSegmentId) {
        val index = shown.indexOfFirst { it.id == focusSegmentId }
        if (index >= 0 && !focused) {
            focused = true
            listState.scrollToItem((index - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (finding) {
            FindBar(
                query = find,
                onQuery = { find = it },
                matchCount = matches.size,
                current = current,
                onPrevious = { if (matches.isNotEmpty()) current = (current - 1 + matches.size) % matches.size },
                onNext = { if (matches.isNotEmpty()) current = (current + 1) % matches.size },
                onClose = {
                    finding = false
                    find = ""
                    only = null
                },
            )
            SpeakerFilter(meeting, selected = only, onSelect = { only = it })
        } else {
            SpeakersRow(meeting, onSpeakerClick = { speakerDialog = it })
        }
        var showCount by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (meeting.status != MeetingStatus.RECORDING) {
                TextButton(onClick = { showCount = !showCount }) {
                    Text("People: ${meeting.expectedSpeakers?.toString() ?: "auto"}")
                }
                TextButton(onClick = vm::redetectSpeakers, enabled = !meeting.isProcessing) {
                    Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Re-detect speakers")
                }
            }
            Spacer(Modifier.weight(1f))
            if (!finding) {
                IconButton(onClick = { finding = true }) { Icon(Icons.Filled.Search, contentDescription = "Find in transcript") }
            }
        }
        if (meeting.status != MeetingStatus.RECORDING) {
            if (showCount) {
                ExpectedSpeakersRow(meeting.expectedSpeakers, onSelect = { vm.setExpectedSpeakers(it) })
                Spacer(Modifier.size(4.dp))
            }
            val voices = meeting.segments.mapNotNull { it.speakerId }.distinct().size
            if (voices <= 1 && meeting.durationMs >= ONE_SPEAKER_HINT_MS && meeting.expectedSpeakers != 1 && !meeting.isProcessing) {
                OneSpeakerCard(meeting, vm)
            }
        }
        HorizontalDivider()
        if (selection.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("${selection.size} selected", Modifier.align(Alignment.CenterVertically).padding(horizontal = 8.dp))
                    TextButton(onClick = onAskAboutSelection) { Icon(Icons.Filled.QuestionAnswer, null); Spacer(Modifier.width(4.dp)); Text("Ask AI") }
                    TextButton(onClick = { assigning = true }) { Icon(Icons.Filled.Person, null); Spacer(Modifier.width(4.dp)); Text("Speaker") }
                    if (selection.size == 1) {
                        TextButton(onClick = {
                            editing = meeting.segments.firstOrNull { it.id in selection }
                            vm.selection.value = emptySet()
                        }) { Icon(Icons.Filled.Edit, null); Spacer(Modifier.width(4.dp)); Text("Edit") }
                    }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(vm.copySelectionText())) }) {
                        Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(4.dp)); Text("Copy")
                    }
                    IconButton(onClick = { vm.selection.value = emptySet() }) { Icon(Icons.Filled.Close, contentDescription = "Clear selection") }
                }
            }
        } else {
            Text(
                "Tap a line to play from there · long-press to select lines and ask AI, fix the speaker or edit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        val marks = bookmarksByLine(meeting)
        val gaps = gapsByLine(meeting)
        val matchColor = MaterialTheme.colorScheme.tertiaryContainer
        LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(shown, key = { it.id }) { seg ->
                val selected = seg.id in selection
                val background = when {
                    selected -> MaterialTheme.colorScheme.primaryContainer
                    seg.id == currentMatchId -> MaterialTheme.colorScheme.secondaryContainer
                    seg.id == activeId -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
                    seg.id == focusSegmentId -> MaterialTheme.colorScheme.secondaryContainer
                    else -> MaterialTheme.colorScheme.surface
                }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(background)
                        .combinedClickable(
                            onClick = { if (selection.isNotEmpty()) vm.toggleSelection(seg.id) else vm.player.seekTo(seg.startMs) },
                            onLongClick = { vm.toggleSelection(seg.id) },
                        )
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpeakerBadge(meeting.speaker(seg.speakerId), onClick = { seg.speakerId?.let { speakerDialog = it } })
                        Spacer(Modifier.width(8.dp))
                        Text(TranscriptFormatter.timestamp(seg.startMs), style = MaterialTheme.typography.labelSmall)
                    }
                    Text(highlight(seg.text, find, matchColor), style = MaterialTheme.typography.bodyLarge)
                    // A mark refers to what was said just before it, so playback starts a little earlier.
                    marks[seg.id]?.forEach { mark ->
                        BookmarkMarker(mark, onClick = { vm.player.seekTo((mark.atMs - BOOKMARK_LEAD_MS).coerceAtLeast(0)) })
                    }
                    gaps[seg.id]?.forEach { GapMarker(it) }
                }
            }
        }
    }

    speakerDialog?.let { id ->
        val speaker = meeting.speaker(id)
        if (speaker == null) {
            speakerDialog = null
        } else {
            SpeakerDialog(
                speaker = speaker,
                meeting = meeting,
                onDismiss = { speakerDialog = null },
                onRename = { vm.renameSpeaker(id, it); speakerDialog = null },
                onDismissSuggestion = { vm.dismissSuggestion(id) },
                onMerge = { into -> vm.mergeSpeaker(id, into); speakerDialog = null },
                onDetectWithAi = { vm.separateSpeakers(); speakerDialog = null },
                onSuggestNames = { vm.suggestNames(); speakerDialog = null },
            )
        }
    }
    if (assigning) {
        AssignSpeakerDialog(
            speakers = meeting.speakers,
            onDismiss = { assigning = false },
            onPick = { vm.assignSelection(it, null); assigning = false },
            onNew = { vm.assignSelection(null, it); assigning = false },
        )
    }
    editing?.let { seg ->
        EditSegmentDialog(
            segment = seg,
            onDismiss = { editing = null },
            onSave = { vm.editSegment(seg.id, it); editing = null },
            onDelete = { vm.deleteSegment(seg.id); editing = null },
        )
    }
}

/** The search box for finding words in one transcript, with "3 of 12" and next/previous. */
@Composable
private fun FindBar(
    query: String,
    onQuery: (String) -> Unit,
    matchCount: Int,
    current: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            placeholder = { Text("Find in transcript") },
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        if (query.trim().length >= 2) {
            Text(
                if (matchCount == 0) "None" else "${current + 1}/$matchCount",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        IconButton(onClick = onPrevious, enabled = matchCount > 0) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Previous match") }
        IconButton(onClick = onNext, enabled = matchCount > 0) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Next match") }
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close find") }
    }
}

/** Shows everyone's lines, or one speaker's. */
@Composable
private fun SpeakerFilter(meeting: Meeting, selected: String?, onSelect: (String?) -> Unit) {
    val present = meeting.segments.mapNotNull { it.speakerId }.toSet()
    val speakers = meeting.speakers.filter { it.id in present }
    if (speakers.size < 2) return
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Everyone") })
        speakers.forEach { speaker ->
            FilterChip(selected = selected == speaker.id, onClick = { onSelect(speaker.id) }, label = { Text(speaker.name) })
        }
    }
}

/** [text] with every occurrence of [query] (two letters or more) marked. */
private fun highlight(text: String, query: String, color: Color): AnnotatedString {
    val q = query.trim()
    if (q.length < 2) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var from = 0
        while (true) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            addStyle(SpanStyle(background = color, fontWeight = FontWeight.SemiBold), at, at + q.length)
            from = at + q.length
        }
    }
}

@Composable
private fun SpeakersRow(meeting: Meeting, onSpeakerClick: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        meeting.speakers.forEach { speaker ->
            val count = meeting.segments.count { it.speakerId == speaker.id }
            Box {
                SpeakerBadge(speaker, onClick = { onSpeakerClick(speaker.id) })
            }
            Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
    if (meeting.speakers.any { it.isPlaceholder }) {
        Text(
            "Names in italics are placeholders for unidentified speakers. Tap one to rename it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
    }
}

@Composable
private fun SpeakerDialog(
    speaker: Speaker,
    meeting: Meeting,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDismissSuggestion: () -> Unit,
    onMerge: (String) -> Unit,
    onDetectWithAi: () -> Unit,
    onSuggestNames: () -> Unit,
) {
    var name by remember(speaker.id) { mutableStateOf(if (speaker.isPlaceholder) "" else speaker.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(speaker.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val lines = meeting.segments.count { it.speakerId == speaker.id }
                if (speaker.voiceProfileId != null && !speaker.isPlaceholder) {
                    Text(
                        "Recognised from your saved voices. Renaming updates the saved voice.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    if (speaker.isPlaceholder) "A placeholder name for a speaker the app couldn't identify ($lines lines)."
                    else "$lines lines in this meeting.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val suggestion = speaker.suggestedName
                if (suggestion != null) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("AI thinks this is $suggestion", style = MaterialTheme.typography.titleSmall)
                            if (!speaker.suggestionReason.isNullOrBlank()) {
                                Text(speaker.suggestionReason!!, style = MaterialTheme.typography.bodySmall)
                            }
                            Row {
                                TextButton(onClick = { onRename(suggestion) }) { Text("Use $suggestion") }
                                TextButton(onClick = onDismissSuggestion) { Text("Dismiss") }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Priya") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val others = meeting.speakers.filter { it.id != speaker.id }
                if (others.isNotEmpty()) {
                    Text("Same person as…", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        others.forEach { other -> SpeakerBadge(other, onClick = { onMerge(other.id) }) }
                    }
                }
                HorizontalDivider()
                Row {
                    TextButton(onClick = onSuggestNames) { Text("Suggest names") }
                    TextButton(onClick = onDetectWithAi) { Text("Re-detect speakers") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun AssignSpeakerDialog(
    speakers: List<Speaker>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onNew: (String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who said this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    speakers.forEach { s -> SpeakerBadge(s, onClick = { onPick(s.id) }) }
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Or a new speaker") },
                    placeholder = { Text("Leave blank for a random name") },
                    singleLine = true,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onNew(name.ifBlank { null }) }) { Text("New speaker") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditSegmentDialog(segment: TranscriptSegment, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var text by remember(segment.id) { mutableStateOf(segment.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit line at ${TranscriptFormatter.timestamp(segment.startMs)}") },
        text = { OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete line", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------
// Ask AI
// ---------------------------------------------------------------------------------------------

@Composable
fun AskTab(meeting: Meeting, busy: Set<Busy>, vm: MeetingViewModel, onOpenSettings: (SettingsPage) -> Unit = {}) {
    val excerpt by vm.excerpt.collectAsStateWithLifecycle()
    val speaking by vm.speaking.collectAsStateWithLifecycle()
    val voicePhase by vm.voicePhase.collectAsStateWithLifecycle()
    val voiceLevel by vm.voiceLevel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice()
    }
    fun toggleVoice() {
        when {
            voicePhase != VoicePhase.OFF -> vm.stopVoice()
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.startVoice()
            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val thinking = Busy.ASK in busy
    // Hidden as soon as the finished answer is in the conversation.
    val partial = vm.answering.collectAsStateWithLifecycle().value?.takeIf { meeting.chat.lastOrNull()?.role == ChatRole.USER }
    LaunchedEffect(meeting.chat.size, thinking, partial != null) {
        val count = meeting.chat.size + if (thinking) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }
    fun send(text: String) {
        vm.ask(text)
        input = ""
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (meeting.chat.isEmpty() && !thinking) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Ask anything about this meeting", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Answers cite timestamps from the transcript. Select lines in the Transcript tab to ask about a specific part.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val suggestions = if (meeting.status == MeetingStatus.RECORDING) listOf(
                    "Catch me up on the last five minutes",
                    "What has been decided so far?",
                    "What should I ask or clarify right now?",
                    "Who has committed to what so far?",
                ) else buildList {
                    add("What were the key decisions and who made them?")
                    add("What did each person commit to?")
                    add("What was left unresolved?")
                    add("Draft a follow-up email to the attendees")
                    add("What should I prepare before the next meeting?")
                    meeting.speakers.firstOrNull { !it.isPlaceholder }?.let { add("What were ${it.name}'s main concerns?") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    suggestions.forEach { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(meeting.chat, key = { it.id }) { message ->
                    val mine = message.role == ChatRole.USER
                    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = when {
                                message.isError -> MaterialTheme.colorScheme.errorContainer
                                mine -> MaterialTheme.colorScheme.primaryContainer
                                else -> MaterialTheme.colorScheme.surfaceContainerHigh
                            },
                            modifier = Modifier.widthIn(max = 340.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                message.excerpt?.let {
                                    Text(
                                        "About ${TranscriptFormatter.timestamp(it.startMs)}–${TranscriptFormatter.timestamp(it.endMs)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (mine || message.isError) {
                                    Text(message.content, style = MaterialTheme.typography.bodyMedium)
                                    if (message.isError && message.id == meeting.chat.lastOrNull()?.id) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (ErrorFixes.of(message.content)?.page() != null) {
                                                ErrorFixButton(message.content, onOpenSettings = onOpenSettings)
                                            }
                                            TextButton(onClick = { vm.retryFailedAnswer(message.id) }, enabled = !thinking) {
                                                Icon(Icons.Filled.Refresh, null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Try again")
                                            }
                                        }
                                    }
                                } else {
                                    SelectionContainer { MarkdownText(message.content) }
                                    if (message.stopped) StoppedLabel()
                                    val isSpeaking = speaking == message.id
                                    IconButton(
                                        onClick = { if (isSpeaking) vm.stopSpeaking() else vm.speak(message.id, message.content) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            if (isSpeaking) Icons.Filled.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                                            contentDescription = if (isSpeaking) "Stop reading" else "Read aloud",
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (thinking) {
                    item {
                        if (partial != null) {
                            StreamingAnswer(partial, maxWidth = 340.dp)
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text("Thinking…", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (meeting.chat.isNotEmpty()) {
                    item {
                        TextButton(onClick = { vm.clearChat() }) { Text("Clear conversation") }
                    }
                }
            }
        }
        excerpt?.let {
            InputChip(
                selected = true,
                onClick = { vm.excerpt.value = null },
                label = { Text("Asking about ${TranscriptFormatter.timestamp(it.startMs)}–${TranscriptFormatter.timestamp(it.endMs)}") },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove", modifier = Modifier.size(16.dp)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (voicePhase != VoicePhase.OFF) {
            VoiceBar(voicePhase, voiceLevel, onStop = vm::stopVoice)
        } else {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::toggleVoice) {
                    Icon(Icons.Filled.RecordVoiceOver, contentDescription = "Talk hands-free")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Ask about the meeting…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                )
                if (thinking) {
                    IconButton(onClick = vm::stopAnswer) { Icon(Icons.Filled.StopCircle, contentDescription = "Stop the answer") }
                } else {
                    IconButton(onClick = { send(input) }, enabled = input.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceBar(phase: VoicePhase, level: Float, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                when (phase) {
                    VoicePhase.SPEAKING -> Icons.AutoMirrored.Filled.VolumeUp
                    VoicePhase.LISTENING -> Icons.Filled.Mic
                    else -> Icons.Filled.AutoAwesome
                },
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when (phase) {
                        VoicePhase.LISTENING -> "Listening… ask your question"
                        VoicePhase.TRANSCRIBING -> "Transcribing…"
                        VoicePhase.THINKING -> "Thinking…"
                        VoicePhase.SPEAKING -> "Answering…"
                        VoicePhase.OFF -> ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (phase == VoicePhase.LISTENING) {
                    LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                } else if (phase != VoicePhase.SPEAKING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                }
                Text(
                    "Hands-free: it listens again after each answer. Stays silent? It stops by itself.",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onStop) { Text("Stop") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Gaps & questions
// ---------------------------------------------------------------------------------------------

@Composable
fun GapsTab(meeting: Meeting, busy: Set<Busy>, vm: MeetingViewModel, onAsk: (String) -> Unit) {
    val gaps = meeting.gaps
    if (gaps == null) {
        val working = meeting.isProcessing || Busy.GAPS in busy
        EmptyState(
            Icons.Filled.QuestionAnswer,
            if (working) "Looking for gaps…" else "Find what's missing",
            "AI reviews the meeting for missing owners, deadlines, unanswered questions and vague details, then asks you questions to fill them in.",
        ) {
            if (working) CircularProgressIndicator()
            else Button(onClick = vm::generateGaps, enabled = meeting.segments.isNotEmpty()) { Text("Find gaps & questions") }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (gaps.gaps.isNotEmpty()) {
            item { Text("Gaps", style = MaterialTheme.typography.titleMedium) }
            items(gaps.gaps) { gap ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Flag, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(gap.title, style = MaterialTheme.typography.titleSmall)
                        }
                        if (gap.detail.isNotBlank()) Text(gap.detail, style = MaterialTheme.typography.bodyMedium)
                        if (gap.suggestion.isNotBlank()) {
                            Text("Suggestion: ${gap.suggestion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onAsk("Help me fill this gap: ${gap.title}. ${gap.detail}") }) {
                            Icon(Icons.Filled.AutoAwesome, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Ask AI to help fill it")
                        }
                    }
                }
            }
        }
        if (gaps.questions.isNotEmpty()) {
            item { Text("Questions for you", style = MaterialTheme.typography.titleMedium) }
            item {
                Text(
                    "Your answers are saved with the meeting and used by the summary and Ask AI.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(gaps.questions, key = { it.id }) { q ->
                var answer by remember(q.id) { mutableStateOf(q.answer.orEmpty()) }
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (!q.answer.isNullOrBlank()) {
                                Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(q.question, style = MaterialTheme.typography.titleSmall)
                        }
                        if (q.context.isNotBlank()) {
                            Text(q.context, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedTextField(
                            value = answer,
                            onValueChange = { answer = it },
                            placeholder = { Text("Your answer") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (answer != q.answer.orEmpty()) {
                            TextButton(onClick = { vm.answerQuestion(q.id, answer) }) { Text("Save answer") }
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = vm::refreshWithAnswers,
                    enabled = Busy.INSIGHTS !in busy && gaps.questions.any { !it.answer.isNullOrBlank() },
                ) { Text("Update summary with answers") }
                OutlinedButton(onClick = vm::generateGaps, enabled = Busy.GAPS !in busy) {
                    Text(if (Busy.GAPS in busy) "Looking…" else "Find more")
                }
            }
        }
    }
}

private const val ONE_SPEAKER_HINT_MS = 3 * 60_000L

/** Shown when a meeting of some length came out as a single speaker, which is rarely right. */
@Composable
private fun OneSpeakerCard(meeting: Meeting, vm: MeetingViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val downloadMb = vm.speakerPackDownloadMb
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Everyone is under one speaker", style = MaterialTheme.typography.titleSmall)
            Text(
                buildString {
                    append("How many people were in this meeting? Pick the number, then detect speakers again.")
                    if (downloadMb != null) append(" Speaker detection ($downloadMb MB) downloads first and then runs on this phone.")
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.size(8.dp))
            ExpectedSpeakersRow(meeting.expectedSpeakers, onSelect = { vm.setExpectedSpeakers(it) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::redetectSpeakers, enabled = Busy.SPEAKERS !in busy) { Text("Detect speakers") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { vm.setExpectedSpeakers(1) }) { Text("It was just me") }
            }
        }
    }
}
