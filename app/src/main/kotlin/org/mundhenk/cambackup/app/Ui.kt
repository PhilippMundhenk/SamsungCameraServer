package org.mundhenk.cambackup.app

import android.net.Uri
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Small view helpers shared by the activities. */
object Ui {
    /**
     * Android 15 draws every app edge to edge: pad the root view with the system bar insets so
     * the toolbar starts below the status bar and lists end above the navigation bar.
     */
    fun edgeToEdge(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** "Pictures/NX (primary)" for a SAF tree URI. */
    fun friendlyTree(uri: Uri): String =
        uri.lastPathSegment?.substringAfter(':')?.ifBlank { "/" }?.let { "$it (${uri.authority?.substringBefore('.')})" } ?: uri.toString()

    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1_000 -> "$bytes B"
        bytes < 1_000_000 -> String.format(Locale.US, "%.0f kB", bytes / 1e3)
        bytes < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1e9)
    }

    fun formatTime(epochMillis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochMillis))
}
