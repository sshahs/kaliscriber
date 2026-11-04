package com.kaliscriber.app.diagnostics

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.kaliscriber.app.AppContainer
import com.kaliscriber.core.ai.ProviderId
import com.kaliscriber.core.diagnostics.ProblemReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Problem reports: the phone, the app's settings (no keys, addresses or meeting content) and the
 * log of what the app did, as a text file the user saves or sends. Nothing is sent by itself.
 */
class ProblemReports(private val container: AppContainer) {
    private val context: Context get() = container.appContext

    suspend fun text(): String = withContext(Dispatchers.IO) {
        ProblemReport.build("KaliScriber problem report", facts(), AppLog.diagnostics?.text().orEmpty())
    }

    fun fileName(): String = "KaliScriber report ${SimpleDateFormat("yyyy-MM-dd HHmm", Locale.ROOT).format(Date())}.txt"

    /** Writes the report into [uri], a file the user chose. */
    suspend fun saveTo(uri: Uri) {
        val report = text()
        withContext(Dispatchers.IO) {
            context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(report.toByteArray()) }
                ?: error("Couldn't write to the chosen file")
        }
        AppLog.i(TAG, "Problem report saved")
    }

    /** A share sheet for an email (or any app) with the report attached. */
    suspend fun shareIntent(): Intent {
        val report = text()
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "reports").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            File(dir, fileName()).apply { writeText(report) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "KaliScriber problem report (${versionLine(context)})")
            .putExtra(Intent.EXTRA_TEXT, "What happened, and roughly when:\n\n\n")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, "Send problem report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun clear() = AppLog.diagnostics?.clear()

    fun logBytes(): Long = AppLog.diagnostics?.sizeBytes() ?: 0

    private fun facts(): List<Pair<String, String>> {
        val s = container.settings()
        val auto = container.autoRecorder
        val power = context.getSystemService(PowerManager::class.java)
        fun allowed(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        fun yes(b: Boolean) = if (b) "yes" else "no"
        val chat = s.configFor(s.chatProvider, null)
        val enabledSchedules = s.scheduledRecordings.count { it.enabled }
        val meetings = container.meetings.all()
        return listOf(
            "Created" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date()),
            "App" to versionLine(context),
            "Phone" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.joinToString()}",
            "Time zone" to TimeZone.getDefault().id,
            "Language" to Locale.getDefault().toLanguageTag(),
            "Free space" to "${context.filesDir.usableSpace / (1024 * 1024)} MB",
            "Meetings" to "${meetings.size} (${meetings.count { it.audioPath != null && it.audioDeletedAt == null }} with recordings)",
            "Recording now" to container.recorder.state.value.javaClass.simpleName,
            "AI model" to "${s.chatProvider.displayName} / ${chat.chatModel.ifBlank { "not set" }}" +
                (s.fastChatProvider?.let { " (quick: ${it.displayName} ${s.fastChatModel.ifBlank { "default" }})" } ?: ""),
            "API keys set" to ProviderId.entries.filter { it.keyRequired && !container.secrets.apiKey(it).isNullOrBlank() }.joinToString { it.displayName }.ifEmpty { "none" },
            "Transcription" to s.sttProvider.displayName + if (s.sttProvider == ProviderId.LOCAL) " (${s.localAsrModel.ifBlank { "default model" }})" else "",
            "Speaker detection on phone" to yes(s.localDiarization),
            "Running notes" to yes(s.liveNotes),
            "Summary after meeting" to yes(s.autoAnalyze),
            "Hide details from AI" to yes(s.redaction.active),
            "Your name set" to yes(s.yourName.isNotBlank()),
            "Projects" to "${s.projects.size}",
            "Microphone" to "${s.microphone.label}, clean-up ${yes(s.audioCleanup)}, chunks ${s.chunkSeconds}s",
            "Calendar auto-record" to "${yes(s.autoRecord)} (${s.autoRecordWhich}), phone calendars ${yes(s.phoneCalendar)}, CalDAV ${yes(s.export.caldavUrl.isNotBlank())}",
            "Scheduled recordings" to "$enabledSchedules on of ${s.scheduledRecordings.size}" + (s.recordingPausedUntil?.let { ", paused until $it" } ?: ""),
            "Task sync" to yes(s.export.caldavTaskSync && s.export.caldavTasks && s.export.caldavUrl.isNotBlank()),
            "Markdown folder" to yes(s.export.markdownFolder.isNotBlank()),
            "Automatic backups" to "${s.backupEvery}" + if (s.backupFolder.isBlank()) " (no folder)" else "",
            "Permissions" to listOf(
                "microphone ${yes(allowed(Manifest.permission.RECORD_AUDIO))}",
                "notifications ${yes(Build.VERSION.SDK_INT < 33 || allowed("android.permission.POST_NOTIFICATIONS"))}",
                "calendar ${yes(allowed(Manifest.permission.READ_CALENDAR))}",
                "exact alarms ${yes(auto.exactAlarmsAllowed())}",
                "full screen ${yes(auto.fullScreenAllowed())}",
                "over other apps ${yes(auto.overlayAllowed())}",
            ).joinToString(", "),
            "Battery optimisation off" to yes(power?.isIgnoringBatteryOptimizations(context.packageName) == true),
        )
    }

    companion object {
        private const val TAG = "Report"

        /** "0.15.0 (24)". */
        fun versionLine(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            "${info.versionName} ($code)"
        }.getOrDefault("unknown version")
    }
}
