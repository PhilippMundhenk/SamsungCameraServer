package org.mundhenk.nxbackup.server

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** stdout + in-memory ring buffer shown in the web UI. */
object ServerLog {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Synchronized
    fun log(message: String) {
        val line = "${LocalDateTime.now().format(fmt)} $message"
        println(line)
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
