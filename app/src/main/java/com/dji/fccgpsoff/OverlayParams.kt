package com.dji.fccgpsoff

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Parameters the user pinned to the floating menu from the parameter editor
 * (2026-08-19).
 *
 * The overlay's built-in buttons (FCC / LED / GPS) are the three things everyone
 * wants; which FOURTH thing matters is per-aircraft and per-pilot, so it is a
 * choice rather than a guess. A pin is made in the editor — the one place that
 * already knows a parameter's type, limits and default — and carries those with
 * it, because the overlay is used while DJI Fly is in front, i.e. exactly when
 * reads are blocked and nothing can be looked up on the aircraft.
 *
 * Only the catalog's own metadata is stored, never a live value: a pin outlives
 * the catalog it was made from (and the app restart), and a stale reading shown
 * over a live flight would be worse than none.
 */
object OverlayParams {

    /**
     * One pinned parameter. [typeName], [min], [max] and [def] are copied from the
     * catalog entry so a write can be encoded with no catalog loaded and no read.
     */
    data class Pin(
        val name: String, val typeName: String,
        val min: String, val max: String, val def: String,
    ) {
        /** The catalog entry this pin was made from, rebuilt for [ParamCatalog.encodeChecked]. */
        fun def(): ParamCatalog.Def = ParamCatalog.Def(name, "", -1, min, max, def, typeName)

        /** A 0/1 parameter gets ON/OFF buttons; anything else gets min/default/max. */
        val binary: Boolean get() = min.toLongOrNull() == 0L && max.toLongOrNull() == 1L

        /** Short label for the overlay row — a full g_config.* name does not fit. */
        fun short(): String = name.substringAfterLast('.').take(22)
    }

    /**
     * How many pins the floating panel can carry. The panel is a fixed-width
     * window over DJI Fly on a short controller screen; past about this many rows
     * it would cover the flight view it is meant to sit beside.
     */
    const val MAX = 6

    private const val PREF = "fcc_prefs"
    private const val KEY = "overlay_params"

    // The parameter table asks per ROW while scrolling, so this must not hit
    // SharedPreferences each time. Invalidated by every mutation.
    @Volatile private var cache: List<Pin>? = null

    fun list(ctx: Context): List<Pin> = cache ?: load(ctx).also { cache = it }

    fun contains(ctx: Context, name: String): Boolean = list(ctx).any { it.name == name }

    /**
     * Pin or unpin [d]. Returns false when the pin was refused because [MAX] is
     * already reached — the caller must then put its checkbox back, since nothing
     * was stored.
     */
    fun set(ctx: Context, d: ParamCatalog.Def, on: Boolean): Boolean {
        val cur = list(ctx)
        if (on && cur.any { it.name == d.name }) return true
        if (on && cur.size >= MAX) return false
        val next = if (on) cur + Pin(d.name, d.typeName, d.min, d.max, d.def)
                   else cur.filter { it.name != d.name }
        save(ctx, next)
        DiagLog.info("overlay params: " + (if (on) "pinned " else "unpinned ") + d.name + " (${next.size}/$MAX)")
        return true
    }

    fun clear(ctx: Context) = save(ctx, emptyList())

    private fun save(ctx: Context, list: List<Pin>) {
        val arr = JSONArray()
        for (p in list) arr.put(JSONObject().apply {
            put("name", p.name); put("type", p.typeName)
            put("min", p.min); put("max", p.max); put("def", p.def)
        })
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
        cache = list
    }

    /** Tolerant read: a malformed or truncated entry is dropped, never thrown —
     *  a bad preference must not take the overlay (or the editor) down with it. */
    private fun load(ctx: Context): List<Pin> = runCatching {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return@runCatching emptyList<Pin>()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val n = o.optString("name").trim()
            if (n.isEmpty()) null
            else Pin(n, o.optString("type", ""), o.optString("min", ""),
                     o.optString("max", ""), o.optString("def", ""))
        }.take(MAX)
    }.getOrElse { DiagLog.warn("overlay params: unreadable preference — ${it.message}"); emptyList() }
}
