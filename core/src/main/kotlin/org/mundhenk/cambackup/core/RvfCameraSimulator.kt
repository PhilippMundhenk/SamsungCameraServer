package org.mundhenk.cambackup.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A camera in Remote Viewfinder mode: UPnP description / SCPD / SOAP control on one port, the
 * endless AVI live stream on another, pictures served after a Shot. It is as strict as the real
 * cameras were reported to be, and records every rule a client breaks in [violations] instead of
 * hanging like the NX1000 would: a missing or wrong User-Agent (401), an undeclared action, two
 * commands at once, AF or Shot while nobody reads the live stream, a second stream reader.
 *
 * Used by the JVM tests and by the app's instrumented tests (it runs on the phone itself).
 */
class RvfCameraSimulator(
    val profile: Profile = Profile.NX1000,
    /** Frames the live view loops over (H.264 access units or JPEGs, matching [codec]). */
    private val frames: List<ByteArray> = listOf(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 3)),
    private val codec: VideoCodec = VideoCodec.H264,
    private val width: Int = 640,
    private val height: Int = 480,
    private val fps: Int = 15,
    /** JPEG served for every picture taken. */
    private val photo: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()),
    /** Host name put into the URLs the camera hands out. */
    private val advertisedHost: String = "127.0.0.1",
    controlPort: Int = 0,
    streamPort: Int = 0,
    /** The user's answer on the camera's "allow this device?" prompt. */
    var allow: (String) -> Boolean = { true },
    private val log: (String) -> Unit = {},
) {
    enum class Profile(
        val model: String,
        val actions: Map<String, List<String>>,
        val maxZoom: Int,
        val uaPrefix: String,
    ) {
        /** 2012 NX: no SCPD zoom, Shot without arguments, the strict ordering rules. */
        NX1000("NX1000", mapOf(
            "Browse" to listOf("ObjectID", "BrowseFlag", "Filter", "StartingIndex", "RequestedCount", "SortCriteria"),
            "GetIP" to emptyList(), "GetInfomation" to emptyList(), "MULTIAF" to emptyList(), "Shot" to emptyList(),
            "ReleaseSelfTimer" to listOf("RELEASETIMER"), "SetLED" to listOf("LEDTIME"),
        ), 0, RvfClient.UA_PREFIX),
        /** 2013 compact (WB250F-like): optical zoom, flash, stream quality. */
        WB250F("WB250F", mapOf(
            "GetInfomation" to emptyList(), "AF" to emptyList(), "AFRELEASE" to emptyList(), "Shot" to listOf("REQUESTIMAGE"),
            "ZoomIN" to emptyList(), "ZoomOUT" to emptyList(), "SetFlash" to listOf("FLASHMODE"), "SetStreamQuality" to listOf("Quality"),
        ), 18, RvfClient.UA_PREFIX),
        /** 2014 NX (NX3000/NX mini): MobileLink-style pairing, GPSINFO, HeartBeat. */
        NX3000("NX3000", mapOf(
            "GetInfomation" to listOf("GPSINFO"), "MULTIAF" to emptyList(), "touchAF" to listOf("AFPOSITION"), "Shot" to emptyList(),
            "HeartBeat" to listOf("Duration"), "SetFlash" to listOf("FLASHMODE"), "StartZoom" to listOf("ZoomDirection"), "StopZoom" to listOf("ZoomDirection"),
        ), 0, RvfClient.UA_PREFIX_ML),
    }

    private val control = HttpServer(controlPort, { handle(it) }, log)
    private var streamSocket: ServerSocket? = null
    private val wantedStreamPort = streamPort
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "rvf-sim").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val readers = AtomicInteger(0)
    private val allowed = Collections.synchronizedSet(HashSet<String>())
    private val shots = AtomicInteger(0)
    @Volatile private var sessionStarted = false
    @Volatile private var zoom = 0

    /** Every rule a client broke; a correct client leaves this empty. */
    val violations: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** SOAP actions in the order they arrived. */
    val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** User-Agents the camera saw on its control port. */
    val userAgents: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** Frames sent over the live stream so far. */
    val framesSent = AtomicInteger(0)
    val shotCount: Int get() = shots.get()
    val streamReaders: Int get() = readers.get()

    val controlPort: Int get() = control.boundPort
    val streamPort: Int get() = streamSocket?.localPort ?: wantedStreamPort

    fun start(): RvfCameraSimulator {
        if (running.getAndSet(true)) return this
        control.start()
        val ss = ServerSocket(); ss.reuseAddress = true; ss.bind(InetSocketAddress(wantedStreamPort)); streamSocket = ss
        pool.execute {
            while (running.get()) {
                val s = try { ss.accept() } catch (e: IOException) { break }
                pool.execute { stream(s) }
            }
        }
        log("RVF simulator (${profile.model}): control ${control.boundPort}, stream ${ss.localPort}")
        return this
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        control.stop()
        runCatching { streamSocket?.close() }
        pool.shutdownNow()
    }

    private fun handle(r: HttpRequest): HttpResponse {
        val ua = r.header("User-Agent") ?: ""
        userAgents += ua
        if (r.path.startsWith("/DCIM/")) {
            return if (r.path.substringAfterLast('/').startsWith("SAM_")) HttpResponse(200, photo, "image/jpeg") else HttpResponse.notFound()
        }
        if (!ua.startsWith(profile.uaPrefix) || (profile.uaPrefix == RvfClient.UA_PREFIX && ua.startsWith(RvfClient.UA_PREFIX_ML))) {
            return HttpResponse(401, "Unauthorized".toByteArray(), "text/plain")
        }
        val id = ua.removePrefix(profile.uaPrefix)
        if (id !in allowed) {
            if (!allow(id)) { log("user said no to $id"); return HttpResponse(401, "Unauthorized".toByteArray(), "text/plain") }
            allowed += id
            log("user allowed $id")
        }
        return when (r.path) {
            "/smp_2_" -> HttpResponse.xml(description())
            "/smp_3_" -> HttpResponse.xml(scpd())
            "/smp_4_" -> soap(r)
            else -> HttpResponse.notFound()
        }
    }

    private fun soap(r: HttpRequest): HttpResponse {
        val action = r.header("SOAPACTION")?.trim('"', ' ')?.substringAfter('#') ?: ""
        val body = r.readBodyText()
        calls += action
        if (!busy.compareAndSet(false, true)) violations += "$action sent while another command was running"
        try {
            val declared = profile.actions[action] ?: run { violations += "undeclared action $action"; return fault(401, "Invalid Action") }
            for (arg in Regex("<u:$action[^>]*>(.*)</u:$action>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                ?.let { Regex("<([A-Za-z_]+)>").findAll(it).map { m -> m.groupValues[1] }.toList() }.orEmpty()) {
                if (arg !in declared) { violations += "$action with undeclared argument $arg"; return fault(501, "Action Failed") }
            }
            for (arg in declared) if (action == "GetInfomation" && !body.contains("<$arg>")) { violations += "$action without $arg"; return fault(402, "Invalid Args") }
            if (action != "GetInfomation" && !sessionStarted) violations += "$action before GetInfomation"
            if ((action == "MULTIAF" || action == "AF" || action == "Shot") && readers.get() == 0) {
                violations += "$action while nobody reads the live stream"
                return fault(501, "Action Failed")
            }
            return HttpResponse.xml(UpnpXml.soapResponse(action, UpnpXml.CDS_SERVICE_TYPE, answer(action, body)))
        } finally { busy.set(false) }
    }

    private fun answer(action: String, body: String): String = when (action) {
        "GetInfomation" -> {
            sessionStarted = true
            val s = "http://$advertisedHost:$streamPort"
            "<GETINFORMATIONRESULT><Resolutions><Resolution><Width>5472</Width><Height>3648</Height></Resolution></Resolutions>" +
                "<Flash><Supports><Support>off</Support><Support>auto</Support><Support>fill</Support></Supports><Defaultflash>auto</Defaultflash></Flash>" +
                "<ZoomInfo><DefaultZoom>0</DefaultZoom><MaxZoom>${profile.maxZoom}</MaxZoom></ZoomInfo><AVAILSHOTS>${999 - shots.get()}</AVAILSHOTS><ROTATION>0</ROTATION>" +
                "<StreamQuality><Quality><Option>high</Option><Option>low</Option></Quality><Default>high</Default></StreamQuality></GETINFORMATIONRESULT>" +
                "<StreamUrl><QualityHighUrl>$s/livestream.avi</QualityHighUrl><QualityLowUrl>$s/qvga_livestream.avi</QualityLowUrl></StreamUrl>"
        }
        "MULTIAF", "AF" -> "<AFSTATUS>1</AFSTATUS>"
        "Shot" -> {
            val n = shots.incrementAndGet()
            if (body.contains("<REQUESTIMAGE>0</REQUESTIMAGE>")) "<AFSHOTRESULT>none</AFSHOTRESULT><AVAILSHOTS>${999 - n}</AVAILSHOTS>"
            else "<AFSHOTRESULT>http://$advertisedHost:$controlPort/DCIM/100PHOTO/SAM_%04d.JPG</AFSHOTRESULT><AVAILSHOTS>${999 - n}</AVAILSHOTS>".format(n)
        }
        "ZoomIN" -> { zoom = minOf(profile.maxZoom, zoom + 1); "<CURRENTZOOM>$zoom</CURRENTZOOM>" }
        "ZoomOUT" -> { zoom = maxOf(0, zoom - 1); "<CURRENTZOOM>$zoom</CURRENTZOOM>" }
        "SetFlash" -> "<CurrentFlashDisplay>${Regex("<FLASHMODE>(.*?)</FLASHMODE>").find(body)?.groupValues?.get(1) ?: ""}</CurrentFlashDisplay>"
        else -> ""
    }

    private fun fault(code: Int, text: String) = HttpResponse.xml(
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>$code</errorCode><errorDescription>$text</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>""",
        500,
    )

    private fun stream(s: Socket) {
        s.use {
            val head = generateSequence { readLine(s) }.takeWhile { it.isNotEmpty() }.toList()
            val ua = head.firstOrNull { it.startsWith("User-Agent:", ignoreCase = true) }?.substringAfter(':')?.trim() ?: ""
            if (!sessionStarted) { violations += "live stream requested before GetInfomation"; s.getOutputStream().write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray()); return }
            if (!ua.startsWith(profile.uaPrefix)) violations += "live stream requested without the pairing User-Agent"
            if (readers.incrementAndGet() > 1) {
                violations += "second live stream reader"
                readers.decrementAndGet()
                s.getOutputStream().write("HTTP/1.1 503 Service Unavailable\r\n\r\n".toByteArray())
                return
            }
            try {
                val out = s.getOutputStream()
                out.write("HTTP/1.1 206 Partial Content\r\nContent-Type: video/avi\r\nConnection: close\r\n\r\n".toByteArray())
                out.write(AviFrameReader.header(width, height, codec))
                var i = 0
                while (running.get()) {
                    out.write(AviFrameReader.chunk(frames[i % frames.size]))
                    out.flush()
                    framesSent.incrementAndGet()
                    i++
                    Thread.sleep(1000L / fps)
                }
            } catch (_: IOException) { // reader went away
            } catch (_: InterruptedException) {
            } finally { readers.decrementAndGet() }
        }
    }

    private fun readLine(s: Socket): String? {
        val b = StringBuilder()
        val input = s.getInputStream()
        while (true) {
            val c = input.read()
            if (c < 0) return if (b.isEmpty()) null else b.toString()
            if (c == '\n'.code) return b.toString().trimEnd('\r')
            b.append(c.toChar())
        }
    }

    private fun description() = """<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:dlna="urn:schemas-dlna-org:device-1-0" xmlns:sec="http://www.sec.co.kr/dlna">
<specVersion><major>1</major><minor>0</minor></specVersion>
<device><dlna:X_DLNADOC>DMS-1.50</dlna:X_DLNADOC><deviceType>${UpnpXml.MEDIA_SERVER_TYPE}</deviceType>
<friendlyName>[Camera]${profile.model}</friendlyName><manufacturer>Samsung Electronics</manufacturer>
<modelDescription>Samsung Camera DMS</modelDescription><modelName>SP1</modelName><modelNumber>1.0</modelNumber>
<serialNumber>20081113 Folderview</serialNumber><UDN>uuid:00000000-0000-0000-0000-00000000rvf0</UDN>
<serviceList>
<service><serviceType>${UpnpXml.CDS_SERVICE_TYPE}</serviceType><serviceId>urn:upnp-org:serviceId:ContentDirectory</serviceId><controlURL>/smp_4_</controlURL><eventSubURL>/smp_5_</eventSubURL><SCPDURL>/smp_3_</SCPDURL></service>
<service><serviceType>${UpnpXml.CMS_SERVICE_TYPE}</serviceType><serviceId>urn:upnp-org:serviceId:ConnectionManager</serviceId><controlURL>/smp_7_</controlURL><eventSubURL>/smp_8_</eventSubURL><SCPDURL>/smp_6_</SCPDURL></service>
</serviceList></device></root>"""

    private fun scpd(): String = buildString {
        append("<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>")
        for ((name, ins) in profile.actions) {
            append("<action><name>$name</name><argumentList>")
            for (a in ins) append("<argument><name>$a</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_$a</relatedStateVariable></argument>")
            append("<argument><name>Result</name><direction>out</direction><relatedStateVariable>A_ARG_TYPE_Result</relatedStateVariable></argument>")
            append("</argumentList></action>")
        }
        append("</actionList></scpd>")
    }
}
