package org.mundhenk.cambackup.server

import org.mundhenk.cambackup.core.HttpHandler
import org.mundhenk.cambackup.core.HttpRequest
import org.mundhenk.cambackup.core.HttpResponse
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.PairingFile
import org.mundhenk.cambackup.core.ServerIdentity
import org.mundhenk.cambackup.core.ServerStats
import org.mundhenk.cambackup.core.StoreResult
import org.mundhenk.cambackup.core.UpnpXml
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
    @Volatile var identity: ServerIdentity,
    private val sink: ServerSink,
    private val ledger: Ledger,
    private val tools: Tools,
    private val stats: () -> ServerStats?,
    private val log: (String) -> Unit,
    private val devices: Devices? = null,
) : HttpHandler {
    private val startedAt = Instant.now()

    /** Set by CamBackupServer: adopts a new identity (config, pairing file, UPnP side) and returns the effective one. */
    @Volatile var onIdentityChange: ((ServerIdentity) -> ServerIdentity)? = null

    override fun handle(req: HttpRequest): HttpResponse {
        val path = req.path
        return when {
            path.startsWith("/api/v1/files/") -> authed(req) { files(req, path.removePrefix("/api/v1/files/")) }
            path == "/api/v1/events" && req.method == "POST" -> authed(req) { events(req) }
            path == "/api/v1/status" && req.method == "GET" -> json(statusJson())
            path == "/api/v1/identity" && req.method == "POST" -> authed(req) { adoptIdentity(req) }
            path == "/api/v1/identity" && req.method == "GET" -> json(identityJson(identity))
            path == "/api/v1/log" && req.method == "GET" -> text(ServerLog.snapshot().joinToString("\n"))
            path == "/health" -> text("ok")
            config.uiEnabled && path == "/" && req.method == "GET" -> html(StatusPage.render(this.statusModel(), req.query["scheme"]))
            config.uiEnabled && config.uiSettings && path == "/settings" && req.method == "GET" -> settingsAuthed(req) { html(SettingsPage.render(config, notice = req.query["saved"]?.let { "Saved. Settings marked restart take effect after a restart, everything else from the next file on." }, scheme = req.query["scheme"])) }
            config.uiEnabled && config.uiSettings && path == "/settings" && req.method == "POST" -> settingsAuthed(req) { saveSettings(req) }
            config.uiEnabled && config.uiSettings && path == "/settings/adopt-env" && req.method == "POST" -> settingsAuthed(req) { adoptEnvironment() }
            config.uiEnabled && path == "/devices/rename" && req.method == "POST" -> settingsAuthed(req) { renameDevice(req) }
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

    /**
     * The settings page runs in a browser, so it uses HTTP Basic auth with the api.token as the
     * password (any user name). Without a token the page is open, like the status page.
     */
    private fun settingsAuthed(req: HttpRequest, body: () -> HttpResponse): HttpResponse {
        val token = config.apiToken ?: return body()
        val header = req.header("authorization") ?: ""
        val presented = if (header.startsWith("Basic ", true)) {
            try { String(java.util.Base64.getDecoder().decode(header.substring(6).trim()), Charsets.UTF_8).substringAfter(':') } catch (e: IllegalArgumentException) { "" }
        } else header.removePrefix("Bearer ").trim()
        if (presented.isEmpty() || !constantTimeEquals(presented, token)) {
            log("Rejected ${req.method} ${req.path} from ${req.remoteAddress}: settings need the api.token")
            return HttpResponse(401, "The settings page needs the api.token as password (any user name).".toByteArray(), "text/plain",
                mapOf("WWW-Authenticate" to "Basic realm=\"Samsung Camera Backup settings\", charset=\"UTF-8\""))
        }
        return body()
    }

    private fun saveSettings(req: HttpRequest): HttpResponse {
        val form = SettingsCatalog.parseForm(req.readBodyText())
        val (updates, errors) = SettingsCatalog.updatesFrom(form)
        if (errors.isNotEmpty()) return html(SettingsPage.render(config, errors = errors))
        // keys the environment overrides are not editable in the browser; keep the file's value for them
        val editable = updates.filterKeys { config.envValue(it) == null }
        config.save(editable)
        log("Settings saved from ${req.remoteAddress}: ${editable.keys.joinToString(", ")}")
        return HttpResponse(303, ByteArray(0), "text/plain", mapOf("Location" to "/settings?saved=1"))
    }

    private fun adoptEnvironment(): HttpResponse {
        val fromEnv = SettingsCatalog.all.mapNotNull { def -> config.envValue(def.key)?.let { def.key to it } }.toMap()
        config.save(fromEnv)
        log("Settings: copied ${fromEnv.size} environment value(s) into ${config.file.name}: ${fromEnv.keys.joinToString(", ")}")
        return HttpResponse(303, ByteArray(0), "text/plain", mapOf("Location" to "/settings?saved=1"))
    }

    /** Status page form: gives a camera (by address) or phone a name. Protected like the settings. */
    private fun renameDevice(req: HttpRequest): HttpResponse {
        val form = SettingsCatalog.parseForm(req.readBodyText())
        val kind = Devices.Kind.entries.firstOrNull { it.name == form["kind"] } ?: return HttpResponse(400, "unknown device kind".toByteArray(), "text/plain")
        val id = form["id"] ?: return HttpResponse(400, "missing device id".toByteArray(), "text/plain")
        val renamed = devices?.rename(kind, id, form["nickname"] ?: "") ?: return HttpResponse(404, "unknown device".toByteArray(), "text/plain")
        log("${if (kind == Devices.Kind.CAMERA) "Camera" else "Phone"} $id ${if (renamed.nickname.isEmpty()) "name removed" else "named \"${renamed.nickname}\""} from ${req.remoteAddress}")
        return HttpResponse(303, ByteArray(0), "text/plain", mapOf("Location" to "/#devices"))
    }

    private fun files(req: HttpRequest, rest: String): HttpResponse {
        val parts = rest.split('/').map { URLDecoder.decode(it, "UTF-8") }
        if (parts.size != 2 || parts.any { it.isBlank() || it == "." || it == ".." }) return HttpResponse(400, "expected /api/v1/files/{date}/{name}".toByteArray(), "text/plain")
        val (date, name) = parts
        val camera = req.header("x-nx-camera") ?: req.remoteAddress
        if (req.method != "HEAD") req.header("x-nx-device")?.let { devices?.seen(Devices.Kind.PHONE, it, req.remoteAddress, req.header("user-agent"), files = 1) }
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

    private fun identityJson(id: ServerIdentity) = "{\"udn\":" + Json.str(id.udn) + ",\"name\":" + Json.str(id.friendlyName) + ",\"mac\":" + Json.str(id.macAddr) + "}"

    /** The phone hands its identity over so one pairing file works for both; the server switches its UPnP side over to it live. */
    private fun adoptIdentity(req: HttpRequest): HttpResponse {
        val body = req.readBodyText()
        val udn = Json.field(body, "udn")?.removePrefix("uuid:")?.lowercase() ?: return HttpResponse(400, "missing udn".toByteArray(), "text/plain")
        val name = Json.field(body, "name")?.takeIf { it.isNotBlank() } ?: identity.friendlyName
        val mac = Json.field(body, "mac") ?: identity.macAddr
        val wanted = try { ServerIdentity(udn, name, mac) } catch (e: IllegalArgumentException) { return HttpResponse(400, (e.message ?: "invalid identity").toByteArray(), "text/plain") }
        val device = req.header("x-nx-device") ?: req.remoteAddress
        if (wanted == identity) { log("Identity from $device already in use"); return json(identityJson(identity)) }
        val applied = onIdentityChange?.invoke(wanted) ?: return HttpResponse(503, "identity changes not supported".toByteArray(), "text/plain")
        log("Identity adopted from $device: ${applied.friendlyName} uuid:${applied.udn} (was uuid:${identity.udn})")
        identity = applied
        return json(identityJson(applied))
    }

    private fun events(req: HttpRequest): HttpResponse {
        val body = req.readBodyText()
        val type = Json.field(body, "type") ?: return HttpResponse(400, "missing type".toByteArray(), "text/plain")
        val camera = Json.field(body, "camera") ?: req.remoteAddress
        val sysOff = Json.field(body, "sysOff") == "true"
        req.header("x-nx-device")?.let { devices?.seen(Devices.Kind.PHONE, it, req.remoteAddress, req.header("user-agent"), session = type == "start") }
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
        val devices: List<Devices.Device> = emptyList(),
    ) {
        /** A camera's given name, or its address when it has none. */
        fun deviceName(address: String): String = devices.firstOrNull { it.kind == Devices.Kind.CAMERA && it.id.equals(address, true) }?.displayName ?: address
    }

    fun statusModel() = StatusModel(
        identity, config.httpPort, Duration.between(startedAt, Instant.now()), stats(),
        ledger.size(), ledger.recent(30), sink.activeSessions(), config.summary(), tools.status(), ServerLog.snapshot().takeLast(60), config.dataDir.absolutePath,
        devices?.all() ?: emptyList(),
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
            append(",\"devices\":[")
            m.devices.forEachIndexed { i, d ->
                if (i > 0) append(',')
                append("{\"kind\":").append(Json.str(d.kind.name.lowercase())).append(",\"id\":").append(Json.str(d.id)).append(",\"name\":").append(Json.str(d.displayName))
                append(",\"sessions\":").append(d.sessions).append(",\"files\":").append(d.files).append(",\"lastSeen\":").append(d.lastSeen).append('}')
            }
            append("]")
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

    fun render(m: Api.StatusModel, scheme: String? = null): String {
        val s = m.stats
        fun mb(b: Long) = String.format("%.1f MB", b / 1e6)
        val cfg = m.config.toMap()
        val sb = StringBuilder()
        sb.append(Html.head("Status", refreshSeconds = 10, scheme = scheme))
        sb.append(Html.header("status"))
        sb.append("<main>")
        // the same status card as the app: dot + state, hint, destination line, primary action
        sb.append("<div class=\"card\"><div class=\"state\"><span class=\"dot\"></span>Running &mdash; ${esc(m.identity.friendlyName)}</div>")
        sb.append("<div class=\"hint\">Waiting for the camera: Wi-Fi &rarr; Auto Backup. UDN <code>uuid:${esc(m.identity.udn)}</code> &middot; port ${m.port} &middot; up ${m.uptime.toHours()}h ${m.uptime.toMinutesPart()}m</div>")
        sb.append("<div class=\"dest\">Photos &rarr; <code>${esc(cfg["storage.dir"] ?: "")}</code> ${esc((cfg["storage.subfolder"] ?: "(flat)").let { if (it.startsWith("(")) it else "($it)" })}")
        if (cfg["export.dir"] != null && cfg["export.dir"] != "(off)") sb.append(" &rarr; <code>${esc(cfg["export.dir"]!!)}</code>")
        sb.append(" &middot; data <code>${esc(m.dataDir)}</code> <a href=\"/settings\">Change</a></div>")
        sb.append("<p style=\"margin:12px 0 0\"><a class=\"btn\" href=\"/pairing.zip\">Download pairing zip for SD card</a> <a class=\"btn secondary\" href=\"/SAMSUNGAutoBackupDESC.ini\">Pairing .ini only</a></p>")
        sb.append("<div class=\"help\">Unzip into the SD card root (creates <code>dlna_web_root/SAMSUNGAutoBackupDESC.ini</code>). Put the same UDN in the Android app so both targets share one pairing file.</div></div>")
        sb.append("<h2>Totals</h2><div class=\"grid\">")
        sb.append("<div class=\"tile\"><b>${s?.sessions?.get() ?: 0}</b><span>sessions since start</span></div>")
        sb.append("<div class=\"tile\"><b>${s?.filesStored?.get() ?: 0}</b><span>files stored since start</span></div>")
        sb.append("<div class=\"tile\"><b>${s?.filesSkipped?.get() ?: 0}</b><span>duplicates skipped</span></div>")
        sb.append("<div class=\"tile\"><b>${mb(s?.bytesStored?.get() ?: 0)}</b><span>received since start</span></div>")
        sb.append("<div class=\"tile\"><b>${m.ledgerSize}</b><span>files in ledger (all time)</span></div>")
        sb.append("<div class=\"tile\"><b>${m.sessions.size}</b><span>sessions in progress</span></div>")
        sb.append("</div>")
        if (s?.lastFile != null) sb.append("<p>Last: <code>${esc(s.lastFile ?: "")}</code> from ${esc(m.deviceName(s.lastCamera ?: "?"))}</p>")
        sb.append("<h2>Transferred photos${if (m.recent.isNotEmpty()) " (${m.ledgerSize})" else ""}</h2>")
        if (m.recent.isEmpty()) sb.append("<p class=\"help\">Nothing received yet. Camera: Wi-Fi &rarr; Auto Backup.<br>&#10003; on the phone &middot; &#10003;&#10003; on the home server</p>")
        else {
            sb.append("<table class=\"files\">")
            for (e in m.recent) sb.append("<tr><td class=\"check\" title=\"on the server\">&#10003;&#10003;</td><td><div class=\"name\">${esc(e.name)}</div><div class=\"help\">${esc(e.date)} &middot; ${mb(e.size)} &middot; ${esc(java.time.Instant.ofEpochMilli(e.receivedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' '))}</div><div class=\"help\">On the server &middot; <code>${esc(e.path)}</code></div></td></tr>")
            sb.append("</table>")
        }
        sb.append("<h2 id=\"devices\">Devices</h2>")
        if (m.devices.isEmpty()) sb.append("<p class=\"help\">No camera or phone has talked to this server yet. This server: <code>uuid:${esc(m.identity.udn)}</code>; the app offers \"Use the server's identity\" so phone and server share one pairing file.</p>")
        else {
            sb.append("<table class=\"files\">")
            for (d in m.devices) {
                val kind = if (d.kind == Devices.Kind.CAMERA) "Camera" else "Phone"
                sb.append("<tr><td class=\"check\" title=\"$kind\">${if (d.kind == Devices.Kind.CAMERA) "&#128247;" else "&#128241;"}</td><td><div class=\"name\">${esc(d.displayName)}${if (d.nickname.isNotEmpty()) " <span class=\"help\">${esc(d.id)}</span>" else ""}</div>")
                sb.append("<div class=\"help\">$kind &middot; ${d.sessions} session${if (d.sessions == 1) "" else "s"} &middot; ${d.files} file${if (d.files == 1) "" else "s"} &middot; last seen ${esc(java.time.Instant.ofEpochMilli(d.lastSeen).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0).toString().replace('T', ' '))}</div>")
                sb.append("<div class=\"help\">${esc(d.address)}${if (d.detail.isNotEmpty()) " &middot; " + esc(d.detail) else ""}</div>")
                sb.append("<form method=\"post\" action=\"/devices/rename\" class=\"rename\"><input type=\"hidden\" name=\"kind\" value=\"${d.kind.name}\"><input type=\"hidden\" name=\"id\" value=\"${esc(d.id)}\">")
                sb.append("<input type=\"text\" name=\"nickname\" maxlength=\"60\" value=\"${esc(d.nickname)}\" placeholder=\"${if (d.kind == Devices.Kind.CAMERA) "Name this camera, e.g. NX1000" else "Name this phone"}\" aria-label=\"Name for ${esc(d.id)}\"> <button class=\"btn secondary\" type=\"submit\">${if (d.nickname.isEmpty()) "Name" else "Rename"}</button></form></td></tr>")
            }
            sb.append("</table>")
        }
        if (m.tools.isNotEmpty()) {
            sb.append("<h2>Tools</h2><p>")
            sb.append(m.tools.entries.joinToString(" &middot; ") { (t, ok) -> "<span class=\"${if (ok) "ok" else "bad"}\">${esc(t)} ${if (ok) "&#10003;" else "&#10007;"}</span>" })
            sb.append(" &middot; <a href=\"/settings\">Settings</a></p>")
        }
        sb.append("<h2>Log</h2><pre>")
        for (l in m.log) sb.append(esc(l)).append('\n')
        sb.append("</pre>")
        sb.append(Html.footer())
        return sb.toString()
    }
}
