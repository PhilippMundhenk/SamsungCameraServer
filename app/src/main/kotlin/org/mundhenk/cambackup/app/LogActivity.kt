package org.mundhenk.cambackup.app

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import org.mundhenk.cambackup.app.databinding.ActivityLogBinding

/** The in-app log (what the camera and the server did), with a clear action. */
class LogActivity : AppCompatActivity() {
    private lateinit var b: ActivityLogBinding
    private val logListener: (String) -> Unit = { line -> b.log.append(line + "\n"); b.scroll.post { b.scroll.fullScroll(android.view.View.FOCUS_DOWN) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityLogBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.edgeToEdge(this, b.root, b.toolbar)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean { menuInflater.inflate(R.menu.menu_log, menu); return true }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_clear_log -> { LogBus.clear(); b.log.text = ""; true }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        b.log.text = LogBus.snapshot().joinToString("\n", postfix = "\n")
        LogBus.addListener(logListener)
    }

    override fun onPause() {
        LogBus.removeListener(logListener)
        super.onPause()
    }
}
