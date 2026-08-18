package com.dji.fccgpsoff

import org.json.JSONArray
import org.json.JSONObject

/**
 * A user-loaded parameter catalog from a DJI Param Studio export. Gives the
 * editor page real names (and their limits/defaults) to search and write by
 * hash. Held in memory; reload via the file picker.
 *
 * Param Studio writes two different shapes and both are accepted — see [load].
 */
object ParamCatalog {

    data class Def(
        val name: String, val value: String, val typeId: Int,
        val min: String, val max: String, val def: String,
        /** `U8`/`I16`/`F32`… — the declared type, or "" when the export didn't name one. */
        val typeName: String = "",
    ) {
        /**
         * A parameter is settable only if its limits leave room to move.
         *
         * `min == max == 0` is the common case — the 166 `g_status.*` counters in
         * the Lito X1 export (total distance, motor hours, flight count). But a
         * pinned non-zero range is just as closed: 4 more entries there declare
         * `min == max` at some other value, and the only value they accept is the
         * one they already hold. Both are folded into the same rule.
         *
         * Absent or non-numeric limits mean "unknown", not "locked" — those stay
         * editable rather than being hidden on a guess. The range check in
         * [encodeChecked] already refuses any out-of-range write to a locked
         * entry; this flag just keeps them out of the list instead of failing at
         * write time.
         */
        val editable: Boolean get() {
            val lo = min.toDoubleOrNull() ?: return true
            val hi = max.toDoubleOrNull() ?: return true
            return lo != hi
        }

        /** `min … max`, or "" when the export carried no limits. */
        val range: String get() = if (min.isEmpty() && max.isEmpty()) "" else "$min … $max"
    }

    @Volatile var params: List<Def> = emptyList(); private set
    @Volatile var sourceName: String = ""; private set

    /**
     * Parse a parameter export and replace the catalog; returns the count loaded.
     * Two on-disk shapes exist and both are accepted:
     *
     *  - **v1 `.dhp`** — a bare JSON array of
     *    `{name, value, min, max, default, type_id}`.
     *  - **v2 `.dhv2params`** — an object `{"version":"v1","params":[…]}` whose
     *    entries name the type as a string and nest the limits one level down
     *    under the matching variant:
     *    `{name, param_type:"U8", data:{"Integer":{limit_min,limit_max,default,value}}}`.
     *
     * Feeding a v2 file to the array parser is what "parse failed" used to mean.
     */
    fun load(json: String, name: String): Int {
        // A BOM is not whitespace to trim(), and it would make JSONArray/JSONObject
        // reject an otherwise valid export on the very first character.
        val text = json.trim().removePrefix("\uFEFF").trim()
        val list = when (text.firstOrNull()) {
            '[' -> parseV1(JSONArray(text))
            '{' -> parseV2(JSONObject(text).optJSONArray("params")
                ?: throw IllegalArgumentException("JSON object without a \"params\" array — not a Param Studio export"))
            else -> throw IllegalArgumentException("not a JSON export (expected a .dhp array or a .dhv2params object)")
        }
        if (list.isEmpty()) throw IllegalArgumentException("no named parameters in the file")
        params = list
        sourceName = name
        DiagLog.info("param catalog loaded: ${list.size} params from $name")
        return list.size
    }

