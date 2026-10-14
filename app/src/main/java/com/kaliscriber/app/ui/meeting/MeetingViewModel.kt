package com.kaliscriber.app.ui.meeting

import android.media.MediaPlayer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.app.speech.VoiceRecorder
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
import java.io.File

enum class Busy { INSIGHTS, GAPS, ASK, SPEAKERS, NAMES, RETRY, BRIEFING }

/** Where the hands-free voice conversation is. */
enum class VoicePhase { OFF, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

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

    private val speech = container.speech

    /** Key of whatever is being read aloud right now (a message id, "summary", "briefing", ...). */
    val speaking: StateFlow<String?> = speech.speaking

    private val voice = VoiceRecorder(container.appContext)
    val voiceLevel: StateFlow<Float> = voice.level
    private val _voicePhase = MutableStateFlow(VoicePhase.OFF)
    val voicePhase: StateFlow<VoicePhase> = _voicePhase.asStateFlow()
    private var voiceJob: Job? = null

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

    // --- Reading aloud --------------------------------------------------------------------------

    fun speak(key: String, text: String) {
        player.pause()
        viewModelScope.launch {
            try {
                speech.speak(key, text)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.message ?: "Could not read aloud")
            }
        }
    }

    fun stopSpeaking() = speech.stop()

    /** The summary, decisions and action items as one text to read aloud. */
    fun summaryForSpeech(): String {
        val m = meeting.value ?: return ""
        val insights = m.insights ?: return ""
        return buildString {
            appendLine("${m.title}.")
            appendLine(insights.summary)
            if (insights.decisions.isNotEmpty()) {
                appendLine("Decisions:")
                insights.decisions.forEach { appendLine("- $it") }
            }
            val open = insights.actionItems.filter { !it.done }
            if (open.isNotEmpty()) {
                appendLine("Action items:")
                open.forEach { item ->
                    appendLine("- ${item.task}" + (item.owner?.let { ", owner $it" } ?: "") + (item.dueDate?.let { ", due $it" } ?: ""))
                }
            }
        }
    }

    /** Writes a spoken recap with the AI model and renders it to audio with the selected voice. */
    fun createBriefing() = launchTask(Busy.BRIEFING, "Audio briefing ready") {
        val script = meeting.value?.briefing?.script?.takeIf { meeting.value?.briefing?.audioPath == null }
            ?: pipeline.generateBriefingScript(meetingId)
        val file = speech.renderToFile(script, repo.audioDir(meetingId))
        pipeline.setBriefingAudio(meetingId, file.absolutePath)
    }

    fun regenerateBriefing() = launchTask(Busy.BRIEFING, "Audio briefing updated") {
        val script = pipeline.generateBriefingScript(meetingId)
        val file = speech.renderToFile(script, repo.audioDir(meetingId))
        pipeline.setBriefingAudio(meetingId, file.absolutePath)
    }

    fun playBriefing() {
        val path = meeting.value?.briefing?.audioPath ?: return
        player.pause()
        viewModelScope.launch {
            runCatching { speech.playFile(BRIEFING_KEY, File(path)) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) _messages.send(it.message ?: "Could not play") }
        }
    }

    // --- Hands-free voice Q&A -------------------------------------------------------------------

    /** Listen → transcribe → ask → speak the answer, then listen again until stopped or silent. */
    fun startVoice() {
        if (voiceJob?.isActive == true) return
        player.pause()
        speech.stop()
        voiceJob = viewModelScope.launch {
            var silentTurns = 0
            try {
                while (isActive) {
                    _voicePhase.value = VoicePhase.LISTENING
                    val file = voice.recordUtterance()
                    if (file == null) {
                        silentTurns++
                        if (silentTurns >= 2) break
                        continue
                    }
                    silentTurns = 0
                    _voicePhase.value = VoicePhase.TRANSCRIBING
                    val question = try {
                        val language = container.settings().transcriptionLanguage.ifBlank { null }
                        container.ai.stt().transcribe(file, language).segments.joinToString(" ") { it.text }.trim()
                    } finally {
                        file.delete()
                    }
                    if (question.isBlank()) continue
                    _voicePhase.value = VoicePhase.THINKING
                    val answer = pipeline.ask(meetingId, question, excerpt = null, spoken = true).getOrNull() ?: break
                    _voicePhase.value = VoicePhase.SPEAKING
                    speech.speak(VOICE_KEY, answer)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.message ?: "Voice conversation stopped")
            } finally {
                voice.cancel()
                _voicePhase.value = VoicePhase.OFF
            }
        }
    }

    fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
        voice.cancel()
        speech.stop()
        _voicePhase.value = VoicePhase.OFF
    }

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
        stopVoice()
        player.release()
    }

    companion object {
        const val BRIEFING_KEY = "briefing"
        const val SUMMARY_KEY = "summary"
        const val VOICE_KEY = "voice"
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

    fun pause() {
        val mp = player ?: return
        if (mp.isPlaying) {
            mp.pause()
            _playing.value = false
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
