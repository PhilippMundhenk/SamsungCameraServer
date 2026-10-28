package org.mundhenk.cambackup.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.xml.parsers.DocumentBuilderFactory

class ServerStats {
    val filesStored = AtomicInteger()
    val filesSkipped = AtomicInteger()
    val bytesStored = AtomicLong()
    val sessions = AtomicInteger()
    @Volatile var lastCamera: String? = null
    @Volatile var lastFile: String? = null
}

/**
 * Samsung "PC Auto Backup" target: a UPnP MediaServer whose only job is to
 * accept CreateObject + upload from the camera.
 *
 * Flow driven by the camera:
 *   SSDP M-SEARCH -> GET device description -> X_BACKUP_START
 *   -> per file: CreateObject (DIDL with name/date/size) -> POST bytes to importUri
 *   -> X_BACKUP_DONE
 */
class BackupServer(
    identity: ServerIdentity,
    private val sink: BackupSink,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    private val log: (String) -> Unit = {},
    /**
     * UPnP error code to answer CreateObject with when the sink says the file
     * already exists. null = accept the upload and discard it (safe default;
     * the camera marks the file as backed up either way).
     */
    private val fileExistsErrorCode: Int? = null,
    private val enableSsdp: Boolean = true,
    private val bindAddress: InetAddress? = null,
    /** Handles everything that is not part of the camera protocol (backend API, web UI). */
    private val fallback: HttpHandler? = null,
) {
    /** What we advertise; see [changeIdentity]. */
    @Volatile var identity: ServerIdentity = identity
        private set
    val stats = ServerStats()
    @Volatile private var started = false
    private val pending = ConcurrentHashMap<String, IncomingFile>()
    private val random = SecureRandom()
    private val http = HttpServer(httpPort, ::route, log)
    private var ssdp: SsdpResponder? = null

    val boundPort: Int get() = http.boundPort

    fun start() {
        http.start()
        started = true
        if (enableSsdp) ssdp = SsdpResponder(identity, boundPort, log, bindAddress = bindAddress).also { it.start() }
        log("Backup server up as '${identity.friendlyName}' uuid:${identity.udn}")
    }

    fun stop() {
        started = false
        ssdp?.stop(); ssdp = null
        http.stop()
        log("Backup server stopped")
    }

    /**
     * Switches to [next] without touching the HTTP side (no port rebind, no dropped request):
     * the device description and SSDP answer with the new UDN from now on. Only the SSDP
     * responder is recreated, so the old uuid says goodbye and the new one announces itself.
     */
    @Synchronized
    fun changeIdentity(next: ServerIdentity) {
        if (next == identity) return
        identity = next
        ssdp?.stop(); ssdp = null
        if (enableSsdp && started) ssdp = SsdpResponder(next, boundPort, log, bindAddress = bindAddress).also { it.start() }
        log("Backup server now advertises '${next.friendlyName}' uuid:${next.udn}")
    }

    private fun route(req: HttpRequest): HttpResponse = when {
        req.method == "GET" && req.path.equals(UpnpXml.DEVICE_DESC_PATH, true) -> {
            log("${if (req.header("user-agent")?.contains("DLNADOC", true) == true) "Camera" else "Client"} ${req.remoteAddress} fetched device description (${req.header("user-agent") ?: "no UA"})")
            HttpResponse.xml(UpnpXml.deviceDescription(identity))
        }
        req.method == "GET" && req.path.endsWith("ContentDirectory1.xml", true) -> HttpResponse.xml(UpnpXml.contentDirectoryScpd)
        req.method == "GET" && req.path.endsWith("ConnectionManager1.xml", true) -> HttpResponse.xml(UpnpXml.connectionManagerScpd)
        req.method == "POST" && req.path == UpnpXml.CDS_CONTROL_PATH -> contentDirectory(req)
        req.method == "POST" && req.path == UpnpXml.CMS_CONTROL_PATH -> connectionManager(req)
        req.method == "POST" && req.path == UpnpXml.UPLOAD_PATH -> upload(req)
        req.method == "SUBSCRIBE" || req.method == "UNSUBSCRIBE" -> HttpResponse(200, ByteArray(0), "text/plain", mapOf("SID" to "uuid:${identity.udn}-sub", "TIMEOUT" to "Second-1800"))
        fallback != null -> fallback.handle(req)
        else -> { log("Unhandled ${req.method} ${req.path} from ${req.remoteAddress}"); HttpResponse.notFound() }
    }

    private fun soapAction(req: HttpRequest): String =
        (req.header("soapaction") ?: "").trim('"').substringAfter('#')

    private fun contentDirectory(req: HttpRequest): HttpResponse {
        val action = soapAction(req)
        val bodyText = req.readBodyText()
        val cds = UpnpXml.CDS_SERVICE_TYPE
        return when (action) {
            "X_BACKUP_START" -> {
                stats.sessions.incrementAndGet(); stats.lastCamera = req.remoteAddress
                // FileCnt is what the camera intends to send: files not yet flagged as backed up on its
                // card. 0 means the camera will sit on its transfer screen and send nothing.
                val announced = Regex("<FileCnt>\\s*(\\d+)\\s*</FileCnt>").find(bodyText)?.groupValues?.get(1)?.toIntOrNull()
                log("Backup session started by ${req.remoteAddress}" + when (announced) {
                    null -> ""
                    0 -> ": 0 files announced, the camera has nothing new to send (every file on the card is already flagged as backed up; a file sent to any target with this UDN is not offered again)"
                    else -> ": $announced file(s) announced"
                })
                sink.onBackupStart(req.remoteAddress)
                HttpResponse.xml(UpnpXml.soapResponse(action, cds, ""))
            }
            "X_BACKUP_DONE" -> {
                val sysOff = Regex("<SysOff>\\s*1\\s*</SysOff>").containsMatchIn(bodyText)
                log("Backup session finished by ${req.remoteAddress} (stored=${stats.filesStored.get()}, skipped=${stats.filesSkipped.get()})")
                sink.onBackupDone(req.remoteAddress, sysOff)
                HttpResponse.xml(UpnpXml.soapResponse(action, cds, ""))
            }
            "CreateObject" -> createObject(req, bodyText)
            "GetTransferProgress" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds,
                "<TransferStatus>COMPLETED</TransferStatus><TransferLength>0</TransferLength><TransferTotal>0</TransferTotal>"))
            "DestroyObject", "StopTransferResource" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, ""))
            "GetSearchCapabilities" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, "<SearchCaps></SearchCaps>"))
            "GetSortCapabilities" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, "<SortCaps></SortCaps>"))
            "GetSystemUpdateID" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, "<Id>1</Id>"))
            "Browse" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds,
                "<Result>${UpnpXml.escape(EMPTY_DIDL)}</Result><NumberReturned>0</NumberReturned><TotalMatches>0</TotalMatches><UpdateID>1</UpdateID>"))
            else -> { log("Unknown CDS action '$action' from ${req.remoteAddress}"); HttpResponse.xml(UpnpXml.soapFault(401, "Invalid Action"), 500) }
        }
    }

    private fun connectionManager(req: HttpRequest): HttpResponse {
        val action = soapAction(req); req.readBodyText()
        val cms = UpnpXml.CMS_SERVICE_TYPE
        return when (action) {
            "GetProtocolInfo" -> HttpResponse.xml(UpnpXml.soapResponse(action, cms,
                "<Source></Source><Sink>http-get:*:image/jpeg:*,http-get:*:video/mp4:*,http-get:*:*:*</Sink>"))
            "GetCurrentConnectionIDs" -> HttpResponse.xml(UpnpXml.soapResponse(action, cms, "<ConnectionIDs>0</ConnectionIDs>"))
            else -> HttpResponse.xml(UpnpXml.soapFault(401, "Invalid Action"), 500)
        }
    }

    private fun createObject(req: HttpRequest, bodyText: String): HttpResponse {
        val file = try { parseCreateObject(bodyText, req.remoteAddress) } catch (e: Exception) {
            log("CreateObject with unparsable DIDL from ${req.remoteAddress}: $e")
            return HttpResponse.xml(UpnpXml.soapFault(712, "Bad metadata"), 500)
        }
        val exists = sink.exists(file)
        if (exists && fileExistsErrorCode != null) {
            stats.filesSkipped.incrementAndGet()
            log("Skip ${file.name} (already stored) -> UPnP error $fileExistsErrorCode")
            return HttpResponse.xml(UpnpXml.soapFault(fileExistsErrorCode, "File exists"), 500)
        }
        val parentId = "UP_${FileSystemSink.safeSegment(file.date)}"
        val objectId = parentId + "_" + ByteArray(5).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        pending[objectId] = file
        val importUri = "http://${req.localAddress}:${boundPort}${UpnpXml.UPLOAD_PATH}?didx=0_id=$objectId"
        val (mime, profile) = file.mimeType to dlnaProfileFor(file.mimeType)
        val didl = UpnpXml.createObjectDidl(objectId, parentId, mime, profile, file.size, importUri, file.upnpClass)
        log("Ready for ${file.name} (${file.size} bytes, ${file.mimeType}${if (exists) ", duplicate" else ""})")
        return HttpResponse.xml(UpnpXml.soapResponse("CreateObject", UpnpXml.CDS_SERVICE_TYPE,
            "<ObjectID>$objectId</ObjectID><Result>${UpnpXml.escape(didl)}</Result>"))
    }

    private fun upload(req: HttpRequest): HttpResponse {
        val objectId = req.query["didx"]?.substringAfter("id=") ?: req.query["id"]
        val file = objectId?.let { pending.remove(it) }
        if (file == null) {
            log("Upload for unknown object '$objectId' from ${req.remoteAddress}")
            return HttpResponse.notFound()
        }
        val effective = if (file.size < 0 && req.contentLength > 0) file.copy(size = req.contentLength) else file
        stats.lastFile = effective.name
        val started = System.nanoTime()
        val result = sink.store(effective, req.body)
        val seconds = (System.nanoTime() - started) / 1e9
        when (result) {
            StoreResult.STORED -> { stats.filesStored.incrementAndGet(); stats.bytesStored.addAndGet(maxOf(effective.size, 0)); log("Stored ${effective.name} (${effective.size} bytes, ${rate(effective.size, seconds)})") }
            StoreResult.ALREADY_EXISTS -> { stats.filesSkipped.incrementAndGet(); log("Discarded duplicate ${effective.name}") }
        }
        return HttpResponse.empty(200)
    }

    /** "12.3 MB/s in 2.1 s" — the camera's Wi-Fi is the usual bottleneck, this makes it visible. */
    private fun rate(bytes: Long, seconds: Double): String {
        val s = maxOf(seconds, 0.001)
        return String.format(java.util.Locale.US, "%.1f MB/s in %.1f s", bytes / 1e6 / s, seconds)
    }

    private fun parseCreateObject(soap: String, camera: String): IncomingFile {
        val doc = parseXml(soap)
        val elements = doc.getElementsByTagNameNS("*", "Elements").item(0)?.textContent
            ?: doc.getElementsByTagName("Elements").item(0)?.textContent
            ?: error("no Elements")
        val didl = parseXml(elements.trim())
        fun text(local: String): String? =
            didl.getElementsByTagNameNS("*", local).item(0)?.textContent?.trim()
        val res = didl.getElementsByTagNameNS("*", "res").item(0) as? Element
        val protocolInfo = res?.getAttribute("protocolInfo") ?: ""
        val mime = protocolInfo.split(':').getOrNull(2)?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val size = res?.getAttribute("size")?.toLongOrNull() ?: -1L
        return IncomingFile(
            name = text("title")?.takeIf { it.isNotBlank() } ?: "unnamed",
            date = text("date")?.takeIf { it.isNotBlank() } ?: "unknown-date",
            size = size,
            mimeType = mime,
            upnpClass = text("class") ?: "object.item",
            cameraAddress = camera,
        )
    }

    private fun parseXml(text: String): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        return f.newDocumentBuilder().parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
    }

    companion object {
        const val DEFAULT_HTTP_PORT = 52235
        private const val EMPTY_DIDL = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"></DIDL-Lite>"

        fun dlnaProfileFor(mime: String): String = when (mime.lowercase()) {
            "image/jpeg" -> "DLNA.ORG_PN=JPEG_LRG"
            "video/mp4" -> "DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC"
            else -> "*"
        }
    }
}
