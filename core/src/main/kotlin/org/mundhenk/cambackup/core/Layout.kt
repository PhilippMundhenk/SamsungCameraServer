package org.mundhenk.cambackup.core

import java.io.File

/**
 * Folder layout for received files, derived from the camera's date (dc:date, `YYYY-MM-DD`).
 *
 * The pattern knows `{yyyy}`, `{MM}`, `{dd}` and `/` for nested folders:
 *   `flat` (the default, also `none` or empty) no folders at all, `{yyyy}-{MM}-{dd}` one folder
 *   per day, `{yyyy}/{MM}` year and month folders, `{yyyy}` one folder per year.
 */
object Layout {
    const val DEFAULT = ""
    const val DAY = "{yyyy}-{MM}-{dd}"
    val PRESETS: Map<String, String> = linkedMapOf(
        "flat" to "", "day" to DAY, "month" to "{yyyy}/{MM}", "year" to "{yyyy}",
    )

    /** The relative folder for [date] under the storage root, "" for flat. Uses [File.separator]. */
    fun subfolder(pattern: String?, date: String): String {
        val p = normalize(pattern)
        if (p.isEmpty()) return ""
        val parts = date.trim().split('-', '/', '.', ' ')
        val yyyy = parts.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "unknown"
        val mm = parts.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "00"
        val dd = parts.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "00"
        return p.replace("{yyyy}", yyyy).replace("{MM}", mm).replace("{dd}", dd)
            .split('/', '\\').filter { it.isNotBlank() }.joinToString(File.separator) { FileSystemSink.safeSegment(it) }
    }

    /** Resolves preset names and the "no folders" spellings; anything else is a pattern. */
    fun normalize(pattern: String?): String {
        val p = pattern?.trim() ?: return DEFAULT
        return when (p.lowercase()) {
            "", "none", "flat", "-" -> ""
            in PRESETS -> PRESETS.getValue(p.lowercase())
            else -> p
        }
    }

    /** `<root>/<subfolder>/<safe name>`. */
    fun target(root: File, pattern: String?, date: String, name: String): File {
        val sub = subfolder(pattern, date)
        val dir = if (sub.isEmpty()) root else File(root, sub)
        return File(dir, FileSystemSink.safeSegment(name))
    }
}
