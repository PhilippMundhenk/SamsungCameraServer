package org.mundhenk.cambackup.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList

/** Protocol corner cases the loopback e2e test does not reach. */
class BackupServerTest {
    private class RecordingSink : BackupSink {
        val events = CopyOnWriteArrayList<String>()
        val stored = LinkedHashMap<String, ByteArray>()
        var existsAnswer = false
        override fun onBackupStart(cameraAddress: String) { events += "start" }
        override fun exists(file: IncomingFile) = existsAnswer
        override fun store(file: IncomingFile, input: InputStream): StoreResult {
            val bytes = input.readBytes()
            if (existsAnswer) return StoreResult.ALREADY_EXISTS
            stored[file.name] = bytes; events += "store:${file.name}:${file.date}:${file.size}:${file.mimeType}:${file.upnpClass}"
            return StoreResult.STORED
        }
        override fun onBackupDone(cameraAddress: String, sysOff: Boolean) { events += "done:$sysOff" }
    }

    private val log = CopyOnWriteArrayList<String>()
    private val sink = RecordingSink()
    private val identity = ServerIdentity.generate("[PC]Unit")
    private lateinit var server: BackupServer
    private val base get() = "http://127.0.0.1:${server.boundPort}"

    @Before
    fun setUp() {
        server = BackupServer(identity, sink, httpPort = 0, log = { log += it }, fileExistsErrorCode = 712, enableSsdp = false,
            fallback = { req -> HttpResponse(200, "fallback ${req.path}".toByteArray(), "text/plain") })
        server.start()
    }

    @After
    fun tearDown() = server.stop()

