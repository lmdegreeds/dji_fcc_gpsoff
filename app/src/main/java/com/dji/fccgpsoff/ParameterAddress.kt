package com.dji.fccgpsoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Honest outcome of a parameter write, so the UI stops reporting "success" for
 *  a frame that never left the socket or was never confirmed. */
enum class WriteResult(val label: String) {
    INVALID_VALUE("invalid value"),
    LINK_DOWN("send failed (link down)"),
    NO_REPLY("sent — no read-back"),
    SENT("sent — read-back differs"),
    CONFIRMED("confirmed")
}

/**
 * Device-independent flight-controller parameter addressing, by NAME (03:F8
 * read / 03:F9 write), never by table/param index.
 *
 * Each logical parameter carries the Lito X1 name and the "other DJI" name. A WRITE
 * still goes to exactly one of them — no candidate spray on the write side, because a
 * write is blind and extra frames are pure churn on a shared bus. What changed on
 * 2026-08-20 is HOW that one name is chosen: [ParamAlias] measures which spelling the
 * connected firmware actually indexes (`03:F7`, whose status 3 is a real "no such
 * parameter"), and [name] returns the measured answer whenever there is one.
 * [AppState.litoMode] is the fallback for an aircraft that has answered nothing yet —
 * a guess, and logged as one.
 *
 * The spread happens on the READ side instead, where it is free: losses on this bus are
 * per-WINDOW, so putting all of a parameter's addresses into one window costs the window
 * it was going to cost anyway, and whichever answers settles the address for every
 * subsequent read, write and read-back. See [key] and [ParamName].
 *
 * Writes go to the UNWRAPPED inject port (40008), which coexists with DJI Fly (40007 is
 * Fly's video mirror and drops the link if written to).
 */
class ParameterAddress(private val lito: String, private val other: String = lito) {

    /** Both names (for diagnostics/labels). */
    val names: List<String> get() = if (lito == other) listOf(lito) else listOf(lito, other)

    /**
     * The identity of this logical parameter — the two spellings joined the way DJI
     * firmware joins them when it carries both (`gps_enable|g_config.gps_cfg.gps_enable`).
     *
     * It is the read address AND the key [ParamAlias] files the measured answer under,
     * so [ParamName.candidates] expands it to exactly the three addresses this parameter
     * can have on any aircraft: the joined form, the short alias, the qualified alias.
     * Before 2026-08-20 only two of the three were ever tried, and which of them was
     * used came from a stored profile rather than from the board.
     */
    val key: String get() = if (lito == other) lito else "$lito${ParamName.SEP}$other"

    /**
     * The name to ADDRESS this parameter by.
     *
     * The measured spelling wins whenever one is known — [ParamAlias] learns it from
     * whichever hash the board actually answered, which is evidence; the Lito/g_config
     * profile is only a guess, and is what is left when there is no evidence yet.
     */
    fun name(): String = ParamAlias.known(key) ?: if (AppState.litoMode) lito else other

    /** Whether [name] came from the board (true) or from the stored profile (false). */
    fun nameIsMeasured(): Boolean = ParamAlias.known(key) != null

    private fun hashOf(name: String): ByteArray = DumlNative.nativeParamHash(name)  // native appends "_0"

    private fun senderFor(port: Int) =
        if (port == DumlWire.PORT_LED || port == DumlWire.PORT_INJECT) DumlWire.SENDER_APP0 else DumlWire.SENDER_APP4
    private fun ctFor(port: Int) =
        if (port == DumlWire.PORT_LED || port == DumlWire.PORT_INJECT) DumlWire.CT_ACK else DumlWire.CT_ACK_BEFORE

    /** Read the (single, profile-selected) parameter. Note: injected reads rarely
     *  route back on RC2, so treat a null as "no reply", not "absent". */
    suspend fun read(port: Int = DumlWire.PORT_INJECT, wrapped: Boolean = false, readMs: Int = 150): ByteArray? =
        withContext(Dispatchers.IO) {
            val n = name()
            val inner = DumlNative.nativeBuildFrame(senderFor(port), DumlWire.DST_FLIGHT,
                ctFor(port), DumlWire.CMDSET_FLYC, DumlWire.CMDID_READ_PARAM_HASH, hashOf(n))
            val wire = if (wrapped) DumlWire.wrap(inner) else inner
            val reply = DumlBus.sendOnce(port, wire, readMs, "read $n")
            if (reply != null && reply.size > 1) reply else null
        }

