package org.mundhenk.cambackup.core

import java.io.IOException
import java.net.URI

/** The camera answered with an error (HTTP status [status], body [body]). */
class RvfException(message: String, val status: Int = 0, val body: String = "") : IOException(message)

/** What GetInfomation told us about the camera and its live stream. */
data class RvfInfo(
    val streamUrl: String,
    val lowStreamUrl: String?,
    val maxZoom: Int,
    val zoom: Int,
    val flashModes: List<String>,
    val flash: String?,
    val availShots: Int?,
    val rotation: Int,
)

/** Answer of Shot: where the camera stored the picture (null if it did not say) and how many fit on the card. */
data class RvfShot(val url: String?, val availShots: Int?)

/**
 * Client for the cameras' "Remote Viewfinder" Wi-Fi mode (2012–2014 Samsung NX / WB / ST / DV
 * models). The camera is the access point and the server: UPnP on [port] (7676), live view on a
 * second port from GetInfomation. Reimplemented from published protocol notes, see
 * docs/PROTOCOL.md, "Remote Viewfinder".
 *
 * Rules the cameras impose, which this class keeps:
 * * every request carries `User-Agent: SEC_RVF_<id>` and `Access-Method: manual`; the first one
 *   makes the camera ask "allow this device?" and may block until the user answers;
 * * one command at a time, in order (the NX1000 hangs otherwise): all calls are serialised;
 * * only actions the camera's SCPD declares are sent, with only the arguments it declares;
 * * on the NX1000 the live stream must be opened right after [connect], and AF / Shot only work
 *   while it is being read.
 */
