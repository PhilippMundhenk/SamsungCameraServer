package org.mundhenk.nxbackup.server

import org.mundhenk.nxbackup.core.HttpHandler
import org.mundhenk.nxbackup.core.HttpRequest
import org.mundhenk.nxbackup.core.HttpResponse
import org.mundhenk.nxbackup.core.IncomingFile
import org.mundhenk.nxbackup.core.PairingFile
import org.mundhenk.nxbackup.core.ServerIdentity
import org.mundhenk.nxbackup.core.ServerStats
import org.mundhenk.nxbackup.core.StoreResult
import org.mundhenk.nxbackup.core.UpnpXml
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/**
 * Everything on the HTTP port that is not the camera protocol:
 *
 *   HEAD/PUT /api/v1/files/{date}/{name}   backend contract used by the Android app
 *   POST     /api/v1/events                {"type":"start"|"done","camera":"ip","sysOff":bool}
 *   GET      /api/v1/status                JSON for dashboards
 *   GET      /api/v1/log                   text
 *   GET      /                             status page
 *   GET      /pairing.zip | /SAMSUNGAutoBackupDESC.ini   pairing file for the SD card
 *
 * `/api/v1/files` and `/api/v1/events` require `Authorization: Bearer <api.token>` when a
 * token is configured. The UPnP paths stay open (the camera cannot authenticate).
 */
