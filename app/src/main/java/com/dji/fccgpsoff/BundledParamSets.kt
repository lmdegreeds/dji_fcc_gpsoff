package com.dji.fccgpsoff

import android.content.Context
import org.json.JSONArray

/**
 * The parameter catalogs shipped inside the APK, so the editor works with no
 * file to pick. Each entry is a real DJI Param Studio export (minified) under
 * `assets/param_sets/`, plus a synthetic "common to all models" set built from
 * the names that appear identically in every bundled model.
 *
 * A chosen set is loaded through the exact same [ParamCatalog.load] path a
 * file-picked export takes, so a bundled set and a user file behave identically
 * once loaded — same parse, same limits, same write validation.
 *
 * The manifest is `assets/param_sets/index.json`, generated from the reference
 * dumps in `params_example/`; see that file for how the sets were derived.
 */
object BundledParamSets {

    private const val DIR = "param_sets"

    /** One shippable set: a stable [id], bilingual label, the asset file, and the
     *  parameter [count] / model-[unique] counts precomputed at bundle time. */
    data class Entry(
        val id: String, val labelRu: String, val labelEn: String,
        val file: String, val count: Int, val unique: Int,
    ) {
        fun label(): String = if (AppState.uiRu) labelRu else labelEn
        /** True for the cross-model "common" set, which leads the list. */
        val isCommon: Boolean get() = id == "_common"
    }

    @Volatile private var cache: List<Entry>? = null

    /** The bundled sets, common-first (as ordered in the manifest). Parsed once
     *  and cached; returns empty if the assets are somehow absent. */
    fun list(ctx: Context): List<Entry> {
        cache?.let { return it }
        val out = runCatching {
            val text = ctx.assets.open("$DIR/index.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Entry(o.getString("id"),
                    o.optString("label_ru", o.optString("label_en")),
                    o.optString("label_en", o.optString("label_ru")),
                    o.getString("file"), o.optInt("count"), o.optInt("unique"))
            }
        }.getOrElse {
            DiagLog.info("bundled param sets unavailable: ${it.message}"); emptyList()
        }
        cache = out
        return out
    }

    /** Look up a set by its stable id (used by the web dashboard's `?id=`). */
    fun byId(ctx: Context, id: String): Entry? = list(ctx).firstOrNull { it.id == id }

    /** Load a bundled set into [ParamCatalog], returning the count loaded. [label]
     *  is the catalog's source name — defaults to the language-appropriate label,
     *  but the web dashboard passes [Entry.labelEn] so its source line stays English
     *  regardless of the app's UI language. Throws the same exceptions
     *  [ParamCatalog.load] would on a malformed asset. */
    fun load(ctx: Context, e: Entry, label: String = e.label()): Int {
        val text = ctx.assets.open("$DIR/${e.file}").bufferedReader().use { it.readText() }
        return ParamCatalog.load(text, label)
    }
}
