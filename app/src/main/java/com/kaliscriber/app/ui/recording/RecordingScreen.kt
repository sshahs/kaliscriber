@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package com.kaliscriber.app.ui.recording

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.kaliscriber.app.ui.common.EmptyState
import com.kaliscriber.app.ui.common.SpeakerBadge
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

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

    fun pause(paused: Boolean) = container.recorder.setPaused(paused)
    fun stop() = container.recorder.stop()
}

@Composable
fun RecordingScreen(onBack: () -> Unit, onFinished: (String) -> Unit) {
    val vm = appViewModel { RecordingViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val meeting by vm.meeting.collectAsStateWithLifecycle()
    var lastMeetingId by remember { mutableStateOf<String?>(null) }
    var sawActive by remember { mutableStateOf(false) }

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
                is RecordingState.Recording -> RecorderHeader(s, onPause = { vm.pause(!s.paused) }, onStop = vm::stop)
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
}

@Composable
private fun RecorderHeader(state: RecordingState.Recording, onPause: () -> Unit, onStop: () -> Unit) {
    val level by animateFloatAsState(state.level, label = "level")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            TranscriptFormatter.timestamp(state.elapsedMs),
            style = MaterialTheme.typography.displayMedium,
            textAlign = TextAlign.Center,
        )
        LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().height(6.dp))
        Text(
            when {
                state.paused -> "Paused"
                state.queuedChunks > 0 -> "Listening · transcribing ${state.queuedChunks} part(s)…"
                else -> "Listening · transcript updates every few seconds"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onPause) {
                Icon(if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.paused) "Resume" else "Pause")
            }
            Button(onClick = onStop, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Filled.Stop, null)
                Spacer(Modifier.width(8.dp))
                Text("Stop & analyse")
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
            "Speech appears here as it is transcribed. Speakers the app can't identify get a random name you can change later.",
            modifier,
        )
        return
    }
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(segments, key = { it.id }) { seg ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeakerBadge(meeting?.speaker(seg.speakerId))
                    Spacer(Modifier.width(8.dp))
                    Text(TranscriptFormatter.timestamp(seg.startMs), style = MaterialTheme.typography.labelSmall)
                }
                Text(seg.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
