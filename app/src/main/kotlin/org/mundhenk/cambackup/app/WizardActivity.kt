package org.mundhenk.cambackup.app

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.mundhenk.cambackup.app.databinding.ActivityWizardBinding

/**
 * First-start wizard, three steps: where photos go -> destination details -> pairing file.
 * Settings has a button to run it again.
 */
class WizardActivity : AppCompatActivity() {
    private lateinit var b: ActivityWizardBinding
    private lateinit var prefs: Prefs
    private var step = 0

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) { PairingExport.takeFolder(this, prefs, uri); showFolder() }
    }
    private val savePairing = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri != null) { PairingExport.write(this, prefs, uri); b.exported.visibility = View.VISIBLE }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        b = ActivityWizardBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.edgeToEdge(this, b.root, b.header)

        b.modeGroup.check(when (prefs.mode) { Mode.PHONE -> R.id.wizModePhone; Mode.PHONE_AND_SERVER -> R.id.wizModePhoneAndServer; Mode.SERVER -> R.id.wizModeServer })
        b.chooseFolder.setOnClickListener { pickFolder.launch(prefs.folderUri) }
        b.backendUrl.setText(prefs.backendUrl); b.backendToken.setText(prefs.backendToken)
        b.serverName.setText(prefs.serverName); b.udn.text = prefs.udn
        b.regenerate.setOnClickListener {
            Ui.confirmNewUdn(this, prefs.udn) { b.udn.text = prefs.regenerateUdn(); LogBus.log("New UDN uuid:${prefs.udn} (previous one kept in the history)") }
        }
        b.exportPairing.setOnClickListener { if (saveIdentity()) savePairing.launch("SAMSUNGAutoBackupDESC.zip") }
        b.next.setOnClickListener { next() }
        b.back.setOnClickListener { if (step == 0) finish() else show(step - 1) }
        show(0)
    }

    private fun next() {
        when (step) {
            0 -> {
                prefs.mode = when (b.modeGroup.checkedRadioButtonId) { R.id.wizModePhone -> Mode.PHONE; R.id.wizModePhoneAndServer -> Mode.PHONE_AND_SERVER; else -> Mode.SERVER }
                show(1)
            }
            1 -> {
                prefs.backendUrl = b.backendUrl.text?.toString() ?: ""
                prefs.backendToken = b.backendToken.text?.toString() ?: ""
                if (prefs.mode.usesServer && prefs.backendUrl.isBlank()) { b.backendUrlLayout.error = getString(R.string.error_no_server_url); return }
                b.backendUrlLayout.error = null
                show(2)
            }
            else -> {
                if (!saveIdentity()) return
                prefs.setupDone = true
                LogBus.log("Setup finished: ${prefs.destinationSummary(this)}")
                finish()
            }
        }
    }

    private fun saveIdentity(): Boolean {
        val error = prefs.saveIdentity(prefs.udn, b.serverName.text?.toString())
        b.serverNameLayout.error = error
        if (error == null) b.serverName.setText(prefs.serverName)
        return error == null
    }

    private fun show(s: Int) {
        step = s
        b.flipper.displayedChild = s
        b.stepLabel.text = getString(R.string.wizard_step, s + 1, 3)
        b.back.setText(if (s == 0) R.string.wizard_skip else R.string.wizard_back)
        b.next.setText(if (s == 2) R.string.wizard_finish else R.string.wizard_next)
        if (s == 1) {
            b.folderGroup.visibility = if (prefs.mode.usesPhone) View.VISIBLE else View.GONE
            b.serverGroup.visibility = if (prefs.mode.usesServer) View.VISIBLE else View.GONE
            showFolder()
        }
    }

    private fun showFolder() {
        b.folderLabel.text = prefs.folderUri?.let { Ui.friendlyTree(it) } ?: getString(R.string.folder_default)
    }

    override fun finish() {
        // Skipping also counts as "seen": the app must not nag on every start.
        prefs.setupDone = true
        super.finish()
    }
}
