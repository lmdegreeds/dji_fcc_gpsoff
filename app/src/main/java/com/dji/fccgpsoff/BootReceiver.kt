package com.dji.fccgpsoff

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Re-arms the auto-start services after a controller reboot, honouring the SAME
 * per-service flags MainActivity applies on launch.
 *
 * Previously this started keepalive UNCONDITIONALLY and never started the
 * overlay — so keepalive came up even with its autostart off, while the overlay
 * stayed down with its autostart on: exactly backwards from the toggles. Now
 * each service starts only if its flag is set, mirroring MainActivity.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Some controllers emit a QUICKBOOT action instead of BOOT_COMPLETED;
        // MY_PACKAGE_REPLACED covers an in-place APK update, which stops the app
        // and takes the services down with it.
        val trigger = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> "quickboot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "apk update"
            else -> return
        }

        // Fresh process on boot: the AppState singleton holds compile-time
        // defaults until the saved prefs are loaded. Without this, every flag
        // reads false and nothing (or the wrong thing) starts.
        AppState.load(context)
        DiagLog.info(
            "$trigger autostart: keepalive=${AppState.autoKeepalive} (${AppState.keepaliveMode.label}) " +
                "diag=${AppState.autoDiag} overlay=${AppState.autoOverlay}"
        )

        if (AppState.autoKeepalive) FccKeepaliveService.start(context)
        if (AppState.autoDiag) DiagService.start(context)
        if (AppState.autoOverlay && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context))) {
            OverlayService.start(context)
        } else if (AppState.autoOverlay) {
            // Flag is on but the "draw over other apps" permission is missing —
            // the window can't be shown until the user grants it in the app.
            DiagLog.warn("boot: overlay autostart on but SYSTEM_ALERT_WINDOW not granted")
        }
    }
}
