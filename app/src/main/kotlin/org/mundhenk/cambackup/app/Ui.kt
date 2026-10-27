package org.mundhenk.cambackup.app

import android.app.Activity
import android.content.res.Configuration
import android.net.Uri
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Small view helpers shared by the activities. */
object Ui {
    /**
     * Android 15 draws every app edge to edge. The brand bar ([topBar]) grows under the status
     * bar (its top padding takes the inset), the rest of [root] is padded by the navigation bar
     * and cutouts. Status/navigation bar icons follow the theme (light icons on the blue bar).
     */
    fun edgeToEdge(activity: Activity, root: View, topBar: View) {
        val barPaddingTop = topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, 0, bars.right, bars.bottom)
            topBar.setPadding(topBar.paddingLeft, barPaddingTop + bars.top, topBar.paddingRight, topBar.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)
        val night = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val controller = WindowCompat.getInsetsController(activity.window, root)
        controller.isAppearanceLightStatusBars = night       // dark icons on the light-blue night bar
        controller.isAppearanceLightNavigationBars = !night  // dark icons on the white surface
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
