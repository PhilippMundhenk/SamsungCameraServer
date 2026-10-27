package org.mundhenk.cambackup.core

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Answers the camera's SSDP M-SEARCH for a MediaServer and announces
 * ourselves periodically. The camera compares the USN's uuid with the UDN
 * from the pairing file, so the uuid here must be the identity's UDN.
 *
 * On Android the caller must hold a WifiManager.MulticastLock while this runs.
 */
class SsdpResponder(
    private val identity: ServerIdentity,
    private val httpPort: Int,
    private val log: (String) -> Unit = {},
    private val notifyIntervalMs: Long = 30_000,
    /** Restrict to one interface address (e.g. the hotspot). Null = all IPv4 interfaces. */
    private val bindAddress: InetAddress? = null,
    /** UDP port to listen on; only tests use anything but 1900. */
    private val ssdpPort: Int = SSDP_PORT,
) {
    private val running = AtomicBoolean(false)
    private var socket: MulticastSocket? = null
    private var listener: Thread? = null
    private var announcer: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        val sock = MulticastSocket(null)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(ssdpPort))
        sock.timeToLive = 2 // matches the camera's own TTL
        var joined = 0
        for (nif in interfaces()) {
            try { sock.joinGroup(InetSocketAddress(GROUP, ssdpPort), nif); joined++ } catch (e: IOException) { log("join ${nif.name} failed: ${e.message}") }
        }
        if (joined == 0) {
            try { sock.joinGroup(GROUP); joined++ } catch (e: IOException) { log("multicast join failed: $e") }
        }
        sock.soTimeout = 1000
        socket = sock
        log("SSDP listening (${joined} interface(s))")
        listener = thread(name = "cambackup-ssdp", isDaemon = true) { listenLoop(sock) }
        announcer = thread(name = "cambackup-ssdp-notify", isDaemon = true) { announceLoop() }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { sendNotify("ssdp:byebye") } catch (_: Exception) {}
        try { socket?.close() } catch (_: IOException) {}
        listener?.interrupt(); announcer?.interrupt()
    }

    private fun listenLoop(sock: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try { sock.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (e: IOException) { if (running.get()) log("ssdp recv: $e"); break }
            val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
            if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
            val st = headerValue(text, "ST") ?: continue
            if (!matches(st)) continue
            val from = packet.socketAddress as InetSocketAddress
            val local = bindAddress ?: localAddressFor(from.address) ?: continue
            val reply = searchResponse(local.hostAddress, st)
            try {
                DatagramSocket().use { it.send(DatagramPacket(reply, reply.size, from)) }
                log("SSDP reply to ${from.address.hostAddress} (ST=$st) via ${local.hostAddress}")
            } catch (e: IOException) { log("ssdp send: $e") }
        }
    }

    private fun announceLoop() {
        while (running.get()) {
            try { sendNotify("ssdp:alive") } catch (e: Exception) { log("ssdp notify: $e") }
            try { Thread.sleep(notifyIntervalMs) } catch (_: InterruptedException) { return }
        }
    }

    private fun matches(st: String): Boolean =
        st == "ssdp:all" || st == "upnp:rootdevice" || st == UpnpXml.MEDIA_SERVER_TYPE ||
            st == "uuid:${identity.udn}" || st.startsWith("urn:schemas-upnp-org:device:MediaServer")

    private fun usnFor(st: String): Pair<String, String> = when {
        st == "ssdp:all" || st.startsWith("urn:schemas-upnp-org:device:MediaServer") ->
            UpnpXml.MEDIA_SERVER_TYPE to "uuid:${identity.udn}::${UpnpXml.MEDIA_SERVER_TYPE}"
        st == "upnp:rootdevice" -> "upnp:rootdevice" to "uuid:${identity.udn}::upnp:rootdevice"
        else -> "uuid:${identity.udn}" to "uuid:${identity.udn}"
    }

    private fun searchResponse(ip: String, st: String): ByteArray {
        val (nt, usn) = usnFor(st)
        return listOf(
            "HTTP/1.1 200 OK",
            "CACHE-CONTROL: max-age=1800",
            "EXT:",
            "LOCATION: http://$ip:$httpPort${UpnpXml.DEVICE_DESC_PATH}",
            "SERVER: MS-Windows/XP UPnP/1.0 PROTOTYPE/1.0",
            "ST: $nt",
            "USN: $usn",
            "CONTENT-LENGTH: 0",
            "", "",
        ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
    }

    private fun sendNotify(nts: String) {
        val addresses = if (bindAddress != null) listOf(bindAddress) else interfaces().flatMap { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
        }
        for (addr in addresses) {
            for (st in listOf("upnp:rootdevice", "uuid:${identity.udn}", UpnpXml.MEDIA_SERVER_TYPE)) {
                val (nt, usn) = usnFor(st)
                val msg = listOf(
                    "NOTIFY * HTTP/1.1",
                    "HOST: ${GROUP.hostAddress}:$ssdpPort",
                    "CACHE-CONTROL: max-age=1800",
                    "LOCATION: http://${addr.hostAddress}:$httpPort${UpnpXml.DEVICE_DESC_PATH}",
                    "NT: $nt",
                    "NTS: $nts",
                    "USN: $usn",
                    "SERVER: MS-Windows/XP UPnP/1.0 PROTOTYPE/1.0",
                    "CONTENT-LENGTH: 0",
                    "", "",
                ).joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)
                MulticastSocket(null).use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress(addr, 0))
                    s.timeToLive = 2
                    s.send(DatagramPacket(msg, msg.size, GROUP, ssdpPort))
                }
            }
        }
    }

    private fun interfaces(): List<NetworkInterface> = try {
        NetworkInterface.getNetworkInterfaces().toList().filter { nif ->
            nif.isUp && !nif.isLoopback && nif.supportsMulticast() &&
                nif.inetAddresses.toList().any { it is Inet4Address } &&
                (bindAddress == null || nif.inetAddresses.toList().contains(bindAddress))
        }
    } catch (e: IOException) { log("interfaces: $e"); emptyList() }

    companion object {
        const val SSDP_PORT = 1900
        val GROUP: InetAddress = InetAddress.getByName("239.255.255.250")

        fun headerValue(message: String, name: String): String? =
            message.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()

        /** The local IPv4 address the OS would use to talk to [remote]. */
        fun localAddressFor(remote: InetAddress): InetAddress? = try {
            DatagramSocket().use { it.connect(remote, 9); it.localAddress.takeIf { a -> !a.isAnyLocalAddress } }
        } catch (_: Exception) { null }
    }
}