class Api(
    private val config: Config,
    private val identity: ServerIdentity,
    private val sink: ServerSink,
    private val ledger: Ledger,
    private val tools: Tools,
    private val stats: () -> ServerStats?,
    private val log: (String) -> Unit,
) : HttpHandler {
    private val startedAt = Instant.now()

    override fun handle(req: HttpRequest): HttpResponse {
        val path = req.path
        return when {
            path.startsWith("/api/v1/files/") -> authed(req) { files(req, path.removePrefix("/api/v1/files/")) }
            path == "/api/v1/events" && req.method == "POST" -> authed(req) { events(req) }
            path == "/api/v1/status" && req.method == "GET" -> json(statusJson())
            path == "/api/v1/log" && req.method == "GET" -> text(ServerLog.snapshot().joinToString("\n"))
            path == "/health" -> text("ok")
            config.uiEnabled && path == "/" && req.method == "GET" -> html(StatusPage.render(this.statusModel()))
            config.uiEnabled && path == "/pairing.zip" -> HttpResponse(200, PairingFile.zip(identity), "application/zip",
                mapOf("Content-Disposition" to "attachment; filename=\"SAMSUNGAutoBackupDESC.zip\""))
            config.uiEnabled && path.equals("/SAMSUNGAutoBackupDESC.ini", true) -> HttpResponse(200, PairingFile.render(identity).toByteArray(Charsets.US_ASCII), "text/plain",
                mapOf("Content-Disposition" to "attachment; filename=\"SAMSUNGAutoBackupDESC.ini\""))
            else -> { log("Unhandled ${req.method} $path from ${req.remoteAddress}"); HttpResponse.notFound() }
        }
    }

    private fun authed(req: HttpRequest, body: () -> HttpResponse): HttpResponse {
        val token = config.apiToken ?: return body()
        val presented = req.header("authorization")?.removePrefix("Bearer ")?.trim() ?: req.header("x-api-token")?.trim()
        if (presented == null || !constantTimeEquals(presented, token)) {
            log("Rejected ${req.method} ${req.path} from ${req.remoteAddress}: bad or missing token")
            return HttpResponse(401, "unauthorized".toByteArray(), "text/plain", mapOf("WWW-Authenticate" to "Bearer"))
        }
        return body()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    private fun files(req: HttpRequest, rest: String): HttpResponse {
        val parts = rest.split('/').map { URLDecoder.decode(it, "UTF-8") }
        if (parts.size != 2 || parts.any { it.isBlank() || it == "." || it == ".." }) return HttpResponse(400, "expected /api/v1/files/{date}/{name}".toByteArray(), "text/plain")
        val (date, name) = parts
        val camera = req.header("x-nx-camera") ?: req.remoteAddress
        val file = IncomingFile(
            name = name, date = date, size = req.contentLength,
            mimeType = req.header("content-type") ?: "application/octet-stream",
            upnpClass = req.header("x-nx-class") ?: "object.item", cameraAddress = camera,
        )
        return when (req.method) {
            "HEAD" -> if (sink.exists(file.copy(size = -1))) HttpResponse.empty(200) else HttpResponse.notFound()
            "PUT", "POST" -> {
                val result = sink.store(file, req.body)
                stats()?.let { s ->
                    s.lastCamera = camera; s.lastFile = name
                    if (result == StoreResult.STORED) { s.filesStored.incrementAndGet(); s.bytesStored.addAndGet(maxOf(file.size, 0)) } else s.filesSkipped.incrementAndGet()
                }
                when (result) {
                    StoreResult.STORED -> { log("Stored $name via phone ${req.remoteAddress} (camera $camera)"); HttpResponse.empty(201) }
                    StoreResult.ALREADY_EXISTS -> { log("Discarded duplicate $name via phone ${req.remoteAddress}"); HttpResponse.empty(200) }
                }
            }
            else -> HttpResponse(405, "method not allowed".toByteArray(), "text/plain")
        }
    }

    private fun events(req: HttpRequest): HttpResponse {
        val body = req.readBodyText()
        val type = Json.field(body, "type") ?: return HttpResponse(400, "missing type".toByteArray(), "text/plain")
        val camera = Json.field(body, "camera") ?: req.remoteAddress
        val sysOff = Json.field(body, "sysOff") == "true"
        when (type) {
            "start" -> { stats()?.let { it.sessions.incrementAndGet(); it.lastCamera = camera }; log("Backup session started (via phone ${req.remoteAddress}, camera $camera)"); sink.onBackupStart(camera) }
            "done" -> { log("Backup session finished (via phone ${req.remoteAddress}, camera $camera)"); sink.onBackupDone(camera, sysOff) }
            else -> return HttpResponse(400, "unknown type".toByteArray(), "text/plain")
        }
        return HttpResponse.empty(204)
    }

    // ------------------------------------------------------------------ status

    class StatusModel(
        val identity: ServerIdentity, val port: Int, val uptime: Duration, val stats: ServerStats?,
        val ledgerSize: Int, val recent: List<Ledger.Entry>, val sessions: Map<String, Session>,
        val config: List<Pair<String, String>>, val tools: Map<String, Boolean>, val log: List<String>, val dataDir: String,
    )

    fun statusModel() = StatusModel(
        identity, config.httpPort, Duration.between(startedAt, Instant.now()), stats(),
        ledger.size(), ledger.recent(30), sink.activeSessions(), config.summary(), tools.status(), ServerLog.snapshot().takeLast(60), config.dataDir.absolutePath,
    )

    private fun statusJson(): String {
        val m = statusModel(); val s = m.stats
        return buildString {
            append("{\"name\":").append(Json.str(m.identity.friendlyName))
            append(",\"udn\":").append(Json.str(m.identity.udn))
            append(",\"port\":").append(m.port)
            append(",\"uptimeSec\":").append(m.uptime.seconds)
            append(",\"sessions\":").append(s?.sessions?.get() ?: 0)
            append(",\"filesStored\":").append(s?.filesStored?.get() ?: 0)
            append(",\"filesSkipped\":").append(s?.filesSkipped?.get() ?: 0)
            append(",\"bytesStored\":").append(s?.bytesStored?.get() ?: 0)
            append(",\"lastCamera\":").append(Json.str(s?.lastCamera))
            append(",\"lastFile\":").append(Json.str(s?.lastFile))
            append(",\"totalFiles\":").append(m.ledgerSize)
            append(",\"activeSessions\":").append(m.sessions.size)
            append(",\"recent\":[")
            m.recent.forEachIndexed { i, e ->
                if (i > 0) append(',')
                append("{\"date\":").append(Json.str(e.date)).append(",\"name\":").append(Json.str(e.name))
                append(",\"size\":").append(e.size).append(",\"path\":").append(Json.str(e.path)).append(",\"receivedAt\":").append(e.receivedAt).append('}')
            }
            append("]}")
        }
    }

    private fun json(s: String) = HttpResponse(200, s.toByteArray(), "application/json")
    private fun text(s: String) = HttpResponse(200, s.toByteArray(), "text/plain; charset=utf-8")
    private fun html(s: String) = HttpResponse(200, s.toByteArray(), "text/html; charset=utf-8")
}

/** Plain, dependency-free status page. */
object StatusPage {
    private fun esc(s: String) = UpnpXml.escape(s)

    fun render(m: Api.StatusModel): String {
        val s = m.stats
        fun mb(b: Long) = String.format("%.1f MB", b / 1e6)
        val sb = StringBuilder()
        sb.append("""<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="refresh" content="10"><title>nxbackup</title>
<style>
:root{--bg:#fff;--fg:#1a1a1a;--muted:#666;--line:#e3e3e3;--accent:#2a6fdb;--ok:#2e7d32;--bad:#c62828;--card:#f7f7f7}
@media(prefers-color-scheme:dark){:root{--bg:#121212;--fg:#eee;--muted:#aaa;--line:#333;--accent:#8ab4f8;--ok:#81c995;--bad:#f28b82;--card:#1e1e1e}}
body{font:15px/1.5 system-ui,sans-serif;margin:0;padding:16px;background:var(--bg);color:var(--fg);max-width:1000px;margin:auto}
h1{font-size:1.4em;margin:.2em 0}h2{font-size:1.05em;margin:1.4em 0 .4em;color:var(--muted);text-transform:uppercase;letter-spacing:.04em}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px}
.tile{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px 12px}
.tile b{display:block;font-size:1.4em}.tile span{color:var(--muted);font-size:.85em}
table{border-collapse:collapse;width:100%}td,th{text-align:left;padding:4px 8px;border-bottom:1px solid var(--line);font-size:.9em}
code,pre{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:.85em}pre{background:var(--card);border:1px solid var(--line);border-radius:8px;padding:10px;overflow:auto;max-height:420px;white-space:pre-wrap}
a{color:var(--accent)}.ok{color:var(--ok)}.bad{color:var(--bad)}.btn{display:inline-block;padding:8px 14px;border:1px solid var(--accent);border-radius:8px;text-decoration:none;margin:4px 8px 4px 0}
</style></head><body>""")
        sb.append("<h1>nxbackup &mdash; ${esc(m.identity.friendlyName)}</h1>")
        sb.append("<div>UDN <code>uuid:${esc(m.identity.udn)}</code> &middot; port ${m.port} &middot; up ${m.uptime.toHours()}h ${m.uptime.toMinutesPart()}m &middot; data <code>${esc(m.dataDir)}</code></div>")
        sb.append("<p><a class=\"btn\" href=\"/pairing.zip\">Download pairing zip for SD card</a> <a class=\"btn\" href=\"/SAMSUNGAutoBackupDESC.ini\">Pairing .ini only</a> <a class=\"btn\" href=\"/api/v1/status\">JSON</a></p>")
        sb.append("<div style=\"color:var(--muted);font-size:.9em\">Unzip into the SD card root (creates <code>dlna_web_root/SAMSUNGAutoBackupDESC.ini</code>). Put the same UDN in the Android app so both targets share one pairing file.</div>")
        sb.append("<h2>Totals</h2><div class=\"grid\">")
        sb.append("<div class=\"tile\"><b>${s?.sessions?.get() ?: 0}</b><span>sessions since start</span></div>")
        sb.append("<div class=\"tile\"><b>${s?.filesStored?.get() ?: 0}</b><span>files stored since start</span></div>")
        sb.append("<div class=\"tile\"><b>${s?.filesSkipped?.get() ?: 0}</b><span>duplicates skipped</span></div>")
        sb.append("<div class=\"tile\"><b>${mb(s?.bytesStored?.get() ?: 0)}</b><span>received since start</span></div>")
        sb.append("<div class=\"tile\"><b>${m.ledgerSize}</b><span>files in ledger (all time)</span></div>")
        sb.append("<div class=\"tile\"><b>${m.sessions.size}</b><span>sessions in progress</span></div>")
        sb.append("</div>")
        if (s?.lastFile != null) sb.append("<p>Last: <code>${esc(s.lastFile ?: "")}</code> from ${esc(s.lastCamera ?: "?")}</p>")
        sb.append("<h2>Recent files</h2>")
        if (m.recent.isEmpty()) sb.append("<p style=\"color:var(--muted)\">Nothing received yet. Camera: Wi-Fi &rarr; Auto Backup.</p>")
        else {
            sb.append("<table><tr><th>Camera date</th><th>Name</th><th>Size</th><th>Now at</th></tr>")
            for (e in m.recent) sb.append("<tr><td>${esc(e.date)}</td><td>${esc(e.name)}</td><td>${mb(e.size)}</td><td><code>${esc(e.path)}</code></td></tr>")
            sb.append("</table>")
        }
        sb.append("<h2>Pipeline</h2><table>")
        for ((k, v) in m.config) sb.append("<tr><td><code>${esc(k)}</code></td><td>${esc(v)}</td></tr>")
        sb.append("</table>")
        if (m.tools.isNotEmpty()) {
            sb.append("<p>Tools: ")
            sb.append(m.tools.entries.joinToString(" &middot; ") { (t, ok) -> "<span class=\"${if (ok) "ok" else "bad"}\">${esc(t)} ${if (ok) "&#10003;" else "&#10007;"}</span>" })
            sb.append("</p>")
        }
        sb.append("<h2>Log</h2><pre>")
        for (l in m.log) sb.append(esc(l)).append('\n')
        sb.append("</pre></body></html>")
        return sb.toString()
    }
}
