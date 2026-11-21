package org.mundhenk.cambackup.app

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import org.mundhenk.cambackup.app.databinding.ActivityViewfinderBinding
import org.mundhenk.cambackup.core.RvfClient
import org.mundhenk.cambackup.core.RvfInfo
import org.mundhenk.cambackup.core.VideoCodec

/**
 * Remote Viewfinder: the camera's live view on the phone, with shutter and zoom. The camera runs
 * its own Wi-Fi in this mode (Wi-Fi menu -> Remote Viewfinder); the phone joins it, and the camera
 * is the gateway of that network.
 */
class ViewfinderActivity : AppCompatActivity(), ViewfinderSession.Listener {
    private lateinit var b: ActivityViewfinderBinding
    private lateinit var prefs: Prefs
    var session: ViewfinderSession? = null; private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        b = ActivityViewfinderBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.edgeToEdge(this, b.root, b.toolbar)
        setSupportActionBar(b.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.saveShots.isChecked = prefs.rvfSaveShots
        b.saveShots.setOnCheckedChangeListener { _, on -> prefs.rvfSaveShots = on }
        b.connect.setOnClickListener { if (session == null) connect() else disconnect() }
        b.shutter.setOnClickListener { b.shutter.isEnabled = false; session?.shoot() }
        b.zoomIn.setOnClickListener { session?.zoom(true) }
        b.zoomOut.setOnClickListener { session?.zoom(false) }
        b.wifiSettings.setOnClickListener {
            runCatching { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        }
        showIdle(getString(R.string.vf_idle))
    }

    override fun onResume() {
        super.onResume()
        // the field shows what will be used: the typed address, else the Wi-Fi gateway
        if (b.address.text.isNullOrBlank()) b.address.setText(prefs.rvfAddress.ifBlank { CameraNetwork.gateway(this) ?: DEFAULT_CAMERA })
    }

    override fun onStop() {
        disconnect()
        super.onStop()
    }

    private fun connect() {
        val typed = b.address.text?.toString().orEmpty()
        val target = CameraNetwork.parse(typed)
        if (target == null) { b.addressLayout.error = getString(R.string.vf_address_invalid); return }
        b.addressLayout.error = null
        // remember only an address that differs from the automatic one
        prefs.rvfAddress = if (typed.trim() == CameraNetwork.gateway(this)) "" else typed
        val s = ViewfinderSession(this, target.first, target.second, this) { b.liveSurface.holder.surface?.takeIf { it.isValid } }
        session = s
        s.start()
    }

    private fun disconnect() {
        val s = session ?: return
        session = null
        s.stop()
    }

    override fun onState(state: ViewfinderSession.State, message: String) {
        when (state) {
            ViewfinderSession.State.CONNECTING -> {
                b.liveStatus.visibility = View.VISIBLE; b.liveStatus.text = message
                b.connect.setText(R.string.vf_cancel)
                b.setupCard.visibility = View.GONE
            }
            ViewfinderSession.State.LIVE -> {
                b.liveStatus.visibility = View.GONE
                b.controls.visibility = View.VISIBLE
                b.shutter.isEnabled = true
                b.connect.setText(R.string.vf_disconnect)
                supportActionBar?.subtitle = message
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ViewfinderSession.State.CLOSED, ViewfinderSession.State.FAILED -> {
                if (state == ViewfinderSession.State.FAILED) session = null
                showIdle(if (state == ViewfinderSession.State.FAILED) getString(R.string.vf_failed, message) else message)
            }
        }
    }

    private fun showIdle(message: String) {
        b.liveStatus.visibility = View.VISIBLE; b.liveStatus.text = message
        b.liveImage.visibility = View.GONE; b.liveInfo.visibility = View.GONE
        b.controls.visibility = View.GONE
        b.setupCard.visibility = View.VISIBLE
        b.connect.setText(R.string.vf_connect)
        supportActionBar?.subtitle = null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onConnected(client: RvfClient, info: RvfInfo) {
        val zoom = if (client.canZoom) View.VISIBLE else View.GONE
        b.zoomIn.visibility = zoom; b.zoomOut.visibility = zoom
    }

    override fun onStream(codec: VideoCodec, width: Int, height: Int) {
        b.liveInfo.visibility = View.VISIBLE
        b.liveInfo.text = getString(R.string.vf_stream_info, width, height, if (codec == VideoCodec.MJPEG) "MJPEG" else "H.264")
        b.liveImage.visibility = if (codec == VideoCodec.MJPEG) View.VISIBLE else View.GONE
    }

    override fun onJpegFrame(bitmap: Bitmap) { b.liveImage.setImageBitmap(bitmap) }

    override fun onShot(name: String?, savedAs: String?, message: String) {
        b.shutter.isEnabled = true
        b.lastShot.visibility = View.VISIBLE
        b.lastShot.text = message
    }

    override fun onZoom(position: Int?) {
        if (position != null) b.liveInfo.text = b.liveInfo.text.toString().substringBefore(" · zoom") + " · zoom $position"
    }

    companion object {
        /** Where the cameras put themselves in Remote Viewfinder mode when nothing better is known. */
        const val DEFAULT_CAMERA = "192.168.102.1"
    }
}
