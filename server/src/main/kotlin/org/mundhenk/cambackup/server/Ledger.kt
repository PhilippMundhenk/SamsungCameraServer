package org.mundhenk.cambackup.server

import java.io.File

/**
 * Persistent index of every file ever received, keyed by camera date + name.
 * Needed because the pipeline may rename or move files, after which "do we have
 * it already?" can no longer be answered by looking into the storage directory.
 *
 * Format: one tab-separated line per file (date, name, size, current path, epoch millis);
 * later lines for the same key win, so path updates are plain appends.
 */
class Ledger(private val file: File) {
    data class Entry(val date: String, val name: String, val size: Long, val path: String, val receivedAt: Long)

    private val entries = LinkedHashMap<String, Entry>()

    init {
        if (file.isFile) {
            file.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 5) {
                    val e = Entry(p[0], p[1], p[2].toLongOrNull() ?: -1, p[3], p[4].toLongOrNull() ?: 0)
                    entries[key(e.date, e.name)] = e
                }
            }
        }
    }

    private fun key(date: String, name: String) = "$date/$name".lowercase()

    @Synchronized
    fun find(date: String, name: String): Entry? = entries[key(date, name)]

    /** true if a file with this name was received for this date and the size matches (or is unknown). */
    @Synchronized
    fun contains(date: String, name: String, size: Long): Boolean {
        val e = entries[key(date, name)] ?: return false
        return size < 0 || e.size < 0 || e.size == size
    }

    @Synchronized
    fun record(date: String, name: String, size: Long, path: File) =
        put(Entry(date, name, size, path.path, System.currentTimeMillis()))

    /** Update the stored path after the pipeline renamed or moved a file. */
    @Synchronized
    fun updatePath(date: String, name: String, path: File) {
        val old = entries[key(date, name)] ?: return
        put(old.copy(path = path.path))
    }

    private fun put(e: Entry) {
        entries[key(e.date, e.name)] = e
        file.parentFile?.mkdirs()
        file.appendText(listOf(e.date, e.name, e.size.toString(), e.path, e.receivedAt.toString()).joinToString("\t", postfix = "\n"))
    }

    @Synchronized
    fun recent(n: Int): List<Entry> = entries.values.toList().takeLast(n).reversed()

    @Synchronized
    fun size(): Int = entries.size
}
