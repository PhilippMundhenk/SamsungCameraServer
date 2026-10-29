package org.mundhenk.cambackup.app

import android.app.Activity
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mundhenk.cambackup.core.BackendSink
import org.mundhenk.cambackup.core.ServerIdentity

/**
 * Phone and server are meant to share one identity (one pairing file on every SD card). This moves
 * it in either direction over the backend API: take the server's identity, or hand the phone's to
 * the server (which rewrites its pairing file and restarts its camera side). Network work runs on a
 * background thread; callbacks arrive on the UI thread.
 */
object IdentityExchange {
    private fun backend(prefs: Prefs) = BackendSink(prefs.backendUrl, LogBus::log, prefs.backendToken, deviceName = prefs.deviceNickname, user = prefs.serverUser)

    /** Why the last [fetch] failed ("HTTP 404", "the server does not speak TLS ..."), for [describe]. */
    @Volatile var lastError: String? = null
        private set

    /** Reads the server's identity; null when there is no server URL or the server does not answer. */
    fun fetch(activity: Activity, prefs: Prefs, onResult: (ServerIdentity?) -> Unit) {
        if (prefs.backendUrl.isBlank()) { lastError = null; onResult(null); return }
        Thread({
            val b = backend(prefs)
            val id = b.fetchIdentity(prefs.serverUser)           // the user this phone belongs to, or the server itself
            lastError = b.lastError
            activity.runOnUiThread { if (!activity.isFinishing) onResult(id) }
        }, "cambackup-identity").start()
    }

    /** One-line verdict for the UI: shared, different, or unknown (with the reason). */
    fun describe(activity: Activity, prefs: Prefs, server: ServerIdentity?): String = when {
        server == null -> activity.getString(R.string.udn_server_unknown, prefs.backendUrl, lastError ?: activity.getString(R.string.udn_server_no_answer))
        server.udn == prefs.udn -> activity.getString(R.string.udn_shared_with_server, server.friendlyName + (prefs.serverUser.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
        else -> activity.getString(R.string.udn_differs_from_server, server.friendlyName, server.udn)
    }

    fun sourceLabel(activity: Activity, prefs: Prefs): String =
        activity.getString(R.string.udn_source, prefs.udnSource.ifBlank { Prefs.SOURCE_GENERATED })

    /**
     * Phone takes the server's identity. A multi-user server first asks whose: the server itself
     * or one of its users (the phone then files its uploads under that user). [confirm] = ask
     * before switching (Settings); the wizard asks nothing on first setup.
     */
    fun adoptFromServer(activity: Activity, prefs: Prefs, server: ServerIdentity, confirm: Boolean, done: () -> Unit) {
        Thread({
            val b = backend(prefs)
            val users = b.fetchUsers() ?: emptyList()
            // [server] may be the identity of the user this phone is bound to; the first choice is the server itself
            val general = if (users.isEmpty()) server else (b.fetchIdentity() ?: server)
            activity.runOnUiThread {
                if (activity.isFinishing) return@runOnUiThread
                if (users.isEmpty()) adoptChosen(activity, prefs, server, "", confirm, done)
                else {
                    val labels = arrayOf(activity.getString(R.string.general_server_identity, general.friendlyName)) + users.map { "${it.friendlyName} (${it.name})" }
                    MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.choose_server_identity_title)
                        .setItems(labels) { _, which ->
                            if (which == 0) adoptChosen(activity, prefs, general, "", confirm, done)
                            else {
                                val u = users[which - 1]
                                adoptChosen(activity, prefs, ServerIdentity(u.udn, u.friendlyName, server.macAddr), u.name, confirm, done)
                            }
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                }
            }
        }, "cambackup-users").start()
    }

    private fun adoptChosen(activity: Activity, prefs: Prefs, server: ServerIdentity, user: String, confirm: Boolean, done: () -> Unit) {
        val apply = {
            prefs.serverUser = user
            prefs.adoptIdentity(server, activity.getString(R.string.udn_source_server, server.friendlyName + (if (user.isNotEmpty()) " ($user)" else ""), prefs.backendUrl))
            LogBus.log("Identity taken over from server ${server.friendlyName}${if (user.isNotEmpty()) " (user $user)" else ""} (${prefs.backendUrl}): uuid:${server.udn}")
            Toast.makeText(activity, R.string.identity_adopted, Toast.LENGTH_SHORT).show()
            done()
        }
        if (!confirm) { apply(); return }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.use_server_identity_title)
            .setMessage(activity.getString(R.string.use_server_identity_message, server.udn, server.friendlyName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.use_server_identity) { _, _ -> apply() }
            .show()
    }

    /** Server takes the phone's identity (needs the token). */
    fun giveToServer(activity: Activity, prefs: Prefs, confirm: Boolean, done: (ServerIdentity?) -> Unit) {
        val mine = prefs.identity()
        val push = {
            Thread({
                val result = backend(prefs).pushIdentity(mine)
                activity.runOnUiThread {
                    if (activity.isFinishing) return@runOnUiThread
                    if (result != null) {
                        LogBus.log("Server ${prefs.backendUrl} now uses this phone's identity: ${result.friendlyName} uuid:${result.udn}")
                        Toast.makeText(activity, R.string.identity_given, Toast.LENGTH_SHORT).show()
                    } else Toast.makeText(activity, R.string.identity_exchange_failed, Toast.LENGTH_LONG).show()
                    done(result)
                }
            }, "cambackup-identity").start()
        }
        if (!confirm) { push(); return }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.give_server_identity_title)
            .setMessage(activity.getString(R.string.give_server_identity_message, prefs.backendUrl, mine.udn, mine.friendlyName))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.give_server_identity) { _, _ -> push() }
            .show()
    }
}
