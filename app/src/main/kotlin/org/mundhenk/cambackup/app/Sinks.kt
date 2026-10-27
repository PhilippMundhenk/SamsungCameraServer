package org.mundhenk.cambackup.app

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.mundhenk.cambackup.core.BackupSink
import org.mundhenk.cambackup.core.FileSystemSink
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.StoreResult
import java.io.File
import java.io.IOException
import java.io.InputStream

object Sinks {
    /** Direct mode: SAF folder if chosen, else the app's own external files dir. */
    fun direct(context: Context, folder: Uri?): LocalSink =
        if (folder != null) SafSink(context, folder) else FileSink(defaultFolder(context))

    fun defaultFolder(context: Context): File =
        (context.getExternalFilesDir("CamBackup") ?: File(context.filesDir, "CamBackup")).apply { mkdirs() }
}

/** A sink that keeps the bytes on the phone and can hand them back for a later upload. */
interface LocalSink : BackupSink {
    /** Opens a stored file again, or null when it is gone. */
    fun open(date: String, name: String): InputStream?
    /** Where the file lives, for the UI. */
    fun describe(date: String, name: String): String
}

/** App-private folder: <root>/<date>/<name>. */
class FileSink(private val root: File) : LocalSink {
    private val fs = FileSystemSink(root)
    override fun exists(file: IncomingFile) = fs.exists(file)
    override fun store(file: IncomingFile, input: InputStream) = fs.store(file, input)
    private fun target(date: String, name: String) = File(File(root, FileSystemSink.safeSegment(date)), FileSystemSink.safeSegment(name))
    override fun open(date: String, name: String): InputStream? = target(date, name).takeIf { it.isFile }?.inputStream()
    override fun describe(date: String, name: String) = target(date, name).path
}

/** Writes into a Storage Access Framework tree: <tree>/<date>/<name>. */
class SafSink(private val context: Context, private val treeUri: Uri) : LocalSink {
    private val root: DocumentFile
        get() = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("folder not accessible: $treeUri")

    private fun dateDir(date: String, create: Boolean): DocumentFile? {
        val name = FileSystemSink.safeSegment(date)
        return root.findFile(name)?.takeIf { it.isDirectory } ?: if (create) root.createDirectory(name) else null
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
        context.contentResolver.openOutputStream(doc.uri, "w")?.use { out -> input.copyTo(out, 256 * 1024) }
            ?: throw IOException("cannot open ${doc.uri}")
        return StoreResult.STORED
    }

    override fun open(date: String, name: String): InputStream? {
        val doc = try { dateDir(date, create = false)?.findFile(name)?.takeIf { it.isFile } } catch (e: IOException) { null } ?: return null
        return context.contentResolver.openInputStream(doc.uri)
    }

    override fun describe(date: String, name: String) = "$treeUri/${FileSystemSink.safeSegment(date)}/$name"

    private object NullOutputStream : java.io.OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }
}
