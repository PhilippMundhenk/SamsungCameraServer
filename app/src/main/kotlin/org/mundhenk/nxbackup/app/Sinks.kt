package org.mundhenk.nxbackup.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.mundhenk.nxbackup.core.BackupSink
import org.mundhenk.nxbackup.core.FileSystemSink
import org.mundhenk.nxbackup.core.IncomingFile
import org.mundhenk.nxbackup.core.StoreResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Sinks {
    /** Direct mode: SAF folder if chosen, else the app's own external files dir. */
    fun direct(context: Context, folder: Uri?): BackupSink =
        if (folder != null) SafSink(context, folder) else FileSystemSink(defaultFolder(context))

    fun defaultFolder(context: Context): File =
        (context.getExternalFilesDir("NXBackup") ?: File(context.filesDir, "NXBackup")).apply { mkdirs() }
}

/** Writes into a Storage Access Framework tree: <tree>/<date>/<name>. */
class SafSink(private val context: Context, private val treeUri: Uri) : BackupSink {
    private val root: DocumentFile
        get() = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("folder not accessible: $treeUri")

    private fun dateDir(file: IncomingFile, create: Boolean): DocumentFile? {
        val name = FileSystemSink.safeSegment(file.date)
        return root.findFile(name)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(name) else null
    }

    override fun exists(file: IncomingFile): Boolean {
        val f = dateDir(file, create = false)?.findFile(file.name) ?: return false
        return f.isFile && (file.size < 0 || f.length() == file.size)
    }

    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        if (exists(file)) {
            input.copyTo(NullOutputStream)
            return StoreResult.ALREADY_EXISTS
        }
        val dir = dateDir(file, create = true) ?: throw IOException("cannot create folder for ${file.date}")
        dir.findFile(file.name)?.delete() // partial from an earlier attempt
        val doc = dir.createFile(file.mimeType, file.name) ?: throw IOException("cannot create ${file.name}")
        context.contentResolver.openOutputStream(doc.uri, "w")?.use { out -> input.copyTo(out, 256 * 1024) }
            ?: throw IOException("cannot open ${doc.uri}")
        return StoreResult.STORED
    }

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}

/**
 * Backend mode: stream every file to the home server over plain HTTP.
 *
 * API contract (to be implemented by the backend):
 *   HEAD {base}/api/v1/files/{date}/{name}            -> 200 exists, 404 not
 *   PUT  {base}/api/v1/files/{date}/{name}  body=bytes -> 201 stored, 200 already existed
 *        headers: Content-Type, Content-Length, X-NX-Camera (ip), X-NX-Class (upnp class)
 *   POST {base}/api/v1/events  JSON {"type":"start"|"done","camera":"ip","sysOff":bool}
 */
class BackendSink(private val baseUrl: String, private val log: (String) -> Unit, private val token: String = "") : BackupSink {

    private fun HttpURLConnection.auth() { if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token") }

    private fun fileUrl(file: IncomingFile): URL {
        val d = URLEncoder.encode(FileSystemSink.safeSegment(file.date), "UTF-8")
        val n = URLEncoder.encode(file.name, "UTF-8")
        return URL("$baseUrl/api/v1/files/$d/$n")
    }

    override fun onBackupStart(cameraAddress: String) = event("start", cameraAddress, false)
    override fun onBackupDone(cameraAddress: String, sysOff: Boolean) = event("done", cameraAddress, sysOff)

    override fun exists(file: IncomingFile): Boolean = try {
        val c = fileUrl(file).openConnection() as HttpURLConnection
        c.requestMethod = "HEAD"; c.connectTimeout = 5000; c.readTimeout = 5000; c.auth()
        val code = c.responseCode; c.disconnect()
        if (code == 401) log("backend rejected the token (401) - check the backend token in the app and api.token on the server")
        code == 200
    } catch (e: IOException) { log("backend HEAD failed: ${e.message}"); false }

    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        val c = fileUrl(file).openConnection() as HttpURLConnection
        c.requestMethod = "PUT"
        c.auth()
        c.doOutput = true
        c.connectTimeout = 10_000
        c.readTimeout = 120_000
        if (file.size >= 0) c.setFixedLengthStreamingMode(file.size) else c.setChunkedStreamingMode(256 * 1024)
        c.setRequestProperty("Content-Type", file.mimeType)
        c.setRequestProperty("X-NX-Camera", file.cameraAddress)
        c.setRequestProperty("X-NX-Class", file.upnpClass)
        c.outputStream.use { out -> input.copyTo(out, 256 * 1024) }
        val code = c.responseCode
        (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes() }
        c.disconnect()
        return when (code) {
            201 -> StoreResult.STORED
            200 -> StoreResult.ALREADY_EXISTS
            401 -> throw IOException("backend PUT ${file.name}: 401 - wrong backend token")
            else -> throw IOException("backend PUT ${file.name}: HTTP $code")
        }
    }

    private fun event(type: String, camera: String, sysOff: Boolean) {
        try {
            val c = URL("$baseUrl/api/v1/events").openConnection() as HttpURLConnection
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 5000; c.readTimeout = 5000; c.auth()
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write("""{"type":"$type","camera":"$camera","sysOff":$sysOff}""".toByteArray()) }
            val code = c.responseCode; c.disconnect()
            if (code >= 400) log("backend event '$type' -> HTTP $code")
        } catch (e: IOException) { log("backend event '$type' failed: ${e.message}") }
    }
}
