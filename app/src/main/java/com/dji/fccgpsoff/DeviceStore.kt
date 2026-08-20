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

    /**
     * HOW a remembered name-variant was decided (2026-08-20).
     *
     * Stored beside the value because the value alone is not enough to act on, and
     * because the app must not present a remembered guess as a measurement — a user
     * reading "Lito (probed)" reasonably concluded a probe had run and produced that
     * answer, when in fact the line was a cache hit 56 ms after startup.
     */
    enum class VariantOrigin(val wire: String) {
        /** `03:F7` answered Ok for one spelling: the aircraft said so. */
        PROBE("probe"),
        /** No spelling was confirmed, but one was explicitly denied, so the other was
         *  taken on that negative alone. Weaker than [PROBE] and re-probable. */
        NEGATIVE("negative"),
        /** The user set it by hand. Outranks every probe. */
        MANUAL("manual"),
        /** Remembered by a build that did not record how it decided. Treated as
         *  unverified — it is exactly the state the Air 3 report came from. */
        LEGACY("legacy");

        companion object {
            fun of(w: String?): VariantOrigin = values().firstOrNull { it.wire == w } ?: LEGACY
        }
    }

    /** A remembered name-variant with its provenance. */
    class VariantRecord(val lito: Boolean, val origin: VariantOrigin, val atMs: Long) {
        /** Can a probe overwrite this? A hand-made choice may not be overwritten. */
        val isManual: Boolean get() = origin == VariantOrigin.MANUAL
        /** Was it ever actually confirmed by the aircraft? */
        val isMeasured: Boolean get() = origin == VariantOrigin.PROBE
    }

    /** Save what we know for [serial]. [lito] null means "variant not determined". */
    fun save(ctx: Context, serial: String, modelCode: String?, modelName: String?,
             lito: Boolean?, origin: VariantOrigin? = null) {
        if (serial.isBlank()) return
        p(ctx).edit().apply {
            modelCode?.let { putString("$serial.code", it) }
            modelName?.let { putString("$serial.name", it) }
            if (lito != null) {
                putBoolean("$serial.lito", lito)
                if (origin != null) {
                    putString("$serial.litoSrc", origin.wire)
                    putLong("$serial.litoAt", System.currentTimeMillis())
                }
            }
            putLong("$serial.at", System.currentTimeMillis())
        }.apply()
    }

    /** Remembered name-variant for [serial]: true = Lito names, false = other, null = unknown. */
    fun variant(ctx: Context, serial: String): Boolean? = record(ctx, serial)?.lito

    /** Remembered name-variant WITH its provenance, or null when nothing is stored. */
    fun record(ctx: Context, serial: String): VariantRecord? {
        if (serial.isBlank()) return null
        val pr = p(ctx)
        if (!pr.contains("$serial.lito")) return null
        // Migration: a build before 2026-08-20 stored only the value and a manual flag.
        val origin = when {
            pr.contains("$serial.litoSrc") -> VariantOrigin.of(pr.getString("$serial.litoSrc", null))
            pr.getBoolean("$serial.litoManual", false) -> VariantOrigin.MANUAL
            else -> VariantOrigin.LEGACY
        }
        // 0 = "when this was decided is not recorded". Deliberately NOT falling back to
        // "$serial.at": save() rewrites that on every call, so a migrated record would be
        // dated to the last time anything was stored — and the UI would report a years-old
        // guess as minutes old (2026-08-20).
        return VariantRecord(
            pr.getBoolean("$serial.lito", true), origin,
            pr.getLong("$serial.litoAt", 0L)
        )
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
        p(ctx).edit()
            .putBoolean("$serial.lito", lito)
            .putBoolean("$serial.litoManual", true)
            .putString("$serial.litoSrc", VariantOrigin.MANUAL.wire)
            .putLong("$serial.litoAt", System.currentTimeMillis())
            .apply()
        DiagLog.info("device store: manual name-variant for $serial = " + if (lito) "Lito" else "other")
    }

    /** Did the user set the variant for [serial] by hand? Then no probe may overwrite it. */
    fun variantIsManual(ctx: Context, serial: String): Boolean =
        record(ctx, serial)?.isManual == true

    /**
     * Forget everything remembered about the name-variant for [serial] — the value, its
     * provenance and the manual flag — so the next [StartupProbe] run actually re-probes.
     *
     * Clearing only the manual flag is not enough: the cached value still short-circuits
     * `known ?: detectVariant()`, so the probe never runs and "re-detect" silently returns
     * the old answer.
     */
    fun clearVariant(ctx: Context, serial: String) {
        if (serial.isBlank()) return
        p(ctx).edit().remove("$serial.lito").remove("$serial.litoManual")
            .remove("$serial.litoSrc").remove("$serial.litoAt").apply()
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
