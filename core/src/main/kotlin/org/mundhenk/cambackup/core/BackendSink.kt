package org.mundhenk.cambackup.core

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
    /** Nickname of this device, sent as X-NX-Device so the server can list who talks to it. */
    private val deviceName: String = "",
) : BackupSink {
    private val baseUrl = baseUrl.trim().trimEnd('/')

    /** Why the last identity/health call failed, for the UI; null after a success. */
    @Volatile var lastError: String? = null
        private set

    private fun reason(e: IOException): String = when {
        e is javax.net.ssl.SSLException && baseUrl.startsWith("https://", ignoreCase = true) ->
            "the server does not speak TLS; the URL says https:// but the home server serves plain http://"
        e.message?.contains("Cleartext", ignoreCase = true) == true -> "plain http:// is blocked on this device (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun HttpURLConnection.auth() {
        if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
        if (deviceName.isNotBlank()) setRequestProperty("X-NX-Device", deviceName.replace(Regex("[\\r\\n]"), " ").take(80))
    }

    /** The server's identity from GET /api/v1/identity, so a phone can advertise the same UDN. Null when unreachable. */
    fun fetchIdentity(): ServerIdentity? = try {
        val c = URL("$baseUrl/api/v1/identity").openConnection() as HttpURLConnection
        c.requestMethod = "GET"; c.connectTimeout = connectTimeoutMs; c.readTimeout = 5000; c.auth()
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code != 200) { lastError = "HTTP $code"; log("backend identity -> HTTP $code"); null }
        else {
            val udn = jsonField(body, "udn"); val name = jsonField(body, "name")
            if (udn == null || name == null) { lastError = "unexpected answer"; log("backend identity: unexpected answer $body"); null }
            else { lastError = null; ServerIdentity(udn.removePrefix("uuid:").lowercase(), name, jsonField(body, "mac") ?: ServerIdentity.DEFAULT_MAC) }
        }
    } catch (e: IOException) { lastError = reason(e); log("backend identity failed: $lastError"); null }
      catch (e: IllegalArgumentException) { lastError = "invalid identity: ${e.message}"; log("backend identity invalid: ${e.message}"); null }

    /**
     * Asks the server to adopt [identity] (POST /api/v1/identity, needs the token). The server
     * rewrites its pairing file and restarts its UPnP side with the new UDN. Returns the identity
     * the server reports afterwards, null on failure (logged).
     */
    fun pushIdentity(identity: ServerIdentity): ServerIdentity? = try {
        val c = URL("$baseUrl/api/v1/identity").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = connectTimeoutMs; c.readTimeout = 15_000; c.auth()
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"udn":"${identity.udn}","name":${jsonString(identity.friendlyName)},"mac":"${identity.macAddr}"}""".toByteArray()) }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        if (code != 200) { log("backend identity push -> HTTP $code ${body.take(200)}"); null }
        else { val udn = jsonField(body, "udn"); val name = jsonField(body, "name"); if (udn != null && name != null) ServerIdentity(udn.removePrefix("uuid:").lowercase(), name, jsonField(body, "mac") ?: identity.macAddr) else null }
    } catch (e: IOException) { lastError = reason(e); log("backend identity push failed: $lastError"); null }

    private fun jsonString(s: String): String = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun jsonField(json: String, name: String): String? =
        Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")

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
        lastError = if (code in 200..299) null else "HTTP $code"
        code in 200..299
    } catch (e: IOException) { lastError = reason(e); false }

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
