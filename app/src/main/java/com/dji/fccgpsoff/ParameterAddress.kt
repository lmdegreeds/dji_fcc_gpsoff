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
 * Each logical parameter carries the Lito X1 name and the "other DJI" name;
 * [AppState.litoMode] picks exactly one, so we write a single name (no candidate
 * spray → fewer writes → less churn on the shared bus). Writes go to the
 * UNWRAPPED inject port (40008), which coexists with DJI Fly (40007 is Fly's
 * video mirror and drops the link if written to).
 */
class ParameterAddress(private val lito: String, private val other: String = lito) {

    /** Both names (for diagnostics/labels). */
    val names: List<String> get() = if (lito == other) listOf(lito) else listOf(lito, other)

    /** The name to use for the current device profile. */
    fun name(): String = if (AppState.litoMode) lito else other

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
        val n = name()
        val body = hashOf(n) + value
        var anySent = false
        repeat(writes) {
            val inner = DumlNative.nativeBuildFrame(senderFor(port), DumlWire.DST_FLIGHT,
                ctFor(port), DumlWire.CMDSET_FLYC, DumlWire.CMDID_WRITE_PARAM_HASH, body)
            val wire = if (wrapped) DumlWire.wrap(inner) else inner
            if (DumlBus.sendFrame(port, wire, "write $n=${DumlWire.toHex(value)}")) anySent = true
            delay(gapMs)
        }
        anySent
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
