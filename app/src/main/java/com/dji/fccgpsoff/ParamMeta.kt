package com.dji.fccgpsoff

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-parameter metadata read from the aircraft itself with `03:F7`, on demand.
 *
 * The catalog files answer this for parameters they happen to contain — and where they do,
 * they are accurate: 12 of 12 sampled entries in the bundled `litox1.json` matched the
 * board exactly, F32 precision included. What they cannot do is cover an aircraft with no
 * export, or notice that they have drifted: 2 of those same 14 sampled names (`lida_x`,
 * `uwb2_y`) do **not exist** on the connected board, and nothing before `03:F7` could tell
 * that from a read that simply didn't route back.
 *
 * So this is a point lookup for the parameter about to be edited, not a bulk load — one
 * request, ~1.5 s, answered in about 70% of windows with DJI Fly running in the background,
 * hence the retry budget. It runs on the wrapped 40007 route (the only one that answers)
 * and is therefore gated by [ForegroundGate] exactly like [ParamRead].
 */
object ParamMeta {

    /** Measured: a single 1500 ms window answers ~70% of the time with Fly backgrounded,
     *  so three attempts put an interactive lookup at ~97% while staying well inside the
     *  diag server's 15 s socket timeout. */
    const val DEFAULT_ATTEMPTS = 3
    private const val WINDOW_MS = 1500

    /** Session cache. Metadata is a property of the firmware, not of the moment, so a hit
     *  is safe for as long as the same aircraft is connected — [clear] is called from
     *  [AircraftSession] when the serial changes. */
    private val cache = ConcurrentHashMap<String, ConfigTable.Info>()

    fun cached(name: String): ConfigTable.Info? = cache[name]

    fun clear() {
        if (cache.isNotEmpty()) DiagLog.info("param metadata cache cleared (${cache.size} entries)")
        cache.clear()
    }

    /** One `03:F7` ask for [name], correlated by its own sequence number — the
     *  "no such parameter" reply is a single status byte that echoes nothing, so
     *  the seq is the only thing there is to match on. */
    private fun askFor(name: String): DumlWindow.Ask {
        val s = DumlSeq.next()
        val inner = DumlWire.withSeq(
            DumlNative.nativeBuildFrame(
                DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
                DumlWire.CMDSET_FLYC, ConfigTable.CMDID_PARAM_INFO_HASH,
                DumlNative.nativeParamHash(name)
            ), s
        )
        return DumlWindow.Ask("info ${ParamName.tag(name)} 03:F7", DumlWire.wrap(inner)) { fr ->
            if (fr.cmdSet != DumlWire.CMDSET_FLYC ||
                fr.cmdId != ConfigTable.CMDID_PARAM_INFO_HASH ||
                !fr.isResponse || fr.seq != s
            ) null else fr.payload
        }
    }

    /**
     * What the board said about several spellings of ONE parameter.
     *
     * The three outcomes are kept apart on purpose, because they are not the same
     * evidence: [name] set is a positive, [absent] is the board explicitly denying a
     * spelling (status 3 — a real answer), and [silent] is nothing coming back, which
     * on this bus means "no route", never "absent". Collapsing the last two is exactly
     * the mistake [StartupProbe.detectVariant] used to make.
     */
    class Resolution(
        /** The spelling the board answered for, or null when none did. */
        val name: String?,
        val info: ConfigTable.Info.Ok?,
        val absent: List<String>,
        val silent: List<String>,
    ) {
        /** Every candidate was explicitly DENIED — the board indexes none of them.
         *  Only true when nothing was left merely silent. */
        val allAbsent: Boolean get() = name == null && silent.isEmpty() && absent.isNotEmpty()
    }

