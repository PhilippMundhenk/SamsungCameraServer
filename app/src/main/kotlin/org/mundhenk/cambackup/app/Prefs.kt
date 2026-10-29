package org.mundhenk.cambackup.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.mundhenk.cambackup.core.ServerIdentity

/** Where received photos go. */
enum class Mode(val usesPhone: Boolean, val usesServer: Boolean) {
    /** Keep on the phone only. */
    PHONE(true, false),
    /** Keep on the phone, upload to the home server whenever it is reachable. */
    PHONE_AND_SERVER(true, true),
    /** Stream straight to the home server, nothing stays on the phone. */
    SERVER(false, true);

    companion object {
        /** Accepts the pre-0.2 names too (DIRECT / BACKEND). */
        fun parse(s: String?): Mode = when (s) {
            null, "", "DIRECT" -> PHONE
            "BACKEND" -> SERVER
            else -> entries.firstOrNull { it.name == s } ?: PHONE
        }
    }
}

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("cambackup", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.parse(sp.getString("mode", null))
        set(v) = sp.edit().putString("mode", v.name).apply()

    /** SAF tree URI chosen by the user; null = app-private external files dir. */
    var folderUri: Uri?
        get() = sp.getString("folder", null)?.let(Uri::parse)
        set(v) = sp.edit().putString("folder", v?.toString()).apply()

    /** Normalised: trimmed, no trailing slash, "http://" added when no scheme was typed. */
    /** Folder layout under the phone folder: a [org.mundhenk.cambackup.core.Layout] preset ("day", "month", "year", "flat"). */
    var folderLayout: String
        get() = sp.getString("folderLayout", "day") ?: "day"
        set(v) = sp.edit().putString("folderLayout", v.trim().ifBlank { "day" }).apply()

    var backendUrl: String
        get() = sp.getString("backend", "") ?: ""
        set(v) {
            val url = v.trim().trimEnd('/').let { if (it.isNotEmpty() && !it.contains("://")) "http://$it" else it }
            sp.edit().putString("backend", url).apply()
        }

    /** Optional bearer token for the backend API (server setting api.token). */
    var backendToken: String
        get() = sp.getString("backendToken", "") ?: ""
        set(v) = sp.edit().putString("backendToken", v.trim()).apply()

    var serverName: String
        get() = sp.getString("name", ServerIdentity.DEFAULT_NAME) ?: ServerIdentity.DEFAULT_NAME
        set(v) = sp.edit().putString("name", v.ifBlank { ServerIdentity.DEFAULT_NAME }).apply()

    var udn: String
        get() {
            val existing = sp.getString("udn", null)
            if (existing != null) return existing
            val fresh = ServerIdentity.generateUdn()
            sp.edit().putString("udn", fresh).apply()
            return fresh
        }
        set(v) = sp.edit().putString("udn", v).apply()

    /** Keep uploading to the home server on its own until nothing is left (phone + server mode). */
    var autoSync: Boolean
        get() = sp.getBoolean("autoSync", true)
        set(v) = sp.edit().putBoolean("autoSync", v).apply()

    /** The first-start wizard has been completed (or skipped). */
    var setupDone: Boolean
        get() = sp.getBoolean("setupDone", false)
        set(v) = sp.edit().putBoolean("setupDone", v).apply()

    fun identity(): ServerIdentity = ServerIdentity(udn, serverName, ServerIdentity.DEFAULT_MAC)

    /** Validates and stores name + UDN from user input; returns the error message or null. */
    fun saveIdentity(udnInput: String?, nameInput: String?): String? {
        val udn = udnInput?.trim()?.lowercase() ?: ""
        val name = nameInput?.trim().orEmpty()
        return try {
            val id = ServerIdentity(udn, name.ifBlank { ServerIdentity.DEFAULT_NAME }, ServerIdentity.DEFAULT_MAC)
            switchUdn(id.udn, SOURCE_MANUAL); this.serverName = id.friendlyName
            null
        } catch (e: IllegalArgumentException) { e.message }
    }

    // ---- UDN history: every UDN this phone ever advertised, newest first ----
    // A camera only finds a server whose UDN is on its SD card, so replacing the UDN cuts off every
    // camera paired with the old one. The history keeps the old codes so they can be put back.

    /** [source] says where the UDN came from ("generated on this phone", "from server [PC]Home"). */
    data class UdnHistoryEntry(val udn: String, val replacedAt: Long, val source: String = "")

    val udnHistory: List<UdnHistoryEntry>
        get() = (sp.getString("udnHistory", "") ?: "").lines().mapNotNull { line ->
            val p = line.split('\t')
            if (p.size >= 2 && p[1].isNotBlank()) UdnHistoryEntry(p[1], p[0].toLongOrNull() ?: 0, p.getOrNull(2) ?: "") else null
        }

    /** Where the current UDN came from (shown under the UDN). */
    var udnSource: String
        get() = sp.getString("udnSource", "") ?: ""
        set(v) = sp.edit().putString("udnSource", v).apply()

    /** How this phone introduces itself to the home server (X-NX-Device) and in the device lists. */
    var deviceNickname: String
        get() = (sp.getString("deviceNickname", null) ?: "").ifBlank { defaultNickname() }
        set(v) = sp.edit().putString("deviceNickname", v.trim()).apply()

    private fun defaultNickname(): String = listOfNotNull(android.os.Build.MANUFACTURER, android.os.Build.MODEL).joinToString(" ").trim().ifBlank { "Phone" }
        .replaceFirstChar { it.uppercase() }

    /** Generates a fresh UDN; the current one goes into the history. Returns the new UDN. */
    fun regenerateUdn(): String = ServerIdentity.generateUdn().also { switchUdn(it, SOURCE_GENERATED) }

    /** Takes over the server's identity (UDN and name) so one pairing file serves phone and server. */
    fun adoptIdentity(id: ServerIdentity, source: String) {
        switchUdn(id.udn, source)
        serverName = id.friendlyName
    }

    /**
     * Makes [target] the current UDN; the current one goes into the history with its source.
     * [source] = where the new UDN comes from; empty = look it up in the history (switching back).
     */
    fun switchUdn(target: String, source: String = "") {
        val old = udn
        if (old == target) return
        val oldSource = udnSource.ifBlank { SOURCE_GENERATED }
        val restored = udnHistory.firstOrNull { it.udn == target }
        val history = listOf(UdnHistoryEntry(old, System.currentTimeMillis(), oldSource)) + udnHistory.filter { it.udn != old && it.udn != target }
        sp.edit()
            .putString("udnHistory", history.take(MAX_UDN_HISTORY).joinToString("\n") { "${it.replacedAt}\t${it.udn}\t${it.source.replace('\t', ' ')}" })
            .putString("udn", target)
            .putString("udnSource", if (source.isNotBlank()) source else restored?.source ?: "")
            .apply()
    }

    companion object {
        const val MAX_UDN_HISTORY = 20
        const val SOURCE_GENERATED = "generated on this phone"
        const val SOURCE_MANUAL = "entered by hand"
    }

    /** Short human readable destination for the status card. */
    fun destinationSummary(context: Context): String {
        val phone = folderUri?.let { Ui.friendlyTree(it) } ?: context.getString(R.string.folder_default_short)
        return when (mode) {
            Mode.PHONE -> context.getString(R.string.dest_phone, phone)
            Mode.PHONE_AND_SERVER -> context.getString(R.string.dest_phone_and_server, phone, backendUrl.ifBlank { "?" })
            Mode.SERVER -> context.getString(R.string.dest_server, backendUrl.ifBlank { "?" })
        }
    }
}
