package com.dji.fccgpsoff

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * The grants this app depends on: one place that knows how to ASK for each and how to
 * check whether it has it (2026-08-20).
 *
 * These queries were scattered — "is the accessibility service enabled" existed three
 * times with two different comparison strategies — and each copy was written where it was
 * first needed rather than where it belongs. A grant is a property of the installation,
 * not of a screen.
 */
object Grants {

    /**
     * Is OUR accessibility service switched on in Android's settings?
     *
     * Distinct from [ForegroundGate.accessibilityConnected], which is "bound right now".
     * Android disables the service on every reinstall, so the two disagree often enough
     * that collapsing them into one flag hides a real failure.
     */
    fun accessibilityEnabled(ctx: Context): Boolean = runCatching {
        val want = ComponentName(ctx, DjiFlyAccessibilityService::class.java).flattenToString()
        val flat = Settings.Secure.getString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        flat.split(':').any { it.equals(want, ignoreCase = true) }
    }.getOrDefault(false)

    /**
     * Is this app exempt from battery optimisation — i.e. allowed to keep running when the
     * screen is off and the controller is idle?
     *
     * **Why it matters here.** The FCC keepalive is a foreground service, and on a stock
     * Android that would be enough. On a controller it is not: the RC sits idle between
     * flights with the screen off, and an optimised app is frozen — the keepalive stops
     * re-applying FCC and nothing says so, because a frozen process cannot write a log line
     * either. The failure is completely silent, and the only trace is a gap in the log.
     */
    fun batteryUnrestricted(ctx: Context): Boolean = runCatching {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    }.getOrDefault(false)

    /**
     * How to ask for the battery exemption, best first.
     *
     * The first is the one-tap system dialog ("Allow app to always run in the background?").
     * It needs `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` in the manifest, and some vendor ROMs
     * refuse it outright — hence the second, the plain optimisation list, which needs no
     * permission and always exists. The caller tries them in order.
     *
     * The direct dialog is restricted on Google Play. This app is not distributed there —
     * it is side-loaded from GitHub releases — and the exemption is the difference between
     * a keepalive that survives the night and one that does not.
     */
    fun batteryExemptionIntents(ctx: Context): List<Intent> = listOf(
        @Suppress("BatteryLife")
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        // Last resort: this app's own settings page, from which the user can reach
        // "Battery" on every ROM that has hidden the two above.
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )

    /**
     * Open the first battery-exemption screen that this device will actually show.
     *
     * Returns false when none of them resolved, which is a real outcome on a stripped ROM
     * and must be reported rather than silently swallowed.
     */
    fun openBatterySettings(ctx: Context, addNewTask: Boolean = false): Boolean {
        for (i in batteryExemptionIntents(ctx)) {
            val intent = if (addNewTask) Intent(i).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else i
            if (runCatching { ctx.startActivity(intent); true }.getOrDefault(false)) {
                DiagLog.info("battery exemption: opened ${intent.action}")
                return true
            }
        }
        DiagLog.warn("battery exemption: this device offers none of the three settings screens " +
            "(${batteryExemptionIntents(ctx).joinToString { it.action.orEmpty() }})")
        return false
    }

    /** Android version note for the UI — the exemption exists from API 23 and this app
     *  runs from 24, so it is always available; kept as a named fact rather than a magic
     *  absence of a version check. */
    const val BATTERY_SUPPORTED_FROM = Build.VERSION_CODES.M
}
