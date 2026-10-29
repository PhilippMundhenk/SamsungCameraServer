package org.mundhenk.cambackup.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.mundhenk.cambackup.core.BackupSink
import org.mundhenk.cambackup.core.FileSystemSink
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.Layout
import org.mundhenk.cambackup.core.StoreResult
import java.io.File
import java.io.IOException
import java.io.InputStream

object Sinks {
    /** Direct mode: SAF folder if chosen, else the app's own external files dir; [layout] defaults to the setting. */
    fun direct(context: Context, folder: Uri?, layout: String = Prefs(context).folderLayout): LocalSink =
        if (folder != null) SafSink(context, folder, layout) else FileSink(defaultFolder(context), context, layout)

    fun defaultFolder(context: Context): File =
        (context.getExternalFilesDir("CamBackup") ?: File(context.filesDir, "CamBackup")).apply { mkdirs() }
}

/** A sink that keeps the bytes on the phone and can hand them back for a later upload. */
interface LocalSink : BackupSink {
    /** Opens a stored file again, or null when it is gone. */
    fun open(date: String, name: String): InputStream?
    /** Where the file lives, for the UI. */
    fun describe(date: String, name: String): String
    /** A content URI other apps can open, or null when the file is gone. */
    fun uriOf(date: String, name: String): Uri?
    /** Bytes on the phone, -1 when the file is gone. */
    fun sizeOf(date: String, name: String): Long
    /** Removes a file (an incomplete one, so the camera sends it again). */
    fun delete(date: String, name: String): Boolean
}

/** App-private folder: <root>/<layout folder>/<name>. */
class FileSink(private val root: File, private val context: Context? = null, private val layout: String = Layout.DAY) : LocalSink {
    private val fs = FileSystemSink(root) { layout }
    override fun exists(file: IncomingFile) = fs.exists(file)
    override fun store(file: IncomingFile, input: InputStream) = fs.store(file, input)
    private fun target(date: String, name: String) = Layout.target(root, layout, date, name)
    override fun open(date: String, name: String): InputStream? = target(date, name).takeIf { it.isFile }?.inputStream()
    override fun describe(date: String, name: String) = target(date, name).path
    override fun uriOf(date: String, name: String): Uri? {
        val f = target(date, name).takeIf { it.isFile } ?: return null
        val c = context ?: return null
        return androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.files", f)
    }
    override fun sizeOf(date: String, name: String): Long = target(date, name).takeIf { it.isFile }?.length() ?: -1L
    override fun delete(date: String, name: String): Boolean = target(date, name).delete()
}

/** Writes into a Storage Access Framework tree: <tree>/<layout folders>/<name>. */
class SafSink(private val context: Context, private val treeUri: Uri, private val layout: String = Layout.DAY) : LocalSink {
    private val root: DocumentFile
        get() = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("folder not accessible: $treeUri")

    /** The folder for [date] according to the layout ("" = the tree itself), nested folders created on demand. */
    private fun dateDir(date: String, create: Boolean): DocumentFile? {
        var dir: DocumentFile = root
        for (segment in Layout.subfolder(layout, date).split(File.separatorChar).filter { it.isNotBlank() }) {
            dir = dir.findFile(segment)?.takeIf { it.isDirectory } ?: (if (create) dir.createDirectory(segment) else null) ?: return null
        }
        return dir
    }

    override fun exists(file: IncomingFile): Boolean {
        val f = dateDir(file.date, create = false)?.findFile(file.name) ?: return false
        return f.isFile && (file.size < 0 || f.length() == file.size)
    }

    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        if (exists(file)) {
            input.copyTo(NullOutputStream)
            return StoreResult.ALREADY_EXISTS
        }
        val dir = dateDir(file.date, create = true) ?: throw IOException("cannot create folder for ${file.date}")
        dir.findFile(file.name)?.delete() // partial from an earlier attempt
        val doc = dir.createFile(file.mimeType, file.name) ?: throw IOException("cannot create ${file.name}")
        val written = context.contentResolver.openOutputStream(doc.uri, "w")?.use { out -> input.copyTo(out, 256 * 1024) }
            ?: throw IOException("cannot open ${doc.uri}")
        if (file.size >= 0 && written != file.size) {
            // the camera (or the user, on the camera) stopped mid-file: keep nothing, the camera sends it again
            doc.delete()
            throw IOException("short upload of ${file.name}: $written of ${file.size} bytes")
        }
        return StoreResult.STORED
    }

    private fun find(date: String, name: String): DocumentFile? =
        try { dateDir(date, create = false)?.findFile(name)?.takeIf { it.isFile } } catch (e: IOException) { null }

    override fun open(date: String, name: String): InputStream? {
        val doc = try { dateDir(date, create = false)?.findFile(name)?.takeIf { it.isFile } } catch (e: IOException) { null } ?: return null
        return context.contentResolver.openInputStream(doc.uri)
    }

    override fun describe(date: String, name: String) =
        "$treeUri/" + Layout.subfolder(layout, date).replace(File.separatorChar, '/').let { if (it.isEmpty()) "" else "$it/" } + name
    override fun uriOf(date: String, name: String): Uri? = find(date, name)?.uri
    override fun sizeOf(date: String, name: String): Long = find(date, name)?.length() ?: -1L
    override fun delete(date: String, name: String): Boolean = find(date, name)?.delete() ?: false

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
