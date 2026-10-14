package com.kaliscriber.app.ui.home

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.app.speech.ModelCatalog
import com.kaliscriber.app.speech.ModelState
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.calendar.MeetingPrep
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.Note
import com.kaliscriber.core.search.MeetingSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(private val container: AppContainer) : ViewModel() {

    val query = MutableStateFlow("")

    val meetings: StateFlow<List<Meeting>> = combine(container.meetings.meetings, query) { list, q ->
        if (q.isBlank()) list else list.filter { m ->
            m.title.contains(q, true) ||
                m.insights?.summary?.contains(q, true) == true ||
                m.speakers.any { it.name.contains(q, true) } ||
                m.segments.any { it.text.contains(q, true) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Meetings matching the search box, with the lines that matched; empty when it is blank. */
    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<List<MeetingSearch.Result>> = combine(container.meetings.meetings, query.debounce(200)) { list, q ->
        if (q.isBlank()) emptyList() else withContext(Dispatchers.Default) { MeetingSearch.search(list, q) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<Note>> = combine(container.notes.notes, query) { list, q ->
        if (q.isBlank()) list else list.filter { it.title.contains(q, true) || it.content.contains(q, true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recording: StateFlow<RecordingState> = container.recorder.state

    /** What to know before the meeting in progress or the next one today, from the calendar. */
    val prep: StateFlow<MeetingPrep.Prep?> = flow {
        while (true) {
            emit(container.calendar.nextEvent())
            delay(5 * 60_000L)
        }
    }.combine(container.meetings.meetings) { event, meetings ->
        event?.let { MeetingPrep.prep(it, meetings, container.voices.profiles.value) }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True when the selected chat or transcription provider still needs an API key. */
    val needsSetup: StateFlow<Boolean> = combine(
        container.settingsStore.value,
        container.secrets.configured,
        container.models.states,
    ) { s, keys, models ->
        val localModelMissing = s.sttProvider == ProviderId.LOCAL &&
            ModelCatalog.asrModels.none { models[it.id] is ModelState.Ready }
        (s.chatProvider.keyRequired && s.chatProvider !in keys) ||
            (s.sttProvider.keyRequired && s.sttProvider !in keys) ||
            localModelMissing
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    suspend fun startRecording(title: String?): Result<String> = runCatching { container.recorder.start(title) }

    suspend fun createNote(): String = container.notes.create().id

    fun deleteMeeting(id: String) = viewModelScope.launch { container.meetings.delete(id) }

    fun deleteNote(id: String) = viewModelScope.launch { container.notes.delete(id) }

    /** Copies the picked audio into app storage and transcribes it in the background. */
    suspend fun importAudio(uri: Uri): Result<String> = runCatching { container.importer.import(uri) }
}
