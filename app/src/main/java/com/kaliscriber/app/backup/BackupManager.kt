package com.kaliscriber.app.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.kaliscriber.app.processing.MeetingJobs
import com.kaliscriber.app.recording.RecordingManager
import com.kaliscriber.app.recording.RecordingState
import com.kaliscriber.core.backup.BackupException
import com.kaliscriber.core.backup.LibraryBackup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes and restores encrypted backups ([LibraryBackup]) to and from files the user picks, in the
 * app's scope under the processing service, so they finish when the user leaves the screen.
 */
class BackupManager(
    private val context: Context,
    private val backup: LibraryBackup,
    private val recorder: RecordingManager,
    private val jobs: MeetingJobs,
    private val scope: CoroutineScope,
) {
    sealed interface State {
        data object Idle : State
        data class Working(val message: String, val progress: Float? = null) : State
        data class Done(val message: String) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** A file name for a new backup, e.g. "KaliScriber 2026-10-15.ksbackup". */
    fun suggestedName(): String = "KaliScriber ${SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())}.ksbackup"

    fun backUp(uri: Uri, password: CharArray, withRecordings: Boolean) = run("Backing up", password) {
        try {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Couldn't open the file to write the backup to.")
            out.buffered(256 * 1024).use { stream ->
                backup.write(stream, password, withRecordings) { fraction ->
                    report("Backing up", fraction)
                }
            }
        } catch (e: Throwable) {
            // Don't leave half a backup behind.
            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            throw e
        }
        "Backup saved. Keep the password safe: without it the backup can't be opened. API keys aren't included."
    }

    fun restore(uri: Uri, password: CharArray, replaceSettings: Boolean) = run("Restoring", password) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Couldn't open the backup.")
        val result = input.buffered(256 * 1024).use { stream ->
            backup.restore(stream, password, replaceSettings) { step -> report(step, null) }
        }
        buildString {
            append("Restored ${count(result.meetingsAdded, "meeting")}")
            if (result.recordings > 0) append(" with ${count(result.recordings, "recording")}")
            if (result.notesAdded > 0) append(", ${count(result.notesAdded, "note")}")
            if (result.voicesAdded > 0) append(", ${count(result.voicesAdded, "saved voice")}")
            append(".")
            if (result.meetingsKept > 0) append(" ${count(result.meetingsKept, "meeting")} already on this phone ${if (result.meetingsKept == 1) "was" else "were"} kept as they are.")
            if (result.settingsRestored) append(" Settings were restored; enter your API keys again in Settings.")
        }
    }

    fun dismiss() {
        if (_state.value !is State.Working) _state.value = State.Idle
    }

    /** Shows progress, once per percent: backups report every block written. */
    private fun report(step: String, fraction: Float?) {
        val percent = fraction?.let { (it * 100).toInt() }
        val current = _state.value
        if (current is State.Working && current.message == step && current.progress?.let { (it * 100).toInt() } == percent) return
        _state.value = State.Working(step, fraction)
        jobs.reportAppStep(step, percent)
    }

    /** Runs [block] in the background; [password] is wiped once it is done. */
    private fun run(what: String, password: CharArray, block: suspend () -> String) {
        if (_state.value is State.Working) return
        if (recorder.state.value is RecordingState.Recording) {
            password.fill('\u0000')
            _state.value = State.Failed("Stop the recording first.")
            return
        }
        report(what, null)
        scope.launch {
            _state.value = try {
                val message = jobs.runApp("backup") { withContext(Dispatchers.IO) { block() } }
                State.Done(message)
            } catch (e: CancellationException) {
                throw e
            } catch (e: BackupException) {
                State.Failed(e.message ?: "The backup couldn't be read.")
            } catch (e: OutOfMemoryError) {
                State.Failed("The phone ran out of memory.")
            } catch (e: Exception) {
                Log.w("KaliScriber", "$what failed", e)
                State.Failed("$what failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                password.fill('\u0000')
            }
        }
    }

    private fun count(n: Int, noun: String) = "$n $noun${if (n == 1) "" else "s"}"

    companion object {
        const val MIN_PASSWORD = 8
    }
}
