package org.mundhenk.nxbackup.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

class HttpServerTest {
    private val logLines = CopyOnWriteArrayList<String>()
    private val seen = CopyOnWriteArrayList<HttpRequest>()
    private val bodies = CopyOnWriteArrayList<ByteArray>()
    private lateinit var server: HttpServer

    @Before
    fun setUp() {
        server = HttpServer(0, { req ->
            seen += req
            when (req.path) {
                "/boom" -> throw IllegalStateException("kaboom")
                "/echo" -> { val b = req.body.readBytes(); bodies += b; HttpResponse(200, b, "application/octet-stream", mapOf("X-Len" to req.contentLength.toString())) }
                "/partial" -> { req.body.read(); HttpResponse.empty(204) } // handler reads one byte, server drains the rest
                "/created" -> HttpResponse.empty(201)
                else -> HttpResponse.notFound()
            }
        }, { logLines += it })
        server.start()
        server.start() // idempotent
    }

    @After
    fun tearDown() { server.stop(); server.stop() }

    private fun raw(request: ByteArray): String = Socket("127.0.0.1", server.boundPort).use { s ->
        s.soTimeout = 10_000
        s.getOutputStream().write(request); s.getOutputStream().flush(); s.shutdownOutput()
        s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
    }

    private fun raw(request: String) = raw(request.toByteArray(Charsets.ISO_8859_1))

    @Test
    fun chunkedBodyIsDecodedIncludingExtensionsAndTrailers() {
        val req = "POST /echo?a=1&b=x%20y&flag&=novalue HTTP/1.1\r\nHost: x\r\nTransfer-Encoding: chunked\r\n\r\n" +
            "5;ext=1\r\nHello\r\n7\r\n, world\r\n0\r\nX-Trailer: 1\r\n\r\n"
        val resp = raw(req)
        assertTrue(resp, resp.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(resp.endsWith("Hello, world"))
        assertTrue(resp.contains("X-Len: -1"))
        val r = seen.last()
        assertEquals(-1L, r.contentLength)
        assertEquals(mapOf("a" to "1", "b" to "x y", "flag" to "", "" to "novalue"), r.query)
        assertEquals("x", r.header("HOST"))
        assertNull(r.header("nope"))
    }

    @Test
    fun largeChunkedBodyThroughHttpUrlConnection() {
        val payload = ByteArray(700_000) { (it * 31).toByte() }
        val c = URL("http://127.0.0.1:${server.boundPort}/echo").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.setChunkedStreamingMode(4096)
        c.outputStream.use { it.write(payload) }
        assertEquals(200, c.responseCode)
        assertArrayEquals(payload, c.inputStream.use { it.readBytes() })
    }

    @Test
    fun headAnswersWithHeadersOnly() {
        val resp = raw("HEAD /echo HTTP/1.1\r\nContent-Length: 3\r\n\r\nabc")
        assertTrue(resp, resp.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(resp.contains("Content-Length: 3\r\n"))
        assertTrue(resp.endsWith("\r\n\r\n")) // no body
        assertArrayEquals("abc".toByteArray(), bodies.last())
    }

    @Test
    fun expectContinueIsAcknowledgedBeforeTheBody() {
        val resp = raw("PUT /created HTTP/1.1\r\nExpect: 100-continue\r\nContent-Length: 2\r\n\r\nok")
        assertTrue(resp, resp.startsWith("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 201 Created\r\n"))
    }

    @Test
    fun handlerExceptionBecomes500AndUnreadBodyIsDrained() {
        val resp = raw("POST /boom HTTP/1.1\r\nContent-Length: 4\r\n\r\nxxxx")
        assertTrue(resp, resp.startsWith("HTTP/1.1 500 Internal Server Error\r\n"))
        assertTrue(resp.contains("kaboom"))
        assertTrue(logLines.any { it.contains("handler error for POST /boom") })
        val partial = raw("POST /partial HTTP/1.1\r\nContent-Length: 10\r\n\r\n0123456789")
        assertTrue(partial, partial.startsWith("HTTP/1.1 204 No Content\r\n"))
    }

    @Test
    fun malformedOrEmptyRequestsCloseQuietly() {
        assertEquals("", raw(""))
        assertEquals("", raw("GARBAGE\r\n\r\n"))
        val tooLong = "GET / HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n"
        assertEquals("", raw(tooLong))
        assertTrue(logLines.any { it.contains("header line too long") })
        // header without ':' is ignored, request still served
        val resp = raw("GET /nothing HTTP/1.1\r\nnocolon\r\n\r\n")
        assertTrue(resp, resp.startsWith("HTTP/1.1 404 Not Found\r\n"))
        // a lone LF-terminated request line also works (lenient parser)
        assertTrue(raw("GET /nothing HTTP/1.1\n\n").startsWith("HTTP/1.1 404"))
    }

    @Test
    fun reasonPhrasesAndDefaults() {
        val expected = mapOf(200 to "OK", 201 to "Created", 204 to "No Content", 302 to "Found", 304 to "Not Modified",
            400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found", 405 to "Method Not Allowed",
            409 to "Conflict", 413 to "Payload Too Large", 500 to "Internal Server Error", 503 to "Service Unavailable", 418 to "Status")
        expected.forEach { (code, phrase) -> assertEquals(phrase, HttpServer.reasonPhrase(code)) }
        val r = HttpResponse()
        assertEquals(200, r.status); assertEquals(0, r.body.size); assertTrue(r.contentType.startsWith("text/xml"))
        assertEquals(404, HttpResponse.notFound().status)
        assertEquals(503, HttpResponse.xml("<x/>", 503).status)
    }

    @Test
    fun chunkedInputStreamEdgeCases() {
        val ok = ChunkedInputStream(ByteArrayInputStream("3\r\nabc\r\n0\r\n\r\n".toByteArray()))
        assertEquals('a'.code, ok.read()); assertEquals('b'.code, ok.read()); assertEquals('c'.code, ok.read())
        assertEquals(-1, ok.read()); assertEquals(-1, ok.read())
        assertEquals(0, ChunkedInputStream(ByteArrayInputStream(ByteArray(0))).read(ByteArray(4), 0, 0))
        assertEquals(-1, ChunkedInputStream(ByteArrayInputStream("0\r\n\r\n".toByteArray())).read(ByteArray(4), 0, 4))
        try { ChunkedInputStream(ByteArrayInputStream("zz\r\n".toByteArray())).read(); throw AssertionError("expected IOException") }
        catch (e: IOException) { assertTrue(e.message!!.contains("bad chunk size")) }
        try { ChunkedInputStream(ByteArrayInputStream("5\r\nab".toByteArray())).readBytes(); throw AssertionError("expected IOException") }
        catch (e: IOException) { assertTrue(e.message!!.contains("unexpected EOF")) }
    }

    @Test
    fun boundedInputStreamStopsAtTheLimit() {
        val b = BoundedInputStream(ByteArrayInputStream("abcdef".toByteArray()), 3)
        assertEquals(3, b.remaining)
        assertEquals('a'.code, b.read())
        val buf = ByteArray(10)
        assertEquals(2, b.read(buf, 0, 10))
        assertEquals(0, b.remaining)
        assertEquals(-1, b.read()); assertEquals(-1, b.read(buf, 0, 10))
        val empty = BoundedInputStream(ByteArrayInputStream(ByteArray(0)), 5)
        assertEquals(-1, empty.read())
        assertFalse(empty.remaining == 0L)
    }
}
