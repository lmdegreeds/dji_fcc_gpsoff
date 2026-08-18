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

    /**
     * Ask the board to describe [name]. Returns null when nothing came back — which on this
     * bus means "no route back", **never** "no such parameter": that case is an
     * [ConfigTable.Info.Absent] with status 3, a real answer.
     *
     * Correlation is by sequence number, because the "no such parameter" reply is a single
     * status byte that echoes nothing. A successful reply is additionally required to carry
     * the name we asked for.
     */
    suspend fun info(name: String, attempts: Int = DEFAULT_ATTEMPTS, force: Boolean = false): ConfigTable.Info? {
        if (name.isEmpty()) return null
        if (!force) cache[name]?.let { return it }
        repeat(attempts) {
            if (!ForegroundGate.readsAllowed()) return null      // bail up front and between attempts
            val s = DumlSeq.next()
            val inner = DumlWire.withSeq(
                DumlNative.nativeBuildFrame(
                    DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
                    DumlWire.CMDSET_FLYC, ConfigTable.CMDID_PARAM_INFO_HASH,
                    DumlNative.nativeParamHash(name)
                ), s
            )
            val ask = DumlWindow.Ask("info $name 03:F7", DumlWire.wrap(inner)) { fr ->
                if (fr.cmdSet != DumlWire.CMDSET_FLYC ||
                    fr.cmdId != ConfigTable.CMDID_PARAM_INFO_HASH ||
                    !fr.isResponse || fr.seq != s
                ) null else fr.payload
            }
            val pl = DumlWindow.ask(DumlWire.PORT_VIDEO_MIRROR, ask, WINDOW_MS)
            if (pl != null) {
                val parsed = ConfigTable.parseParamInfo(pl)
                // A success that names a DIFFERENT parameter would mean the sequence number
                // collided with another client's; drop it rather than describe the wrong row.
                if (parsed is ConfigTable.Info.Ok && parsed.name != name) {
                    DiagLog.warn("03:F7 for $name answered with '${parsed.name}' — ignoring")
                } else if (parsed != null) {
                    cache[name] = parsed
                    return parsed
                }
            }
        }
        return null
    }

    /**
     * A [ParamCatalog.Def] describing [name] as the BOARD declares it, or null if the board
     * didn't answer or has no such parameter. Drops straight into
     * [ParamCatalog.encodeChecked], so the existing (unit-tested) encoder does the range and
     * width checking against the aircraft's own limits instead of a file's.
     */
    suspend fun boardDef(name: String, attempts: Int = DEFAULT_ATTEMPTS): ParamCatalog.Def? =
        (info(name, attempts) as? ConfigTable.Info.Ok)?.toDef()

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
        val info = info(name)
        if (info is ConfigTable.Info.Absent)
            return ResetResult.NO_SUCH_PARAM to
                "$name: the aircraft reports no such parameter (03:F7 status ${info.status})"
        val ok = info as? ConfigTable.Info.Ok
            ?: return ResetResult.UNKNOWN_DEFAULT to
                "$name: the aircraft did not answer 03:F7, so its default is unknown — not sending a blind reset"

        val body = DumlNative.nativeParamHash(name)
        var sent = false
        repeat(RESET_WRITES) {
            val inner = DumlNative.nativeBuildFrame(
                DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
                DumlWire.CMDSET_FLYC, ConfigTable.CMDID_RESET_PARAM_HASH, body
            )
            if (DumlBus.sendFrame(DumlWire.PORT_INJECT, inner, "reset $name 03:FA")) sent = true
            kotlinx.coroutines.delay(RESET_GAP_MS)
        }
        if (!sent) return ResetResult.LINK_DOWN to "$name: reset frame did not leave the socket"

        // 03:FA mutates. "No read-back" does NOT mean "nothing happened" — say so.
        // encodeChecked (not encode) so a float default like 0.059999998 is laid out as
        // IEEE-754 rather than failing an integer parse and losing the confirmation.
        val want = (ParamCatalog.encodeChecked(ok.toDef(), ok.def, ok.size)
            as? ParamCatalog.Encoded.Ok)?.bytes
        val back = if (want != null) ParamRead.confirmWrite(name, want) else null
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
