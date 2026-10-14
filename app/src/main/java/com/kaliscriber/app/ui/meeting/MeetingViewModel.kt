package com.kaliscriber.app.ui.meeting

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.core.model.ExcerptRef
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.transcript.TranscriptFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Busy { INSIGHTS, GAPS, ASK, SPEAKERS, NAMES, RETRY }

class MeetingViewModel(private val container: AppContainer, val meetingId: String) : ViewModel() {

    private val repo = container.meetings
    private val pipeline = container.pipeline

    val meeting: StateFlow<Meeting?> = repo.observe(meetingId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, repo.get(meetingId))

    private val _busy = MutableStateFlow<Set<Busy>>(emptySet())
    val busy: StateFlow<Set<Busy>> = _busy.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    /** Transcript segments selected with a long press. */
    val selection = MutableStateFlow<Set<String>>(emptySet())

    /** The excerpt the next question in "Ask AI" is about. */
    val excerpt = MutableStateFlow<ExcerptRef?>(null)

    val player = AudioPlayer(viewModelScope)

    private fun launchTask(kind: Busy, success: String? = null, block: suspend () -> Unit) {
        if (kind in _busy.value) return
        _busy.update { it + kind }
        viewModelScope.launch {
            try {
                block()
                if (success != null) _messages.send(success)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.message ?: "Something went wrong")
            } finally {
                _busy.update { it - kind }
            }
        }
    }

    fun generateInsights() = launchTask(Busy.INSIGHTS) { pipeline.generateInsights(meetingId) }
    fun generateGaps() = launchTask(Busy.GAPS) { pipeline.generateGaps(meetingId) }
    fun refreshWithAnswers() = launchTask(Busy.INSIGHTS, "Summary updated with your answers") { pipeline.refreshWithAnswers(meetingId) }
    fun separateSpeakers() = launchTask(Busy.SPEAKERS, "Speakers re-detected") { pipeline.separateSpeakers(meetingId) }
    fun suggestNames() = launchTask(Busy.NAMES, "Name suggestions updated") { pipeline.suggestSpeakerNames(meetingId) }
    fun retryPending() = launchTask(Busy.RETRY) {
        pipeline.retryPendingChunks(meetingId)
        pipeline.analyze(meetingId)
    }

    fun ask(question: String) {
        if (question.isBlank()) return
        val about = excerpt.value
        excerpt.value = null
        launchTask(Busy.ASK) { pipeline.ask(meetingId, question, about) }
    }

    fun clearChat() = viewModelScope.launch { repo.clearChat(meetingId) }

    fun askAboutSelection() {
        val m = meeting.value ?: return
        val selected = m.segments.filter { it.id in selection.value }
        if (selected.isEmpty()) return
        excerpt.value = ExcerptRef(selected.first().startMs, selected.last().endMs, selected.map { it.id })
        selection.value = emptySet()
    }

    fun toggleSelection(segmentId: String) = selection.update { if (segmentId in it) it - segmentId else it + segmentId }

    fun copySelectionText(): String {
        val m = meeting.value ?: return ""
        val selected = m.segments.filter { it.id in selection.value }
        selection.value = emptySet()
        return TranscriptFormatter.forPrompt(m, selected)
    }

    fun renameMeeting(title: String) = viewModelScope.launch { repo.rename(meetingId, title) }
    fun renameSpeaker(speakerId: String, name: String) = viewModelScope.launch { repo.renameSpeaker(meetingId, speakerId, name) }
    fun dismissSuggestion(speakerId: String) = viewModelScope.launch { repo.dismissSuggestion(meetingId, speakerId) }
    fun mergeSpeaker(fromId: String, intoId: String) = viewModelScope.launch { repo.mergeSpeakers(meetingId, fromId, intoId) }

    fun assignSelection(speakerId: String?, newName: String?) = viewModelScope.launch {
        val ids = selection.value
        selection.value = emptySet()
        if (speakerId != null) repo.reassignSegments(meetingId, ids, speakerId)
        else repo.assignToNewSpeaker(meetingId, ids, newName)
    }

    fun editSegment(segmentId: String, text: String) = viewModelScope.launch { repo.editSegment(meetingId, segmentId, text) }
    fun deleteSegment(segmentId: String) = viewModelScope.launch { repo.deleteSegment(meetingId, segmentId) }
    fun setActionDone(itemId: String, done: Boolean) = viewModelScope.launch { repo.setActionItemDone(meetingId, itemId, done) }
    fun answerQuestion(questionId: String, answer: String) = viewModelScope.launch { repo.answerQuestion(meetingId, questionId, answer) }

    suspend fun saveAsNote(): String? {
        val m = meeting.value ?: return null
        return container.notes.create(title = m.title, content = TranscriptFormatter.markdown(m), meetingId = m.id).id
    }

    suspend fun delete() {
        player.release()
        repo.delete(meetingId)
    }

    fun markdown(): String = meeting.value?.let(TranscriptFormatter::markdown).orEmpty()

    override fun onCleared() {
        player.release()
    }
}

/** Plays the meeting recording and reports the position so the transcript can follow along. */
class AudioPlayer(private val scope: CoroutineScope) {
    private var player: MediaPlayer? = null
    private var path: String? = null
    private var ticker: Job? = null

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    fun load(newPath: String?) {
        if (newPath == path) return
        release()
        if (newPath == null) return
        runCatching {
            MediaPlayer().apply {
                setDataSource(newPath)
                prepare()
            }
        }.onSuccess { mp ->
            mp.setOnCompletionListener { _playing.value = false }
            player = mp
            path = newPath
            _duration.value = mp.duration.toLong()
        }
    }

    fun toggle() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            _playing.value = false
        } else {
            mp.start()
            _playing.value = true
            tick()
        }
    }

    fun seekTo(ms: Long) {
        val mp = player ?: return
        mp.seekTo(ms.coerceIn(0L, _duration.value).toInt())
        _position.value = ms
        if (!mp.isPlaying) {
            mp.start()
            _playing.value = true
            tick()
        }
    }

    private fun tick() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive && _playing.value) {
                player?.let { _position.value = it.currentPosition.toLong() }
                delay(250)
            }
        }
    }

    fun release() {
        ticker?.cancel()
        runCatching { player?.release() }
        player = null
        path = null
        _playing.value = false
        _position.value = 0
        _duration.value = 0
    }
}
