package com.kaliscriber.app.sync

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.kaliscriber.app.diagnostics.AppLog
import com.kaliscriber.core.ai.AppSettings
import com.kaliscriber.core.store.JsonValueStore
import com.kaliscriber.core.store.MeetingRepository
import com.kaliscriber.core.sync.MarkdownSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Writes one Markdown note per meeting into a folder the user picked (e.g. an Obsidian vault)
 * and keeps it up to date as meetings change, following [MarkdownSync]'s plan.
 */
class MarkdownFolderSync(
    private val context: Context,
    private val meetings: MeetingRepository,
    private val settings: () -> AppSettings,
    private val state: JsonValueStore<Map<String, MarkdownSync.Entry>>,
) {
    sealed interface Status {
        data object Idle : Status
        data object Working : Status
        data class Done(val at: Long, val written: Int) : Status
        data class Failed(val message: String) : Status
    }

    private val _status = MutableStateFlow<Status>(Status.Idle)
    val status: StateFlow<Status> = _status.asStateFlow()
    private val mutex = Mutex()

    /** Syncs a few seconds after meetings or the folder settings change. */
    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope, settingsFlow: StateFlow<AppSettings>) {
        scope.launch {
            combine(meetings.meetings, settingsFlow.map { it.export.markdownFolder to it.export.markdownTranscript }) { list, folder ->
                list.map { it.id to it.updatedAt } to folder
            }
                .debounce(SETTLE_MS)
                .collect { (_, folder) -> if (folder.first.isNotBlank()) sync() }
        }
    }

    /** Brings the folder up to date now; returns how many notes were written. */
    suspend fun sync(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            val export = settings().export
            val tree = export.markdownFolder.takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return@withContext 0
            _status.value = Status.Working
            try {
                val written = syncTo(tree, export.markdownTranscript)
                _status.value = Status.Done(System.currentTimeMillis(), written)
                written
            } catch (e: Exception) {
                AppLog.w(TAG, "Markdown folder sync failed", e)
                _status.value = Status.Failed(
                    if (e is SecurityException) "KaliScriber can no longer write to the folder. Choose it again." else e.message ?: "Couldn't write to the folder",
                )
                0
            }
        }
    }

    /** Forgets what was written, so choosing a folder writes every note into it. */
    suspend fun reset() {
        state.update { emptyMap() }
        _status.value = Status.Idle
    }

    /** The folder's name as the user sees it, or null if it can't be read. */
    fun folderName(uri: String): String? = runCatching {
        val tree = Uri.parse(uri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private suspend fun syncTo(tree: Uri, transcript: Boolean): Int {
        val resolver = context.contentResolver
        val folderId = DocumentsContract.getTreeDocumentId(tree)
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        val files = children(tree, folderId)
        val all = meetings.all()
        val before = state.value.value
        // An empty library next to a full folder means the meetings didn't load; never wipe the folder for that.
        if (all.isEmpty() && before.isNotEmpty()) return 0
        val plan = MarkdownSync.plan(all, before, transcript)
        if (plan.isEmpty()) return 0
        val next = before.filterKeys { id -> all.any { it.id == id } }.toMutableMap()
        val custom = settings().customFormats
        val projects = settings().projects
        var written = 0
        for (action in plan) {
            when (action) {
                is MarkdownSync.Action.Delete -> {
                    files.remove(action.file)?.let { id ->
                        runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
                            .onFailure { AppLog.w(TAG, "Couldn't delete ${action.file}", it) }
                    }
                }
                is MarkdownSync.Action.Write -> {
                    val meeting = meetings.get(action.meetingId) ?: continue
                    val doc = files[action.file]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it) }
                        ?: DocumentsContract.createDocument(resolver, folder, MIME, action.file)
                        ?: error("Couldn't create ${action.file}")
                    files[action.file] = DocumentsContract.getDocumentId(doc)
                    resolver.openOutputStream(doc, "wt")?.use { out ->
                        out.write(MarkdownSync.note(meeting, transcript, custom, projects = projects).toByteArray())
                    } ?: error("Couldn't write ${action.file}")
                    next[meeting.id] = MarkdownSync.Entry(action.file, meeting.updatedAt, transcript)
                    written++
                    // Save as it goes, so an interrupted sync doesn't write everything again.
                    if (written % SAVE_EVERY == 0) state.update { next.toMap() }
                }
            }
        }
        state.update { next.toMap() }
        return written
    }

    /** File name to document id for the files directly in the folder. */
    private fun children(tree: Uri, folderId: String): MutableMap<String, String> {
        val out = HashMap<String, String>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folderId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out[name] = id
            }
        }
        return out
    }

    private companion object {
        const val TAG = "MarkdownSync"
        const val MIME = "text/markdown"
        const val SETTLE_MS = 5_000L
        const val SAVE_EVERY = 20
    }
}
