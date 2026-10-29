package org.mundhenk.cambackup.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.mundhenk.cambackup.app.databinding.ActivitySettingsBinding
import org.mundhenk.cambackup.core.PairingFile

/** Mode, destination, server connection and the identity the camera is paired with. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    private lateinit var prefs: Prefs

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) { PairingExport.takeFolder(this, prefs, uri); showFolder() }
    }
    private val savePairing = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) PairingExport.write(this, prefs, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.edgeToEdge(this, b.root, b.toolbar)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.modeGroup.check(when (prefs.mode) { Mode.PHONE -> R.id.modePhone; Mode.PHONE_AND_SERVER -> R.id.modePhoneAndServer; Mode.SERVER -> R.id.modeServer })
        b.modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode = when (id) { R.id.modePhone -> Mode.PHONE; R.id.modePhoneAndServer -> Mode.PHONE_AND_SERVER; else -> Mode.SERVER }
            showMode()
        }
        showMode(); showFolder()
        b.chooseFolder.setOnClickListener { pickFolder.launch(prefs.folderUri) }
        b.resetFolder.setOnClickListener { prefs.folderUri = null; showFolder() }
        b.backendUrl.setText(prefs.backendUrl)
        Ui.warnAboutHttps(b.backendUrl, b.backendUrlLayout)
        b.backendToken.setText(prefs.backendToken)
        b.serverName.setText(prefs.serverName)
        b.udn.setText(prefs.udn)
        b.regenerate.setOnClickListener {
            Ui.confirmNewUdn(this, prefs.udn) {
                val fresh = prefs.regenerateUdn()
                b.udn.setText(fresh)
                LogBus.log("New UDN uuid:$fresh (previous one kept in the history)")
                showUdnHistory(); showServerIdentity()
            }
        }
        b.exportPairing.setOnClickListener { if (save()) savePairing.launch("SAMSUNGAutoBackupDESC.zip") }
        b.showWizard.setOnClickListener { startActivity(Intent(this, WizardActivity::class.java)) }
        b.deviceNickname.setText(prefs.deviceNickname)
        b.useServerIdentity.setOnClickListener {
            val server = serverIdentity ?: return@setOnClickListener
            if (!save()) return@setOnClickListener
            IdentityExchange.adoptFromServer(this, prefs, server, confirm = true) { b.udn.setText(prefs.udn); b.serverName.setText(prefs.serverName); showUdnHistory(); showServerIdentity() }
        }
        b.giveServerIdentity.setOnClickListener {
            if (!save()) return@setOnClickListener
            IdentityExchange.giveToServer(this, prefs, confirm = true) { showServerIdentity() }
        }
        showUdnHistory()
    }

    private var serverIdentity: org.mundhenk.cambackup.core.ServerIdentity? = null

    /** Compares this phone's identity with the server's (server modes only) and offers both directions. */
    private fun showServerIdentity() {
        b.udnSource.text = IdentityExchange.sourceLabel(this, prefs)
        val show = prefs.mode.usesServer && prefs.backendUrl.isNotBlank()
        b.identityExchange.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        b.serverIdentityInfo.setText(R.string.udn_server_checking)
        b.useServerIdentity.isEnabled = false; b.giveServerIdentity.isEnabled = false
        IdentityExchange.fetch(this, prefs) { id ->
            serverIdentity = id
            b.serverIdentityInfo.text = IdentityExchange.describe(this, prefs, id)
            val locked = BackupService.running
            b.useServerIdentity.isEnabled = id != null && id.udn != prefs.udn && !locked
            b.giveServerIdentity.isEnabled = id != null && id.udn != prefs.udn && !locked
        }
    }

    /** Previous UDNs, newest first; tapping one switches back to it (after a warning). */
    private fun showUdnHistory() {
        val history = prefs.udnHistory
        b.udnHistory.removeAllViews()
        b.udnHistoryEmpty.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
        for (entry in history) {
            val row = layoutInflater.inflate(R.layout.item_udn_history, b.udnHistory, false)
            row.findViewById<android.widget.TextView>(R.id.historyUdn).text = entry.udn
            row.findViewById<android.widget.TextView>(R.id.historyDate).text = getString(R.string.udn_history_replaced, Ui.formatTime(entry.replacedAt), entry.source.ifBlank { Prefs.SOURCE_GENERATED })
            row.isEnabled = !BackupService.running
            row.setOnClickListener {
                Ui.confirmRestoreUdn(this, entry.udn) {
                    prefs.switchUdn(entry.udn)
                    b.udn.setText(entry.udn)
                    LogBus.log("Switched back to UDN uuid:${entry.udn}")
                    showUdnHistory(); showServerIdentity()
                }
            }
            b.udnHistory.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        val locked = BackupService.running
        b.lockedNotice.visibility = if (locked) View.VISIBLE else View.GONE
        for (v in listOf(b.modePhone, b.modePhoneAndServer, b.modeServer, b.chooseFolder, b.resetFolder, b.backendUrl, b.backendToken, b.serverName, b.udn, b.regenerate, b.deviceNickname)) v.isEnabled = !locked
        showUdnHistory()
        showServerIdentity()
    }

    override fun onPause() {
        if (!BackupService.running) save()
        super.onPause()
    }

    /** Persists the text fields; false (with the error shown) when the UDN is not valid. */
    private fun save(): Boolean {
        prefs.backendUrl = b.backendUrl.text?.toString() ?: ""
        prefs.backendToken = b.backendToken.text?.toString() ?: ""
        prefs.deviceNickname = b.deviceNickname.text?.toString() ?: ""
        val error = prefs.saveIdentity(b.udn.text?.toString(), b.serverName.text?.toString())
        if (error != null) { b.udn.error = error; return false }
        b.udn.error = null
        b.udn.setText(prefs.udn); b.serverName.setText(prefs.serverName)
        return true
    }

    private fun showMode() {
        val m = prefs.mode
        b.folderGroup.visibility = if (m.usesPhone) View.VISIBLE else View.GONE
        b.serverGroup.visibility = if (m.usesServer) View.VISIBLE else View.GONE
    }

    private fun showFolder() {
        b.folderLabel.text = prefs.folderUri?.let { Ui.friendlyTree(it) } ?: getString(R.string.folder_default)
        b.resetFolder.visibility = if (prefs.folderUri != null) View.VISIBLE else View.GONE
    }
}

/** Folder + pairing-file plumbing shared by Settings and the wizard. */
object PairingExport {
    fun takeFolder(activity: AppCompatActivity, prefs: Prefs, uri: Uri) {
        try {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            LogBus.log("No persistable permission for $uri (${e.message}); using it anyway")
        }
        prefs.folderUri = uri
        LogBus.log("Folder: ${Ui.friendlyTree(uri)}")
    }

    fun write(activity: AppCompatActivity, prefs: Prefs, uri: Uri) {
        try {
            activity.contentResolver.openOutputStream(uri, "w")!!.use { it.write(PairingFile.zip(prefs.identity())) }
            Toast.makeText(activity, R.string.pairing_saved, Toast.LENGTH_SHORT).show()
            LogBus.log("Pairing file exported for uuid:${prefs.udn}")
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.pairing_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
            LogBus.log("Pairing export failed: $e")
        }
    }
}
