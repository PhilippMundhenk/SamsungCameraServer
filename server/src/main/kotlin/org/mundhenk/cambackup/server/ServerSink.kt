package org.mundhenk.cambackup.server

import org.mundhenk.cambackup.core.BackupSink
import org.mundhenk.cambackup.core.FileSystemSink
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.StoreResult
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * The home server's sink. Files land in `<storage>/<layout folder>/<name>` (written as `.part`
 * first), are recorded in the [Ledger] and handed to the [Pipeline]. Used both by the
 * UPnP path (camera talks to us directly) and the backend API (phone forwards files),
 * so dedup and post-processing are identical no matter how a file arrived.
 * [layout] is a [org.mundhenk.cambackup.core.Layout] pattern ("" = flat).
 */
class ServerSink(
    private val storage: File,
    private val ledger: Ledger,
    private val pipeline: Pipeline,
    private val log: (String) -> Unit,
    private val layout: String = org.mundhenk.cambackup.core.Layout.DEFAULT,
) : BackupSink {
    private val sessions = ConcurrentHashMap<String, Session>()

    fun session(camera: String): Session = sessions.getOrPut(camera) { Session(camera) }
    fun activeSessions(): Map<String, Session> = sessions.toMap()

    override fun onBackupStart(cameraAddress: String) {
        sessions[cameraAddress] = Session(cameraAddress)
    }

    override fun exists(file: IncomingFile): Boolean {
        if (ledger.contains(file.date, file.name, file.size)) return true
        val f = target(file)
        return f.isFile && (file.size < 0 || f.length() == file.size)
    }

    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        val session = session(file.cameraAddress)
        if (exists(file)) {
            drain(input)
            session.skipped++
            return StoreResult.ALREADY_EXISTS
        }
        // a different file with the same name (the camera's counter wraps, or a flat layout):
        // never overwrite, number the newcomer
        val f = unique(target(file))
        if (f.name != FileSystemSink.safeSegment(file.name)) log("${file.name} already exists with different content, storing as ${f.name}")
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        var written = 0L
        tmp.outputStream().buffered(256 * 1024).use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = input.read(buf); if (n < 0) break
                out.write(buf, 0, n); written += n
            }
        }
        if (file.size >= 0 && written != file.size) {
            tmp.delete()
            throw java.io.IOException("short upload of ${file.name}: $written of ${file.size} bytes")
        }
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
        ledger.record(file.date, file.name, written, f)
        session.stored++
        val pf = ProcessedFile(file.date, file.name, written, file.cameraAddress, f)
        synchronized(session.files) { session.files += pf }
        pipeline.submitFile(pf)
        return StoreResult.STORED
    }

    override fun onBackupDone(cameraAddress: String, sysOff: Boolean) {
        val session = sessions.remove(cameraAddress) ?: Session(cameraAddress)
        log("Session from $cameraAddress: ${session.stored} stored, ${session.skipped} skipped" + if (sysOff) ", camera asks for shutdown" else "")
        pipeline.submitSessionDone(session, sysOff)
    }

    private fun target(file: IncomingFile): File =
        org.mundhenk.cambackup.core.Layout.target(storage, layout, file.date, file.name)

    private fun unique(target: File): File {
        if (!target.exists()) return target
        val base = target.nameWithoutExtension; val ext = target.extension.let { if (it.isEmpty()) "" else ".$it" }
        var i = 1
        while (true) {
            val c = File(target.parentFile, "${base}_$i$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(64 * 1024)
        while (input.read(buf) >= 0) { /* discard */ }
    }
}
