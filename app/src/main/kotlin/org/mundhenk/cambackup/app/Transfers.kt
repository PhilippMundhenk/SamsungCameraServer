package org.mundhenk.cambackup.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** ✓ = stored on the phone, ✓✓ = on the home server (forwarded or uploaded later). */
enum class TransferState { ON_PHONE, ON_SERVER }

data class Transfer(
    val date: String,        // camera date (dc:date)
    val name: String,        // SAM_0001.JPG
    val size: Long,
    val state: TransferState,
    val at: Long,            // epoch millis of the last state change
    val location: String,    // folder / URL the file is at, for the UI
) {
    val key get() = "$date/$name".lowercase()
}

/**
 * The list shown on the main screen, persisted as one tab-separated line per file in the app's
 * private files dir (later lines for the same date+name win, so state changes are plain appends).
 */
object Transfers {
    private val entries = LinkedHashMap<String, Transfer>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null
    private var loadedFrom: File? = null

    @Synchronized
    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, "transfers.tsv")
        if (loadedFrom == f) return
        file = f; loadedFrom = f
        entries.clear()
        if (f.isFile) f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size >= 6) {
                val state = runCatching { TransferState.valueOf(p[3]) }.getOrNull() ?: return@forEachLine
                val t = Transfer(p[0], p[1], p[2].toLongOrNull() ?: -1, state, p[4].toLongOrNull() ?: 0, p[5])
                entries[t.key] = t
            }
        }
    }

    @Synchronized
    fun record(date: String, name: String, size: Long, state: TransferState, location: String) =
        put(Transfer(date, name, size, state, System.currentTimeMillis(), location))

    /** Called after a deferred upload succeeded. */
    @Synchronized
    fun markOnServer(date: String, name: String, location: String) {
        val old = entries[Transfer(date, name, 0, TransferState.ON_PHONE, 0, "").key] ?: return
        put(old.copy(state = TransferState.ON_SERVER, at = System.currentTimeMillis(), location = location))
    }

    private fun put(t: Transfer) {
        entries[t.key] = t
        file?.appendText(listOf(t.date, t.name, t.size.toString(), t.state.name, t.at.toString(), t.location).joinToString("\t", postfix = "\n"))
        notifyListeners()
    }

    /** Newest first. */
    @Synchronized
    fun list(): List<Transfer> = entries.values.sortedByDescending { it.at }

    @Synchronized
    fun pending(): List<Transfer> = entries.values.filter { it.state == TransferState.ON_PHONE }.sortedBy { it.at }

    @Synchronized
    fun get(date: String, name: String): Transfer? = entries[Transfer(date, name, 0, TransferState.ON_PHONE, 0, "").key]

    /** Drops one entry (an incomplete file that was deleted). */
    @Synchronized
    fun remove(date: String, name: String) {
        if (entries.remove(Transfer(date, name, 0, TransferState.ON_PHONE, 0, "").key) == null) return
        file?.writeText(entries.values.joinToString("") { t -> listOf(t.date, t.name, t.size.toString(), t.state.name, t.at.toString(), t.location).joinToString("\t", postfix = "\n") })
        notifyListeners()
    }

    @Synchronized
    fun clear() {
        entries.clear()
        file?.delete()
        notifyListeners()
    }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun notifyListeners() { main.post { listeners.forEach { it() } } }
}
