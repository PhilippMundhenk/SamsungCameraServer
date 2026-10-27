package org.mundhenk.cambackup.app

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * A minimal Storage Access Framework provider backed by a directory in the test app's cache,
 * so the SAF code path (SafSink, folder picking) runs on the emulator without the system picker.
 * Document ids are paths relative to [root]; the tree root has id "root".
 */
class TestDocumentsProvider : DocumentsProvider() {
    companion object {
        const val AUTHORITY = "org.mundhenk.cambackup.test.documents"
        lateinit var root: File
        fun treeUri() = android.provider.DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root")
        fun documentUri(relative: String) = android.provider.DocumentsContract.buildDocumentUri(AUTHORITY, relative)
    }

    private val rootCols = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_DOCUMENT_ID)
    private val docCols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_FLAGS, Document.COLUMN_LAST_MODIFIED)

    override fun onCreate(): Boolean {
        root = File(context!!.cacheDir, "saf-root").apply { mkdirs() }
        return true
    }

    private fun fileFor(id: String): File = if (id == "root") root else File(root, id)
    private fun idFor(f: File): String = if (f == root) "root" else f.relativeTo(root).path.replace('\\', '/')

    private fun MatrixCursor.addFile(f: File) {
        val mime = if (f.isDirectory) Document.MIME_TYPE_DIR else Uploader.mimeFor(f.name)
        val flags = if (f.isDirectory) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE
        newRow().add(Document.COLUMN_DOCUMENT_ID, idFor(f)).add(Document.COLUMN_DISPLAY_NAME, if (f == root) "SAF test" else f.name)
            .add(Document.COLUMN_MIME_TYPE, mime).add(Document.COLUMN_SIZE, f.length()).add(Document.COLUMN_FLAGS, flags).add(Document.COLUMN_LAST_MODIFIED, f.lastModified())
    }

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(rootCols).apply {
        newRow().add(Root.COLUMN_ROOT_ID, "root").add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE).add(Root.COLUMN_TITLE, "SAF test").add(Root.COLUMN_DOCUMENT_ID, "root")
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = MatrixCursor(docCols).apply {
        val f = fileFor(documentId)
        if (!f.exists()) throw FileNotFoundException(documentId)
        addFile(f)
    }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor = MatrixCursor(docCols).apply {
        fileFor(parentDocumentId).listFiles()?.sortedBy { it.name }?.forEach { addFile(it) }
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val f = File(fileFor(parentDocumentId), displayName)
        if (mimeType == Document.MIME_TYPE_DIR) f.mkdirs() else { f.parentFile?.mkdirs(); f.createNewFile() }
        return idFor(f)
    }

    override fun deleteDocument(documentId: String) { fileFor(documentId).deleteRecursively() }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        fileFor(documentId).canonicalPath.startsWith(fileFor(parentDocumentId).canonicalPath)

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val f = fileFor(documentId)
        if (!f.exists() && mode.contains('w')) { f.parentFile?.mkdirs(); f.createNewFile() }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }
}
