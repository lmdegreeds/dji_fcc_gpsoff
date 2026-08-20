package com.dji.fccgpsoff

import java.util.concurrent.ConcurrentHashMap

/**
 * Which spelling of a pipe-joined parameter name THIS aircraft actually indexes
 * (2026-08-20).
 *
 * [ParamName] explains why a name can have several spellings and why they are
 * different addresses. This object holds the answer for the connected board, and
 * the answer is always MEASURED, never assumed: [ParamMeta.resolve] asks `03:F7`
 * for every candidate in a single 40007 window, and `03:F7` is the one command on
 * this bus whose negative is real — status 3 means "no such parameter", where an
 * unanswered `03:F8` read means only "nothing came back".
 *
 * Cost discipline. A name with no separator has exactly one candidate, so nothing
 * here opens a socket for it — the Lito X1 path costs precisely what it cost
 * before. A joined name costs ONE window, which is a window the caller was about
 * to spend on a blind read anyway, and the answer is then reused for every read,
 * write, reset and read-back of that parameter for the rest of the session.
 *
 * [preferred] generalises the answer across the catalog: firmwares are consistent
 * about which half of the pair they index, so once one name has resolved, a bulk
 * read can ask the likely spelling first instead of every candidate for all 600
 * rows. It is a hint that orders candidates, never a claim — a miss still falls
 * back to asking the rest.
 *
 * Cleared by [AircraftSession] when the serial changes, exactly like
 * [ParamMeta.clear]: this is a property of the board in front of us, not of the app.
 */
object ParamAlias {

    /** asked name → the spelling this board answered to. */
    private val resolved = ConcurrentHashMap<String, String>()
    /** asked name → when we last asked and the board denied EVERY candidate. Kept so a
     *  hopeless name is not re-asked on every row render; a real absence does not change
     *  while the same aircraft is connected. */
    private val denied = ConcurrentHashMap<String, Long>()

    /**
     * Which candidate position has been winning on this aircraft: 0 = the joined form
     * as the firmware spells it, 1 = the first alias part, and so on. -1 = nothing
     * learned yet. A HINT for ordering, never a substitute for asking.
     */
    @Volatile var preferred: Int = -1; private set

    /** Number of names resolved so far this session — for the state snapshot. */
    val resolvedCount: Int get() = resolved.size

    fun clear() {
        val n = resolved.size
        resolved.clear(); denied.clear(); preferred = -1
        if (n > 0) DiagLog.info("param alias map cleared ($n resolved name(s))")
    }

    /** The measured spelling for [name], or null when it has not been resolved. */
    fun known(name: String): String? = resolved[name]

    /**
     * The spelling to address [name] by RIGHT NOW, without opening anything: the
     * measured one when we have it, otherwise the name exactly as given. Safe to call
     * from any thread and from a render loop.
     */
    fun best(name: String): String = resolved[name] ?: name

    /** True when the board denied every spelling of [name] this session. */
    fun isDenied(name: String): Boolean = denied.containsKey(name)

    /**
     * Candidate spellings for [name], the likely one first.
     *
     * Ordering only — the list is always complete, so a wrong hint costs nothing but
     * the position of an ask inside a window that carries all of them anyway.
     */
    fun order(name: String): List<String> {
        val c = ParamName.candidates(name)
        val p = preferred
        if (p <= 0 || p >= c.size) return c
        val out = ArrayList<String>(c.size)
        out.add(c[p])
        for (i in c.indices) if (i != p) out.add(c[i])
        return out
    }

    /** Record that [winner] is the spelling the board answered to for [asked]. */
    fun note(asked: String, winner: String) {
        val had = resolved.put(asked, winner)
        denied.remove(asked)
        val idx = ParamName.candidates(asked).indexOf(winner)
        if (idx >= 0) preferred = idx
        if (had == winner) return
        if (asked == winner) {
            DiagLog.info("param name: '$asked' — the board indexes it as asked (${ParamName.tag(winner)})")
        } else {
            DiagLog.info("param name: '$asked' → the board indexes '${ParamName.tag(winner)}' " +
                "(alias ${idx.coerceAtLeast(0)} of ${ParamName.candidates(asked).size})")
        }
    }

    /** Record that the board explicitly denied every spelling of [asked]. */
    fun noteDenied(asked: String) {
        ReadStats.denied()
        if (denied.put(asked, System.currentTimeMillis()) == null)
            DiagLog.warn("param name: this aircraft has NO parameter under any spelling of '$asked' " +
                "(03:F7 status ${ConfigTable.ST_NO_SUCH_PARAM} for " +
                ParamName.candidates(asked).joinToString(", ") { ParamName.tag(it) } + ")")
    }

    /**
     * The spelling to address [name] by, asking the board once if we do not know yet.
     *
     * Returns the name unchanged — and opens nothing — when it carries a single
     * spelling, when it is already resolved, or when the board has already denied it.
     * A resolution that cannot be reached (read gate closed, nothing answered) returns
     * the name as given and says so in the log, so a shared log never leaves a caller
     * guessing which address a frame carried.
     */
    suspend fun resolve(name: String): String {
        if (name.isEmpty()) return name
        resolved[name]?.let { return it }
        val cands = ParamName.candidates(name)
        if (cands.size <= 1) return name
        if (denied.containsKey(name)) return name
        // Nothing can be asked while DJI Fly owns the screen, and that is the normal state
        // for a write from the floating panel. Return quietly rather than reporting a
        // failure to learn something we never tried to learn.
        if (!ForegroundGate.readsAllowed()) return name
        val r = ParamMeta.resolve(order(name))
        return when {
            r.name != null -> { note(name, r.name); r.name }
            r.allAbsent -> { noteDenied(name); name }
            else -> {
                DiagLog.warn("param name: '${name}' unresolved — no spelling answered 03:F7 " +
                    "(${r.silent.size} silent, ${r.absent.size} denied); addressing it as the board spells it")
                name
            }
        }
    }
}
