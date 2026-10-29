package org.mundhenk.cambackup.core

import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Behaves like the camera's Auto Backup client, built from the NX1000
 * firmware strings and pc-autobackup packet logs. Used by the JVM e2e test,
 * the Android instrumented test, and for manual testing against the app.
 */
class CameraSimulator(
    private val serverHost: String,
    private val serverPort: Int = BackupServer.DEFAULT_HTTP_PORT,
    private val log: (String) -> Unit = {},
) {
    data class SimFile(val name: String, val date: String, val mime: String, val bytes: ByteArray) {
        val upnpClass: String get() = if (mime.startsWith("video/")) "object.item.videoItem" else "object.item.imageItem"
    }

    data class Result(val udn: String, val friendlyName: String, val uploaded: List<String>)

    /** Full session: description -> START -> files -> DONE. */
    fun runBackup(files: List<SimFile>, sysOff: Boolean = false): Result {
        val desc = get("http://$serverHost:$serverPort${UpnpXml.DEVICE_DESC_PATH}")
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(ByteArrayInputStream(desc.toByteArray()))
        val udn = doc.getElementsByTagNameNS("*", "UDN").item(0).textContent.removePrefix("uuid:")
        val name = doc.getElementsByTagNameNS("*", "friendlyName").item(0).textContent
        log("Found server '$name' uuid:$udn")

        soap("X_BACKUP_START", "<UDN>uuid:$udn</UDN><NumOfContents>${files.size}</NumOfContents><SystemOff>${if (sysOff) 1 else 0}</SystemOff>")
        // like the NX1000 on its second session: ask about the last object of the previous session (long gone)
        val (gone, goneBody) = soapRaw("Browse", "<ObjectID>UP_2021-06-07_7606e6afaf</ObjectID><BrowseFlag>BrowseMetadata</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria>")
        check(gone == 500 && goneBody.contains("<errorCode>701</errorCode>")) { "Browse of a stale object must be UPnP error 701, got HTTP $gone: $goneBody" }
        val browsed = soap("Browse", "<ObjectID>0</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>0</RequestedCount><SortCriteria></SortCriteria>")
        check(browsed.contains("&lt;container ")) { "Browse of the root returned no container: $browsed" }
        val uploaded = ArrayList<String>()
        for (f in files) {
            val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/"><item id="" restricted="0" parentID="DLNA.ORG_AnyContainer"><dc:title>${UpnpXml.escape(f.name)}</dc:title><dc:date>${f.date}</dc:date><upnp:class>${f.upnpClass}</upnp:class><res protocolInfo="*:*:${f.mime}:${BackupServer.dlnaProfileFor(f.mime)};DLNA.ORG_CI=0" size="${f.bytes.size}"></res></item></DIDL-Lite>"""
            val (code, body) = soapRaw("CreateObject", "<ContainerID>DLNA.ORG_AnyContainer</ContainerID><Elements>${UpnpXml.escape(didl)}</Elements>")
            if (code != 200) { log("CreateObject for ${f.name} refused: HTTP $code"); continue }
            val importUri = Regex("importUri=&quot;([^&]*)&quot;").find(body)?.groupValues?.get(1)
                ?: Regex("importUri=\"([^\"]*)\"").find(body)?.groupValues?.get(1)
                ?: error("no importUri in CreateObject response: $body")
            val uri = importUri.replace("&amp;", "&")
            log("Uploading ${f.name} to $uri")
            val conn = URL(uri).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(f.bytes.size)
            conn.setRequestProperty("Content-Type", f.mime)
            conn.setRequestProperty("User-Agent", "DLNADOC/1.50 SEC_HHP_[Camera]NX1000/1.0")
            conn.outputStream.use { it.write(f.bytes) }
            val rc = conn.responseCode
            conn.inputStream.use { it.readBytes() }
            if (rc == 200) uploaded += f.name else log("Upload of ${f.name} failed: HTTP $rc")
        }
        soap("X_BACKUP_DONE", "<UDN>uuid:$udn</UDN><SystemOff>${if (sysOff) 1 else 0}</SystemOff>")
        return Result(udn, name, uploaded)
    }

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", "DLNADOC/1.50 SEC_HHP_[Camera]NX1000/1.0")
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun soap(action: String, args: String): String {
        val (code, body) = soapRaw(action, args)
        check(code == 200) { "$action failed: HTTP $code $body" }
        return body
    }

    private fun soapRaw(action: String, args: String): Pair<Int, String> {
        val envelope = """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body><u:$action xmlns:u="${UpnpXml.CDS_SERVICE_TYPE}">$args</u:$action></s:Body></s:Envelope>"""
        val conn = URL("http://$serverHost:$serverPort${UpnpXml.CDS_CONTROL_PATH}").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"${UpnpXml.CDS_SERVICE_TYPE}#$action\"")
        conn.setRequestProperty("User-Agent", "DLNADOC/1.50 SEC_HHP_[Camera]NX1000/1.0")
        conn.outputStream.use { it.write(envelope.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        val stream = if (code < 400) conn.inputStream else conn.errorStream
        val body = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        return code to body
    }

    companion object {
        /** Send an SSDP M-SEARCH and return the LOCATION of the first MediaServer answering. */
        fun discover(timeoutMs: Int = 3000, log: (String) -> Unit = {}, host: InetAddress = SsdpResponder.GROUP, port: Int = SsdpResponder.SSDP_PORT): String? {
            val msg = listOf(
                "M-SEARCH * HTTP/1.1", "HOST: 239.255.255.250:1900", "MAN: \"ssdp:discover\"",
                "MX: 2", "ST: ${UpnpXml.MEDIA_SERVER_TYPE}", "", "",
            ).joinToString("\r\n").toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = timeoutMs
                s.send(DatagramPacket(msg, msg.size, InetSocketAddress(host, port)))
                val buf = ByteArray(2048)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val p = DatagramPacket(buf, buf.size)
                    try { s.receive(p) } catch (_: SocketTimeoutException) { return null }
                    val text = String(p.data, 0, p.length, Charsets.ISO_8859_1)
                    val loc = SsdpResponder.headerValue(text, "LOCATION")
                    log("SSDP answer from ${p.address.hostAddress}: $loc")
                    if (loc != null) return loc
                }
            }
            return null
        }
    }
}