class RvfClient(
    val host: String,
    val port: Int = DEFAULT_PORT,
    /** Opaque id the camera remembers after the first "allow"; keep it stable per installation. */
    private val clientId: String,
    private val http: RvfHttp = RvfHttp(),
    private val log: (String) -> Unit = {},
    private val pairTimeoutMs: Int = 60_000,
    private val timeoutMs: Int = 25_000,
) {
    private val lock = Any()
    private var userAgent = "$UA_PREFIX$clientId"
    private var controlPath = "/smp_4_"

    var friendlyName: String = ""; private set
    var modelName: String = ""; private set
    /** Declared actions with their input argument names, from the SCPD. */
    var actions: Map<String, List<String>> = emptyMap(); private set
    var info: RvfInfo? = null; private set

    fun has(action: String) = actions.containsKey(action)
    val canZoom: Boolean get() = has("ZoomIN") && has("ZoomOUT") && (info?.maxZoom ?: 0) > 0
    val canFocus: Boolean get() = has("MULTIAF") || has("AF")

    /** Pairing (if new), description, SCPD, GetInfomation. Open the live stream right afterwards. */
    fun connect(): RvfInfo = synchronized(lock) {
        val desc = describe()
        friendlyName = tag(desc, "friendlyName") ?: ""
        modelName = tag(desc, "modelName") ?: ""
        val cds = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(desc)
            .map { it.groupValues[1] }.firstOrNull { it.contains("ContentDirectory") }
        controlPath = cds?.let { tag(it, "controlURL") }?.let(::pathOf) ?: "/smp_4_"
        val scpdPath = cds?.let { tag(it, "SCPDURL") }?.let(::pathOf) ?: "/smp_3_"
        log("Camera '$friendlyName' ($modelName), control $controlPath, actions from $scpdPath")
        actions = parseScpd(get(scpdPath, timeoutMs))
        log("Camera actions: ${actions.keys.sorted().joinToString(", ")}")
        val args = if ("GPSINFO" in actions["GetInfomation"].orEmpty()) listOf("GPSINFO" to "UNKNOWN") else emptyList()
        val parsed = parseInfo(callLocked("GetInfomation", args), host)
        info = parsed
        log("Live view at ${parsed.streamUrl}")
        parsed
    }

    /** Half-press: autofocus. Returns the camera's AFSTATUS (null if it sent none). */
    fun focus(): String? = synchronized(lock) {
        val action = if (has("MULTIAF") || !has("AF")) "MULTIAF" else "AF"
        tag(callLocked(action), "AFSTATUS")
    }

    /** Full press. [toPhone]: ask the camera to offer the picture for download (where declared). */
    fun shoot(toPhone: Boolean = true): RvfShot = synchronized(lock) {
        val args = if ("REQUESTIMAGE" in actions["Shot"].orEmpty()) listOf("REQUESTIMAGE" to if (toPhone) "1" else "0") else emptyList()
        val r = callLocked("Shot", args)
        RvfShot(tag(r, "AFSHOTRESULT")?.trim()?.takeIf { it.startsWith("http") }, tag(r, "AVAILSHOTS")?.trim()?.toIntOrNull())
    }

    /** One zoom step; returns the new zoom position when the camera tells it. */
    fun zoom(tele: Boolean): Int? = synchronized(lock) {
        tag(callLocked(if (tele) "ZoomIN" else "ZoomOUT"), "CURRENTZOOM")?.trim()?.toIntOrNull()
    }

    fun setFlash(mode: String): Unit = synchronized(lock) { callLocked("SetFlash", listOf("FLASHMODE" to mode)); Unit }

    /** Keep-alive where the camera has one; false when it does not (reading the stream keeps those awake). */
    fun heartBeat(): Boolean = synchronized(lock) {
        if (!has("HeartBeat")) return false
        val arg = actions["HeartBeat"].orEmpty().firstOrNull() ?: "Duration"
        callLocked("HeartBeat", listOf(arg to "30"))
        true
    }

    /** Opens the endless live stream; the caller reads frames until it closes the response. */
    fun openStream(low: Boolean = false): Pair<RvfHttpResponse, AviFrameReader> {
        val i = info ?: throw IllegalStateException("connect() first")
        val uri = URI(if (low && i.lowStreamUrl != null) i.lowStreamUrl else i.streamUrl)
        val r = http.request(uri.host, if (uri.port > 0) uri.port else 80, "GET", uri.rawPath.ifEmpty { "/" }, baseHeaders(), readTimeoutMs = timeoutMs)
        if (r.status != 200 && r.status != 206) { val body = runCatching { r.text() }.getOrDefault(""); r.close(); throw RvfException("live view refused: HTTP ${r.status}", r.status, body) }
        return r to AviFrameReader(r.body)
    }

    /** Fetches a picture the camera reported (plain GET, no pairing needed). */
    fun download(url: String): ByteArray {
        val uri = URI(url)
        http.request(uri.host, if (uri.port > 0) uri.port else 80, "GET", uri.rawPath.ifEmpty { "/" }, baseHeaders(), readTimeoutMs = timeoutMs).use { r ->
            if (r.status != 200 && r.status != 206) throw RvfException("download of $url failed: HTTP ${r.status}", r.status)
            return r.body.readBytes()
        }
    }

    /** Any declared action, for the rest of the SCPD; refuses undeclared ones (they hang some cameras). */
    fun call(action: String, args: List<Pair<String, String>> = emptyList()): String = synchronized(lock) { callLocked(action, args) }

    private fun callLocked(action: String, args: List<Pair<String, String>> = emptyList()): String {
        if (actions.isNotEmpty() && !has(action)) throw RvfException("the camera does not offer $action")
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${UpnpXml.CDS_SERVICE_TYPE}">${args.joinToString("") { (k, v) -> "<$k>${UpnpXml.escape(v)}</$k>" }}</u:$action></s:Body></s:Envelope>""".toByteArray(Charsets.UTF_8)
        val headers = baseHeaders() + listOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPACTION" to "\"${UpnpXml.CDS_SERVICE_TYPE}#$action\"",
        )
        http.request(host, port, "POST", controlPath, headers, body, timeoutMs).use { r ->
            val text = r.text()
            if (r.status != 200) throw RvfException("$action failed: HTTP ${r.status}${tag(text, "errorDescription")?.let { " ($it)" } ?: ""}", r.status, text)
            log("$action: OK")
            return if (text.contains("&lt;")) unescape(text) else text
        }
    }

    /** GET of the description; this is the request that pairs. 401 = not (yet) allowed. */
    private fun describe(): String {
        val first = runCatching { get("/smp_2_", pairTimeoutMs) }
        val e = first.exceptionOrNull() ?: return first.getOrThrow()
        if (e !is RvfException || e.status != 401) throw e
        // newer models (NX3000, NX mini) want the MobileLink-style prefix and a /mode/control knock first
        log("Camera refused $userAgent, trying the newer pairing")
        knockModeControl()
        userAgent = "$UA_PREFIX_ML$clientId"
        return try { get("/smp_2_", pairTimeoutMs) } catch (e2: RvfException) {
            if (e2.status == 401) throw RvfException("the camera refused this phone: confirm the prompt on the camera's screen, or restart Remote Viewfinder on the camera", 401, e2.body)
            throw e2
        }
    }

    private fun knockModeControl() {
        runCatching {
            http.request(host, MODE_PORT, "HEAD", "/mode/control", listOf(
                "User-Agent" to "SEC_MODE_$clientId", "NTS" to "alive", "HOST-Mac" to clientId,
                "HOST-port" to "$MODE_PORT", "HOST-PNumber" to "none", "Access-Method" to "manual",
            ), ByteArray(0), pairTimeoutMs).use { log("/mode/control: HTTP ${it.status}") }
        }.onFailure { log("/mode/control not offered (${it.message})") }
    }

    private fun get(path: String, readTimeout: Int): String =
        http.request(host, port, "GET", path, baseHeaders(), readTimeoutMs = readTimeout).use { r ->
            val text = r.text()
            if (r.status != 200) throw RvfException("GET $path: HTTP ${r.status}", r.status, text)
            text
        }

    private fun baseHeaders() = listOf("User-Agent" to userAgent, "Access-Method" to "manual")

    companion object {
        const val DEFAULT_PORT = 7676
        const val MODE_PORT = 7788
        const val UA_PREFIX = "SEC_RVF_"
        const val UA_PREFIX_ML = "SEC_RVF_ML_"

        fun tag(xml: String, name: String): String? =
            Regex("<(?:[A-Za-z0-9_]+:)?$name(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)

        private fun tags(xml: String, name: String): List<String> =
            Regex("<$name(?:\\s[^>]*)?>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.groupValues[1].trim() }.toList()

        fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

        /** "http://192.168.102.1:7676/smp_4_" or "smp_4_" -> "/smp_4_". */
        fun pathOf(url: String): String {
            val u = url.trim()
            if (u.startsWith("http://") || u.startsWith("https://")) return URI(u).rawPath.ifEmpty { "/" }
            return if (u.startsWith("/")) u else "/$u"
        }

        /** Action name -> its input argument names. */
        fun parseScpd(xml: String): Map<String, List<String>> =
            Regex("<action>(.*?)</action>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val block = m.groupValues[1]
                val name = Regex("^\\s*<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(block)?.groupValues?.get(1)?.trim()
                    ?: tag(block.substringBefore("<argumentList>"), "name")?.trim() ?: return@mapNotNull null
                val ins = tags(block, "argument").filter { tag(it, "direction")?.trim() == "in" }.mapNotNull { tag(it, "name")?.trim() }
                name to ins
            }.toMap()

        /** GetInfomation answer -> [RvfInfo]; a missing stream URL falls back to the documented default on [host]. */
        fun parseInfo(xml: String, host: String): RvfInfo {
            val high = tag(xml, "QualityHighUrl")?.trim()?.takeIf { it.startsWith("http") }
            val low = tag(xml, "QualityLowUrl")?.trim()?.takeIf { it.startsWith("http") }
            val zoom = tag(xml, "ZoomInfo") ?: ""
            val flash = tag(xml, "Flash") ?: ""
            return RvfInfo(
                streamUrl = high ?: low ?: "http://$host:$STREAM_PORT/livestream.avi",
                lowStreamUrl = low,
                maxZoom = tag(zoom, "MaxZoom")?.trim()?.toIntOrNull() ?: 0,
                zoom = tag(zoom, "DefaultZoom")?.trim()?.toIntOrNull() ?: 0,
                flashModes = tags(flash, "Support"),
                flash = tag(flash, "Defaultflash")?.trim(),
                availShots = tag(xml, "AVAILSHOTS")?.trim()?.toIntOrNull(),
                rotation = tag(xml, "ROTATION")?.trim()?.toIntOrNull() ?: 0,
            )
        }

        const val STREAM_PORT = 7679
    }
}
