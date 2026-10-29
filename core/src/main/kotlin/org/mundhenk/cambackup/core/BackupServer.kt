package org.mundhenk.cambackup.core

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.File
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
    /**
     * Where unfinished uploads wait for the camera to resume them (null = no resume: bodies stream
     * straight into the sink). With a spool the camera is told `dlna:resumeUpload="1"`, a Browse of
     * a pending object reports `dlna:uploadedSize`, and a POST with `Content-Range: bytes <from>-…`
     * continues the file; complete files are handed to the sink in one piece.
     */
    private val spoolDir: File? = null,
) {
    /** What we advertise; see [changeIdentity]. */
    @Volatile var identity: ServerIdentity = identity
        private set
    val stats = ServerStats()
    @Volatile private var started = false
    private val pending = ConcurrentHashMap<String, IncomingFile>()
    private val pendingFile: File? = spoolDir?.let { File(it, "pending.tsv") }
    init { if (spoolDir != null) restorePending(spoolDir) }
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
        req.method == "GET" && req.path.endsWith("ContentDirectory1.xml", true) -> { log("${req.remoteAddress} fetched the ContentDirectory service description"); HttpResponse.xml(UpnpXml.contentDirectoryScpd) }
        req.method == "GET" && req.path.endsWith("ConnectionManager1.xml", true) -> { log("${req.remoteAddress} fetched the ConnectionManager service description"); HttpResponse.xml(UpnpXml.connectionManagerScpd) }
        req.method == "POST" && req.path == UpnpXml.CDS_CONTROL_PATH -> contentDirectory(req)
        req.method == "POST" && req.path == UpnpXml.CMS_CONTROL_PATH -> connectionManager(req)
        req.method == "POST" && req.path == UpnpXml.UPLOAD_PATH -> upload(req)
        req.method == "SUBSCRIBE" || req.method == "UNSUBSCRIBE" -> {
            log("${req.method} ${req.path} from ${req.remoteAddress}${req.header("callback")?.let { " (callback $it)" } ?: ""}")
            HttpResponse(200, ByteArray(0), "text/plain", mapOf("SID" to "uuid:${identity.udn}-sub", "TIMEOUT" to "Second-1800"))
        }
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
                val announced = Regex("<(?:\\w+:)?(?:NumOfContents|FileCnt)[^>]*>\\s*(\\d+)\\s*</(?:\\w+:)?(?:NumOfContents|FileCnt)>").find(bodyText)?.groupValues?.get(1)?.toIntOrNull()
                log("Backup session started by ${req.remoteAddress}" + when (announced) {
                    null -> " (arguments: ${soapArguments(bodyText).take(300)})"
                    0 -> ": 0 files announced, the camera has nothing new to send (every file on the card is already flagged as backed up; a file sent to any target with this UDN is not offered again)"
                    else -> ": $announced file(s) announced"
                })
                sink.onBackupStart(req.remoteAddress)
                HttpResponse.xml(UpnpXml.soapResponse(action, cds, ""))
            }
            "X_BACKUP_DONE" -> {
                val sysOff = Regex("<(?:\\w+:)?(?:SysOff|SystemOff)[^>]*>\\s*1\\s*</(?:\\w+:)?(?:SysOff|SystemOff)>").containsMatchIn(bodyText)
                log("Backup session finished by ${req.remoteAddress} (stored=${stats.filesStored.get()}, skipped=${stats.filesSkipped.get()})")
                sink.onBackupDone(req.remoteAddress, sysOff)
                HttpResponse.xml(UpnpXml.soapResponse(action, cds, ""))
            }
            "CreateObject" -> createObject(req, bodyText)
            "Browse" -> browse(req, bodyText)
            else -> { log("CDS $action from ${req.remoteAddress}"); genericContentDirectory(action, bodyText, req) }
        }
    }

    /** The inside of the SOAP action element, for the log. */
    private fun soapArguments(soap: String): String =
        Regex("<(?:\\w+:)?X_BACKUP_START[^>]*>(.*?)</(?:\\w+:)?X_BACKUP_START>", RegexOption.DOT_MATCHES_ALL).find(soap)?.groupValues?.get(1)?.trim()
            ?: soap.replace(Regex("\\s+"), " ").trim()

    private fun soapArg(soap: String, name: String): String? =
        Regex("<(?:\\w+:)?$name[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL).find(soap)?.groupValues?.get(1)?.trim()

    /**
     * Browse. The NX1000 asks about the last object of its previous session right after
     * X_BACKUP_START (seen: `Browse UP_2021-06-07_7606e6afaf` after a session the 1,000-file cap
     * had cut). An empty result left it waiting forever. Now: the root "0" holds one upload
     * folder, the parentIDs we hand out ("UP_<date>") are that folder, an object still waiting
     * for its upload is returned as the item it was created as, and anything else is UPnP error
     * 701 "No such object", so the camera knows to create it anew.
     */
    private fun browse(req: HttpRequest, soap: String): HttpResponse {
        val id = soapArg(soap, "ObjectID") ?: "0"
        val flag = soapArg(soap, "BrowseFlag") ?: "BrowseDirectChildren"
        val metadata = flag.equals("BrowseMetadata", ignoreCase = true)
        val cds = UpnpXml.CDS_SERVICE_TYPE
        fun result(didl: String, returned: Int, total: Int) = HttpResponse.xml(UpnpXml.soapResponse("Browse", cds,
            "<Result>${UpnpXml.escape(didl)}</Result><NumberReturned>$returned</NumberReturned><TotalMatches>$total</TotalMatches><UpdateID>1</UpdateID>"))
        val pendingFile = pending[id]
        val kind = when {
            id == "0" -> "root"
            id == UPLOAD_CONTAINER || DATE_CONTAINER.matches(id) -> "folder"
            pendingFile != null -> "pending object"
            else -> "unknown object"
        }
        log("CDS Browse from ${req.remoteAddress} (ObjectID $id, $flag) -> $kind${if (kind == "unknown object") ", UPnP error 701" else ""}")
        return when (kind) {
            "root" -> if (metadata) result(UpnpXml.containerDidl("0", "-1", identity.friendlyName, 1), 1, 1)
                      else result(UpnpXml.containerDidl(UPLOAD_CONTAINER, "0", UPLOAD_TITLE, 0), 1, 1)
            "folder" -> if (metadata) result(UpnpXml.containerDidl(id, "0", UPLOAD_TITLE, 0), 1, 1) else result(EMPTY_DIDL, 0, 0)
            "pending object" -> {
                val f = pendingFile!!
                val importUri = "http://${req.localAddress}:${boundPort}${UpnpXml.UPLOAD_PATH}?didx=0_id=$id"
                result(UpnpXml.createObjectDidl(id, id.substringBeforeLast('_'), f.mimeType, dlnaProfileFor(f.mimeType), f.size, importUri, f.upnpClass,
                    resumable = spoolDir != null, uploadedSize = uploadedBytes(id)), 1, 1)
            }
            else -> HttpResponse.xml(UpnpXml.soapFault(701, "No such object"), 500)
        }
    }

    private fun genericContentDirectory(action: String, bodyText: String, req: HttpRequest): HttpResponse {
        val cds = UpnpXml.CDS_SERVICE_TYPE
        return when (action) {
            "GetTransferProgress" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds,
                "<TransferStatus>COMPLETED</TransferStatus><TransferLength>0</TransferLength><TransferTotal>0</TransferTotal>"))
            "DestroyObject", "StopTransferResource" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, ""))
            "GetSearchCapabilities" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, "<SearchCaps></SearchCaps>"))
            "GetSortCapabilities" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, "<SortCaps></SortCaps>"))
            "GetSystemUpdateID" -> HttpResponse.xml(UpnpXml.soapResponse(action, cds, "<Id>1</Id>"))
            else -> { log("Unknown CDS action '$action' from ${req.remoteAddress}"); HttpResponse.xml(UpnpXml.soapFault(401, "Invalid Action"), 500) }
        }
    }

    private fun connectionManager(req: HttpRequest): HttpResponse {
        val action = soapAction(req); req.readBodyText()
        log("CMS $action from ${req.remoteAddress}")
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
        log("CreateObject for ${file.name} (${file.size} bytes, ${file.mimeType}) from ${req.remoteAddress}")
        val started = System.nanoTime()
        val exists = sink.exists(file)
        val lookupMs = (System.nanoTime() - started) / 1_000_000
        if (lookupMs > 2000) log("Duplicate check for ${file.name} took ${lookupMs / 1000.0} s (slow storage?)")
        if (exists && fileExistsErrorCode != null) {
            stats.filesSkipped.incrementAndGet()
            log("Skip ${file.name} (already stored) -> UPnP error $fileExistsErrorCode")
            return HttpResponse.xml(UpnpXml.soapFault(fileExistsErrorCode, "File exists"), 500)
        }
        val parentId = "UP_${FileSystemSink.safeSegment(file.date)}"
        val objectId = parentId + "_" + ByteArray(5).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        pending[objectId] = file
        savePending()
        val importUri = "http://${req.localAddress}:${boundPort}${UpnpXml.UPLOAD_PATH}?didx=0_id=$objectId"
        val (mime, profile) = file.mimeType to dlnaProfileFor(file.mimeType)
        val didl = UpnpXml.createObjectDidl(objectId, parentId, mime, profile, file.size, importUri, file.upnpClass, resumable = spoolDir != null)
        log("Ready for ${file.name} (${file.size} bytes, ${file.mimeType}${if (exists) ", duplicate" else ""})")
        return HttpResponse.xml(UpnpXml.soapResponse("CreateObject", UpnpXml.CDS_SERVICE_TYPE,
            "<ObjectID>$objectId</ObjectID><Result>${UpnpXml.escape(didl)}</Result>"))
    }

    private fun upload(req: HttpRequest): HttpResponse {
        val objectId = req.query["didx"]?.substringAfter("id=") ?: req.query["id"]
        val file = objectId?.let { pending[it] }
        if (objectId == null || file == null) {
            log("Upload for unknown object '$objectId' from ${req.remoteAddress}")
            return HttpResponse.notFound()
        }
        log("Receiving ${file.name} (${if (req.contentLength >= 0) req.contentLength else "chunked"} bytes) from ${req.remoteAddress}")
        val part = partFile(objectId)
        if (part == null) { pending.remove(objectId); return storeStreaming(file, req) }
        return uploadResumable(objectId, file, part, req)
    }

    /** No spool: the body streams straight into the sink; a short body is the sink's problem (it rejects it). */
    private fun storeStreaming(file: IncomingFile, req: HttpRequest): HttpResponse {
        val effective = if (file.size < 0 && req.contentLength > 0) file.copy(size = req.contentLength) else file
        stats.lastFile = effective.name
        val started = System.nanoTime()
        val result = sink.store(effective, req.body)
        finish(effective, result, started)
        return HttpResponse.empty(200)
    }

    /**
     * With a spool: the body is appended to `<spool>/<objectId>.part` at the offset the camera's
     * `Content-Range` names (none = from the start); the sink only ever sees complete files.
     */
    private fun uploadResumable(objectId: String, file: IncomingFile, part: File, req: HttpRequest): HttpResponse {
        part.parentFile?.mkdirs()
        val have = if (part.isFile) part.length() else 0L
        val offset = req.header("content-range")?.let { Regex("bytes\\s*=?\\s*(\\d+)\\s*-").find(it)?.groupValues?.get(1)?.toLongOrNull() }
        when {
            offset == null && have > 0 -> log("Upload of ${file.name} starts over at 0 although $have bytes were spooled; request headers: ${req.headers.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
            offset != null && offset != have -> {
                log("Upload of ${file.name}: camera resumes at $offset but $have bytes are spooled -> 416")
                return HttpResponse(416, ByteArray(0), "text/plain", mapOf("Content-Range" to "bytes */${if (file.size >= 0) file.size.toString() else "*"}"))
            }
            offset != null -> log("Resuming ${file.name} at $offset of ${file.size} bytes")
        }
        val started = System.nanoTime()
        java.io.FileOutputStream(part, offset != null && offset > 0).use { out -> req.body.copyTo(out, 256 * 1024) }
        val total = part.length()
        val expected = if (file.size >= 0) file.size else total
        if (total < expected) {
            log("Upload of ${file.name} stopped at $total of $expected bytes; kept for the camera to resume")
            return HttpResponse(400, "incomplete".toByteArray(), "text/plain")
        }
        if (total > expected) {
            log("Upload of ${file.name}: $total bytes for a $expected-byte file, dropped")
            part.delete()
            return HttpResponse(400, "too much data".toByteArray(), "text/plain")
        }
        val effective = if (file.size < 0) file.copy(size = total) else file
        stats.lastFile = effective.name
        val result = part.inputStream().use { sink.store(effective, it) }
        part.delete(); pending.remove(objectId); savePending()
        finish(effective, result, started)
        return HttpResponse.empty(200)
    }

    private fun finish(effective: IncomingFile, result: StoreResult, startedNanos: Long) {
        val seconds = (System.nanoTime() - startedNanos) / 1e9
        when (result) {
            StoreResult.STORED -> { stats.filesStored.incrementAndGet(); stats.bytesStored.addAndGet(maxOf(effective.size, 0)); log("Stored ${effective.name} (${effective.size} bytes, ${rate(effective.size, seconds)})") }
            StoreResult.ALREADY_EXISTS -> { stats.filesSkipped.incrementAndGet(); log("Discarded duplicate ${effective.name}") }
        }
    }

    private fun partFile(objectId: String): File? = spoolDir?.let { File(it, "$objectId.part") }

    /** Bytes of [objectId] already in the spool (0 without a spool or a part). */
    fun uploadedBytes(objectId: String): Long = partFile(objectId)?.takeIf { it.isFile }?.length() ?: 0L

    /** Pending objects survive a restart (the camera asks for them next session); stale parts are dropped. */
    private fun restorePending(dir: File) {
        dir.mkdirs()
        val f = File(dir, "pending.tsv")
        if (f.isFile) f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 7) pending[p[0]] = IncomingFile(p[2], p[1], p[3].toLongOrNull() ?: -1L, p[4], p[5], p[6])
        }
        val weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000
        dir.listFiles { x -> x.name.endsWith(".part") }?.forEach { part ->
            val id = part.name.removeSuffix(".part")
            if (!pending.containsKey(id) || part.lastModified() < weekAgo) { part.delete(); pending.remove(id); log("Dropped stale partial upload ${part.name}") }
        }
        savePending()
        if (pending.isNotEmpty()) log("${pending.size} unfinished upload(s) waiting for the camera to resume")
    }

    private fun savePending() {
        val f = pendingFile ?: return
        synchronized(f) {
            f.writeText(pending.entries.sortedBy { it.key }.joinToString("") { (id, x) ->
                listOf(id, x.date, x.name, x.size.toString(), x.mimeType, x.upnpClass, x.cameraAddress).joinToString("\t", postfix = "\n")
            })
        }
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
        /** The one folder a Browse of the root shows; CreateObject's parentIDs ("UP_<date>") resolve to it too. */
        const val UPLOAD_CONTAINER = "UP"
        const val UPLOAD_TITLE = "Auto Backup"
        private val DATE_CONTAINER = Regex("UP_[0-9A-Za-z-]+")

        fun dlnaProfileFor(mime: String): String = when (mime.lowercase()) {
            "image/jpeg" -> "DLNA.ORG_PN=JPEG_LRG"
            "video/mp4" -> "DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC"
            else -> "*"
        }
    }
}
