package org.mundhenk.nxbackup.core

import java.io.File
import java.io.InputStream

/** Metadata the camera sends in CreateObject before uploading a file. */
data class IncomingFile(
    val name: String,          // e.g. SAM_0001.JPG / SAM_0001.SRW / SAM_0001.MP4
    val date: String,          // dc:date as sent by camera, e.g. 2012-01-01
    val size: Long,            // bytes, -1 if unknown
    val mimeType: String,      // from protocolInfo, e.g. image/jpeg
    val upnpClass: String,     // e.g. object.item.imageItem
    val cameraAddress: String, // IP of the camera
)

enum class StoreResult { STORED, ALREADY_EXISTS }

/**
 * Where received files go. Implementations: filesystem (JVM tests), SAF folder
 * (Android direct mode), HTTP backend (Android backend mode).
 */
interface BackupSink {
    /** Called on X_BACKUP_START. */
    fun onBackupStart(cameraAddress: String) {}

    /**
     * Return ALREADY_EXISTS to make the server answer CreateObject with the
     * UPnP "file exists" error so the camera marks it done and moves on.
     */
    fun exists(file: IncomingFile): Boolean = false

    /** Consume exactly [file].size bytes (or until EOF if size < 0) from [input]. */
    fun store(file: IncomingFile, input: InputStream): StoreResult

    /** Called on X_BACKUP_DONE. [sysOff] is the camera's "shut down PC" flag. */
    fun onBackupDone(cameraAddress: String, sysOff: Boolean) {}
}

/** Simple filesystem sink: <root>/<date>/<name>. Dedup by name + size. */
class FileSystemSink(private val root: File) : BackupSink {
    override fun exists(file: IncomingFile): Boolean {
        val f = target(file)
        return f.isFile && (file.size < 0 || f.length() == file.size)
    }

    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        if (exists(file)) {
            input.skipAll()
            return StoreResult.ALREADY_EXISTS
        }
        val f = target(file)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".part")
        tmp.outputStream().use { out -> input.copyTo(out) }
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true); tmp.delete()
        }
        return StoreResult.STORED
    }

    private fun target(file: IncomingFile): File =
        File(File(root, safeSegment(file.date)), safeSegment(file.name))

    companion object {
        fun safeSegment(s: String): String =
            s.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "unknown" }
    }
}

internal fun InputStream.skipAll() {
    val buf = ByteArray(64 * 1024)
    while (read(buf) >= 0) { /* drain */ }
}
