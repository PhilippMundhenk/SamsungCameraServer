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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Answers the camera's SSDP M-SEARCH for a MediaServer and announces
 * ourselves periodically. The camera compares the USN's uuid with the UDN
 * from the pairing file, so the uuid here must be the identity's UDN.
 *
 * Networks come and go while we run (the phone's hotspot is usually switched on after the
 * server started), so the multicast group is (re)joined on every interface that shows up and
 * NOTIFYs go out on each interface with that interface's own address in LOCATION.
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
    /** Where the interfaces come from; tests swap this to simulate a hotspot coming up. */
    private val interfaceSource: () -> List<NetworkInterface> = { NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList() },
) {
    private val running = AtomicBoolean(false)
    private val ignoredSeen = ConcurrentHashMap<String, Long>()
    /** Interfaces (by name) the group is currently joined on. */
    private val joined = ConcurrentHashMap<String, NetworkInterface>()
    private val notifyFailed = ConcurrentHashMap.newKeySet<String>()
    /** No usable interface at start: joined on the OS default instead, until a real one shows up. */
    @Volatile private var fallbackJoined = false
    private var socket: MulticastSocket? = null
    private var listener: Thread? = null
    private var announcer: Thread? = null

    fun start() {
        if (running.getAndSet(true)) return
        val sock = MulticastSocket(null)
        sock.reuseAddress = true
        sock.bind(InetSocketAddress(ssdpPort))
        sock.timeToLive = 2 // matches the camera's own TTL
        socket = sock
        val initial = joinNewInterfaces(sock, announce = false)
        if (joined.isEmpty()) {
            try { sock.joinGroup(GROUP); fallbackJoined = true } catch (e: IOException) { log("multicast join failed: $e") }
        }
        sock.soTimeout = 1000
        log("SSDP listening (${joined.size} interface(s)${if (initial.isNotEmpty()) ": " + initial.joinToString(", ") else ""})")
        listener = thread(name = "cambackup-ssdp", isDaemon = true) { listenLoop(sock) }
        announcer = thread(name = "cambackup-ssdp-notify", isDaemon = true) { announceLoop(sock) }
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        try { sendNotify("ssdp:byebye") } catch (_: Exception) {}
        try { socket?.close() } catch (_: IOException) {}
        listener?.interrupt(); announcer?.interrupt()
    }

    /**
     * Joins the group on interfaces we are not on yet and forgets the ones that disappeared (so a
     * hotspot switched off and on again is joined afresh). Returns "name (address)" of the newly
     * joined ones; with [announce] each is also logged on its own.
     */
    private fun joinNewInterfaces(sock: MulticastSocket, announce: Boolean = true): List<String> {
        val current = interfaces()
        val names = current.map { it.name }.toSet()
        for ((gone, nif) in joined.filterKeys { it !in names }) {
            joined.remove(gone)
            // the OS may or may not have dropped the membership with the interface; make sure, so
            // the interface can be joined again when it comes back
            try { sock.leaveGroup(InetSocketAddress(GROUP, ssdpPort), nif) } catch (_: Exception) {}
            log("SSDP: interface $gone went away")
        }
        val added = ArrayList<String>()
        if (fallbackJoined && current.any { !joined.containsKey(it.name) }) {
            // the default-interface membership would clash with the per-interface one (EINVAL/EADDRINUSE)
            try { sock.leaveGroup(GROUP) } catch (_: IOException) {}
            fallbackJoined = false
        }
        for (nif in current) {
            if (joined.containsKey(nif.name)) continue
            val label = "${nif.name} (${nif.inetAddresses.toList().filterIsInstance<Inet4Address>().joinToString(", ") { it.hostAddress }})" +
                (if (runCatching { nif.supportsMulticast() }.getOrDefault(true)) "" else " [interface reports no multicast flag, trying anyway]")
            try {
                sock.joinGroup(InetSocketAddress(GROUP, ssdpPort), nif)
                joined[nif.name] = nif; added += label
                if (announce) log("SSDP now also listening on $label (the hotspot or a new network came up)")
            } catch (e: IOException) { log("join ${nif.name} failed: ${e.message}") }
        }
        return added
    }

    private fun listenLoop(sock: MulticastSocket) {
        val buf = ByteArray(2048)
        while (running.get()) {
            val packet = DatagramPacket(buf, buf.size)
            try { sock.receive(packet) } catch (_: SocketTimeoutException) { continue } catch (e: IOException) { if (running.get()) log("ssdp recv: $e"); break }
            val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
            if (!text.startsWith("M-SEARCH", ignoreCase = true)) continue
            val from = packet.socketAddress as InetSocketAddress
            val st = headerValue(text, "ST") ?: continue
            if (!matches(st)) {
                // Seen but not ours: logged once a minute per sender+target so a camera searching for
                // something unexpected shows up without the log drowning in Windows/HA chatter.
                val key = "${from.address.hostAddress}|$st"
                val now = System.currentTimeMillis()
                if (now - (ignoredSeen[key] ?: 0L) > IGNORED_LOG_INTERVAL_MS) {
                    ignoredSeen[key] = now
                    log("SSDP search from ${from.address.hostAddress} for '$st' ignored (not a MediaServer/rootdevice/our uuid)${headerValue(text, "USER-AGENT")?.let { " UA=$it" } ?: ""}")
                }
                continue
            }
            // LOCATION must carry an address the camera can reach: the one the OS would use towards
            // it, or failing that the interface that shares the camera's network (the hotspot).
            val local = bindAddress ?: localAddressFor(from.address) ?: addressOnSameNetwork(from.address, interfaces())
            if (local == null) {
                log("SSDP search from ${from.address.hostAddress} not answered: no local address on its network")
                continue
            }
            val reply = searchResponse(local.hostAddress, st)
            try {
                DatagramSocket().use { it.send(DatagramPacket(reply, reply.size, from)) }
                log("SSDP reply to ${from.address.hostAddress} (ST=$st) via ${local.hostAddress}")
            } catch (e: IOException) { log("ssdp send: $e") }
        }
    }

    /** Checks for new interfaces every few seconds and sends NOTIFY every [notifyIntervalMs]. */
    private fun announceLoop(sock: MulticastSocket) {
        var lastNotify = 0L
        while (running.get()) {
            joinNewInterfaces(sock)
            val now = System.currentTimeMillis()
            if (now - lastNotify >= notifyIntervalMs) {
                try { sendNotify("ssdp:alive") } catch (e: Exception) { log("ssdp notify: $e") }
                lastNotify = now
            }
            try { Thread.sleep(minOf(notifyIntervalMs, INTERFACE_CHECK_MS)) } catch (_: InterruptedException) { return }
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

    /** One NOTIFY per interface address, sent out on that interface so each network hears its own address. */
    private fun sendNotify(nts: String) {
        val targets: List<Pair<NetworkInterface?, InetAddress>> =
            if (bindAddress != null) listOf(interfaces().firstOrNull() to bindAddress)
            else interfaces().flatMap { nif -> nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { nif to it } }
        for ((nif, addr) in targets) {
            try {
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
                        if (nif != null) try { s.networkInterface = nif } catch (_: IOException) { /* bound address decides */ }
                        s.send(DatagramPacket(msg, msg.size, GROUP, ssdpPort))
                    }
                }
            } catch (e: IOException) {
                // an interface that cannot multicast (VPN, bridge): say so once, not every 30 s
                if (notifyFailed.add(nif?.name ?: addr.hostAddress)) log("ssdp notify via ${addr.hostAddress}: $e")
            }
        }
    }

    private fun interfaces(): List<NetworkInterface> = try {
        interfaceSource().filter { nif ->
            nif.isUp && !nif.isLoopback &&
                nif.inetAddresses.toList().any { it is Inet4Address } &&
                (bindAddress == null || nif.inetAddresses.toList().contains(bindAddress))
        }
    } catch (e: IOException) { log("interfaces: $e"); emptyList() }

    companion object {
        const val SSDP_PORT = 1900
        const val IGNORED_LOG_INTERVAL_MS = 60_000L
        /** How often newly appeared interfaces (the hotspot) are picked up. */
        const val INTERFACE_CHECK_MS = 5_000L
        val GROUP: InetAddress = InetAddress.getByName("239.255.255.250")

        fun headerValue(message: String, name: String): String? =
            message.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()

        /** The local IPv4 address the OS would use to talk to [remote]. */
        fun localAddressFor(remote: InetAddress): InetAddress? = try {
            DatagramSocket().use { it.connect(remote, 9); it.localAddress.takeIf { a -> !a.isAnyLocalAddress } }
        } catch (_: Exception) { null }

        /** The IPv4 address of the interface whose subnet contains [remote], if any. */
        fun addressOnSameNetwork(remote: InetAddress, interfaces: List<NetworkInterface>): InetAddress? {
            val r = (remote as? Inet4Address)?.address ?: return null
            for (nif in interfaces) for (ia in nif.interfaceAddresses) {
                val a = ia.address as? Inet4Address ?: continue
                val prefix = ia.networkPrefixLength.toInt()
                if (prefix !in 1..32) continue
                val mask = (-1L shl (32 - prefix)).toInt()
                if ((toInt(a.address) and mask) == (toInt(r) and mask)) return a
            }
            return null
        }

        private fun toInt(b: ByteArray): Int = ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255)
    }
}
