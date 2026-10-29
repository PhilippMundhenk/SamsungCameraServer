package org.mundhenk.cambackup.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.mundhenk.cambackup.core.BackendSink
import org.mundhenk.cambackup.core.BackupServer
import org.mundhenk.cambackup.core.BackupSink

/**
 * Foreground service hosting the UPnP backup target. Holds a multicast lock
 * (SSDP needs it on Android) and a partial wake lock while running.
 */
class BackupService : Service() {
    private var server: BackupServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        if (server != null) return START_STICKY
        LogBus.log("Samsung Camera Backup ${BuildConfig.VERSION_NAME} starting the server")
        startForegroundCompat()
        acquireLocks()
        val prefs = Prefs(this)
        Transfers.init(this)
        val sink = buildSink(this, prefs)
        try {
            server = BackupServer(prefs.identity(), sink, log = LogBus::log, spoolDir = java.io.File(filesDir, "spool")).also { it.start() }
            running = true
        } catch (e: Exception) {
            LogBus.log("Start failed: $e")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(); server = null
        running = false
        releaseLocks()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, BackupService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("cambackup-ssdp").apply { setReferenceCounted(false); acquire() }
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cambackup-wifi").apply { setReferenceCounted(false); acquire() }
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cambackup:server").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        multicastLock = null; wifiLock = null; wakeLock = null
    }

    companion object {
        const val ACTION_STOP = "org.mundhenk.cambackup.STOP"
        private const val CHANNEL = "server"
        private const val NOTIF_ID = 1

        @Volatile var running: Boolean = false
            private set

        /** The sink for the configured mode, wrapped so the transfer list follows every file. */
        fun buildSink(context: Context, prefs: Prefs): BackupSink = when (prefs.mode) {
            Mode.PHONE, Mode.PHONE_AND_SERVER -> {
                val local = Sinks.direct(context, prefs.folderUri)
                val sync = prefs.mode == Mode.PHONE_AND_SERVER
                LogBus.log("Mode: ${if (sync) "phone + server" else "phone"} → ${prefs.folderUri ?: Sinks.defaultFolder(context)}" + if (sync) " → ${prefs.backendUrl}" else "")
                RecordingSink(local, TransferState.ON_PHONE, local::describe) {
                    if (sync) { Uploader.syncInBackground(context); AutoSync.schedule(context) }
                }
            }
            Mode.SERVER -> {
                LogBus.log("Mode: server → ${prefs.backendUrl}" + if (prefs.backendToken.isBlank()) " (no token)" else " (token set)")
                RecordingSink(BackendSink(prefs.backendUrl, LogBus::log, prefs.backendToken, deviceName = prefs.deviceNickname), TransferState.ON_SERVER, { _, _ -> prefs.backendUrl })
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, BackupService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BackupService::class.java).setAction(ACTION_STOP))
        }
    }
}
