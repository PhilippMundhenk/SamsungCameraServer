package org.mundhenk.cambackup.server

import java.io.File

/**
 * Who talks to this server: cameras (by address, from their sessions) and phones (by the nickname
 * the app sends as X-NX-Device). Persisted as `<data>/devices.tsv`, one line per device, last
 * line per key wins. Shown on the status page so a household can see which device did what.
 */
class Devices(private val file: File) {
    enum class Kind { CAMERA, PHONE }

    data class Device(
        val kind: Kind,
        val id: String,          // camera: IP address; phone: nickname
        val address: String,     // last address seen
        val firstSeen: Long,
        val lastSeen: Long,
        val sessions: Int,
        val files: Int,
        val detail: String,      // e.g. the user agent
    ) { val key get() = "${kind.name}/${id.lowercase()}" }

    private val devices = LinkedHashMap<String, Device>()

    init {
        if (file.isFile) file.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 8) runCatching {
                val d = Device(Kind.valueOf(p[0]), p[1], p[2], p[3].toLong(), p[4].toLong(), p[5].toInt(), p[6].toInt(), p[7])
                devices[d.key] = d
            }
        }
    }

    /** A session started (camera) or a phone announced itself. */
    @Synchronized
    fun seen(kind: Kind, id: String, address: String, detail: String? = null, session: Boolean = false, files: Int = 0) {
        if (id.isBlank()) return
        val now = System.currentTimeMillis()
        val key = "${kind.name}/${id.lowercase()}"
        val old = devices[key]
        val d = Device(kind, id, address, old?.firstSeen ?: now, now, (old?.sessions ?: 0) + (if (session) 1 else 0), (old?.files ?: 0) + files,
            detail?.takeIf { it.isNotBlank() } ?: old?.detail ?: "")
        devices[key] = d
        file.parentFile?.mkdirs()
        file.appendText(listOf(d.kind.name, d.id, d.address, d.firstSeen, d.lastSeen, d.sessions, d.files, d.detail.replace('\t', ' ').replace('\n', ' ')).joinToString("\t", postfix = "\n"))
    }

    /** Newest activity first. */
    @Synchronized
    fun all(): List<Device> = devices.values.sortedByDescending { it.lastSeen }
}
