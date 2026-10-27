package org.mundhenk.cambackup.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

class SsdpTest {
    private val identity = ServerIdentity.generate("[PC]Ssdp")
    private val loopback: InetAddress = InetAddress.getLoopbackAddress()

    private fun freeUdpPort(): Int = DatagramSocket(0).use { it.localPort }

    private fun search(port: Int, st: String, timeoutMs: Int = 2000, prefix: String = "M-SEARCH * HTTP/1.1"): String? {
        val msg = "$prefix\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: $st\r\n\r\n".toByteArray()
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            s.send(DatagramPacket(msg, msg.size, InetSocketAddress(loopback, port)))
            val p = DatagramPacket(ByteArray(2048), 2048)
            return try { s.receive(p); String(p.data, 0, p.length, Charsets.ISO_8859_1) } catch (_: java.net.SocketTimeoutException) { null }
        }
    }

    @Test
    fun answersMatchingSearchTargetsWithLocationAndUsn() {
        val port = freeUdpPort()
        val log = CopyOnWriteArrayList<String>()
        val r = SsdpResponder(identity, 51234, { log += it }, notifyIntervalMs = 150, bindAddress = loopback, ssdpPort = port)
        r.start(); r.start()
        try {
            val all = search(port, "ssdp:all")
            assertNotNull("no reply for ssdp:all; log=$log", all)
            assertTrue(all!!, all.startsWith("HTTP/1.1 200 OK") && all.contains("LOCATION: http://127.0.0.1:51234${UpnpXml.DEVICE_DESC_PATH}"))
            assertTrue(all.contains("USN: uuid:${identity.udn}::${UpnpXml.MEDIA_SERVER_TYPE}"))
            val root = search(port, "upnp:rootdevice")!!
            assertTrue(root, root.contains("ST: upnp:rootdevice") && root.contains("USN: uuid:${identity.udn}::upnp:rootdevice"))
            val byUuid = search(port, "uuid:${identity.udn}")!!
            assertTrue(byUuid, byUuid.contains("USN: uuid:${identity.udn}\r\n"))
            val ms = search(port, UpnpXml.MEDIA_SERVER_TYPE)!!
            assertTrue(ms.contains("ST: ${UpnpXml.MEDIA_SERVER_TYPE}"))
            // the simulator's discover() does the same thing
            val loc = CameraSimulator.discover(2000, { log += it }, loopback, port)
            assertEquals("http://127.0.0.1:51234${UpnpXml.DEVICE_DESC_PATH}", loc)
            // ignored: other search targets, non-M-SEARCH packets, packets without ST
            assertNull(search(port, "urn:schemas-upnp-org:device:MediaRenderer:1", 700))
            assertNull(search(port, "ssdp:all", 700, prefix = "NOTIFY * HTTP/1.1"))
            val noSt = "M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\n\r\n".toByteArray()
            DatagramSocket().use { s ->
                s.soTimeout = 700
                s.send(DatagramPacket(noSt, noSt.size, InetSocketAddress(loopback, port)))
                try { s.receive(DatagramPacket(ByteArray(64), 64)); throw AssertionError("unexpected reply") } catch (_: java.net.SocketTimeoutException) {}
            }
            Thread.sleep(400) // let the announcer loop run a few NOTIFY rounds
            assertTrue(log.any { it.startsWith("SSDP listening") })
            assertTrue(log.any { it.contains("SSDP reply to 127.0.0.1") })
        } finally { r.stop(); r.stop() }
    }

    @Test
    fun unboundResponderPicksTheLocalAddressForTheCaller() {
        val port = freeUdpPort()
        val r = SsdpResponder(identity, 52235, notifyIntervalMs = 60_000, ssdpPort = port)
        r.start()
        try {
            val reply = search(port, UpnpXml.MEDIA_SERVER_TYPE)
            assertNotNull(reply)
            assertTrue(reply!!, reply.contains("LOCATION: http://127.0.0.1:52235/"))
        } finally { r.stop() }
    }

    @Test
    fun helpers() {
        assertEquals("http://x/y", SsdpResponder.headerValue("HTTP/1.1 200 OK\r\nlocation: http://x/y\r\nST: a", "LOCATION"))
        assertNull(SsdpResponder.headerValue("HTTP/1.1 200 OK\r\n", "LOCATION"))
        assertEquals(loopback.hostAddress, SsdpResponder.localAddressFor(loopback)?.hostAddress)
        assertEquals(1900, SsdpResponder.SSDP_PORT)
        assertEquals("239.255.255.250", SsdpResponder.GROUP.hostAddress)
        assertNull(CameraSimulator.discover(300, {}, loopback, freeUdpPort()))
    }
}