    private fun http(method: String, path: String, body: ByteArray? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> {
        val c = URL(base + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        c.disconnect()
        return code to text
    }

    private fun soap(action: String, args: String, service: String = UpnpXml.CDS_SERVICE_TYPE, control: String = UpnpXml.CDS_CONTROL_PATH): Pair<Int, String> {
        val env = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:$action xmlns:u="$service">$args</u:$action></s:Body></s:Envelope>"""
        return http("POST", control, env.toByteArray(), mapOf("SOAPACTION" to "\"$service#$action\"", "Content-Type" to "text/xml"))
    }

    @Test
    fun descriptionDocumentsAndEventing() {
        assertTrue(http("GET", UpnpXml.DEVICE_DESC_PATH).second.contains("<UDN>uuid:${identity.udn}</UDN>"))
        assertTrue(http("GET", UpnpXml.CDS_SCPD_PATH).second.contains("<name>X_BACKUP_START</name>"))
        assertTrue(http("GET", UpnpXml.CMS_SCPD_PATH).second.contains("<name>GetProtocolInfo</name>"))
        assertTrue(log.any { it.contains("fetched device description") })
        // SUBSCRIBE is answered with a dummy SID; UNSUBSCRIBE likewise (HttpURLConnection cannot send those verbs, go raw)
        val raw = java.net.Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("SUBSCRIBE /upnp/event/ContentDirectory1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        assertTrue(raw, raw.startsWith("HTTP/1.1 200") && raw.contains("SID: uuid:${identity.udn}-sub"))
        val raw2 = java.net.Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("UNSUBSCRIBE /upnp/event/ContentDirectory1 HTTP/1.1\r\n\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        assertTrue(raw2.startsWith("HTTP/1.1 200"))
        // everything else goes to the fallback handler
        assertEquals(200 to "fallback /api/v1/status", http("GET", "/api/v1/status"))
    }

    @Test
    fun genericContentDirectoryAndConnectionManagerActions() {
        assertTrue(soap("GetTransferProgress", "<TransferID>1</TransferID>").second.contains("<TransferStatus>COMPLETED</TransferStatus>"))
        assertTrue(soap("DestroyObject", "<ObjectID>x</ObjectID>").second.contains("DestroyObjectResponse"))
        assertTrue(soap("StopTransferResource", "<TransferID>1</TransferID>").second.contains("StopTransferResourceResponse"))
        assertTrue(soap("GetSearchCapabilities", "").second.contains("<SearchCaps></SearchCaps>"))
        assertTrue(soap("GetSortCapabilities", "").second.contains("<SortCaps></SortCaps>"))
        assertTrue(soap("GetSystemUpdateID", "").second.contains("<Id>1</Id>"))
        // Browse: root lists the upload folder; metadata of root / folder / any parentID we handed out; stale ids -> 701
        val rootChildren = soap("Browse", "<ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>").second
        assertTrue(rootChildren, rootChildren.contains("<NumberReturned>1</NumberReturned><TotalMatches>1</TotalMatches>") && rootChildren.contains("&lt;container id=&quot;UP&quot; parentID=&quot;0&quot;"))
        assertTrue(rootChildren.contains("Auto Backup") && rootChildren.contains("object.container.storageFolder") && rootChildren.contains("createClass"))
        val rootMeta = soap("Browse", "<ObjectID>0</ObjectID><BrowseFlag>BrowseMetadata</BrowseFlag>").second
        assertTrue(rootMeta, rootMeta.contains("&lt;container id=&quot;0&quot; parentID=&quot;-1&quot;") && rootMeta.contains("[PC]Unit"))
        val folderMeta = soap("Browse", "<ObjectID>UP_2026-10-27</ObjectID><BrowseFlag>BrowseMetadata</BrowseFlag>").second
        assertTrue(folderMeta, folderMeta.contains("&lt;container id=&quot;UP_2026-10-27&quot;") && folderMeta.contains("<TotalMatches>1</TotalMatches>"))
        assertTrue(soap("Browse", "<ObjectID>UP</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag>").second.contains("<TotalMatches>0</TotalMatches>"))
        val (staleCode, stale) = soap("Browse", "<ObjectID>UP_2021-06-07_7606e6afaf</ObjectID><BrowseFlag>BrowseMetadata</BrowseFlag>")
        assertEquals(500, staleCode); assertTrue(stale.contains("<errorCode>701</errorCode>"))
        assertTrue(log.toString(), log.any { it == "CDS Browse from 127.0.0.1 (ObjectID 0, BrowseDirectChildren) -> root" } && log.any { it == "CDS GetSystemUpdateID from 127.0.0.1" })
        assertTrue(log.any { it == "CDS Browse from 127.0.0.1 (ObjectID UP_2021-06-07_7606e6afaf, BrowseMetadata) -> unknown object, UPnP error 701" })
        // an object that still waits for its upload is browsable as the item it was created as
        val created = soap("CreateObject", "<ContainerID>DLNA.ORG_AnyContainer</ContainerID><Elements>" + UpnpXml.escape(
            """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="" parentID="DLNA.ORG_AnyContainer" restricted="0"><dc:title>B.JPG</dc:title><dc:date>2026-10-28</dc:date><upnp:class>object.item.imageItem</upnp:class><res protocolInfo="http-get:*:image/jpeg:*" size="7"></res></item></DIDL-Lite>""") + "</Elements>").second
        val objectId = Regex("<ObjectID>([^<]+)</ObjectID>").find(created)!!.groupValues[1]
        val pendingMeta = soap("Browse", "<ObjectID>$objectId</ObjectID><BrowseFlag>BrowseMetadata</BrowseFlag>").second
        assertTrue(pendingMeta, pendingMeta.contains("&lt;item id=&quot;$objectId&quot; parentID=&quot;UP_2026-10-28&quot;") && pendingMeta.contains("importUri=") && pendingMeta.contains("<TotalMatches>1</TotalMatches>"))
        assertTrue(log.any { it == "CDS Browse from 127.0.0.1 (ObjectID $objectId, BrowseMetadata) -> pending object" })
        val (code, fault) = soap("NoSuchAction", "")
        assertEquals(500, code); assertTrue(fault.contains("<errorCode>401</errorCode>"))
        assertTrue(log.any { it.contains("Unknown CDS action 'NoSuchAction'") })

        val cms = UpnpXml.CMS_SERVICE_TYPE; val ctl = UpnpXml.CMS_CONTROL_PATH
        assertTrue(soap("GetProtocolInfo", "", cms, ctl).second.contains("http-get:*:image/jpeg:*"))
        assertTrue(log.any { it == "CMS GetProtocolInfo from 127.0.0.1" })
        assertTrue(soap("GetCurrentConnectionIDs", "", cms, ctl).second.contains("<ConnectionIDs>0</ConnectionIDs>"))
        assertEquals(500, soap("GetCurrentConnectionInfo", "", cms, ctl).first)
    }

    /** An identity change (phone hands its UDN over) must not restart the HTTP side: the request that asks for it is still being answered. */
    @Test
    fun identityIsSwitchedLiveWithoutRestartingHttp() {
        val port = server.boundPort
        assertTrue(http("GET", UpnpXml.DEVICE_DESC_PATH).second.contains("<UDN>uuid:${identity.udn}</UDN>"))
        val other = ServerIdentity("4a682b0b-0361-dbae-6155-00000000beef", "[PC]Other", ServerIdentity.DEFAULT_MAC)
        server.changeIdentity(other)
        server.changeIdentity(other)                                   // no-op the second time
        assertEquals(other, server.identity)
        assertEquals(port, server.boundPort)
        val desc = http("GET", UpnpXml.DEVICE_DESC_PATH).second
        assertTrue(desc, desc.contains("<UDN>uuid:${other.udn}</UDN>") && desc.contains("<friendlyName>[PC]Other</friendlyName>"))
        assertEquals(1, log.count { it == "Backup server now advertises '[PC]Other' uuid:${other.udn}" })
        // the event subscription id follows too, and the session counters survive
        soap("X_BACKUP_START", "<UDN>uuid:${other.udn}</UDN><FileCnt>1</FileCnt><SysOff>0</SysOff>")
        assertEquals(1, server.stats.sessions.get())
        val raw = java.net.Socket("127.0.0.1", server.boundPort).use { s ->
            s.getOutputStream().write("SUBSCRIBE /upnp/event/ContentDirectory1 HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
        }
        assertTrue(raw, raw.contains("SID: uuid:${other.udn}-sub"))
    }

    /** The camera's announced file count is the first thing to look at when it sends nothing. */
    @Test
    fun sessionStartLogsTheAnnouncedFileCount() {
        soap("X_BACKUP_START", "<UDN>uuid:${identity.udn}</UDN><NumOfContents>3</NumOfContents><SystemOff>0</SystemOff>")
        assertTrue(log.toString(), log.any { it == "Backup session started by 127.0.0.1: 3 file(s) announced" })
        soap("X_BACKUP_START", "<UDN>uuid:${identity.udn}</UDN><FileCnt> 0 </FileCnt><SysOff>0</SysOff>")
        assertTrue(log.toString(), log.any { it.startsWith("Backup session started by 127.0.0.1: 0 files announced, the camera has nothing new to send") })
        soap("X_BACKUP_START", "<UDN>uuid:${identity.udn}</UDN>")
        assertTrue(log.toString(), log.any { it == "Backup session started by 127.0.0.1 (arguments: <UDN>uuid:${identity.udn}</UDN>)" })
        // namespaced arguments (some SOAP stacks prefix them) are understood too
        soap("X_BACKUP_START", "<u:UDN>uuid:${identity.udn}</u:UDN><u:FileCnt>7</u:FileCnt>")
        assertTrue(log.toString(), log.any { it == "Backup session started by 127.0.0.1: 7 file(s) announced" })
        assertEquals(4, server.stats.sessions.get())
        assertEquals(listOf("start", "start", "start", "start"), sink.events)
    }

    @Test
    fun createObjectValidationAndDuplicates() {
        // start/done bookkeeping with SysOff
        soap("X_BACKUP_START", "<UDN>uuid:${identity.udn}</UDN><FileCnt>1</FileCnt><SysOff>0</SysOff>")
        assertEquals(1, server.stats.sessions.get())
        assertEquals("127.0.0.1", server.stats.lastCamera)
        // unparsable DIDL -> UPnP fault 712
        val (badCode, badFault) = soap("CreateObject", "<ContainerID>x</ContainerID><Elements>&lt;not xml</Elements>")
        assertEquals(500, badCode); assertTrue(badFault.contains("<errorCode>712</errorCode>"))
        assertTrue(log.any { it.contains("unparsable DIDL") })
        // no Elements at all
        assertEquals(500, soap("CreateObject", "<ContainerID>x</ContainerID>").first)
        // duplicate with fileExistsErrorCode configured -> fault with that code, counted as skipped
        sink.existsAnswer = true
        val (dupCode, dupFault) = soap("CreateObject", "<ContainerID>x</ContainerID><Elements>${UpnpXml.escape(didl("SAM_0001.JPG"))}</Elements>")
        assertEquals(500, dupCode); assertTrue(dupFault.contains("<errorCode>712</errorCode>"))
        assertEquals(1, server.stats.filesSkipped.get())
        sink.existsAnswer = false
        soap("X_BACKUP_DONE", "<UDN>uuid:${identity.udn}</UDN><SysOff> 1 </SysOff>")
        assertEquals(listOf("start", "done:true"), sink.events)
    }

    @Test
    fun uploadWithDefaultsUnknownSizeAndAlternativeQuery() {
        // DIDL without title/date/class/size/protocolInfo: defaults apply, size comes from Content-Length
        val minimal = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="" restricted="0"><res></res></item></DIDL-Lite>"""
        val (code, body) = soap("CreateObject", "<ContainerID>x</ContainerID><Elements>${UpnpXml.escape(minimal)}</Elements>")
        assertEquals(200, code)
        val importUri = Regex("importUri=&quot;([^&]*)&quot;").find(body)!!.groupValues[1].replace("&amp;", "&")
        assertTrue(importUri, importUri.contains("?didx=0_id=UP_unknown-date_"))
        val objectId = importUri.substringAfter("id=")
        val bytes = ByteArray(1234) { it.toByte() }
        // the camera's own query form
        assertEquals(200, http("POST", "${UpnpXml.UPLOAD_PATH}?didx=0_id=$objectId", bytes).first)
        assertArrayEquals(bytes, sink.stored["unnamed"])
        assertTrue(sink.events.last(), sink.events.last() == "store:unnamed:unknown-date:1234:application/octet-stream:object.item")
        assertEquals(1234L, server.stats.bytesStored.get())
        assertEquals("unnamed", server.stats.lastFile)
        // same object again -> unknown (consumed), and the plain id= form is accepted too
        assertEquals(404, http("POST", "${UpnpXml.UPLOAD_PATH}?didx=0_id=$objectId", bytes).first)
        assertEquals(404, http("POST", "${UpnpXml.UPLOAD_PATH}?id=nope", bytes).first)
        assertEquals(404, http("POST", UpnpXml.UPLOAD_PATH, bytes).first)
        assertTrue(log.any { it.contains("Upload for unknown object") })
        // duplicate discovered at upload time (sink says exists only now) -> discarded, 200
        val (c2, b2) = soap("CreateObject", "<ContainerID>x</ContainerID><Elements>${UpnpXml.escape(didl("SAM_0002.JPG"))}</Elements>")
        assertEquals(200, c2)
        val id2 = Regex("importUri=&quot;([^&]*)&quot;").find(b2)!!.groupValues[1].replace("&amp;", "&").substringAfter("id=")
        sink.existsAnswer = true
        assertEquals(200, http("POST", "${UpnpXml.UPLOAD_PATH}?id=$id2", bytes).first)
        assertEquals(1, server.stats.filesSkipped.get())
        assertTrue(log.any { it.contains("Discarded duplicate SAM_0002.JPG") })
        assertFalse(sink.stored.containsKey("SAM_0002.JPG"))
    }

    @Test
    fun withoutFallbackUnknownPathsAre404() {
        val plain = BackupServer(identity, sink, httpPort = 0, log = { log += it }, enableSsdp = false).apply { start() }
        try {
            val c = URL("http://127.0.0.1:${plain.boundPort}/whatever").openConnection() as HttpURLConnection
            assertEquals(404, c.responseCode)
            assertTrue(log.any { it.contains("Unhandled GET /whatever") })
            assertEquals("DLNA.ORG_PN=JPEG_LRG", BackupServer.dlnaProfileFor("image/JPEG"))
            assertEquals("DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC", BackupServer.dlnaProfileFor("video/mp4"))
            assertEquals("*", BackupServer.dlnaProfileFor("image/x-samsung-srw"))
        } finally { plain.stop() }
    }

    private fun didl(name: String, size: Long = 10) =
        """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/"><item id="" restricted="0" parentID="DLNA.ORG_AnyContainer"><dc:title>$name</dc:title><dc:date>2026-10-27</dc:date><upnp:class>object.item.imageItem</upnp:class><res protocolInfo="*:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG" size="$size"></res></item></DIDL-Lite>"""

    @Suppress("unused")
    private fun ByteArrayOutputStream.text() = toString("UTF-8")
}
