package com.dji.fccgpsoff

/**
 * Parameter names that carry more than one spelling (2026-08-20).
 *
 * DJI firmware names some flight-controller parameters with a pipe-joined pair.
 * `03:E1` on a DJI Air 3 returns the literal string
 * `gps_enable|g_config.gps_cfg.gps_enable`, and a Param Studio export of the same
 * board carries it verbatim (`params_example/dji air3 dh v1.dhp`); a Lito X1 names
 * the same parameter `gps_enable`, with no pipe at all.
 *
 * This matters because a parameter is ADDRESSED by a hash over its name
 * ([DumlNative.nativeParamHash], which appends `_0`), so the three spellings are
 * three different addresses:
 *
 * | spelling | hash |
 * |---|---|
 * | `gps_enable` | `9d8a8881` |
 * | `g_config.gps_cfg.gps_enable` | `829542c5` |
 * | `gps_enable\|g_config.gps_cfg.gps_enable` | `c2e4c359` |
 *
 * Until 2026-08-20 only [ParamGroups] knew a name could be a joined pair, and it
 * used that for LABELLING only. Every addressing path hashed the joined string, so
 * on an aircraft that indexes an alias part the editor read, wrote and reset an
 * address no parameter has — silently, because an unanswered read on this bus is
 * indistinguishable from an absent one. Observed in a user's log: three `03:F8`
 * windows at `c2e4c359` returned nothing while `9d8a8881` answered in 168 ms.
 *
 * WHICH spelling a given firmware indexes is a property of the aircraft, and this
 * project has no business guessing it — [ParamAlias] measures it with `03:F7`,
 * whose status 3 is a real "no such parameter". This object only does the string
 * work, so it stays pure and unit-testable off-device.
 */
object ParamName {

    const val SEP = '|'

    /**
     * The alias parts of [name], in the order the firmware spells them.
     *
     * A name with no separator is its own only part, so callers need no special
     * case. Blank parts are dropped: a trailing separator is a truncated `03:E1`
     * name, not an empty alias.
     */
    fun parts(name: String): List<String> =
        name.split(SEP).map { it.trim() }.filter { it.isNotEmpty() }

    /** True when [name] carries more than one spelling. */
    fun isJoined(name: String): Boolean = parts(name).size > 1

    /**
     * Every spelling worth addressing [name] by, most-defensible first.
     *
     * The joined form leads because it is the only spelling we have ever seen the
     * board itself report; the alias parts follow because a firmware may index
     * those instead. De-duplicated and order-stable, so a plain name yields exactly
     * one candidate and costs exactly what it costs today.
     */
    fun candidates(name: String): List<String> {
        val n = name.trim()
        if (n.isEmpty()) return emptyList()
        val p = parts(n)
        if (p.size <= 1) return listOf(p.firstOrNull() ?: n)
        val out = ArrayList<String>(p.size + 1)
        out.add(n)
        for (x in p) if (x !in out) out.add(x)
        return out
    }

    /**
     * Do [a] and [b] name the same parameter?
     *
     * True when they are equal, or when either appears among the other's alias
     * parts. This is what makes a `03:F7` reply usable: the board answers with its
     * canonical (joined) name, which is not the string that was asked for, and
     * comparing the two with `!=` threw the answer away — see [ParamMeta.info].
     */
    fun sameParam(a: String, b: String): Boolean {
        if (a == b) return true
        val pa = parts(a)
        val pb = parts(b)
        if (pa.isEmpty() || pb.isEmpty()) return false
        return b in pa || a in pb || pa.any { it in pb }
    }

    /**
     * The spelling to SHOW a human: the last alias part, which is the fully
     * qualified `g_config.*` form when there is one. The short alias is the
     * ambiguous half — `gps_enable` says nothing about which subsystem owns it.
     */
    fun display(name: String): String = parts(name).lastOrNull() ?: name

    /** `name#hash` — the form every addressing log line uses, so a shared log can be
     *  read without hand-computing hashes. Falls back to the bare name if the native
     *  hash is unavailable (it is a JNI call and this must never throw into a log). */
    fun tag(name: String): String = runCatching {
        name + "#" + DumlWire.toHex(DumlNative.nativeParamHash(name))
    }.getOrDefault(name)
}