    /**
     * Ask about every spelling in [names] — all of them in ONE 40007 window.
     *
     * Batching is what makes this affordable: the wire was measured to lose whole
     * windows rather than individual requests (see [DumlWindow]), so N spellings in one
     * window cost the same window as one spelling and answer or miss together. Anything
     * still silent is re-asked on the next attempt; anything denied is settled and never
     * re-asked.
     */
    suspend fun resolve(names: List<String>, attempts: Int = DEFAULT_ATTEMPTS,
                        useCache: Boolean = true): Resolution {
        val wanted = names.filter { it.isNotEmpty() }.distinct()
        if (wanted.isEmpty()) return Resolution(null, null, emptyList(), emptyList())
        // Answer from the session cache before opening anything. Metadata is a property of
        // the firmware, not of the moment, and the old point-lookup path served a cache hit
        // for free — going straight to the wire here would have made `reset()` spend windows
        // on 40007 that `info()` never spent (2026-08-20).
        val absent = LinkedHashSet<String>()
        if (useCache) {
            for (n in wanted) when (val c = cache[n]) {
                is ConfigTable.Info.Ok -> return Resolution(n, c, absent.toList(), wanted - absent - n)
                is ConfigTable.Info.Absent -> absent.add(n)
                else -> {}
            }
        }
        var open = wanted.filter { it !in absent }
        if (open.isEmpty()) return Resolution(null, null, absent.toList(), emptyList())
        repeat(attempts) {
            if (!ForegroundGate.readsAllowed()) return Resolution(null, null, absent.toList(), open)
            val got = DumlWindow.collect(DumlWire.PORT_VIDEO_MIRROR, open.map { askFor(it) }, WINDOW_MS)
            val stillOpen = ArrayList<String>(open.size)
            for (i in open.indices) {
                val pl = got[i]
                if (pl == null) { stillOpen.add(open[i]); continue }
                when (val parsed = ConfigTable.parseParamInfo(pl)) {
                    is ConfigTable.Info.Ok -> {
                        // A success naming a DIFFERENT parameter means the sequence number
                        // collided with another client's — drop it rather than describe the
                        // wrong row. Compared by alias PART, because the board answers with
                        // its canonical joined name, which is legitimately not the string we
                        // asked for; an exact `!=` here threw away every good reply on an
                        // aircraft with joined names (fixed 2026-08-20).
                        if (!ParamName.sameParam(parsed.name, open[i])) {
                            DiagLog.warn("03:F7 for ${open[i]} answered about '${parsed.name}' — " +
                                "not the same parameter, ignoring (seq collision)")
                            stillOpen.add(open[i])
                        } else {
                            cache[open[i]] = parsed
                            if (parsed.name != open[i]) cache[parsed.name] = parsed
                            return Resolution(open[i], parsed, absent.toList(),
                                stillOpen + open.subList(i + 1, open.size))
                        }
                    }
                    is ConfigTable.Info.Absent -> { absent.add(open[i]); cache[open[i]] = parsed }
                    null -> stillOpen.add(open[i])
                }
            }
            if (stillOpen.isEmpty()) return Resolution(null, null, absent.toList(), emptyList())
            open = stillOpen
        }
        return Resolution(null, null, absent.toList(), open)
    }

    /**
     * Ask the board to describe [name]. Returns null when nothing came back — which on this
     * bus means "no route back", **never** "no such parameter": that case is an
     * [ConfigTable.Info.Absent] with status 3, a real answer.
     */
    suspend fun info(name: String, attempts: Int = DEFAULT_ATTEMPTS, force: Boolean = false): ConfigTable.Info? {
        if (name.isEmpty()) return null
        if (!force) cache[name]?.let { return it }
        val r = resolve(listOf(name), attempts, useCache = !force)
        // Only report an absence this call actually established; a cached one from an
        // earlier ask must not be passed off as the answer to a forced re-read.
        return r.info ?: (cache[name] as? ConfigTable.Info.Absent)?.takeIf { name in r.absent }
    }

    /**
     * A [ParamCatalog.Def] describing [name] as the BOARD declares it, or null if the board
     * didn't answer or has no such parameter. Drops straight into
     * [ParamCatalog.encodeChecked], so the existing (unit-tested) encoder does the range and
     * width checking against the aircraft's own limits instead of a file's.
     */
    suspend fun boardDef(name: String, attempts: Int = DEFAULT_ATTEMPTS): ParamCatalog.Def? {
        val r = resolve(ParamAlias.order(name), attempts)
        r.name?.let { ParamAlias.note(name, it) }
        if (r.allAbsent) ParamAlias.noteDenied(name)
        return r.info?.toDef()
    }

