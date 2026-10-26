package org.mundhenk.nxbackup.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.mundhenk.nxbackup.app.databinding.ActivityMainBinding
import org.mundhenk.nxbackup.core.PairingFile
import org.mundhenk.nxbackup.core.ServerIdentity

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    // Every service state change produces a log line, so refreshing the status here keeps the
    // button/label in sync without polling.
    private val logListener: (String) -> Unit = { line -> b.log.append(line + "\n"); showStatus() }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.folderUri = uri
            showFolder()
        }
    }

    private val savePairing = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.openOutputStream(uri, "w")!!.use { it.write(PairingFile.zip(prefs.identity())) }
            Toast.makeText(this, R.string.pairing_saved, Toast.LENGTH_SHORT).show()
            LogBus.log("Pairing file exported for uuid:${prefs.udn}")
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.pairing_failed) + ": " + e.message, Toast.LENGTH_LONG).show()
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        // mode
        b.modeGroup.check(if (prefs.mode == Mode.DIRECT) R.id.modeDirect else R.id.modeBackend)
        b.modeGroup.setOnCheckedChangeListener { _, id ->
            prefs.mode = if (id == R.id.modeDirect) Mode.DIRECT else Mode.BACKEND
            showMode()
        }
        showMode()
        showFolder()
        b.chooseFolder.setOnClickListener { pickFolder.launch(prefs.folderUri) }
        b.backendUrl.setText(prefs.backendUrl)
        b.backendToken.setText(prefs.backendToken)

        // identity
        b.serverName.setText(prefs.serverName)
        b.udn.setText(prefs.udn)
        b.regenerate.setOnClickListener { b.udn.setText(ServerIdentity.generateUdn()) }
        b.exportPairing.setOnClickListener {
            if (saveIdentity()) savePairing.launch("SAMSUNGAutoBackupDESC.zip")
        }

        // server
        b.startStop.setOnClickListener {
            if (BackupService.running) BackupService.stop(this) else {
                if (!saveIdentity()) return@setOnClickListener
                prefs.backendUrl = b.backendUrl.text?.toString() ?: ""
                prefs.backendToken = b.backendToken.text?.toString() ?: ""
                if (prefs.mode == Mode.BACKEND && prefs.backendUrl.isBlank()) {
                    b.backendUrl.error = getString(R.string.backend_url_hint); return@setOnClickListener
                }
                BackupService.start(this)
            }
            b.root.postDelayed({ showStatus() }, 400)
        }
        b.clearLog.setOnClickListener { LogBus.clear(); b.log.text = "" }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        b.log.text = LogBus.snapshot().joinToString("\n", postfix = "\n")
        LogBus.addListener(logListener)
        showStatus()
    }

    override fun onPause() {
        LogBus.removeListener(logListener)
        super.onPause()
    }

    private fun saveIdentity(): Boolean {
        val udn = b.udn.text?.toString()?.trim()?.lowercase() ?: ""
        val name = b.serverName.text?.toString()?.trim().orEmpty()
        return try {
            val id = ServerIdentity(udn, name.ifBlank { ServerIdentity.DEFAULT_NAME }, ServerIdentity.DEFAULT_MAC)
            prefs.udn = id.udn; prefs.serverName = id.friendlyName
            b.udn.setText(id.udn); b.serverName.setText(id.friendlyName)
            true
        } catch (e: IllegalArgumentException) {
            b.udn.error = e.message; false
        }
    }

    private fun showMode() {
        val direct = prefs.mode == Mode.DIRECT
        b.directGroup.visibility = if (direct) android.view.View.VISIBLE else android.view.View.GONE
        b.backendGroup.visibility = if (direct) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun showFolder() {
        b.folderLabel.text = prefs.folderUri?.let { friendlyTree(it) } ?: getString(R.string.folder_default)
    }

    private fun showStatus() {
        val running = BackupService.running
        b.status.setText(if (running) R.string.status_running else R.string.status_stopped)
        b.startStop.setText(if (running) R.string.stop else R.string.start)
        val enabled = !running
        b.modeDirect.isEnabled = enabled; b.modeBackend.isEnabled = enabled
        b.chooseFolder.isEnabled = enabled; b.backendUrl.isEnabled = enabled; b.backendToken.isEnabled = enabled
        b.serverName.isEnabled = enabled; b.udn.isEnabled = enabled; b.regenerate.isEnabled = enabled
    }

    private fun friendlyTree(uri: Uri): String =
        uri.lastPathSegment?.substringAfter(':')?.ifBlank { "/" }?.let { "$it (${uri.authority?.substringBefore('.')})" } ?: uri.toString()
}
