package com.dji.fccgpsoff

import android.content.Context

/**
 * Per-serial memory of what we learned about a drone: its model and which
 * parameter-name variant (Lito short names vs the g_config.* forms) it answered
 * to. Keyed by the factory serial so a known drone is recognised instantly on
 * the next connect without re-probing.
 */
object DeviceStore {

    private const val PREF = "device_store"

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** Save what we know for [serial]. [lito] null means "variant not determined". */
    fun save(ctx: Context, serial: String, modelCode: String?, modelName: String?, lito: Boolean?) {
        if (serial.isBlank()) return
        p(ctx).edit().apply {
            modelCode?.let { putString("$serial.code", it) }
            modelName?.let { putString("$serial.name", it) }
            if (lito != null) putBoolean("$serial.lito", lito)
            putLong("$serial.at", System.currentTimeMillis())
        }.apply()
    }

    /** Remembered name-variant for [serial]: true = Lito names, false = other, null = unknown. */
    fun variant(ctx: Context, serial: String): Boolean? {
        val pr = p(ctx)
        return if (pr.contains("$serial.lito")) pr.getBoolean("$serial.lito", true) else null
    }

    /**
     * Record that the user chose the name-variant for [serial] by hand.
     *
     * Without this the override was silently undone: [StartupProbe] restores the cached
     * variant on every run and pushes it into [AppState], so the next probe after a manual
     * flip put the old value straight back with no message.
     */
    fun setManualVariant(ctx: Context, serial: String, lito: Boolean) {
        if (serial.isBlank()) return
        p(ctx).edit().putBoolean("$serial.lito", lito).putBoolean("$serial.litoManual", true).apply()
        DiagLog.info("device store: manual name-variant for $serial = " + if (lito) "Lito" else "other")
    }

    /** Did the user set the variant for [serial] by hand? Then no probe may overwrite it. */
    fun variantIsManual(ctx: Context, serial: String): Boolean =
        p(ctx).getBoolean("$serial.litoManual", false)

    /**
     * Forget everything remembered about the name-variant for [serial] — both the value and
     * the manual flag — so the next [StartupProbe] run actually re-probes.
     *
     * Clearing only the manual flag is not enough: the cached value still short-circuits
     * `known ?: detectVariant()`, so the probe never runs and "re-detect" silently returns
     * the old answer.
     */
    fun clearVariant(ctx: Context, serial: String) {
        if (serial.isBlank()) return
        p(ctx).edit().remove("$serial.lito").remove("$serial.litoManual").apply()
        DiagLog.info("device store: name-variant for $serial forgotten — it will be re-probed")
    }

    /** Parameter-table fingerprint reported by `03:E0` — identifies the firmware build. */
    fun saveTableFingerprint(ctx: Context, serial: String, crc: Long, entries: Int) {
        if (serial.isBlank()) return
        p(ctx).edit().putLong("$serial.tblcrc", crc).putInt("$serial.tblnum", entries).apply()
    }

    /** (crc, entries) last seen for [serial], or null. */
    fun tableFingerprint(ctx: Context, serial: String): Pair<Long, Int>? {
        val pr = p(ctx)
        if (!pr.contains("$serial.tblcrc")) return null
        return pr.getLong("$serial.tblcrc", 0L) to pr.getInt("$serial.tblnum", 0)
    }

    /** Remembered (code, name) for [serial], or null. */
    fun model(ctx: Context, serial: String): Pair<String, String?>? {
        val code = p(ctx).getString("$serial.code", null) ?: return null
        return code to p(ctx).getString("$serial.name", null)
    }

    /** Drop the remembered model for [serial] (the name-variant is kept — it's the
     *  param-probe result, not the mislabelled model). Used to recover from a bad
     *  cached identity, e.g. a model scraped off DJI Fly's device-picker screen. */
    fun forgetModel(ctx: Context, serial: String) {
        if (serial.isBlank()) return
        p(ctx).edit().remove("$serial.code").remove("$serial.name").apply()
        DiagLog.info("device store: forgot cached model for $serial")
    }
}