    /** v1 `.dhp`: limits sit flat on each entry and the type is the numeric `type_id`. */
    private fun parseV1(arr: JSONArray): List<Def> {
        val list = ArrayList<Def>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").trim()
            if (n.isEmpty()) continue
            val id = o.optInt("type_id", -1)
            list.add(Def(n, o.optString("value", ""), id,
                o.optString("min", ""), o.optString("max", ""), o.optString("default", ""),
                TYPE_NAMES[id] ?: ""))
        }
        return list
    }

    /** v2 `.dhv2params`: type is a name, limits are nested under `data.Integer` / `data.Float`. */
    private fun parseV2(arr: JSONArray): List<Def> {
        val list = ArrayList<Def>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").trim()
            if (n.isEmpty()) continue
            val type = o.optString("param_type", "").trim().uppercase()
            val data = o.optJSONObject("data")
            val box = data?.optJSONObject("Integer") ?: data?.optJSONObject("Float")
            val float = data?.optJSONObject("Float") != null || type.startsWith("F")
            list.add(Def(n, num(box, "value", float, type),
                TYPE_NAMES.entries.firstOrNull { it.value == type }?.key ?: -1,
                num(box, "limit_min", float, type), num(box, "limit_max", float, type),
                num(box, "default", float, type), type))
        }
        return list
    }

    /**
     * Render one v2 limit/value as the string the rest of this object expects.
     * Integers keep an exact decimal form so the min/max check can parse them;
     * floats always keep a decimal point (at the type's real precision), which
     * keeps `toLongOrNull()` null and so keeps a float out of the integer paths.
     */
    private fun num(o: JSONObject?, key: String, float: Boolean, type: String): String {
        if (o == null || o.isNull(key)) return ""
        if (!float) return o.optLong(key, 0L).toString()
        val d = o.optDouble(key, 0.0)
        if (d.isNaN() || d.isInfinite()) return ""
        return if (type == "F64") d.toString() else d.toFloat().toString()
    }

    /**
     * The two exports name the same types differently — v1 carries a numeric
     * `type_id`, v2 a `param_type` string. The pairing was read off a v1 and a v2
     * export of the same aircraft (dji air3): all 821 shared parameters agree on
     * U8→0, U16→1, U32→2, I8→4, I16→5, F32→8, F64→9. 3/6/7 are the unobserved
     * gaps in that run, filled in from its unsigned/signed/float grouping.
     */
    internal val TYPE_NAMES = mapOf(
        0 to "U8", 1 to "U16", 2 to "U32", 3 to "U64",
        4 to "I8", 5 to "I16", 6 to "I32", 7 to "I64",
        8 to "F32", 9 to "F64",
    )

    /** Exact-name lookup (for a write that needs the catalog's min/max/type). */
    fun find(name: String): Def? = params.firstOrNull { it.name == name }

    /** Case-insensitive name filter — every match, uncapped. Callers that render a
     *  row per entry should cap with [search] and report the full [matches] count,
     *  so a truncated view never reads as "that's all there is". */
    fun matches(q: String, editableOnly: Boolean = false): List<Def> {
        val ql = q.trim().lowercase()
        return params.filter {
            (!editableOnly || it.editable) && (ql.isEmpty() || it.name.lowercase().contains(ql))
        }
    }

    /** [matches] capped so a list stays snappy; a [limit] of 0 or less means no cap. */
    fun search(q: String, limit: Int = 300, editableOnly: Boolean = false): List<Def> {
        val all = matches(q, editableOnly)
        return if (limit <= 0) all else all.take(limit)
    }

    /** Outcome of [encodeChecked]: either the exact bytes to write (with a note on
     *  how the width was chosen, for the confirm dialog) or a human reason it was
     *  rejected. */
    sealed interface Encoded {
        data class Ok(val bytes: ByteArray, val widthNote: String) : Encoded
        data class Invalid(val reason: String) : Encoded
    }

    /**
     * Safe encode for the parameter editor. Unlike [encode] it never silently
     * assumes a 1-byte width or truncates:
     *   - `0x..` is an explicit-width raw literal (expert mode); if the parameter's
     *     read-back width is known it must match.
     *   - a decimal is range-checked against the catalog min/max, and packed
     *     little-endian into the **known** width — read-back first, then the
     *     declared type, and only then a guess from max. If the width can't be
     *     determined it is REFUSED: writing a flight-controller param with a
     *     guessed size is worse than not writing.
     *   - an F32/F64 param takes a decimal and is laid out as IEEE-754 LE by
     *     [encodeFloat] — its width is never a guess, so this is safe.
     *   - a negative value on an integer param still requires raw `0x..` (the
     *     signed encoding isn't modelled).
     */
    fun encodeChecked(def: Def, valueStr: String, readWidth: Int?): Encoded {
        val s = valueStr.trim()
        if (s.isEmpty()) return Encoded.Invalid("empty value")
        if (s.startsWith("0x", ignoreCase = true)) {
            val bytes = runCatching { DumlWire.hex(s.substring(2)) }.getOrNull()
                ?: return Encoded.Invalid("not valid hex")
            if (bytes.isEmpty()) return Encoded.Invalid("empty hex")
            if (readWidth != null && bytes.size != readWidth)
                return Encoded.Invalid("hex is ${bytes.size} B but the parameter reads back $readWidth B")
            return Encoded.Ok(bytes, "raw hex (${bytes.size} B)")
        }
        if (def.typeName == "F32" || def.typeName == "F64") return encodeFloat(def, s, readWidth)
        val n = s.toLongOrNull()
            ?: return Encoded.Invalid("not a decimal integer (use 0x.. for raw bytes)")
        def.min.toLongOrNull()?.let { if (n < it) return Encoded.Invalid("$n is below min $it") }
        def.max.toLongOrNull()?.let { if (n > it) return Encoded.Invalid("$n is above max $it") }
        if (n < 0) return Encoded.Invalid("negative value — enter raw 0x.. (signed type not modelled)")
        val typeWidth = widthOfType(def.typeName)
        val width = readWidth ?: typeWidth ?: inferWidth(def)
            ?: return Encoded.Invalid("unknown width — read the value first, or enter raw 0x.. bytes")
        if (width < 8 && n >= (1L shl (8 * width)))
            return Encoded.Invalid("$n does not fit in $width byte(s)")
        val bytes = ByteArray(width) { ((n shr (8 * it)) and 0xFF).toByte() }
        val note = when {
            readWidth != null -> "$width B (from read-back)"
            typeWidth != null -> "$width B (from type ${def.typeName})"
            else -> "$width B (inferred from max ${def.max})"
        }
        return Encoded.Ok(bytes, note)
    }

    /**
     * IEEE-754 little-endian encode for an F32/F64 parameter — the same layout the
     * FC stores, and what makes "reset to default" possible for a float (a default
     * like `0.059999998` has no integer form). Range-checked against the catalog
     * limits; the width is the declared type's and must agree with a read-back if
     * there is one. Raw `0x..` never reaches here — the caller takes it verbatim.
     */
    private fun encodeFloat(def: Def, s: String, readWidth: Int?): Encoded {
        val d = s.toDoubleOrNull() ?: return Encoded.Invalid("not a number (use 0x.. for raw bytes)")
        if (d.isNaN() || d.isInfinite()) return Encoded.Invalid("not a finite number")
        def.min.toDoubleOrNull()?.let { if (d < it) return Encoded.Invalid("$d is below min $it") }
        def.max.toDoubleOrNull()?.let { if (d > it) return Encoded.Invalid("$d is above max $it") }
        val width = if (def.typeName == "F64") 8 else 4
        if (readWidth != null && readWidth != width)
            return Encoded.Invalid("${def.typeName} is $width B but the parameter reads back $readWidth B")
        val bits = if (width == 8) d.toRawBits()
                   else (d.toFloat().toRawBits().toLong() and 0xFFFFFFFFL)
        return Encoded.Ok(ByteArray(width) { ((bits shr (8 * it)) and 0xFF).toByte() },
            "$width B IEEE-754 LE (${def.typeName})")
    }

    /** Byte width the declared type implies, or null when it can't be trusted — an
     *  absent/unknown type, or a float (whose bytes [encodeFloat] lays out instead).
     *  Also used by [ConfigTable] to slice a 4-byte limit field down to the parameter's
     *  real width before decoding it. */
    internal fun widthOfType(type: String): Int? = when (type) {
        "U8", "I8" -> 1
        "U16", "I16" -> 2
        "U32", "I32" -> 4
        "U64", "I64" -> 8
        else -> null
    }

    /** Minimum byte width that can hold the catalog's declared max, or null if max
     *  is absent/non-numeric (then the caller must refuse a sized decimal write). */
    private fun inferWidth(def: Def): Int? {
        val max = def.max.toLongOrNull() ?: return null
        if (max < 0) return null
        var w = 1
        while (w < 8 && max >= (1L shl (8 * w))) w++
        return w
    }

    /**
     * Encode a user-entered value to bytes. `0x..` is taken as raw little-endian
     * hex; otherwise a decimal integer packed little-endian into [width] bytes
     * (width comes from the param's current read-back length, so we never change
     * its size). Returns null on parse failure.
     */
    fun encode(valueStr: String, width: Int): ByteArray? {
        val s = valueStr.trim()
        if (s.startsWith("0x", true)) {
            val hex = s.substring(2).replace(" ", "")
            if (hex.isEmpty() || hex.length % 2 != 0) return null
            return runCatching { DumlWire.hex(hex) }.getOrNull()
        }
        val n = s.toLongOrNull() ?: return null
        val w = if (width in 1..8) width else 1
        return ByteArray(w) { ((n shr (8 * it)) and 0xFF).toByte() }
    }

    /** Decode a little-endian byte value to an unsigned decimal string. */
    fun decode(v: ByteArray): String {
        var n = 0L
        for (i in v.indices.reversed()) n = (n shl 8) or (v[i].toLong() and 0xFF)
        return n.toString()
    }

    /**
     * Decode for display using the declared type: an F32/F64 blob is IEEE-754 and
     * an I8/I16/I32/I64 one is two's complement, so neither reads correctly as the
     * unsigned integer [decode] falls back to (a `-1` I16 would print as 65535).
     * Falls back to [decode] when the type is absent or the width disagrees.
     */
    fun decode(v: ByteArray, typeName: String): String {
        var bits = 0L
        for (i in v.indices.reversed()) bits = (bits shl 8) or (v[i].toLong() and 0xFF)
        return when {
            typeName == "F32" && v.size == 4 -> Float.fromBits(bits.toInt()).toString()
            typeName == "F64" && v.size == 8 -> Double.fromBits(bits).toString()
            typeName.startsWith("I") && v.size in 1..8 && v.size == widthOfType(typeName) -> {
                val shift = 64 - 8 * v.size
                ((bits shl shift) shr shift).toString()   // sign-extend
            }
            else -> decode(v)
        }
    }
}
