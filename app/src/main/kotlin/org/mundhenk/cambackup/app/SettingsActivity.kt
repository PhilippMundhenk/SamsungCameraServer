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
import org.mundhenk.cambackup.core.ServerIdentity

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
        b.backendToken.setText(prefs.backendToken)
        b.serverName.setText(prefs.serverName)
        b.udn.setText(prefs.udn)
        b.regenerate.setOnClickListener { b.udn.setText(ServerIdentity.generateUdn()) }
        b.exportPairing.setOnClickListener { if (save()) savePairing.launch("SAMSUNGAutoBackupDESC.zip") }
        b.showWizard.setOnClickListener { startActivity(Intent(this, WizardActivity::class.java)) }
    }

    override fun onResume() {
        super.onResume()
        val locked = BackupService.running
        b.lockedNotice.visibility = if (locked) View.VISIBLE else View.GONE
        for (v in listOf(b.modePhone, b.modePhoneAndServer, b.modeServer, b.chooseFolder, b.resetFolder, b.backendUrl, b.backendToken, b.serverName, b.udn, b.regenerate)) v.isEnabled = !locked
    }

    override fun onPause() {
        if (!BackupService.running) save()
        super.onPause()
    }

    /** Persists the text fields; false (with the error shown) when the UDN is not valid. */
    private fun save(): Boolean {
        prefs.backendUrl = b.backendUrl.text?.toString() ?: ""
        prefs.backendToken = b.backendToken.text?.toString() ?: ""
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
