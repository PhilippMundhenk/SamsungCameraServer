package org.mundhenk.cambackup.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * On the road the camera joins the phone's Wi-Fi hotspot. Android lets no app switch the hotspot
 * on (the tethering APIs are system-only since Android 8), so the button on the main screen
 * brings the user to the hotspot screen of the system settings, as close as the platform allows.
 */
object Hotspot {
    /** Most direct first: the tethering screen (AOSP and most vendor ROMs), then the generic wireless settings. */
    fun candidates(): List<Intent> = listOf(
        Intent().setClassName("com.android.settings", "com.android.settings.TetherSettings"),
        Intent("android.settings.TETHER_SETTINGS"),
        Intent(Settings.ACTION_WIRELESS_SETTINGS),
    )

    /** Opens the first candidate this phone can show; false (and a log line saying so) when there is none. */
    fun openSettings(context: Context, canOpen: (Intent) -> Boolean = { context.packageManager.resolveActivity(it, 0) != null }): Boolean {
        for (intent in candidates()) {
            if (!canOpen(intent)) continue
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                LogBus.log(context.getString(R.string.hotspot_opening))
                return true
            } catch (e: ActivityNotFoundException) {
                // resolvable but gone: try the next one
            } catch (e: SecurityException) {
                // some ROMs keep the tethering screen to themselves
            }
        }
        LogBus.log(context.getString(R.string.hotspot_no_settings))
        return false
    }
}
