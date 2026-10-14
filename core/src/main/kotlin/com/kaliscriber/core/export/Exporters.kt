package com.kaliscriber.core.export

import com.kaliscriber.core.ai.await
import com.kaliscriber.core.model.Meeting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** What an export produced; [url] opens the result when the target has one. */
data class ExportResult(val message: String, val url: String? = null)

class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

private val JSON = "application/json; charset=utf-8".toMediaType()
private val ExportJson = Json { ignoreUnknownKeys = true; isLenient = true }

private suspend fun OkHttpClient.send(request: Request, target: String): Pair<Int, String> {
    val response = try {
        newCall(request).await()
    } catch (e: IOException) {
        throw ExportException("Could not reach $target: ${e.message}", e)
    }
    return response.use { it.code to (it.body?.string().orEmpty()) }
}

private fun parseJson(text: String, target: String): JsonObject = try {
    ExportJson.parseToJsonElement(text).jsonObject
} catch (e: Exception) {
    throw ExportException("$target returned an unexpected response: ${text.take(200)}", e)
}

private fun errorDetail(body: String): String = runCatching {
    val obj = ExportJson.parseToJsonElement(body).jsonObject
    (obj["message"] ?: (obj["error"] as? JsonObject)?.get("message") ?: obj["error"])?.jsonPrimitive?.contentOrNull
}.getOrNull() ?: body.take(200)

// -------------------------------------------------------------------------------------------------
// Notion
// -------------------------------------------------------------------------------------------------

/** Creates a Notion page under a parent page with an internal integration token. */
class NotionExporter(
    private val http: OkHttpClient,
    private val token: String,
    parent: String,
    private val baseUrl: String = "https://api.notion.com",
) {
    private val parentId: String = pageId(parent) ?: throw ExportException("Set the Notion parent page (its link or id) in Settings.")

    suspend fun export(doc: ExportDocument): ExportResult {
        if (token.isBlank()) throw ExportException("Add your Notion integration token in Settings.")
        val blocks = doc.blocks.map(::block)
        val first = blocks.take(MAX_CHILDREN)
        val body = buildJsonObject {
            putJsonObject("parent") { put("page_id", parentId) }
            putJsonObject("properties") {
                putJsonObject("title") { put("title", richText(listOf(Run(doc.title)))) }
            }
            put("children", JsonArray(first))
        }
        val (code, text) = http.send(request("/v1/pages").post(body.toString().toRequestBody(JSON)).build(), "Notion")
        if (code !in 200..299) throw ExportException(notionError(code, text))
        val page = parseJson(text, "Notion")
        val id = page["id"]?.jsonPrimitive?.contentOrNull ?: throw ExportException("Notion did not return the new page")
        for (batch in blocks.drop(MAX_CHILDREN).chunked(MAX_CHILDREN)) {
            val append = buildJsonObject { put("children", JsonArray(batch)) }
            val (c, t) = http.send(request("/v1/blocks/$id/children").patch(append.toString().toRequestBody(JSON)).build(), "Notion")
            if (c !in 200..299) throw ExportException(notionError(c, t))
        }
        return ExportResult("Saved to Notion", page["url"]?.jsonPrimitive?.contentOrNull)
    }

    /** Checks the token can see the parent page. */
    suspend fun test(): ExportResult {
        val (code, text) = http.send(request("/v1/pages/$parentId").get().build(), "Notion")
        if (code !in 200..299) throw ExportException(notionError(code, text))
        return ExportResult("Notion connection works")
    }

    private fun request(path: String) = Request.Builder()
        .url(baseUrl.trimEnd('/') + path)
        .header("Authorization", "Bearer $token")
        .header("Notion-Version", NOTION_VERSION)

    private fun notionError(code: Int, body: String): String = when (code) {
        401 -> "Notion rejected the token. Check the integration token in Settings."
        403, 404 -> "Notion can't see the parent page. Open it in Notion and add your integration under ••• → Connections."
        else -> "Notion error $code: ${errorDetail(body)}"
    }

    companion object {
        const val NOTION_VERSION = "2022-06-28"
        private const val MAX_CHILDREN = 100
        private const val MAX_TEXT = 2_000

        /** Accepts a page id or any Notion page URL and returns the dashed UUID. */
        fun pageId(input: String): String? {
            // The id is the last 32 hex characters of the last path segment ("My-Page-<id>" or a bare id).
            val segment = input.trim().substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
            val hex = Regex("[0-9a-fA-F]{32}$").find(segment.replace("-", ""))?.value ?: return null
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}".lowercase()
        }

        fun block(b: Block): JsonObject = when (b) {
            is Block.Heading -> typed("heading_${b.level}") { put("rich_text", richText(ExportContent.runs(b.text))) }
            is Block.Paragraph -> typed("paragraph") { put("rich_text", richText(ExportContent.runs(b.text))) }
            is Block.Bullet -> typed("bulleted_list_item") { put("rich_text", richText(ExportContent.runs(b.text))) }
            is Block.Todo -> typed("to_do") {
                put("rich_text", richText(ExportContent.runs(b.text)))
                put("checked", b.done)
            }
        }

        private fun typed(type: String, content: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("object", "block")
            put("type", type)
            putJsonObject(type, content)
        }

        fun richText(runs: List<Run>) = buildJsonArray {
            for (run in runs) {
                for (piece in run.text.chunked(MAX_TEXT)) {
                    add(buildJsonObject {
                        put("type", "text")
                        putJsonObject("text") { put("content", piece) }
                        if (run.bold) putJsonObject("annotations") { put("bold", true) }
                    })
                }
            }
        }
    }
}

