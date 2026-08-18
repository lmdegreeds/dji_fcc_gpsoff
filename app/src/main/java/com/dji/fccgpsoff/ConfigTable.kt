package com.dji.fccgpsoff

/**
 * Decoders for the FLYC "Config Table" commands beyond the read/write pair the app
 * already uses (`03:F8` / `03:F9`). All verified on a Lito X1 v400 over an RC 2 —
 * see [`doc/config-table-commands.md`](../../../../../../../doc/config-table-commands.md)
 * for the raw captures, status-code derivations and answer rates.
 *
 *   `03:F7` Get Param Info By Hash  — type, width, min/max/default and the canonical
 *                                     NAME, addressed by the same hash `03:F8` uses.
 *   `03:FA` Reset Params By Hash    — reset one parameter to the board's own default.
 *   `03:E0` Get Tbl Attribute       — entry count + CRC of the parameter table.
 *   `03:E1` Get Item Attribute      — the same metadata as F7 but addressed by index.
 *
 * `03:FB`/`03:FC` (the plural by-hash forms) and `03:F0` (the 2015 by-index info) are
 * **dead on this firmware** — silent across every payload layout tried. Don't add them
 * back without a fresh capture.
 *
 * Every parser here is TOTAL: a short, truncated or garbage payload returns null and
 * never throws. These bytes arrive off a bus shared with DJI Fly, so a decoder that
 * throws would take down the window walk around it.
 */
object ConfigTable {

    // ---- command ids (cmd_set 0x03) ----
    const val CMDID_PARAM_INFO_HASH = 0xF7
    const val CMDID_RESET_PARAM_HASH = 0xFA
    const val CMDID_TBL_ATTRIBUTE = 0xE0
    const val CMDID_ITEM_ATTRIBUTE = 0xE1

    // ---- status codes, derived from behaviour (no vendor documentation) ----
    /** Request succeeded. */
    const val ST_OK = 0
    /** F7 only: the flight controller has no parameter with that hash. A real ANSWER,
     *  not silence — this is what makes name probing decisive. Confirmed with garbage
     *  names (`zzz_not_a_real_param_at_all`, `qqqqqqqqqqqq`) against a positive control. */
    const val ST_NO_SUCH_PARAM = 3
    /** E0/E1: no table with that number (table 5 returns it from both commands). */
    const val ST_NO_SUCH_TABLE = 9
    /** E1: the slot at that index is empty. NOT an error — roughly 40% of the 1594
     *  slots on a Lito X1 v400 answer this, while the neighbouring indices decode fine. */
    const val ST_EMPTY_SLOT = 14

    // ================================ 03:F7 ================================

    /** Outcome of a by-hash metadata request. */
    sealed interface Info {
        /**
         * The board's own description of a parameter. [name] is the canonical name as
         * the firmware spells it — note the hash is computed over `name + "_0"` but the
         * string returned here carries **no** suffix, so it can be compared directly to
         * the name that was asked for.
         */
        data class Ok(
            val name: String,
            val typeId: Int,
            val typeName: String,
            val size: Int,
            val attribute: Int,
            val min: String,
            val max: String,
            val def: String,
        ) : Info {
            /** Render as a catalog entry so the existing encoder/validator can consume it
             *  unchanged — [ParamCatalog.encodeChecked] takes a [ParamCatalog.Def]. */
            fun toDef(): ParamCatalog.Def =
                ParamCatalog.Def(name, "", typeId, min, max, def, typeName)
        }

        /** The board answered, but not with metadata. [status] 3 means "no such parameter". */
        data class Absent(val status: Int) : Info
    }

    /**
     * `03:F7` response:
     * ```
     * +0  status     u8
     * +1  type_id    u16
     * +3  size       u16     value width in bytes
     * +5  attribute  u16     flags; 0 seen on g_status.* counters, 3 and 7 elsewhere.
     *                        Meaning of the bits is NOT established — display only.
     * +7  limit_min  u32*
     * +11 limit_max  u32*
     * +15 limit_def  u32*
     * +19 name       asciiz
     * ```
     * `u32*` is a union reinterpreted per `type_id` — see [limitOf].
     *
     * **The limit order here is min, max, def. [parseItem] (E1) uses def, min, max.**
     * That is why the two have separate field readers instead of one shared helper.
     */
    fun parseParamInfo(pl: ByteArray): Info? {
        if (pl.isEmpty()) return null
        val status = pl[0].toInt() and 0xFF
        if (status != ST_OK) return Info.Absent(status)
        if (pl.size < 20) return null                       // status 0 but too short to be real
        val typeId = le16(pl, 1)
        val typeName = ParamCatalog.TYPE_NAMES[typeId] ?: ""
        return Info.Ok(
            name = asciiz(pl, 19),
            typeId = typeId,
            typeName = typeName,
            size = le16(pl, 3),
            attribute = le16(pl, 5),
            min = limitOf(pl, 7, typeName),
            max = limitOf(pl, 11, typeName),
            def = limitOf(pl, 15, typeName),
        )
    }

    // ================================ 03:FA ================================

    /**
     * `03:FA` response: `status u8 | param_hash u32 | new_value (size bytes)`.
     *
     * [value] is what the parameter holds **after** the reset, so a status-0 reply is
     * both the acknowledgement and the read-back in one frame. Only 40007 returns it;
     * sent on 40008 or 40009 the command still acts but answers nothing, and the caller
     * must confirm with a separate `03:F8`.
     */
    data class Reset(val status: Int, val hash: ByteArray, val value: ByteArray) {
        // data class + ByteArray: identity equals would be wrong here, and this type is
        // compared in tests, so spell both out.
        override fun equals(other: Any?): Boolean =
            other is Reset && status == other.status &&
                hash.contentEquals(other.hash) && value.contentEquals(other.value)

