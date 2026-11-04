package com.kaliscriber.app.diagnostics

import android.content.Context
import android.util.Log
import com.kaliscriber.core.diagnostics.DiagnosticLog
import java.io.File
import java.util.concurrent.Executors

/**
 * Android's log plus the app's own log on the phone, which problem reports are made from. Has
 * the same calls as [Log] so it can stand in for it. Writing happens off the calling thread.
 */
object AppLog {
    @Volatile private var log: DiagnosticLog? = null
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLog").apply { isDaemon = true } }

    fun init(context: Context) {
        if (log == null) log = DiagnosticLog(File(context.filesDir, "logs"))
    }

    val diagnostics: DiagnosticLog? get() = log

    fun i(tag: String, message: String): Int {
        write(DiagnosticLog.Level.INFO, tag, message, null)
        return Log.i(tag, message)
    }

    fun w(tag: String, message: String): Int {
        write(DiagnosticLog.Level.WARN, tag, message, null)
        return Log.w(tag, message)
    }

    fun w(tag: String, message: String, error: Throwable?): Int {
        write(DiagnosticLog.Level.WARN, tag, message, error)
        return Log.w(tag, message, error)
    }

    fun e(tag: String, message: String): Int {
        write(DiagnosticLog.Level.ERROR, tag, message, null)
        return Log.e(tag, message)
    }

    fun e(tag: String, message: String, error: Throwable?): Int {
        write(DiagnosticLog.Level.ERROR, tag, message, error)
        return Log.e(tag, message, error)
    }

    /** Writes at once, for a crash that is about to end the process. */
    fun crash(error: Throwable, thread: String) {
        log?.add(DiagnosticLog.Level.ERROR, "Crash", "The app stopped in thread $thread", error)
    }

    private fun write(level: DiagnosticLog.Level, tag: String, message: String, error: Throwable?) {
        val target = log ?: return
        runCatching { writer.execute { target.add(level, tag, message, error) } }
    }
}
