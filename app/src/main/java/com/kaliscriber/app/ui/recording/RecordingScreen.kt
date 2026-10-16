@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.kaliscriber.app.ui.recording

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.sqrt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.appViewModel
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.app.ui.common.BookmarkMarker
import com.kaliscriber.app.ui.common.GapMarker
import com.kaliscriber.app.ui.common.gapsByLine
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.common.SpeakerBadge
import com.kaliscriber.app.ui.common.bookmarksByLine
import com.kaliscriber.core.model.Bookmark
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingStatus
import com.kaliscriber.core.speakers.SpeakerAssigner
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.map
import java.util.Date
import java.text.DateFormat

class RecordingViewModel(private val container: AppContainer) : ViewModel() {
    val state: StateFlow<RecordingState> = container.recorder.state

    val meeting: StateFlow<Meeting?> = container.recorder.state
        .flatMapLatest { s ->
            val id = when (s) {
                is RecordingState.Recording -> s.meetingId
                is RecordingState.Finishing -> s.meetingId
                RecordingState.Idle -> null
            }
            if (id.isNullOrEmpty()) flowOf(null) else container.meetings.observe(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The recording auto-record started from the calendar, if this is one. */
    val autoActive = container.autoRecorder.state
        .map { it.active }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun keepRecording() {
        container.scope.launch { container.autoRecorder.keepRecording() }
    }

    fun pause(paused: Boolean) = container.recorder.setPaused(paused)
    suspend fun bookmark(): Bookmark? = container.recorder.bookmark()
    fun noteBookmark(id: String, note: String) {
        val meetingId = meeting.value?.id ?: return
        viewModelScope.launch { container.meetings.editBookmark(meetingId, id, note) }
    }
    fun setExpectedSpeakers(count: Int?) {
        val id = meeting.value?.id ?: return
        viewModelScope.launch { container.pipeline.setExpectedSpeakers(id, count) }
    }
    fun stop() = container.recorder.stop()
}

@Composable
fun RecordingScreen(onBack: () -> Unit, onAskAi: (String) -> Unit, onFinished: (String) -> Unit) {
    val vm = appViewModel { RecordingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    val auto by vm.autoActive.collectAsStateWithLifecycle()
    var lastMeetingId by remember { mutableStateOf<String?>(null) }
    var sawActive by remember { mutableStateOf(false) }
    var noting by remember { mutableStateOf<Bookmark?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(state) {
        when (val s = state) {
            is RecordingState.Recording -> { lastMeetingId = s.meetingId; sawActive = true }
            // Processing continues in the background; show the meeting page where results appear.
            is RecordingState.Finishing -> onFinished(s.meetingId)
            RecordingState.Idle -> if (sawActive) {
                lastMeetingId?.let(onFinished) ?: onBack()
            } else {
                // Capture never started (e.g. the microphone is busy); don't spin forever.
                delay(5_000)
                onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(meeting?.title ?: "Recording") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            val s = state
            when (s) {
                is RecordingState.Recording -> {
                    RecorderHeader(
                        s,
                        onPause = { vm.pause(!s.paused) },
                        onStop = vm::stop,
                        onMark = { scope.launch { noting = vm.bookmark() } },
                    )
                    auto?.takeIf { it.meetingId == s.meetingId && it.stopAtEnd }?.let { a ->
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "From your calendar · stops by itself at ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(a.end))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::keepRecording) { Text("Keep recording") }
                        }
                    }
                    ExpectedSpeakersRow(meeting?.expectedSpeakers, onSelect = vm::setExpectedSpeakers)
                    TextButton(
                        onClick = { onAskAi(s.meetingId) },
                        enabled = !meeting?.segments.isNullOrEmpty(),
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    ) {
                        Icon(Icons.Filled.QuestionAnswer, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ask AI about the meeting so far")
                    }
                }
                is RecordingState.Finishing -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Finishing transcript and generating insights…")
                }
                RecordingState.Idle -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text("Starting microphone…")
                }
            }
            LiveTranscript(meeting, Modifier.weight(1f))
        }
    }

    noting?.let { mark ->
        var note by remember(mark.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { noting = null },
            title = { Text("Marked ${TranscriptFormatter.timestamp(mark.atMs)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("This moment gets extra weight in the notes and summary. Add a few words if you like.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(note, { note = it }, placeholder = { Text("e.g. pricing decision") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (note.isNotBlank()) vm.noteBookmark(mark.id, note)
                    noting = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { noting = null }) { Text("Skip") } },
        )
    }
}

@Composable
private fun RecorderHeader(state: RecordingState.Recording, onPause: () -> Unit, onStop: () -> Unit, onMark: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    // The screen stays on while it shows a recording, so the time and transcript can be glanced at.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            TranscriptFormatter.timestamp(state.elapsedMs),
            style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
            color = if (state.paused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            when {
                state.paused -> "Paused"
                state.queuedChunks > 0 -> "Listening · transcribing ${state.queuedChunks} part(s)…"
                else -> "Listening · the transcript updates every few seconds"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Waveform(state.level, state.paused || state.interruption != null, Modifier.fillMaxWidth().height(64.dp))
        state.interruption?.let { reason ->
            Text(
                "${reason.replaceFirstChar(Char::uppercase)} has the microphone, so nothing is being recorded. " +
                    "Recording carries on by itself when it's over, and the gap is marked in the transcript.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(12.dp),
            )
        }
        state.micNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FilledTonalButton(
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onMark()
            },
            enabled = !state.paused,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Icon(Icons.Filled.BookmarkAdd, null)
            Spacer(Modifier.width(10.dp))
            Text("Mark this moment", style = MaterialTheme.typography.titleMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f).height(56.dp)) {
                Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.paused) "Resume" else "Pause")
            }
            HoldToStop(onStop, Modifier.weight(1.4f))
        }
    }
}

/** The input level over the last few seconds, as bars that scroll from right to left. */
@Composable
private fun Waveform(level: Float, paused: Boolean, modifier: Modifier = Modifier) {
    val latest by rememberUpdatedState(if (paused) 0f else level)
    val bars = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (true) {
            bars.add(latest.coerceIn(0f, 1f))
            while (bars.size > WAVE_BARS) bars.removeAt(0)
            delay(WAVE_STEP_MS)
        }
    }
    val color = if (paused) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary
    Canvas(modifier.semantics { contentDescription = if (paused) "Paused" else "Sound level" }) {
        val step = size.width / WAVE_BARS
        val minimum = 2.dp.toPx()
        bars.forEachIndexed { i, value ->
            val height = maxOf(minimum, sqrt(value) * size.height)
            val x = (WAVE_BARS - bars.size + i) * step + step / 2
            drawLine(
                color,
                Offset(x, (size.height - height) / 2),
                Offset(x, (size.height + height) / 2),
                strokeWidth = step * 0.6f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Stop needs a press of about a second, so a stray tap can't end the meeting. Screen readers get
 * a plain Stop action.
 */
@Composable
private fun HoldToStop(onStop: () -> Unit, modifier: Modifier = Modifier) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var hint by remember { mutableStateOf(false) }
    var stopped by remember { mutableStateOf(false) }
    fun stop() {
        if (stopped) return
        stopped = true
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onStop()
    }
    Box(
        modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    hint = false
                    val hold = scope.launch {
                        progress.animateTo(1f, tween(HOLD_TO_STOP_MS, easing = LinearEasing))
                        stop()
                    }
                    tryAwaitRelease()
                    if (progress.value < 1f) {
                        hold.cancel()
                        hint = true
                        scope.launch { progress.animateTo(0f) }
                    }
                })
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                onClick(label = "Stop and write the notes") {
                    stop()
                    true
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.matchParentSize().padding(end = 0.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.value).background(MaterialTheme.colorScheme.error.copy(alpha = 0.45f)))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Stop, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                if (hint) "Keep holding to stop" else "Hold to stop",
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

private const val WAVE_BARS = 60
private const val WAVE_STEP_MS = 100L
private const val HOLD_TO_STOP_MS = 900

/** "How many people?" picker; a known count makes speaker detection more accurate. */
@Composable
fun ExpectedSpeakersRow(selected: Int?, onSelect: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("People in this meeting", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Auto") })
            (2..8).forEach { n ->
                FilterChip(selected = selected == n, onClick = { onSelect(n) }, label = { Text(if (n == 8) "8+" else "$n") })
            }
        }
    }
}

@Composable
private fun LiveTranscript(meeting: Meeting?, modifier: Modifier = Modifier) {
    val segments = meeting?.segments.orEmpty()
    val listState = rememberLazyListState()
    LaunchedEffect(segments.size) {
        if (segments.isNotEmpty()) listState.animateScrollToItem(segments.lastIndex)
    }
    if (meeting?.lastError != null && meeting.pendingChunks.isNotEmpty()) {
        Text(
            "Some audio couldn't be transcribed yet (${meeting.lastError}). It will be retried when you stop.",
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    if (segments.isEmpty()) {
        EmptyState(
            Icons.Filled.GraphicEq,
            "Live transcript",
            "Speech appears here as it is transcribed. Speakers are identified every ten minutes; ones the app doesn't know get a random name you can change.",
            modifier,
        )
        return
    }
    val marks = meeting?.let(::bookmarksByLine).orEmpty()
    val gaps = meeting?.let(::gapsByLine).orEmpty()
    // Speakers are detected in ten-minute blocks while recording; until then a line has no one to show.
    val identifiedUntil = segments.lastOrNull { meeting?.speaker(it.speakerId)?.label?.startsWith(SpeakerAssigner.LIVE_PREFIX) == true }?.endMs
    if (identifiedUntil != null) {
        Text(
            "Speakers identified up to ${TranscriptFormatter.timestamp(identifiedUntil)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(segments, key = { it.id }) { seg ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val speaker = meeting?.speaker(seg.speakerId)
                    if (speaker?.label == SpeakerAssigner.UNKNOWN_LABEL && meeting?.status == MeetingStatus.RECORDING) {
                        Text("Speaker not identified yet", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        SpeakerBadge(speaker)
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(TranscriptFormatter.timestamp(seg.startMs), style = MaterialTheme.typography.labelSmall)
                }
                Text(seg.text, style = MaterialTheme.typography.bodyLarge)
                marks[seg.id]?.forEach { BookmarkMarker(it) }
                gaps[seg.id]?.forEach { GapMarker(it) }
            }
        }
    }
}
