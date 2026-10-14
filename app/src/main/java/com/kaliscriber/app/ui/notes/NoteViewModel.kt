package com.kaliscriber.app.ui.notes

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kaliscriber.app.AppContainer
import com.kaliscriber.core.ai.NoteAction
import com.kaliscriber.core.model.Note
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AiResult(val label: String, val text: String)

class NoteViewModel(private val container: AppContainer, val noteId: String) : ViewModel() {
    private val repo = container.notes
    private val initial: Note? = repo.get(noteId)

    private val _title = MutableStateFlow(initial?.title.orEmpty())
    val title: StateFlow<String> = _title.asStateFlow()
    private val _content = MutableStateFlow(initial?.content.orEmpty())
    val content: StateFlow<String> = _content.asStateFlow()

    val exists: Boolean get() = repo.get(noteId) != null

    private val _busy = MutableStateFlow<String?>(null)
    /** Description of the running AI or transcription task, if any. */
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _result = MutableStateFlow<AiResult?>(null)
    val result: StateFlow<AiResult?> = _result.asStateFlow()

    private val _dictating = MutableStateFlow(false)
    val dictating: StateFlow<Boolean> = _dictating.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private var saveJob: Job? = null
    private val dictation = DictationRecorder(container.appContext)

    fun setTitle(value: String) {
        _title.value = value
        scheduleSave()
    }

    fun setContent(value: String) {
        _content.value = value
        scheduleSave()
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(400)
            save()
        }
    }

    private suspend fun save() {
        val t = _title.value
        val c = _content.value
        repo.update(noteId) { it.copy(title = t, content = c) }
    }

    private fun currentNote(): Note? = repo.get(noteId)?.copy(title = _title.value, content = _content.value)

    fun runAction(action: NoteAction) = runInstruction(action.label, action.instruction, isTitle = action == NoteAction.TITLE)

    fun runCustom(instruction: String) {
        if (instruction.isNotBlank()) runInstruction("Your instruction", instruction, isTitle = false)
    }

    private fun runInstruction(label: String, instruction: String, isTitle: Boolean) {
        val note = currentNote() ?: return
        if (_busy.value != null) return
        _busy.value = "$label…"
        viewModelScope.launch {
            try {
                val text = container.meetingAi.noteInstruction(note, instruction)
                if (isTitle) setTitle(text.lines().first().trim().trim('"', '#', ' ')) else _result.value = AiResult(label, text)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.message ?: "AI request failed")
            } finally {
                _busy.value = null
            }
        }
    }

    fun applyResult(replace: Boolean) {
        val r = _result.value ?: return
        setContent(if (replace) r.text else _content.value.trimEnd() + "\n\n## ${r.label}\n" + r.text)
        _result.value = null
    }

    fun dismissResult() {
        _result.value = null
    }

    val speaking: StateFlow<String?> = container.speech.speaking

    fun toggleReadAloud() {
        if (speaking.value == speechKey) {
            container.speech.stop()
            return
        }
        val text = listOf(_title.value, _content.value).filter { it.isNotBlank() }.joinToString("\n\n")
        viewModelScope.launch {
            try {
                container.speech.speak(speechKey, text)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.message ?: "Could not read aloud")
            }
        }
    }

    private val speechKey get() = "note-$noteId"

    fun toggleDictation() {
        if (_dictating.value) stopDictation() else startDictation()
    }

    private fun startDictation() {
        try {
            dictation.start()
            _dictating.value = true
        } catch (e: Exception) {
            viewModelScope.launch { _messages.send("Could not start dictation: ${e.message}") }
        }
    }

    private fun stopDictation() {
        _dictating.value = false
        val file = dictation.stop() ?: return
        _busy.value = "Transcribing…"
        viewModelScope.launch {
            try {
                val language = container.settings().transcriptionLanguage.ifBlank { null }
                val text = container.ai.stt().transcribe(file, language).segments.joinToString(" ") { it.text }.trim()
                if (text.isNotEmpty()) {
                    val existing = _content.value.trimEnd()
                    setContent(if (existing.isEmpty()) text else "$existing\n$text")
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _messages.send(e.message ?: "Transcription failed")
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
                _busy.value = null
            }
        }
    }

    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        saveJob?.cancel()
        repo.delete(noteId)
        onDone()
    }

    override fun onCleared() {
        if (speaking.value == speechKey) container.speech.stop()
        dictation.stop()?.delete()
        if (!exists) return
        val t = _title.value
        val c = _content.value
        if (t.isBlank() && c.isBlank()) {
            // Don't leave empty notes behind when the editor is closed without typing.
            container.scope.launch { repo.delete(noteId) }
        } else if (saveJob?.isActive == true) {
            // Persist pending edits even though this scope is going away.
            container.scope.launch { repo.update(noteId) { it.copy(title = t, content = c) } }
        }
    }
}

/** Short voice notes recorded with [MediaRecorder] while the note editor is open. */
class DictationRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    fun start() {
        val target = File(context.cacheDir, "dictation_${System.currentTimeMillis()}.m4a")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(16_000)
            r.setAudioEncodingBitRate(32_000)
            r.setOutputFile(target.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            throw e
        }
        recorder = r
        file = target
    }

    /** Returns the recorded file, or null if nothing usable was recorded. */
    fun stop(): File? {
        val r = recorder ?: return null
        recorder = null
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        val f = file
        file = null
        return f?.takeIf { ok && it.length() > 0 } ?: f?.let { it.delete(); null }
    }
}