    /**
     * Write [value] to the profile-selected parameter, repeated [writes] times.
     *
     * Returns true if at least one of the redundant repeats actually went out
     * (connect + write) — the repeats exist to survive a dropped frame, so one
     * confirmed send is a success; all repeats failing to leave the socket is a
     * genuine failure the caller can report honestly.
     */
    suspend fun write(value: ByteArray, port: Int = DumlWire.PORT_INJECT, wrapped: Boolean = false,
                      writes: Int = 3, gapMs: Long = 100): Boolean = withContext(Dispatchers.IO) {
        // NOTE: this deliberately does NOT resolve the address first. Settling it means a
        // 03:F7 round on 40007 — DJI Fly's video mirror — and a write path that opens
        // windows there would contradict the whole reason writes live on 40008. The
        // address is settled for free by the next READ of this parameter, which the UI
        // already performs after every write; until then a write says in the log that its
        // address is assumed, and a read-back says whether it landed. Considered and
        // rejected 2026-08-20.
        val n = name()
        val body = hashOf(n) + value
        // All repeats over ONE connection: on 40009 a fresh connect evicts the
        // previous client, so three sockets in a row could knock out the very
        // writes they repeat (see DumlBus.sendMany).
        //
        // Each repeat carries its OWN sequence number. They used to be built
        // identically — same seq 0, therefore the same CRC and the same bytes on the
        // wire — so a receiver that de-duplicates on (sender, seq, cmd) would have
        // collapsed the redundancy back to a single frame, and a log or a capture
        // could not tell a repeat from a re-log of the same one (2026-08-20).
        val wires = (0 until writes).map {
            val inner = DumlWire.withSeq(DumlNative.nativeBuildFrame(senderFor(port), DumlWire.DST_FLIGHT,
                ctFor(port), DumlWire.CMDSET_FLYC, DumlWire.CMDID_WRITE_PARAM_HASH, body), DumlSeq.next())
            if (wrapped) DumlWire.wrap(inner) else inner
        }
        val tags = List(wires.size) { i -> "write ${ParamName.tag(n)}=${DumlWire.toHex(value)} #${i + 1}/$writes" }
        val sent = DumlBus.sendMany(port, wires, gapMs.toInt(), tags)
        // The write is blind (40008 answers nothing), so this line is the only record of
        // WHAT was addressed and whether the bytes left the socket. It names the address
        // and says whether that address was measured on this board or merely assumed —
        // a write under a guessed spelling is a silent no-op, and that is exactly the
        // failure a shared log has to be able to show.
        DiagLog.info("write ${ParamName.tag(n)} = ${DumlWire.toHex(value)} · p$port · " +
            "$sent/$writes frame(s) out · address " +
            (if (nameIsMeasured()) "measured on this aircraft"
             else "from the ${if (AppState.litoMode) "Lito" else "g_config.*"} profile (not verified on this aircraft)"))
        sent > 0
    }

    companion object {
        // ---- logical params: (Lito X1 name, other-DJI name) ----
        /** Radio regulatory / region level. */
        val REGULATORY = ParameterAddress("ce_regulatory_level", "c1_regulatory_restriction")
        /** SDR link-loss safety gates — same name across models. */
        val SDR_LOST_NEVER = ParameterAddress("sdr_lost_prevent_never_takeoff_en")
        val SDR_LOST_HAS   = ParameterAddress("sdr_lost_prevent_has_takeoff_en")
        /** Front arm LEDs. */
        val FOREARM_LED = ParameterAddress("forearm_led_ctrl", "g_config.misc_cfg.forearm_lamp_ctrl")
        /** Master GNSS switch. */
        val GPS_ENABLE = ParameterAddress("gps_enable", "g_config.gps_cfg.gps_enable")
        /** Flight-mode switch (table-0, same name across models): 3 = ATTI, 12 = Cine.
         *  Confirmed live on Lito X1 (fswitch_selection, hash 58fd9834). */
        val FLIGHT_MODE = ParameterAddress("fswitch_selection")

        const val LED_ON: Byte = 0xEF.toByte()
        const val LED_OFF: Byte = 0x00
        const val GPS_ON: Byte = 0x01
        const val GPS_OFF: Byte = 0x00
        const val MODE_ATTI: Byte = 0x03
        const val MODE_CINE: Byte = 0x0C
    }
}
