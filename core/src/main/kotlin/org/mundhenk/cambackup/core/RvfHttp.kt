package org.mundhenk.cambackup.core

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/** One answer of the camera: status line, lower-cased headers, and the body still to be read. */
class RvfHttpResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: InputStream,
    private val socket: Socket,
) : AutoCloseable {
    fun header(name: String): String? = headers[name.lowercase()]
    fun text(): String = body.use { it.readBytes().toString(Charsets.UTF_8) }
    override fun close() { runCatching { socket.close() } }
}

/**
 * Minimal HTTP/1.0 client for the camera's Remote Viewfinder server. The cameras' HTTP stack is
 * from 2012: it hangs on a wrong Content-Length, answers the endless live stream with 206 without
 * a Range request, and wants exactly one request per connection. A raw socket keeps every byte
 * under our control. [socketFactory] lets Android route the connection over the camera's Wi-Fi
 * (which has no internet) instead of mobile data.
 */
class RvfHttp(
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    private val connectTimeoutMs: Int = 10_000,
) {
    fun request(
        host: String,
        port: Int,
        method: String,
        path: String,
        headers: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        readTimeoutMs: Int = 25_000,
    ): RvfHttpResponse {
        val socket = socketFactory.createSocket()
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val head = StringBuilder()
            head.append("$method $path HTTP/1.0\r\n")
            head.append("Host: $host:$port\r\n")
            for ((k, v) in headers) head.append("$k: $v\r\n")
            if (body != null) head.append("Content-Length: ${body.size}\r\n")
            head.append("Connection: close\r\n\r\n")
            val out = socket.getOutputStream()
            // one write: some cameras drop a request that arrives in several TCP segments
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1) + (body ?: ByteArray(0)))
            out.flush()
            val input = BufferedInputStream(socket.getInputStream())
            val statusLine = readLine(input) ?: throw IOException("camera closed the connection without an answer")
            val status = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("not an HTTP answer: $statusLine")
            val hdrs = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) hdrs[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = hdrs["content-length"]?.toLongOrNull()
            val bodyStream: InputStream = if (len != null && len >= 0) BoundedInputStream(input, len) else input
            return RvfHttpResponse(status, hdrs, bodyStream, socket)
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (buf.size() == 0) null else buf.toString(Charsets.ISO_8859_1.name())
            if (c == '\n'.code) return buf.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            buf.write(c)
            if (buf.size() > 16_384) throw IOException("header line too long")
        }
    }
}
