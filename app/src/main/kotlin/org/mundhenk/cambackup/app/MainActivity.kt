package org.mundhenk.cambackup.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import org.mundhenk.cambackup.app.databinding.ActivityMainBinding
import org.mundhenk.cambackup.app.databinding.ItemTransferBinding

/** Home screen: server status, start/stop, and the list of transferred photos (✓ / ✓✓). */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val adapter = TransferAdapter()
    // Every service state change produces a log line, so refreshing the status here keeps the
    // button/label in sync without polling.
    private val logListener: (String) -> Unit = { showStatus() }
    private val transferListener: () -> Unit = { showTransfers() }
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Transfers.init(this)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.edgeToEdge(this, b.root, b.toolbar)
        setSupportActionBar(b.toolbar)
        b.transfers.layoutManager = LinearLayoutManager(this)
        b.transfers.adapter = adapter

        b.startStop.setOnClickListener {
            if (BackupService.running) BackupService.stop(this) else {
                if (prefs.mode.usesServer && prefs.backendUrl.isBlank()) {
                    LogBus.log(getString(R.string.error_no_server_url)); startActivity(Intent(this, SettingsActivity::class.java))
                } else BackupService.start(this)
            }
            b.root.postDelayed({ showStatus() }, 400)
        }
        b.syncNow.setOnClickListener {
            if (!Uploader.syncInBackground(this) { runOnUiThread { showStatus() } }) LogBus.log(getString(R.string.sync_already_running))
            showStatus()
        }
        b.openSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)

        if (!prefs.setupDone) startActivity(Intent(this, WizardActivity::class.java))
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
        R.id.action_log -> { startActivity(Intent(this, LogActivity::class.java)); true }
        R.id.action_clear_transfers -> { Transfers.clear(); true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        LogBus.addListener(logListener)
        Transfers.addListener(transferListener)
        showStatus(); showTransfers()
    }

    override fun onPause() {
        LogBus.removeListener(logListener)
        Transfers.removeListener(transferListener)
        super.onPause()
    }

    private fun showStatus() {
        val running = BackupService.running
        b.status.setText(if (running) R.string.status_running else R.string.status_stopped)
        b.statusHint.setText(if (running) R.string.status_running_hint else R.string.status_stopped_hint)
        b.statusDot.isSelected = running
        b.destination.text = prefs.destinationSummary(this)
        b.startStop.setText(if (running) R.string.stop else R.string.start)
        val pending = Transfers.pending().size
        b.syncNow.visibility = if (prefs.mode == Mode.PHONE_AND_SERVER) View.VISIBLE else View.GONE
        b.syncNow.isEnabled = !Uploader.running
        b.syncNow.text = when {
            Uploader.running -> getString(R.string.sync_running)
            pending > 0 -> getString(R.string.sync_now_pending, pending)
            else -> getString(R.string.sync_now)
        }
    }

    private fun showTransfers() {
        val list = Transfers.list()
        adapter.submitList(list)
        b.empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        b.transfersTitle.text = if (list.isEmpty()) getString(R.string.transfers_title) else getString(R.string.transfers_title_n, list.size)
        showStatus()
    }

    class TransferAdapter : ListAdapter<Transfer, TransferAdapter.Holder>(Diff) {
        class Holder(val b: ItemTransferBinding) : RecyclerView.ViewHolder(b.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(ItemTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val t = getItem(position); val c = holder.b.root.context
            val onServer = t.state == TransferState.ON_SERVER
            holder.b.check.text = if (onServer) "✓✓" else "✓"
            holder.b.check.setTextColor(ContextCompat.getColor(c, if (onServer) R.color.state_server else R.color.state_phone))
            holder.b.check.contentDescription = c.getString(if (onServer) R.string.state_on_server else R.string.state_on_phone)
            holder.b.name.text = t.name
            holder.b.details.text = c.getString(R.string.transfer_details, t.date, Ui.formatSize(t.size), Ui.formatTime(t.at))
            holder.b.location.text = c.getString(if (onServer) R.string.state_on_server else R.string.state_on_phone) + " · " + t.location
        }

        object Diff : DiffUtil.ItemCallback<Transfer>() {
            override fun areItemsTheSame(a: Transfer, b: Transfer) = a.key == b.key
            override fun areContentsTheSame(a: Transfer, b: Transfer) = a == b
        }
    }
}
