package org.mundhenk.cambackup.app

import android.content.Context
import org.mundhenk.cambackup.core.BackendSink
import org.mundhenk.cambackup.core.BackupSink
import org.mundhenk.cambackup.core.IncomingFile
import org.mundhenk.cambackup.core.StoreResult
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps the real sink: every stored file lands in [Transfers] with the right check mark, and the
 * end of a session can trigger the deferred upload (phone + server mode).
 */
class RecordingSink(
    private val inner: BackupSink,
    private val state: TransferState,
    private val locate: (date: String, name: String) -> String,
    private val afterSession: () -> Unit = {},
) : BackupSink {
    override fun onBackupStart(cameraAddress: String) = inner.onBackupStart(cameraAddress)
    override fun exists(file: IncomingFile) = inner.exists(file)
    override fun store(file: IncomingFile, input: InputStream): StoreResult {
        val r = inner.store(file, input)
        if (r == StoreResult.STORED) Transfers.record(file.date, file.name, file.size, state, locate(file.date, file.name))
        return r
    }
    override fun onBackupDone(cameraAddress: String, sysOff: Boolean) {
        inner.onBackupDone(cameraAddress, sysOff)
        afterSession()
    }
}

/** Uploads files that are only on the phone (✓) to the home server (✓✓). One run at a time. */
object Uploader {
    private val busy = AtomicBoolean(false)
    @Volatile var lastResult: String = ""
        private set

    val running: Boolean get() = busy.get()

    /**
     * Starts a background run; false when one is already running. The summary line is logged only
     * after [running] is false again, so a screen that refreshes on log lines sees the final state.
     */
    fun syncInBackground(context: Context, onFinished: () -> Unit = {}): Boolean {
        if (!busy.compareAndSet(false, true)) return false
        val app = context.applicationContext
        Thread({
            var summary = "Sync failed"
            try { summary = sync(app, logSummary = false) } catch (e: Exception) { summary = "Sync failed: $e" }
            finally { lastResult = summary; busy.set(false); LogBus.log(summary); onFinished() }
        }, "cambackup-upload").start()
        return true
    }

    /** Synchronous variant, returns the summary line (logged unless [logSummary] is false). */
    fun sync(context: Context, mimeOf: (String) -> String = ::mimeFor, logSummary: Boolean = true): String {
        val prefs = Prefs(context)
        Transfers.init(context)
        val pending = Transfers.pending()
        val summary: String = when {
            !prefs.mode.usesServer -> "Sync: mode '${prefs.mode}' has no server, nothing to upload"
            prefs.backendUrl.isBlank() -> "Sync: no server URL configured (${pending.size} waiting)"
            pending.isEmpty() -> "Sync: everything is on the server"
            else -> {
                val backend = BackendSink(prefs.backendUrl, LogBus::log, prefs.backendToken)
                if (!backend.reachable()) "Sync: server ${prefs.backendUrl} not reachable, ${pending.size} file(s) waiting"
                else upload(context, prefs, backend, pending, mimeOf)
            }
        }
        if (logSummary) LogBus.log(summary)
        return summary
    }

    private fun upload(context: Context, prefs: Prefs, backend: BackendSink, pending: List<Transfer>, mimeOf: (String) -> String): String {
        val local = Sinks.direct(context, prefs.folderUri)
        var uploaded = 0; var missing = 0; var failed = 0
        backend.onBackupStart("phone")
        for (t in pending) {
            val input = try { local.open(t.date, t.name) } catch (e: IOException) { null }
            if (input == null) { missing++; LogBus.log("Sync: ${t.name} is no longer on the phone"); continue }
            val file = IncomingFile(t.name, t.date, t.size, mimeOf(t.name), if (mimeOf(t.name).startsWith("video/")) "object.item.videoItem" else "object.item.imageItem", "phone")
            try {
                input.use { backend.store(file, it) }
                Transfers.markOnServer(t.date, t.name, prefs.backendUrl)
                uploaded++
                LogBus.log("Uploaded ${t.name} to ${prefs.backendUrl}")
            } catch (e: IOException) {
                failed++; LogBus.log("Upload of ${t.name} failed: ${e.message}")
                if (e.message?.contains("401") == true) break
            }
        }
        backend.onBackupDone("phone", false)
        return "Sync: $uploaded uploaded, $failed failed, $missing missing"
    }

    fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "srw" -> "image/x-samsung-srw"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        else -> "application/octet-stream"
    }
}