    /** Outcome of a [reset]. */
    enum class ResetResult { NO_SUCH_PARAM, UNKNOWN_DEFAULT, LINK_DOWN, SENT, CONFIRMED }

    /**
     * Reset [name] to the **board's own** default with `03:FA`.
     *
     * Route: 40008, unwrapped, sender 2 / `cmd_type` 0x40 — the same transport
     * [ParameterAddress.write] already uses for `03:F9`. Measured on a Lito X1 v400:
     * `03:FA` acts on 40008 from a single frame, on 40009 only with the controller's own
     * identity (sender 130 / 0x20) and only with repeats, and on 40007 with repeats. 40008
     * is chosen because it is the app's existing write port and carries no video.
     *
     * 40008 returns nothing, so confirmation is a separate `03:F8` read on 40007 —
     * [ParamRead.confirmWrite], the same read-back path a normal write uses.
     */
    suspend fun reset(name: String): Pair<ResetResult, String> {
        // Which SPELLING this board indexes is measured here, not assumed: the same
        // 03:F7 round that fetches the default also settles the address, so a joined
        // `a|b` name costs no extra window (2026-08-20, see [ParamAlias]).
        val r = resolve(ParamAlias.order(name))
        val addr = r.name
        val ok = r.info
        if (addr == null || ok == null) {
            if (r.allAbsent) {
                ParamAlias.noteDenied(name)
                return ResetResult.NO_SUCH_PARAM to
                    "$name: the aircraft reports no such parameter under any spelling it is " +
                        "known by (03:F7 status ${ConfigTable.ST_NO_SUCH_PARAM} for " +
                        r.absent.joinToString(", ") { ParamName.tag(it) } + ")"
            }
            return ResetResult.UNKNOWN_DEFAULT to
                "$name: the aircraft did not answer 03:F7, so its default is unknown — not sending a blind reset"
        }
        ParamAlias.note(name, addr)

        val body = DumlNative.nativeParamHash(addr)
        var sent = false
        repeat(RESET_WRITES) { i ->
            // A distinct sequence number per repeat, so the two frames are not byte
            // identical — see [ParameterAddress.write].
            val inner = DumlWire.withSeq(DumlNative.nativeBuildFrame(
                DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
                DumlWire.CMDSET_FLYC, ConfigTable.CMDID_RESET_PARAM_HASH, body
            ), DumlSeq.next())
            if (DumlBus.sendFrame(DumlWire.PORT_INJECT, inner,
                    "reset ${ParamName.tag(addr)} 03:FA #${i + 1}/$RESET_WRITES")) sent = true
            kotlinx.coroutines.delay(RESET_GAP_MS)
        }
        if (!sent) return ResetResult.LINK_DOWN to "$name: reset frame did not leave the socket"

        // 03:FA mutates. "No read-back" does NOT mean "nothing happened" — say so.
        // encodeChecked (not encode) so a float default like 0.059999998 is laid out as
        // IEEE-754 rather than failing an integer parse and losing the confirmation.
        val want = (ParamCatalog.encodeChecked(ok.toDef(), ok.def, ok.size)
            as? ParamCatalog.Encoded.Ok)?.bytes
        val back = if (want != null) ParamRead.confirmWrite(addr, want) else null
        return when {
            back == null ->
                ResetResult.SENT to "$name: reset sent (board default ${ok.def}) — no read-back; " +
                    "it may well have applied, the read simply did not route"
            want != null && back.value.contentEquals(want) ->
                ResetResult.CONFIRMED to "$name: reset to board default ${ok.def} — CONFIRMED after ${back.afterMs} ms"
            else ->
                ResetResult.SENT to "$name: reset sent (board default ${ok.def}) — read-back still " +
                    "${ParamCatalog.decode(back.value, ok.typeName)} after ${back.afterMs} ms"
        }
    }

    /** Same repeat policy [ParameterAddress.write] uses; a single frame sufficed on 40008
     *  in testing, but a dropped frame is free to survive. */
    private const val RESET_WRITES = 2
    private const val RESET_GAP_MS = 120L
}
