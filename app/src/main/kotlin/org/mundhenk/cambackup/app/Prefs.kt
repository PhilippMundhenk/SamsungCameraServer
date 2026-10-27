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

    var backendUrl: String
        get() = sp.getString("backend", "") ?: ""
        set(v) = sp.edit().putString("backend", v.trim().trimEnd('/')).apply()

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
            this.udn = id.udn; this.serverName = id.friendlyName
            null
        } catch (e: IllegalArgumentException) { e.message }
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
