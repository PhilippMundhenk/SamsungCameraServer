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
    /** The folder-layout radio group (wizard and settings): shows the setting, saves every change. */
    fun bindFolderLayout(group: android.widget.RadioGroup, prefs: Prefs) {
        val ids = mapOf("day" to R.id.layoutDay, "month" to R.id.layoutMonth, "year" to R.id.layoutYear, "flat" to R.id.layoutFlat)
        group.check(ids[prefs.folderLayout] ?: R.id.layoutDay)
        group.setOnCheckedChangeListener { _, id -> prefs.folderLayout = ids.entries.firstOrNull { it.value == id }?.key ?: "day" }
    }

    /** Tap on a transfer: hand the file to the phone's viewer (gallery, video player, ...). */
    fun openTransfer(activity: Activity, prefs: Prefs, t: Transfer) {
        val uri = runCatching { Sinks.direct(activity, prefs.folderUri).uriOf(t.date, t.name) }.getOrNull()
        if (uri == null) {
            val msg = activity.getString(R.string.open_not_on_phone, t.name)
            LogBus.log(msg); android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, Uploader.mimeFor(t.name))
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(intent) } catch (e: android.content.ActivityNotFoundException) {
            val msg = activity.getString(R.string.open_no_app, t.name)
            LogBus.log(msg); android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    /** Live hint under a server-URL field: https:// does not work against the plain-HTTP home server. */
    fun warnAboutHttps(field: android.widget.EditText, layout: com.google.android.material.textfield.TextInputLayout) {
        fun update(text: CharSequence?) {
            layout.helperText = if (text.toString().trim().startsWith("https://", ignoreCase = true)) field.context.getString(R.string.backend_https_hint) else null
        }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) = update(s)
        })
        update(field.text)
    }

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

    /**
     * Changing the UDN cuts off every camera paired with the current one: always confirm, say so,
     * and mention that the old code stays in the history. [onConfirm] runs only after "Generate".
     */
    fun confirmNewUdn(activity: Activity, currentUdn: String, onConfirm: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.udn_new_title)
            .setMessage(activity.getString(R.string.udn_new_message, currentUdn))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.udn_new_confirm) { _, _ -> onConfirm() }
            .show()
    }

    /** Same warning for switching back to a UDN from the history. */
    fun confirmRestoreUdn(activity: Activity, previousUdn: String, onConfirm: () -> Unit) {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.udn_restore_title)
            .setMessage(activity.getString(R.string.udn_restore_message, previousUdn))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.udn_restore_confirm) { _, _ -> onConfirm() }
            .show()
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
