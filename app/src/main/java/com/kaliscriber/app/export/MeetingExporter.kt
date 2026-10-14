package com.kaliscriber.app.export

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kaliscriber.app.data.ExportSecret
import com.kaliscriber.app.data.SecretStore
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.export.AffineExporter
import com.kaliscriber.core.export.CalDavExporter
import com.kaliscriber.core.export.ExportContent
import com.kaliscriber.core.export.ExportDocument
import com.kaliscriber.core.export.ExportException
import com.kaliscriber.core.export.ExportResult
import com.kaliscriber.core.export.GoogleDocsExporter
import com.kaliscriber.core.export.NotionExporter
import com.kaliscriber.core.model.Meeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest

/** Where a meeting can be sent. */
enum class ExportTarget(val label: String, val description: String) {
    EMAIL("Email", "Send the notes with your email app"),
    GOOGLE_DOCS("Google Docs", "Create a Google Doc"),
    NOTION("Notion", "Create a page in your Notion workspace"),
    AFFINE("AFFiNE", "Create a doc in your AFFiNE workspace"),
    CALDAV("Calendar & tasks (Baikal)", "Add the meeting and its action items to your CalDAV calendar"),
}

/** Turns a meeting into notes for each export target, using the user's own accounts. */
class MeetingExporter(
    private val context: Context,
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val settings: () -> AppSettings,
) {
    fun document(meeting: Meeting): ExportDocument = ExportContent.document(meeting, settings().export.includeTranscript)

    /** Whether the target has what it needs; null when ready, otherwise what is missing. */
    fun missingSetup(target: ExportTarget): String? {
        val e = settings().export
        return when (target) {
            ExportTarget.EMAIL, ExportTarget.GOOGLE_DOCS -> null
            ExportTarget.NOTION -> when {
                secrets.secret(ExportSecret.NOTION_TOKEN).isNullOrBlank() -> "Add a Notion integration token"
                NotionExporter.pageId(e.notionParent) == null -> "Choose the Notion parent page"
                else -> null
            }
            ExportTarget.AFFINE -> when {
                e.affineWorkspaceId.isBlank() -> "Add your AFFiNE workspace id"
                secrets.secret(ExportSecret.AFFINE_TOKEN).isNullOrBlank() -> "Add an AFFiNE MCP token"
                else -> null
            }
            ExportTarget.CALDAV -> when {
                e.caldavUrl.isBlank() -> "Add your calendar URL"
                e.caldavUser.isBlank() || secrets.secret(ExportSecret.CALDAV_PASSWORD).isNullOrBlank() -> "Add your calendar username and password"
                else -> null
            }
        }
    }

    fun emailIntent(meeting: Meeting): Intent {
        val doc = document(meeting)
        val recipients = settings().export.emailTo.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_EMAIL, recipients.toTypedArray())
            putExtra(Intent.EXTRA_SUBJECT, "Meeting notes: ${meeting.title}")
            putExtra(Intent.EXTRA_TEXT, ExportContent.plainText(doc))
            // Only email apps answer mailto:, so the chooser isn't cluttered with chat apps.
            selector = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
        }
        return Intent.createChooser(send, "Email notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    suspend fun notion(meeting: Meeting): ExportResult {
        val e = settings().export
        val token = secrets.secret(ExportSecret.NOTION_TOKEN).orEmpty()
        return NotionExporter(http, token, e.notionParent).export(document(meeting))
    }

    suspend fun testNotion(): ExportResult =
        NotionExporter(http, secrets.secret(ExportSecret.NOTION_TOKEN).orEmpty(), settings().export.notionParent).test()

    /**
     * Creates an AFFiNE doc. If the server doesn't allow writing yet, the notes are copied to the
     * clipboard and the workspace opened so they can be pasted into a new doc.
     */
    suspend fun affine(meeting: Meeting): ExportResult {
        val exporter = affineExporter()
        val doc = document(meeting)
        return try {
            exporter.export(doc)
        } catch (e: AffineExporter.WriteUnsupported) {
            copyToClipboard(doc.title, "# ${doc.title}\n\n${doc.markdown}")
            ExportResult(e.message ?: AffineExporter.WRITE_UNSUPPORTED, exporter.workspaceUrl())
        }
    }

    suspend fun testAffine(): ExportResult = affineExporter().test()

    private fun affineExporter(): AffineExporter {
        val e = settings().export
        return AffineExporter(http, e.affineServer, e.affineWorkspaceId.trim(), secrets.secret(ExportSecret.AFFINE_TOKEN).orEmpty())
    }

    suspend fun caldav(meeting: Meeting): ExportResult {
        val e = settings().export
        val description = ExportContent.plainText(ExportContent.document(meeting, includeTranscript = false))
        return calDav().export(meeting, description, e.caldavEvent, e.caldavTasks)
    }

    suspend fun testCaldav(): ExportResult = calDav().test()

    private fun calDav(): CalDavExporter {
        val e = settings().export
        return CalDavExporter(http, e.caldavUrl, e.caldavTasksUrl, e.caldavUser.trim(), secrets.secret(ExportSecret.CALDAV_PASSWORD).orEmpty())
    }

    suspend fun googleDocsUpload(meeting: Meeting, accessToken: String): ExportResult =
        GoogleDocsExporter(http).export(accessToken, document(meeting))

    /**
     * Writes the notes as a Word file and opens it in the Google Docs app (or any app that can
     * open it). Works without any Google sign-in setup.
     */
    suspend fun googleDocsFileIntent(meeting: Meeting): Intent {
        val doc = document(meeting)
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            File(dir, safeFileName(doc.title) + ".docx").apply { writeBytes(ExportContent.docx(doc)) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, DOCX_MIME)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val docs = Intent(view).setPackage(GOOGLE_DOCS_PACKAGE)
        return if (docs.resolveActivity(context.packageManager) != null) docs else Intent.createChooser(view, "Open notes").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun safeFileName(title: String) = title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().take(60).ifBlank { "Meeting notes" }

    /** SHA-1 of this app's signing certificate, needed to register a Google OAuth client. */
    fun signingSha1(): String? = runCatching {
        val pm = context.packageManager
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val cert = signatures?.firstOrNull()?.toByteArray() ?: return null
        MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
    }.getOrNull()

    companion object {
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val GOOGLE_DOCS_PACKAGE = "com.google.android.apps.docs.editors.docs"
    }
}

/** Makes export failures readable whatever their origin. */
fun Throwable.exportMessage(): String = when (this) {
    is ExportException -> message ?: "Export failed"
    else -> message ?: javaClass.simpleName
}
