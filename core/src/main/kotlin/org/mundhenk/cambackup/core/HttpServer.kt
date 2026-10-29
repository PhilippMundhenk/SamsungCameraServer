package org.mundhenk.cambackup.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>, // lower-cased keys
    val body: InputStream,            // bounded to Content-Length (or chunk-decoded)
    val contentLength: Long,          // -1 when chunked
    val remoteAddress: String,
    val localAddress: String,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun readBodyText(): String = body.readBytes().toString(Charsets.UTF_8)
}

class HttpResponse(
    val status: Int = 200,
    val body: ByteArray = ByteArray(0),
    val contentType: String = "text/xml; charset=\"utf-8\"",
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    companion object {
        fun xml(body: String, status: Int = 200) = HttpResponse(status, body.toByteArray(Charsets.UTF_8))
        fun empty(status: Int = 200) = HttpResponse(status, ByteArray(0), "text/plain")
        fun notFound() = HttpResponse(404, "Not Found".toByteArray(), "text/plain")
    }
}

fun interface HttpHandler {
    fun handle(request: HttpRequest): HttpResponse
}

/**
 * Deliberately tiny HTTP/1.1 server: one thread per connection, no keep-alive,
 * body streamed to the handler bounded by Content-Length. The camera's HTTP
 * stack is from 2012 and not fully standards compliant; being lenient here
 * (no strict header parsing, Connection: close on every reply) is the point.
 */
class HttpServer(
    private val port: Int,
    private val handler: HttpHandler,
    private val log: (String) -> Unit = {},
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, THREAD_NAME).apply { isDaemon = true } }

    val boundPort: Int get() = serverSocket?.localPort ?: port

    fun start() {
        if (running.getAndSet(true)) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        serverSocket = ss
        pool.execute {
            while (running.get()) {
                val socket = try { ss.accept() } catch (e: IOException) { if (running.get()) log("accept failed: $e"); break }
                pool.execute { serve(socket) }
            }
        }
        log("HTTP listening on ${ss.localPort}")
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { serverSocket?.close() } catch (_: IOException) {}
        pool.shutdownNow()
        // stop() may run on one of our own handler threads (an identity change restarts the server
        // from inside a request): shutdownNow interrupted that thread too; clear the flag so the
        // handler can still finish its response.
        if (Thread.currentThread().name == THREAD_NAME) Thread.interrupted()
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 120_000
            val input = BufferedInputStream(s.getInputStream(), 64 * 1024)
            val output = s.getOutputStream()
            try {
                val request = parse(input, s) ?: return
                if (request.header("expect")?.equals("100-continue", true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); output.flush()
                }
                val response = try {
                    handler.handle(request)
                } catch (e: Exception) {
                    log("handler error for ${request.method} ${request.path}: $e")
                    HttpResponse(500, e.toString().toByteArray(), "text/plain")
                }
                // drain whatever the handler did not read so the camera's send completes cleanly
                try { request.body.skipAll() } catch (_: IOException) {}
                write(output, response, headOnly = request.method == "HEAD")
            } catch (e: SocketException) {
                log("connection dropped: ${e.message}")
            } catch (e: IOException) {
                log("io error: $e")
            }
        }
    }

    private fun parse(input: InputStream, socket: Socket): HttpRequest? {
        val remote = socket.inetAddress.hostAddress ?: "?"
        // A TLS ClientHello starts with 0x16 0x03: someone used https:// on this plain-HTTP port.
        input.mark(2); val b0 = input.read(); val b1 = input.read(); input.reset()
        if (b0 == 0x16 && b1 == 0x03) {
            log("TLS handshake from $remote: this port speaks plain HTTP, use http:// instead of https://")
            return null
        }
        val requestLine = readLine(input) ?: run { log("Connection from $remote closed without a request"); return null }
        val parts = requestLine.trim().split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        if (!method.all { it in 'A'..'Z' || it == '-' }) {
            log("Not an HTTP request from $remote (${requestLine.length} bytes), ignored")
            return null
        }
        val target = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val qIndex = target.indexOf('?')
        val path = if (qIndex >= 0) target.substring(0, qIndex) else target
        val query = if (qIndex >= 0) parseQuery(target.substring(qIndex + 1)) else emptyMap()
        val chunked = headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
        val length = if (chunked) -1L else headers["content-length"]?.toLongOrNull() ?: 0L
        val body: InputStream = if (chunked) ChunkedInputStream(input) else BoundedInputStream(input, length)
        return HttpRequest(
            method, path, query, headers, body, length,
            socket.inetAddress.hostAddress ?: "?", socket.localAddress.hostAddress ?: "?",
        )
    }

    private fun parseQuery(q: String): Map<String, String> {
        // The camera sends ?didx=0_id=<objectId> — a '=' inside the value. Only split on the first '='.
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val i = pair.indexOf('=')
            val k = if (i >= 0) pair.substring(0, i) else pair
            val v = if (i >= 0) pair.substring(i + 1) else ""
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 16 * 1024) throw IOException("header line too long")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun write(out: OutputStream, r: HttpResponse, headOnly: Boolean = false) {
        val reason = reasonPhrase(r.status)
        val head = StringBuilder()
            .append("HTTP/1.1 ").append(r.status).append(' ').append(reason).append("\r\n")
            .append("Content-Type: ").append(r.contentType).append("\r\n")
            .append("Content-Length: ").append(r.body.size).append("\r\n")
            .append("Server: Linux/2.6 UPnP/1.0 cambackup/0.1\r\n")
            .append("EXT:\r\n")
            .append("Connection: close\r\n")
        for ((k, v) in r.extraHeaders) head.append(k).append(": ").append(v).append("\r\n")
        head.append("\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        if (!headOnly) out.write(r.body)
        out.flush()
    }

    companion object {
        private const val THREAD_NAME = "cambackup-http"

        fun reasonPhrase(status: Int): String = when (status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 302 -> "Found"; 304 -> "Not Modified"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"
            500 -> "Internal Server Error"; 503 -> "Service Unavailable"; else -> "Status"
        }
    }
}

