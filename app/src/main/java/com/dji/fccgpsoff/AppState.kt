package com.dji.fccgpsoff

import android.content.Context

/**
 * Small persisted app state: device-name profile + per-service "auto-start on
 * launch" flags.
 *
 * `litoMode` selects which parameter-name variant is written (one name, no
 * candidate spray): Lito X1 uses the short names (forearm_led_ctrl, gps_enable,
 * ce_regulatory_level), most other DJI use the g_config.* / c1_* forms.
 */
object AppState {
    @Volatile var litoMode = true          // default to Lito (the test device)
    // FCC auto-apply-on-connect and the floating controls are ON by default — the
    // app is meant to come up armed on a fresh install / first boot. Overlay still
    // only actually starts once SYSTEM_ALERT_WINDOW is granted.
    @Volatile var autoKeepalive = true
    /** Which trigger the keepalive uses; event-driven by default (no idle traffic). */
    @Volatile var keepaliveMode = KeepaliveMode.HOME_POINT
    @Volatile var autoDiag = false
    @Volatile var autoOverlay = true
    /** True once we've shown the first-launch accessibility prompt (reset on reinstall). */
    @Volatile var a11yPrompted = false
    /** True once we've asked about the battery-optimisation exemption. Asked at most once
     *  outside the wizard: it is a recommendation, not a requirement, and an app that keeps
     *  re-opening a system settings screen is an app people stop reading. */
    @Volatile var batteryPrompted = false
    /** True once the setup wizard has been completed (or explicitly skipped through).
     *  While false, [MainActivity] routes straight to [SetupWizardActivity]. */
    @Volatile var wizardDone = false
    /** UI language of the WHOLE app: true = Russian. Seeded from the device
     *  locale on first run, then whatever the user picked. See [t]. */
    @Volatile var uiRu = true
    /** Check GitHub for a newer release on launch (at most every 6 h). */
    @Volatile var autoUpdateCheck = true
    /** Also offer releases GitHub marks as pre-release. Off: a pre-release is a
     *  build the author has not called finished, so it must be opted into. */
    @Volatile var updatePrerelease = false
    /** When the last check ran, so a launch does not hit the API every time. */
    @Volatile var lastUpdateCheckMs = 0L
    /** Radio country every FCC command writes. AU is what every hardware-confirmed
     *  run used; the rest of the list is what the firmware was seen to take. */
    @Volatile var fccRegion = FccRegion.DEFAULT

    /**
     * Which wizard step to resume on — persisted, not just held in the Activity.
     *
     * Granting "install unknown apps" is an app-op change, and the system restarts
     * our process when it is toggled: the Activity is rebuilt from scratch (a saved
     * instance-state Bundle does not survive that reliably), so a step kept only in
     * a field snapped back to the welcome page. Reset to 0 when the wizard finishes.
     */
    @Volatile var wizardStep = 0

    /** Persisted SAF tree uri for DJI Fly's FlightRecord folder (null = not granted). */
    @Volatile var recordsTree: String? = null

    /** Last dragged position of the floating overlay handle (px, TOP|LEFT). -1 = unset ⇒ default. */
    @Volatile var overlayX = -1
    @Volatile var overlayY = -1

    private const val PREF = "fcc_prefs"

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        litoMode = p.getBoolean("lito_mode", true)
        autoKeepalive = p.getBoolean("auto_keepalive", true)
        keepaliveMode = KeepaliveMode.of(p.getString("keepalive_mode", null))
        autoDiag = p.getBoolean("auto_diag", false)
        autoOverlay = p.getBoolean("auto_overlay", true)
        a11yPrompted = p.getBoolean("a11y_prompted", false)
        batteryPrompted = p.getBoolean("battery_prompted", false)
        wizardDone = p.getBoolean("wizard_done", false)
        // Default to the device locale, not to a fixed choice; migrate the older
        // wizard-only key, so a build installed before the
        // language became app-wide keeps the choice the user already made.
        val seed = p.getBoolean("wizard_ru", java.util.Locale.getDefault().language == "ru")
        uiRu = p.getBoolean("ui_ru", seed)
        wizardStep = p.getInt("wizard_step", 0)
        autoUpdateCheck = p.getBoolean("auto_update_check", true)
        updatePrerelease = p.getBoolean("update_prerelease", false)
        lastUpdateCheckMs = p.getLong("last_update_check", 0L)
        fccRegion = FccRegion.of(p.getString("fcc_region", null))
        recordsTree = p.getString("records_tree", null)
        overlayX = p.getInt("overlay_x", -1)
        overlayY = p.getInt("overlay_y", -1)
    }

    private fun put(ctx: Context, key: String, v: Boolean) =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(key, v).apply()

    /** Save the floating overlay's dragged position (persists across restarts). */
    fun setOverlayPos(ctx: Context, x: Int, y: Int) {
        overlayX = x; overlayY = y
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("overlay_x", x).putInt("overlay_y", y).apply()
    }

    fun setLito(ctx: Context, v: Boolean) { litoMode = v; put(ctx, "lito_mode", v); DiagLog.info("device profile: " + (if (v) "Lito X1" else "Other DJI")) }
    fun setAutoKeepalive(ctx: Context, v: Boolean) { autoKeepalive = v; put(ctx, "auto_keepalive", v) }
    fun setKeepaliveMode(ctx: Context, v: KeepaliveMode) {
        keepaliveMode = v
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("keepalive_mode", v.wire).apply()
        DiagLog.info("keepalive trigger: ${v.label}")
    }
    fun setAutoDiag(ctx: Context, v: Boolean) { autoDiag = v; put(ctx, "auto_diag", v) }
    fun setA11yPrompted(ctx: Context, v: Boolean) { a11yPrompted = v; put(ctx, "a11y_prompted", v) }
    fun setBatteryPrompted(ctx: Context, v: Boolean) { batteryPrompted = v; put(ctx, "battery_prompted", v) }
    fun setWizardDone(ctx: Context, v: Boolean) { wizardDone = v; put(ctx, "wizard_done", v) }
    fun setUiRu(ctx: Context, v: Boolean) { uiRu = v; put(ctx, "ui_ru", v) }
    fun setAutoUpdateCheck(ctx: Context, v: Boolean) { autoUpdateCheck = v; put(ctx, "auto_update_check", v) }
    fun setUpdatePrerelease(ctx: Context, v: Boolean) { updatePrerelease = v; put(ctx, "update_prerelease", v) }
    fun setLastUpdateCheck(ctx: Context, v: Long) {
        lastUpdateCheckMs = v
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putLong("last_update_check", v).apply()
    }
    // commit(), not apply(), on purpose: the app-op grants the wizard asks for can
    // restart the process immediately, before an async write would have reached
    // disk — and losing this write is exactly the bug it exists to prevent.
    @android.annotation.SuppressLint("ApplySharedPref")
    fun setWizardStep(ctx: Context, v: Int) {
        wizardStep = v
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt("wizard_step", v).commit()
    }
    fun setAutoOverlay(ctx: Context, v: Boolean) { autoOverlay = v; put(ctx, "auto_overlay", v) }
    fun setFccRegion(ctx: Context, v: FccRegion) {
        fccRegion = v
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("fcc_region", v.code).apply()
        DiagLog.info("FCC region: " + v.display())
    }
    fun setRecordsTree(ctx: Context, v: String?) {
        recordsTree = v
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString("records_tree", v).apply()
    }
}
