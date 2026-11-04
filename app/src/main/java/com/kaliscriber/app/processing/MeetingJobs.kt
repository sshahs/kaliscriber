package com.kaliscriber.app.processing

import android.content.Context
import android.content.Intent
import com.kaliscriber.app.diagnostics.AppLog
import androidx.core.content.ContextCompat
import com.kaliscriber.core.ai.AiException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runs long meeting jobs started from a screen (speaker detection, AI analysis) in the app's scope
 * instead of the screen's, under [ProcessingService], a foreground service with a wake lock. They
 * then keep going when the user leaves the screen or the app, or the phone locks.
 */
class MeetingJobs(private val context: Context, private val scope: CoroutineScope) {

    private val _active = MutableStateFlow<Set<String>>(emptySet())

    /** Meetings with a job running, and app jobs (ids starting with [APP_JOB]). */
    val active: StateFlow<Set<String>> = _active.asStateFlow()

    private val _appStep = MutableStateFlow<Pair<String?, Int?>>(null to null)
    /** What an app job (such as a backup) is doing, and its percentage when known. */
    val appStep: StateFlow<Pair<String?, Int?>> = _appStep.asStateFlow()

    fun reportAppStep(step: String, percent: Int? = null) {
        _appStep.value = step to percent
    }

    /**
     * Starts [block] for [meetingId] and waits for it. Cancelling the caller (e.g. leaving the screen)
     * stops the waiting, not the job. Only one job runs per meeting at a time.
     */
    suspend fun run(meetingId: String, block: suspend () -> Unit) {
        synchronized(this) {
            if (meetingId in _active.value) throw AiException("This meeting is still being processed. Try again when it finishes.")
            _active.value = _active.value + meetingId
        }
        val job = scope.async {
            try {
                block()
            } finally {
                synchronized(this@MeetingJobs) { _active.value = _active.value - meetingId }
            }
        }
        startService()
        job.await()
    }

    /** Runs [block] as the app job [name] under the processing service, like [run], and returns its result. */
    suspend fun <T> runApp(name: String, block: suspend () -> T): T {
        val id = APP_JOB + name
        synchronized(this) {
            if (id in _active.value) throw AiException("This is already running.")
            _active.value = _active.value + id
        }
        val job = scope.async {
            try {
                block()
            } finally {
                synchronized(this@MeetingJobs) { _active.value = _active.value - id }
                _appStep.value = null to null
            }
        }
        startService()
        return job.await()
    }

    companion object {
        /** Prefix of jobs that aren't about one meeting. */
        const val APP_JOB = "app:"
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
        } catch (e: Exception) {
            // Not allowed from the background on newer Android; the job still runs while the app is open.
            AppLog.w("KaliScriber", "Could not start the processing service: ${e.message}")
        }
    }
}
