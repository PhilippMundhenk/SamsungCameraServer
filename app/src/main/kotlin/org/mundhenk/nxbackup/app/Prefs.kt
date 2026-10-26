package org.mundhenk.nxbackup.app

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.mundhenk.nxbackup.core.ServerIdentity

enum class Mode { DIRECT, BACKEND }

class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("nxbackup", Context.MODE_PRIVATE)

    var mode: Mode
        get() = Mode.valueOf(sp.getString("mode", Mode.DIRECT.name)!!)
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

    fun identity(): ServerIdentity = ServerIdentity(udn, serverName, ServerIdentity.DEFAULT_MAC)
}
