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
    /** `<Version>` ("CSC_1.0", "DSC_1.1", "1.0"), null on the 2012 firmware. */
    val version: String? = null,
) {
    /** The number in [version] (1.0 for "CSC_1.0"), 0 when there is none. */
    val versionNumber: Double get() = version?.let { Regex("(\\d+(?:\\.\\d+)?)").find(it)?.groupValues?.get(1)?.toDoubleOrNull() } ?: 0.0
}

/** Answer of Shot: where the camera stored the picture (null if it did not say) and how many fit on the card. */
data class RvfShot(val url: String?, val availShots: Int?)

/** Answer of MULTIAF / AF: [ok] = in focus (or manual focus), [status] as the camera sent it. */
data class RvfFocus(val ok: Boolean, val status: String?)

/** A downloaded picture: [preview] = the camera's small `_SM` copy, the full one stays on the card. */
class RvfPicture(val name: String, val bytes: ByteArray, val preview: Boolean)

/**
 * Client for the cameras' "Remote Viewfinder" Wi-Fi mode (2012–2014 Samsung NX / WB / ST / DV
 * models). The camera is the access point and the server: UPnP on [port] (7676), live view on a
 * second port from GetInfomation. Reimplemented from published protocol notes and from what
 * Samsung's own apps do (Remote Viewfinder 2.2.11 for the 2012 models, SMART CAMERA App 1.4.0),
 * see docs/PROTOCOL.md, "Remote Viewfinder".
 *
 * Rules the cameras impose, which this class keeps:
 * * the pairing `User-Agent: SEC_RVF_<id>` goes on the description request only (as in both of
 *   Samsung's apps); that request makes the camera ask "allow this device?" and may block until
 *   the user answers;
 * * one command at a time, in order (the NX1000 hangs otherwise): all calls are serialised;
 * * only actions the camera's SCPD declares are sent, every declared argument present;
 * * on the NX1000 the live stream must be opened right after [connect], and AF / Shot only work
 *   while it is being read; the camera ends the stream at every Shot (open it again afterwards).
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
    /** Port of the optional `/mode/control` handshake (7788 on the cameras). */
    private val modePort: Int = MODE_PORT,
    /** Shot can take long (exposure, writing the card): its own read timeout. */
    private val shotTimeoutMs: Int = 90_000,
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

    /** The NX1000 / NX1100 declare ZoomIN/ZoomOUT but zoom with the lens ring; Samsung's app hides zoom there too. */
    val canZoom: Boolean get() = has("ZoomIN") && has("ZoomOUT") && (info?.maxZoom ?: 0) > 0 && !isLensZoomOnly
    val canFocus: Boolean get() = has("MULTIAF") || has("AF")
    private val isLensZoomOnly: Boolean get() = LENS_ZOOM_MODELS.any { friendlyName.contains(it) || modelName.contains(it) }
    /** NX models: a failed focus cancels the shot (Samsung's isCancellableShotByAFFail). */
    val cancelsShotOnFocusFail: Boolean get() = friendlyName.contains("NX") || modelName.contains("NX")

    /** /mode/control knock, pairing (if new), description, SCPD, GetInfomation. Open the live stream right afterwards. */
    fun connect(): RvfInfo = synchronized(lock) {
        // Samsung's SMART CAMERA App knocks first on every camera; MVERSION marks the newer ones
        if (knockModeControl()?.contains("MVERSION", ignoreCase = true) == true) userAgent = "$UA_PREFIX_ML$clientId"
        val desc = describe()
        friendlyName = tag(desc, "friendlyName") ?: ""
        modelName = tag(desc, "modelName") ?: ""
        val cds = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(desc)
            .map { it.groupValues[1] }.firstOrNull { it.contains("ContentDirectory") }
        controlPath = cds?.let { tag(it, "controlURL") }?.let(::pathOf) ?: "/smp_4_"
        val scpdPath = cds?.let { tag(it, "SCPDURL") }?.let(::pathOf) ?: "/smp_3_"
        log("Camera '$friendlyName' ($modelName), control $controlPath, actions from $scpdPath")
        actions = parseScpd(get(scpdPath))
        log("Camera actions: ${actions.keys.sorted().joinToString(", ")}")
        val args = if ("GPSINFO" in actions["GetInfomation"].orEmpty()) listOf("GPSINFO" to "UNKNOWN") else emptyList()
        val parsed = parseInfo(callLocked("GetInfomation", args), host)
        info = parsed
        log("Live view at ${parsed.streamUrl}${parsed.version?.let { ", firmware protocol $it" } ?: ""}")
        parsed
    }

    /** Half-press: autofocus. AFSTATUS "AFFAIL" or "0" = not in focus; anything else (frame list, "3" = MF) = ok. */
    fun focus(): RvfFocus = synchronized(lock) {
        val action = if (has("MULTIAF") || !has("AF")) "MULTIAF" else "AF"
        val status = tag(callLocked(action), "AFSTATUS")?.trim()
        RvfFocus(status == null || (status != "AFFAIL" && status != "0"), status)
    }

    /** Lets go of the half-press (focus lock / self-timer): ReleaseSelfTimer(0), where declared. */
    fun releaseFocus(): Boolean = synchronized(lock) {
        if (!has("ReleaseSelfTimer")) return false
        callLocked("ReleaseSelfTimer", listOf("RELEASETIMER" to "0"))
        true
    }

    /** Full press. [toPhone]: ask the camera to offer the picture for download (where declared). */
    fun shoot(toPhone: Boolean = true): RvfShot = synchronized(lock) {
        val args = if ("REQUESTIMAGE" in actions["Shot"].orEmpty()) listOf("REQUESTIMAGE" to if (toPhone) "1" else "0") else emptyList()
        val r = try { callLocked("Shot", args, shotTimeoutMs) } catch (e: RvfException) {
            if (e.body.contains("<errorCode>803</errorCode>")) throw RvfException("the camera could not take the picture, try again", e.status, e.body)
            throw e
        }
        RvfShot(tag(r, "AFSHOTRESULT")?.trim()?.takeIf { it.startsWith("http") }, tag(r, "AVAILSHOTS")?.trim()?.toIntOrNull())
    }

    /** One zoom step; returns the new zoom position when the camera tells it. */
    fun zoom(tele: Boolean): Int? = synchronized(lock) {
        tag(callLocked(if (tele) "ZoomIN" else "ZoomOUT"), "CURRENTZOOM")?.trim()?.toIntOrNull()
    }

    /** [mode] as the camera listed it in GetInfomation's Flash/Supports (spelling varies: "off", "OFF"). */
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
        val r = http.request(uri.host, if (uri.port > 0) uri.port else 80, "GET", uri.rawPath.ifEmpty { "/" }, readTimeoutMs = timeoutMs)
        if (r.status != 200 && r.status != 206) { val body = runCatching { r.text() }.getOrDefault(""); r.close(); throw RvfException("live view refused: HTTP ${r.status}", r.status, body) }
        return r to AviFrameReader(r.body)
    }

    /**
     * The picture of a [shot]. Cameras without a protocol version (the 2012 firmware, NX1000) only
     * hand out the small `<url>_SM` copy (that is all Samsung's apps fetch from them); newer ones the
     * original. The other one is tried when the first fails.
     */
    fun downloadShot(url: String): RvfPicture {
        val name = url.substringAfterLast('/').ifBlank { "shot.jpg" }
        val previewFirst = (info?.versionNumber ?: 0.0) < 1.0
        val order = if (previewFirst) listOf(true, false) else listOf(false, true)
        var last: IOException? = null
        for (preview in order) {
            try {
                return RvfPicture(name, download(if (preview) url + "_SM" else url), preview)
            } catch (e: IOException) { log("download of ${if (preview) "${name}_SM" else name} failed: ${e.message}"); last = e }
        }
        throw last!!
    }

    /** Fetches a picture the camera reported (plain GET, no pairing header, like Samsung's apps). */
    fun download(url: String): ByteArray {
        val uri = URI(url)
        http.request(uri.host, if (uri.port > 0) uri.port else 80, "GET", uri.rawPath.ifEmpty { "/" }, readTimeoutMs = timeoutMs).use { r ->
            if (r.status != 200 && r.status != 206) throw RvfException("download of $url failed: HTTP ${r.status}", r.status)
            return r.body.readBytes()
        }
    }

    /** Any declared action, for the rest of the SCPD; refuses undeclared ones (they hang some cameras). */
    fun call(action: String, args: List<Pair<String, String>> = emptyList()): String = synchronized(lock) { callLocked(action, args) }

    private fun callLocked(action: String, args: List<Pair<String, String>> = emptyList(), readTimeout: Int = timeoutMs): String {
        if (actions.isNotEmpty() && !has(action)) throw RvfException("the camera does not offer $action")
        // every declared input argument goes out, empty when not set (as Samsung's UPnP stack does)
        val given = args.toMap()
        val all = actions[action]?.map { it to (given[it] ?: "") }?.let { declared -> declared + args.filter { (k, _) -> declared.none { it.first == k } } } ?: args
        val body = """<?xml version="1.0" encoding="utf-8"?>
<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${UpnpXml.CDS_SERVICE_TYPE}">${all.joinToString("") { (k, v) -> "<$k>${UpnpXml.escape(v)}</$k>" }}</u:$action></s:Body></s:Envelope>""".toByteArray(Charsets.UTF_8)
        val headers = listOf(
            "Content-Type" to "text/xml; charset=\"utf-8\"",
            "SOAPACTION" to "\"${UpnpXml.CDS_SERVICE_TYPE}#$action\"",
        )
        http.request(host, port, "POST", controlPath, headers, body, readTimeout).use { r ->
            val text = r.text()
            if (r.status != 200) throw RvfException("$action failed: HTTP ${r.status}${tag(text, "errorDescription")?.let { " ($it)" } ?: ""}", r.status, text)
            log("$action: OK")
            return if (text.contains("&lt;")) unescape(text) else text
        }
    }

    /** GET of the description; this is the request that pairs. 401 = not (yet) allowed, 503 = another phone. */
    private fun describe(): String {
        val first = runCatching { getPaired("/smp_2_") }
        val e = first.exceptionOrNull() ?: return first.getOrThrow()
        if (e !is RvfException || e.status != 401 || userAgent.startsWith(UA_PREFIX_ML)) throw friendly(e)
        // newer models (NX3000, NX mini) that did not say so on /mode/control: try their prefix once
        log("Camera refused $userAgent, trying the newer pairing")
        userAgent = "$UA_PREFIX_ML$clientId"
        return try { getPaired("/smp_2_") } catch (e2: IOException) { throw friendly(e2) }
    }

    private fun friendly(e: Throwable): Throwable = when ((e as? RvfException)?.status) {
        401 -> RvfException("the camera refused this phone: confirm the prompt on the camera's screen, or restart Remote Viewfinder on the camera", 401, e.message ?: "")
        503 -> RvfException("the camera is already connected to another phone; only one at a time", 503, e.message ?: "")
        else -> e
    }

    /** Best effort, like the SMART CAMERA App: one HEAD on 7788. The 2012 models have no such port; null then. */
    private fun knockModeControl(): String? = runCatching {
        http.request(host, modePort, "HEAD", "/mode/control", listOf(
            "User-Agent" to "SEC_MODE_$clientId", "NTS" to "alive", "HOST-Mac" to clientId,
            "HOST-port" to "$MODE_PORT", "HOST-PNumber" to "none", "Access-Method" to "manual",
        ), ByteArray(0), readTimeoutMs = pairTimeoutMs, connectTimeout = 3_000).use { r ->
            val text = runCatching { r.text() }.getOrDefault("")
            log("/mode/control: HTTP ${r.status}")
            r.headers.entries.joinToString("\n") { "${it.key}: ${it.value}" } + "\n" + text
        }
    }.onFailure { log("/mode/control not offered (${it.message})") }.getOrNull()

    private fun getPaired(path: String): String = get(path, listOf("User-Agent" to userAgent, "Access-Method" to "manual"), pairTimeoutMs)

    private fun get(path: String, headers: List<Pair<String, String>> = emptyList(), readTimeout: Int = timeoutMs): String =
        http.request(host, port, "GET", path, headers, readTimeoutMs = readTimeout).use { r ->
            val text = r.text()
            if (r.status != 200) throw RvfException("GET $path: HTTP ${r.status}", r.status, text)
            text
        }

    companion object {
        const val DEFAULT_PORT = 7676
        const val MODE_PORT = 7788
        const val UA_PREFIX = "SEC_RVF_"
        const val UA_PREFIX_ML = "SEC_RVF_ML_"
        val LENS_ZOOM_MODELS = listOf("NX1000", "NX1100")

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

        /**
         * GetInfomation answer -> [RvfInfo]. Stream URL: StreamUrl (high/low by StreamQuality/Default),
         * else GETIPRESULT (the 2012 firmware puts the stream URL there), else the documented default.
         */
        fun parseInfo(xml: String, host: String): RvfInfo {
            val high = tag(xml, "QualityHighUrl")?.trim()?.takeIf { it.startsWith("http") }
            val low = tag(xml, "QualityLowUrl")?.trim()?.takeIf { it.startsWith("http") }
            val legacy = tag(xml, "GETIPRESULT")?.trim()?.takeIf { it.startsWith("http") }
            val preferLow = tag(tag(xml, "StreamQuality") ?: "", "Default")?.trim().equals("low", ignoreCase = true)
            val zoom = tag(xml, "ZoomInfo") ?: ""
            val flash = tag(xml, "Flash") ?: ""
            return RvfInfo(
                streamUrl = (if (preferLow) low ?: high else high ?: low) ?: legacy ?: "http://$host:$STREAM_PORT/livestream.avi",
                lowStreamUrl = low,
                maxZoom = tag(zoom, "MaxZoom")?.trim()?.toIntOrNull() ?: 0,
                zoom = tag(zoom, "DefaultZoom")?.trim()?.toIntOrNull() ?: 0,
                flashModes = tags(flash, "Support"),
                flash = tag(flash, "Defaultflash")?.trim(),
                availShots = tag(xml, "AVAILSHOTS")?.trim()?.toIntOrNull(),
                rotation = tag(xml, "ROTATION")?.trim()?.toIntOrNull() ?: 0,
                version = tag(xml, "Version")?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        const val STREAM_PORT = 7679
    }
}
