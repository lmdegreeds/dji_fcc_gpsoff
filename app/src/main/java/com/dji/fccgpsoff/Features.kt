package com.dji.fccgpsoff

import android.content.Context

/**
 * High-level actions, RC2-only (loopback).
 *
 * FCC has two device-independent halves:
 *   1. SDR assistant HARDWARE-REGISTER writes (09:27, addr 0xffff0048=2 =
 *      setForceFcc) — addressed by register address, not name → universal.
 *   2. Flight-controller PARAM writes addressed BY NAME via [ParameterAddress]
 *      (the name is picked by the Lito/Other device profile) → firmware-
 *      independent (no indices).
 *
 * Values that are genuinely device-specific (e.g. the regulatory level byte) are
 * written best-effort; the register writes are the proven, device-independent
 * core. Every frame is logged via [DumlBus], so what actually went out is in the
 * log. Success here means the frames actually LEFT THE SOCKET (connect + write),
 * not that the aircraft confirmed them — injected reads don't route back on RC2,
 * so there is no aircraft-side ack. A partial send is still reported as failure.
 */
class Features(ctx: Context) {

    private val runner = ProfileRunner(ctx)

    // Whether THIS Features holds a main-channel reference. Guards connect/disconnect
    // so a component takes exactly one ref no matter how often it (re)connects.
    @Volatile private var holdsMain = false

    /**
     * Persistent main-channel presence (serial probe / telemetry). Ref-counted via
     * [DumlTransport]: the first connect takes a reference and starts the channel;
     * repeated connects just re-ensure it's up (e.g. the keepalive retry loop).
     * Returns the port or -1.
     */
    fun connect(): Int =
        if (!holdsMain) { holdsMain = true; DumlTransport.acquire() } else DumlTransport.ensureUp()

    /** Release this component's channel reference; the channel stops only when the
     *  last holder disconnects. A no-op if this Features never connected. */
    fun disconnect() { if (holdsMain) { holdsMain = false; DumlTransport.release() } }

    /**
     * Switch radio CE→FCC by running FreeFCC's confirmed-working fcc.json
     * sequence (sender 130, port 40009, 2 rounds) — the register writes
     * (09:27 setForceFcc) + region are the device-independent core. Then a
     * name-addressed regulatory write in the *profile's* spelling — on Lito X1
     * that is ce_regulatory_level, which FreeFCC's fixed c1_* hash misses.
     *
     * Persistent: the change only takes effect after a controller + aircraft
     * reboot.
     */
    suspend fun applyFcc(): Boolean {
        DiagLog.info("applyFcc: running fcc.json (FreeFCC sequence)")
        // Hold ONE lease across the profile AND the follow-up regulatory write, so
        // the whole Apply FCC is atomic against another of our sessions on 40009
        // (previously the profile released the lock before the regulatory write).
        val lease = PortSessionLock.acquire(DumlWire.PORT_FCC) ?: run {
            DiagLog.warn("applyFcc: port ${DumlWire.PORT_FCC} busy — skipped"); return false
        }
        return try {
            val res = runner.run(runner.load("fcc.json"), alreadyLeased = true)
            val reg = ParameterAddress.REGULATORY.write(byteArrayOf(1), port = DumlWire.PORT_FCC, wrapped = false)
            // Honest result: the profile frames AND the regulatory write all had to
            // leave the socket. A partial send (link dropped mid-sequence) is a failed
            // apply — do not report "FCC applied" for it. Cf. FreeFCC 099081c.
            val ok = res.sent && reg
            if (!ok) DiagLog.warn("applyFcc: incomplete send (frames=${res.sent}, regulatory=$reg) — not applied")
            ok
        } finally { lease.close() }
    }

    // Restore CE was removed on purpose: reverting to CE drops 5.8 and needs an
    // aircraft reboot, and this build is FCC-only. The ce_restore.json asset is
    // kept for reference/native, but nothing in the app plays it.

    /** Arm LEDs on/off — name-addressed, UNWRAPPED inject port 40008 (never
     *  40007: that is DJI Fly's video mirror). Few writes, spaced, to keep the
     *  shared bus quiet. */
    suspend fun setLed(on: Boolean): Boolean =
        ParameterAddress.FOREARM_LED.write(byteArrayOf(if (on) 0xEF.toByte() else 0x00), writes = 2, gapMs = 120)

    /** Master GNSS switch — name-addressed. */
    suspend fun setGps(on: Boolean): Boolean =
        ParameterAddress.GPS_ENABLE.write(byteArrayOf(if (on) 1 else 0), writes = 2, gapMs = 120)

    /** Flight mode: Cine (12) when [cine], else ATTI (3). Name-addressed, 40008. */
    suspend fun setFlightMode(cine: Boolean): Boolean =
        ParameterAddress.FLIGHT_MODE.write(
            byteArrayOf(if (cine) ParameterAddress.MODE_CINE else ParameterAddress.MODE_ATTI), writes = 2, gapMs = 120)

    /** Read HW/LDR/app version (VersionInquiry). Null is the norm on RC2 —
     *  injected reads are not routed back to us. */
    suspend fun deviceInfo(): ByteArray? = runner.run(runner.load("device_info.json")).reply
}