/** Decodes a chunked transfer-encoded body (used by the Android app when the size is unknown). */
class ChunkedInputStream(private val inner: InputStream) : InputStream() {
    private var remainingInChunk = 0L
    private var eof = false

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val b = inner.read()
            if (b < 0) throw IOException("unexpected EOF in chunked body")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun nextChunk(): Boolean {
        if (eof) return false
        if (remainingInChunk > 0) return true
        var line = readLine()
        if (line.isEmpty()) line = readLine() // CRLF terminating the previous chunk
        val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size '$line'")
        if (size == 0L) {
            while (readLine().isNotEmpty()) { /* trailers */ }
            eof = true
            return false
        }
        remainingInChunk = size
        return true
    }

    override fun read(): Int {
        if (!nextChunk()) return -1
        val b = inner.read()
        if (b < 0) throw IOException("unexpected EOF in chunked body")
        remainingInChunk--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!nextChunk()) return -1
        val n = inner.read(b, off, minOf(len.toLong(), remainingInChunk).toInt())
        if (n < 0) throw IOException("unexpected EOF in chunked body")
        remainingInChunk -= n
        return n
    }
}

/** Reads at most [limit] bytes from [inner]; EOF afterwards. */
class BoundedInputStream(private val inner: InputStream, private val limit: Long) : InputStream() {
    private var consumed = 0L
    val remaining: Long get() = limit - consumed

    override fun read(): Int {
        if (consumed >= limit) return -1
        val b = inner.read()
        if (b >= 0) consumed++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (consumed >= limit) return -1
        val n = inner.read(b, off, minOf(len.toLong(), limit - consumed).toInt())
        if (n > 0) consumed += n
        return n
    }
}
