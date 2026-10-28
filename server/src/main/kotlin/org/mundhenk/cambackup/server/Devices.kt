package org.mundhenk.cambackup.server

import java.io.File

/**
 * Who talks to this server: cameras (by address, from their sessions) and phones (by the nickname
 * the app sends as X-NX-Device). Every device can be given a name on the status page. Persisted
 * as `<data>/devices.tsv`, one line per device, last line per key wins.
 */
class Devices(private val file: File) {
    enum class Kind { CAMERA, PHONE }

    data class Device(
        val kind: Kind,
        val id: String,          // camera: IP address; phone: nickname from the app
        val address: String,     // last address seen
        val firstSeen: Long,
        val lastSeen: Long,
        val sessions: Int,
        val files: Int,
        val detail: String,      // e.g. the user agent
        val nickname: String = "",   // given on the status page; "" = none
    ) {
        val key get() = "${kind.name}/${id.lowercase()}"
        /** What the UI and the log call this device. */
        val displayName get() = nickname.ifBlank { id }
    }

    private val devices = LinkedHashMap<String, Device>()

    init {
        if (file.isFile) file.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 8) runCatching {
                val d = Device(Kind.valueOf(p[0]), p[1], p[2], p[3].toLong(), p[4].toLong(), p[5].toInt(), p[6].toInt(), p[7], p.getOrNull(8) ?: "")
                devices[d.key] = d
            }
        }
    }

    /** A session started (camera) or a phone announced itself. */
    @Synchronized
    fun seen(kind: Kind, id: String, address: String, detail: String? = null, session: Boolean = false, files: Int = 0) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        val old = devices["${kind.name}/${id.lowercase()}"]
        put(Device(kind, id, address, old?.firstSeen ?: now, now, (old?.sessions ?: 0) + (if (session) 1 else 0), (old?.files ?: 0) + files,
            detail?.takeIf { it.isNotBlank() } ?: old?.detail ?: "", old?.nickname ?: ""))
    }

    /** Names (or, with a blank [nickname], un-names) a device; unknown devices are ignored. */
    @Synchronized
    fun rename(kind: Kind, id: String, nickname: String): Device? {
        val old = devices["${kind.name}/${id.lowercase()}"] ?: return null
        return put(old.copy(nickname = nickname.trim().replace(Regex("[\\t\\r\\n]"), " ").take(60)))
    }

    @Synchronized
    fun find(kind: Kind, id: String): Device? = devices["${kind.name}/${id.lowercase()}"]

    /** The name to show for a camera address or phone id, falling back to the id itself. */
    fun displayName(kind: Kind, id: String): String = find(kind, id)?.displayName ?: id

    private fun put(d: Device): Device {
        devices[d.key] = d
        file.parentFile?.mkdirs()
        file.appendText(listOf(d.kind.name, d.id, d.address, d.firstSeen, d.lastSeen, d.sessions, d.files, d.detail.replace('\t', ' ').replace('\n', ' '), d.nickname).joinToString("\t", postfix = "\n"))
        return d
    }

    /** Newest activity first. */
    @Synchronized
    fun all(): List<Device> = devices.values.sortedByDescending { it.lastSeen }
}