// -------------------------------------------------------------------------------------------------
// CalDAV (Baikal, Nextcloud, Radicale, ...)
// -------------------------------------------------------------------------------------------------

/**
 * Puts the meeting as a calendar event and its action items as tasks into CalDAV collections.
 * Resources use stable UIDs, so exporting again updates them instead of creating duplicates.
 */
class CalDavExporter(
    http: OkHttpClient,
    private val eventsUrl: String,
    tasksUrl: String,
    username: String,
    password: String,
) {
    private val tasksUrl = tasksUrl.ifBlank { eventsUrl }
    private val http = http.newBuilder().authenticator(BasicOrDigestAuthenticator(username, password)).build()

    suspend fun export(meeting: Meeting, description: String, includeEvent: Boolean, includeTasks: Boolean): ExportResult {
        if (eventsUrl.isBlank()) throw ExportException("Set your CalDAV calendar URL (e.g. Baikal) in Settings.")
        var events = 0
        var tasks = 0
        if (includeEvent) {
            put(eventsUrl, ICalendar.meetingUid(meeting), ICalendar.event(meeting, description))
            events++
        }
        if (includeTasks) {
            for (item in meeting.insights?.actionItems.orEmpty()) {
                put(tasksUrl, ICalendar.taskUid(meeting, item), ICalendar.task(meeting, item))
                tasks++
            }
        }
        val parts = listOfNotNull(
            "the meeting event".takeIf { events > 0 },
            "$tasks task${if (tasks == 1) "" else "s"}".takeIf { tasks > 0 },
        )
        if (parts.isEmpty()) throw ExportException("Nothing to export: turn on the event or tasks, or generate action items first.")
        return ExportResult("Saved ${parts.joinToString(" and ")} to your calendar")
    }

    /** PROPFIND on the collection to check the URL and credentials. */
    suspend fun test(): ExportResult {
        val url = collection(eventsUrl)
        val body = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop><d:displayname/></d:prop></d:propfind>"""
        val request = Request.Builder().url(url)
            .header("Depth", "0")
            .method("PROPFIND", body.toRequestBody("application/xml; charset=utf-8".toMediaType()))
            .build()
        val (code, _) = http.send(request, "your CalDAV server")
        if (code !in 200..299) throw ExportException(davError(code))
        return ExportResult("Calendar connection works")
    }

    private suspend fun put(collectionUrl: String, uid: String, ics: String) {
        val url = collection(collectionUrl) + "$uid.ics"
        val request = Request.Builder().url(url)
            .put(ics.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()
        val (code, _) = http.send(request, "your CalDAV server")
        if (code !in 200..299) throw ExportException(davError(code))
    }

    private fun collection(url: String): String {
        val trimmed = url.trim()
        if (trimmed.toHttpUrlOrNull() == null) throw ExportException("The CalDAV URL is not valid: $trimmed")
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    private fun davError(code: Int) = when (code) {
        401 -> "The CalDAV server rejected the username or password."
        403 -> "The CalDAV server refused the change. Does this calendar accept tasks (VTODO)?"
        404 -> "Calendar not found. For Baikal use https://<server>/dav.php/calendars/<user>/<calendar>/"
        415 -> "This calendar does not accept that item type. Use a separate tasks calendar for action items."
        else -> "CalDAV error $code"
    }
}

// -------------------------------------------------------------------------------------------------
// AFFiNE
// -------------------------------------------------------------------------------------------------

/**
 * Creates an AFFiNE doc through the workspace MCP endpoint (`/api/workspaces/<id>/mcp`, JSON-RPC
 * over HTTP) with an MCP access token created in AFFiNE's workspace settings.
 */
class AffineExporter(
    private val http: OkHttpClient,
    serverUrl: String,
    private val workspaceId: String,
    private val token: String,
) {
    private val server = serverUrl.trim().trimEnd('/').ifBlank { "https://app.affine.pro" }

    /** Thrown when the server does not offer document creation (yet) to this token. */
    class WriteUnsupported(message: String) : Exception(message)

    suspend fun export(doc: ExportDocument): ExportResult {
        check()
        val result = call(
            "tools/call",
            buildJsonObject {
                put("name", "create_document")
                putJsonObject("arguments") {
                    put("title", doc.title)
                    put("content", doc.markdown)
                }
            },
        )
        val text = result["content"]?.jsonArray?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (result["isError"]?.jsonPrimitive?.contentOrNull == "true") throw ExportException("AFFiNE: $text")
        val docId = runCatching { ExportJson.parseToJsonElement(text).jsonObject["docId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return ExportResult("Saved to AFFiNE", docId?.let { "$server/workspace/$workspaceId/$it" })
    }

    /** Verifies the token and that this server lets it create documents. */
    suspend fun test(): ExportResult {
        val tools = call("tools/list", JsonObject(emptyMap()))["tools"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
        if ("create_document" !in tools) throw WriteUnsupported(WRITE_UNSUPPORTED)
        return ExportResult("AFFiNE connection works")
    }

    fun workspaceUrl() = "$server/workspace/$workspaceId/all"

    private fun check() {
        if (workspaceId.isBlank()) throw ExportException("Set your AFFiNE workspace id in Settings.")
        if (token.isBlank()) throw ExportException("Add an AFFiNE MCP token in Settings (workspace settings → Integrations → MCP).")
    }

    private suspend fun call(method: String, params: JsonObject): JsonObject {
        check()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }
        val request = Request.Builder()
            .url("$server/api/workspaces/$workspaceId/mcp")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val (code, text) = http.send(request, "AFFiNE")
        if (code == 401 || code == 403) throw ExportException("AFFiNE rejected the MCP token or workspace id.")
        if (code == 404) throw WriteUnsupported("This AFFiNE server has no MCP endpoint. $WRITE_UNSUPPORTED")
        if (code !in 200..299) throw ExportException("AFFiNE error $code: ${errorDetail(text)}")
        val json = parseJson(text, "AFFiNE")
        (json["error"] as? JsonObject)?.let { error ->
            val message = error["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (message.contains("Tool not found")) throw WriteUnsupported(WRITE_UNSUPPORTED)
            throw ExportException("AFFiNE: $message")
        }
        return json["result"] as? JsonObject ?: JsonObject(emptyMap())
    }

    companion object {
        const val WRITE_UNSUPPORTED =
            "This AFFiNE server doesn't allow creating docs through its API yet (it needs a read-write MCP token on a server with document writing). " +
                "The notes were copied so you can paste them into a new AFFiNE doc instead."
    }
}

// -------------------------------------------------------------------------------------------------
// Google Docs (via Google Drive)
// -------------------------------------------------------------------------------------------------

/** Uploads HTML to Google Drive, converting it to a Google Doc, using an OAuth access token. */
class GoogleDocsExporter(
    private val http: OkHttpClient,
    private val uploadUrl: String = "https://www.googleapis.com/upload/drive/v3/files",
) {
    suspend fun export(accessToken: String, doc: ExportDocument): ExportResult {
        val metadata = buildJsonObject {
            put("name", doc.title)
            put("mimeType", "application/vnd.google-apps.document")
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(ExportContent.html(doc).toRequestBody("text/html; charset=utf-8".toMediaType()))
            .build()
        val request = Request.Builder()
            .url("$uploadUrl?uploadType=multipart&fields=id,webViewLink")
            .header("Authorization", "Bearer $accessToken")
            .post(body)
            .build()
        val (code, text) = http.send(request, "Google Drive")
        if (code == 401) throw ExportException("Google sign-in expired. Try again.")
        if (code !in 200..299) throw ExportException("Google Drive error $code: ${errorDetail(text)}")
        val json = parseJson(text, "Google Drive")
        val id = json["id"]?.jsonPrimitive?.contentOrNull
        val link = json["webViewLink"]?.jsonPrimitive?.contentOrNull ?: id?.let { "https://docs.google.com/document/d/$it/edit" }
        return ExportResult("Saved to Google Docs", link)
    }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    }
}
