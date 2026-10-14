package com.kaliscriber.core

import com.kaliscriber.core.export.AffineExporter
import com.kaliscriber.core.export.BasicOrDigestAuthenticator
import com.kaliscriber.core.export.Block
import com.kaliscriber.core.export.CalDavExporter
import com.kaliscriber.core.export.ExportContent
import com.kaliscriber.core.export.ExportDocument
import com.kaliscriber.core.export.ExportException
import com.kaliscriber.core.export.GoogleDocsExporter
import com.kaliscriber.core.export.ICalendar
import com.kaliscriber.core.export.NotionExporter
import com.kaliscriber.core.model.ActionItem
import com.kaliscriber.core.model.Meeting
import com.kaliscriber.core.model.MeetingInsights
import com.kaliscriber.core.model.Speaker
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExportersTest {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterTest
    fun tearDown() = server.shutdown()

    private val meeting = Meeting(
        id = "m1", title = "Launch sync", createdAt = 1_760_000_000_000, durationMs = 1_800_000,
        speakers = listOf(Speaker("s1", "Priya", isPlaceholder = false)),
        insights = MeetingInsights(
            summary = "We agreed the **launch** date; budget, pending.",
            decisions = listOf("Launch on 3 Nov"),
            actionItems = listOf(
                ActionItem("a1", "Email the press list", owner = "Sam", dueDate = "Friday", dueDateIso = "2026-10-16", priority = "high"),
                ActionItem("a2", "Book venue", done = true),
            ),
        ),
    )

    @Test
    fun `document renders to blocks, text, html and docx`() {
        val doc = ExportContent.document(meeting, includeTranscript = false)
        assertTrue(doc.blocks.any { it is Block.Heading && it.text == "Summary" })
        assertTrue(doc.blocks.contains(Block.Todo("Email the press list (Sam, due Friday, high)", false)))
        assertTrue(doc.blocks.contains(Block.Todo("Book venue", true)))
        assertTrue(ExportContent.plainText(doc).contains("[x] Book venue"))
        val html = ExportContent.html(doc)
        assertTrue(html.contains("<strong>launch</strong>"))
        assertTrue(html.contains("<li>☑ Book venue</li>"))
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(ExportContent.docx(doc))).use { zip ->
            while (true) names += (zip.nextEntry ?: break).name
        }
        assertTrue("word/document.xml" in names && "[Content_Types].xml" in names)
    }

    @Test
    fun `notion page is created with blocks and long notes are appended in batches`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"page-1","url":"https://notion.so/page-1"}"""))
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        val doc = ExportDocument("Big", (1..150).joinToString("\n") { "- item $it" })
        val result = NotionExporter(http, "secret_x", "https://www.notion.so/My-Page-0123456789abcdef0123456789abcdef", server.url("").toString())
            .export(doc)
        assertEquals("https://notion.so/page-1", result.url)
        val create = server.takeRequest()
        assertEquals("/v1/pages", create.path)
        assertEquals("Bearer secret_x", create.getHeader("Authorization"))
        assertEquals(NotionExporter.NOTION_VERSION, create.getHeader("Notion-Version"))
        val body = Json.parseToJsonElement(create.body.readUtf8()).jsonObject
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", body["parent"]!!.jsonObject["page_id"]!!.jsonPrimitive.content)
        assertEquals(100, body["children"]!!.jsonArray.size)
        val append = server.takeRequest()
        assertEquals("PATCH", append.method)
        assertEquals("/v1/blocks/page-1/children", append.path)
        assertEquals(50, Json.parseToJsonElement(append.body.readUtf8()).jsonObject["children"]!!.jsonArray.size)
    }

    @Test
    fun `caldav puts event and tasks with digest auth`() = runTest {
        val challenge = """Digest realm="BaikalDAV", nonce="abc123", qop="auth", algorithm=MD5, opaque="xyz""""
        repeat(3) {
            server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", challenge))
            server.enqueue(MockResponse().setResponseCode(201))
        }
        val url = server.url("/dav.php/calendars/sunny/default").toString()
        val result = CalDavExporter(http, url, "", "sunny", "pw").export(meeting, "Summary", includeEvent = true, includeTasks = true)
        assertEquals("Saved the meeting event and 2 tasks to your calendar", result.message)

        server.takeRequest() // unauthenticated attempt
        val authed = server.takeRequest()
        assertEquals("PUT", authed.method)
        assertEquals("/dav.php/calendars/sunny/default/kaliscriber-meeting-m1.ics", authed.path)
        val auth = authed.getHeader("Authorization")!!
        val p = BasicOrDigestAuthenticator.parseParams(auth.removePrefix("Digest "))
        val ha1 = BasicOrDigestAuthenticator.md5("sunny:BaikalDAV:pw")
        val ha2 = BasicOrDigestAuthenticator.md5("PUT:${p["uri"]}")
        assertEquals(BasicOrDigestAuthenticator.md5("$ha1:abc123:${p["nc"]}:${p["cnonce"]}:auth:$ha2"), p["response"])
        assertEquals("xyz", p["opaque"])
        val ics = authed.body.readUtf8()
        assertTrue(ics.contains("BEGIN:VEVENT") && ics.contains("SUMMARY:Launch sync"))

        server.takeRequest()
        val task = server.takeRequest()
        val todo = task.body.readUtf8()
        assertTrue(todo.contains("BEGIN:VTODO"))
        assertTrue(todo.contains("DUE;VALUE=DATE:20261016"))
        assertTrue(todo.contains("PRIORITY:1"))
    }

    @Test
    fun `caldav basic auth and helpful errors`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", """Basic realm="dav""""))
        server.enqueue(MockResponse().setResponseCode(404))
        val e = assertFailsWith<ExportException> {
            CalDavExporter(http, server.url("/cal/").toString(), "", "u", "p").export(meeting, "x", includeEvent = true, includeTasks = false)
        }
        assertTrue(e.message!!.contains("dav.php/calendars"))
        server.takeRequest()
        assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `icalendar escapes and folds long lines`() {
        assertEquals("a\\, b\\; c\\\\ d\\ne", ICalendar.escape("a, b; c\\ d\ne"))
        val folded = ICalendar.fold("DESCRIPTION:" + "é".repeat(80))
        assertTrue(folded.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
        assertEquals("DESCRIPTION:" + "é".repeat(80), folded.replace("\r\n ", ""))
    }

    @Test
    fun `affine creates a doc through the workspace mcp endpoint`() = runTest {
        server.enqueue(MockResponse().setBody(
            """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"{\"success\":true,\"docId\":\"doc9\"}"}]}}""",
        ))
        val exporter = AffineExporter(http, server.url("").toString(), "ws1", "aff_mcp_v1.a.b")
        val result = exporter.export(ExportDocument("Launch", "## Summary\nDone"))
        assertEquals(server.url("").toString().trimEnd('/') + "/workspace/ws1/doc9", result.url)
        val request = server.takeRequest()
        assertEquals("/api/workspaces/ws1/mcp", request.path)
        assertEquals("Bearer aff_mcp_v1.a.b", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("tools/call", body["method"]!!.jsonPrimitive.content)
        assertEquals("create_document", body["params"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `affine without write support is reported clearly`() = runTest {
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Tool not found: create_document"}}"""))
        assertFailsWith<AffineExporter.WriteUnsupported> {
            AffineExporter(http, server.url("").toString(), "ws1", "t").export(ExportDocument("A", "b"))
        }
    }

    @Test
    fun `google docs upload converts html to a doc`() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"d1","webViewLink":"https://docs.google.com/document/d/d1/edit"}"""))
        val result = GoogleDocsExporter(http, server.url("/upload").toString()).export("tok", ExportDocument("T", "Hello"))
        assertEquals("https://docs.google.com/document/d/d1/edit", result.url)
        val request = server.takeRequest()
        assertEquals("Bearer tok", request.getHeader("Authorization"))
        assertTrue(request.path!!.contains("uploadType=multipart"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("application/vnd.google-apps.document") && body.contains("<p>Hello</p>"))
    }
}
