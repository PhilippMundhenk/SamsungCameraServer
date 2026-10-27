package org.mundhenk.nxbackup.core

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Forwards every file to the home server over plain HTTP (the phone's "server" modes).
 *
 * API contract (implemented by the server module's Api):
 *   HEAD {base}/api/v1/files/{date}/{name}            -> 200 exists, 404 not
 *   PUT  {base}/api/v1/files/{date}/{name}  body=bytes -> 201 stored, 200 already existed
 *        headers: Content-Type, Content-Length, X-NX-Camera (ip), X-NX-Class (upnp class)
 *   POST {base}/api/v1/events  JSON {"type":"start"|"done","camera":"ip","sysOff":bool}
 *   Authorization: Bearer <token> on all of them when a token is configured.
 */
class BackendSink(
    baseUrl: String,
    private val log: (String) -> Unit = {},
    private val token: String = "",
    private val connectTimeoutMs: Int = 5000,
) : BackupSink {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    private fun HttpURLConnection.auth() { if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token") }

    private fun fileUrl(file: IncomingFile): URL {
        val d = URLEncoder.encode(FileSystemSink.safeSegment(file.date), "UTF-8")
        val n = URLEncoder.encode(file.name, "UTF-8")
        return URL("$baseUrl/api/v1/files/$d/$n")
    }

    override fun onBackupStart(cameraAddress: String) = event("start", cameraAddress, false)
    override fun onBackupDone(cameraAddress: String, sysOff: Boolean) = event("done", cameraAddress, sysOff)

    override fun exists(file: IncomingFile): Boolean = try {
        val c = fileUrl(file).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = connectTimeoutMs; c.readTimeout = 5000; c.auth()
        val code = c.responseCode; c.disconnect()
        if (code == 401) log("backend rejected the token (401) - check the backend token in the app and api.token on the server")
        code == 200
    } catch (e: IOException) { log("backend HEAD failed: ${e.message}"); false }

    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        val c = fileUrl(file).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.auth()
        c.doOutput = true
        c.connectTimeout = connectTimeoutMs
        c.readTimeout = 120_000
        if (file.size >= 0) c.setFixedLengthStreamingMode(file.size) else c.setChunkedStreamingMode(256 * 1024)
        c.setRequestProperty("Content-Type", file.mimeType)
        c.setRequestProperty("X-NX-Camera", file.cameraAddress)
        c.setRequestProperty("X-NX-Class", file.upnpClass)
        c.outputStream.use { out -> input.copyTo(out, 256 * 1024) }
        val code = c.responseCode
        (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() }
        c.disconnect()
        return when (code) {
            201 -> StoreResult.STORED
            200 -> StoreResult.ALREADY_EXISTS
            401 -> throw IOException("backend PUT ${file.name}: 401 - wrong backend token")
            else -> throw IOException("backend PUT ${file.name}: HTTP $code")
        }
    }

    /** True when the server answers /health; used before a deferred upload run. */
    fun reachable(): Boolean = try {
        val c = URL("$baseUrl/health").openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = connectTimeoutMs; c.readTimeout = 5000
        val code = c.responseCode; c.disconnect()
        code in 200..299
    } catch (e: IOException) { false }

    private fun event(type: String, camera: String, sysOff: Boolean) {
        try {
            val c = URL("$baseUrl/api/v1/events").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = connectTimeoutMs; c.readTimeout = 5000; c.auth()
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"type":"$type","camera":"$camera","sysOff":$sysOff}""".toByteArray()) }
            val code = c.responseCode; c.disconnect()
            if (code >= 400) log("backend event '$type' -> HTTP $code")
        } catch (e: IOException) { log("backend event '$type' failed: ${e.message}") }
    }
}