        override fun hashCode(): Int =
            (status * 31 + hash.contentHashCode()) * 31 + value.contentHashCode()
    }

    fun parseReset(pl: ByteArray): Reset? {
        if (pl.size < 5) return null
        return Reset(
            status = pl[0].toInt() and 0xFF,
            hash = pl.copyOfRange(1, 5),
            value = pl.copyOfRange(5, pl.size),
        )
    }

    // ================================ 03:E0 ================================

    /**
     * `03:E0` response: `status u16 | table_no u16 | entries_crc u32 | entries_num u32`.
     *
     * [entriesCrc] + [entriesNum] identify the firmware's parameter-table build — a cheap
     * fingerprint for spotting that a loaded export came from a different aircraft or
     * firmware. Lito X1 v400: table 0 only, 1594 entries, crc 0x2ae1a5ad. Note 1594
     * counts empty slots too; the Param Studio export of the same board has 953 named.
     */
    data class TableAttr(val status: Int, val tableNo: Int, val entriesCrc: Long, val entriesNum: Int)

    fun parseTableAttr(pl: ByteArray): TableAttr? {
        // A rejected table answers with the status ALONE — two bytes, no echo (tables 1..5
        // return a bare `0900`). Anything stricter would drop a real answer as garbage.
        if (pl.size < 2) return null
        val status = le16(pl, 0)
        val tableNo = if (pl.size >= 4) le16(pl, 2) else -1
        if (status != ST_OK) return TableAttr(status, tableNo, 0L, 0)
        if (pl.size < 12) return null
        return TableAttr(status, tableNo, le32u(pl, 4), le32(pl, 8))
    }

    // ================================ 03:E1 ================================

    /**
     * `03:E1` response:
     * ```
     * +0  status       u16
     * +2  table_no     u16
     * +4  param_index  u16
     * +6  type_id      u16
     * +8  size         u16
     * +10 limit_def    u32*     <-- def FIRST here
     * +14 limit_min    u32*
     * +18 limit_max    u32*
     * +22 name         asciiz
     * ```
     * Error replies are only 2 bytes (`status`) and echo nothing, so they can be matched
     * to a request by sequence number but never by content.
     */
    data class Item(
        val status: Int,
        val tableNo: Int,
        val index: Int,
        val name: String,
        val typeId: Int,
        val typeName: String,
        val size: Int,
        val min: String,
        val max: String,
        val def: String,
    ) {
        fun toDef(): ParamCatalog.Def = ParamCatalog.Def(name, "", typeId, min, max, def, typeName)
    }

    fun parseItem(pl: ByteArray): Item? {
        if (pl.size < 2) return null
        val status = le16(pl, 0)
        if (status != ST_OK) {
            return Item(status, if (pl.size >= 4) le16(pl, 2) else -1, -1, "", -1, "", 0, "", "", "")
        }
        if (pl.size < 23) return null
        val typeId = le16(pl, 6)
        val typeName = ParamCatalog.TYPE_NAMES[typeId] ?: ""
        return Item(
            status = status,
            tableNo = le16(pl, 2),
            index = le16(pl, 4),
            name = asciiz(pl, 22),
            typeId = typeId,
            typeName = typeName,
            size = le16(pl, 8),
            def = limitOf(pl, 10, typeName),   // def, min, max — NOT F7's order
            min = limitOf(pl, 14, typeName),
            max = limitOf(pl, 18, typeName),
        )
    }

    // ================================ helpers ================================

    /**
     * Decode one 4-byte limit field per its `type_id`.
     *
     * The field is a fixed 4 bytes whatever the parameter's real width, so a narrow
     * signed type must be sign-extended from ITS OWN width, not from 32 bits — reading
     * an I16 `-1` as an unsigned 32-bit would print 4294967295. Slicing to the declared
     * width and handing it to [ParamCatalog.decode] gets that (and IEEE-754 for F32) for
     * free from a decoder that is already unit-tested.
     *
     * 8-byte types (U64/I64/F64) can't fit in the 4-byte field; their limits are read as
     * an unsigned 32-bit value, which is a guess — no F64 parameter has been captured.
     */
    private fun limitOf(pl: ByteArray, off: Int, typeName: String): String {
        if (off + 4 > pl.size) return ""
        val raw = pl.copyOfRange(off, off + 4)
        // widthOfType returns null for floats and unknown types; then the full 4 bytes
        // are the right slice anyway (F32 is exactly 4, and an unknown type has no better
        // reading than the raw 32-bit value).
        val w = ParamCatalog.widthOfType(typeName)
        val slice = if (w != null && w in 1..4) raw.copyOfRange(0, w) else raw
        return ParamCatalog.decode(slice, typeName)
    }

    /** Text up to the NUL terminator, stopping at the first non-printable byte so a
     *  truncated frame yields a short name instead of mojibake. */
    fun asciiz(pl: ByteArray, off: Int): String {
        if (off >= pl.size) return ""
        val sb = StringBuilder()
        for (i in off until pl.size) {
            val c = pl[i].toInt() and 0xFF
            if (c < 0x20 || c > 0x7E) break
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun le32u(b: ByteArray, off: Int): Long = le32(b, off).toLong() and 0xFFFFFFFFL
}
