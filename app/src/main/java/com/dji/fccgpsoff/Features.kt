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

    companion object {
        /** When the last Apply FCC finished, from any caller. Process-wide because
         *  the bus is: the keepalive, the overlay and the diag server all share it. */
        @Volatile var lastApplyFinishedMs = 0L; private set

        /** ms since the last apply finished, or [Long.MAX_VALUE] if none this run. */
        fun sinceLastApplyMs(): Long =
            if (lastApplyFinishedMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - lastApplyFinishedMs
    }

    /**
     * Switch radio CE→FCC with ONE write: `07:30` to receiver 9, payload
     * `41550000415500000100` (fcc.json).
     *
     * It used to be FreeFCC's 21-frame sequence played twice, followed by a
     * name-addressed `ce_regulatory_level` write. Frame-subset experiments on
     * 2026-08-19 (RC 2 + Lito X1, doc/fcc-minimal-sequence.md) cut all of that:
     * this one frame raises transmit power AND enables 5.8 GHz, and the
     * regulatory write never stuck — it reads back `ff` whether FCC is on or off.
     *
     * The frame is sent EIGHT times, one second apart (fcc.json `rounds`, one
     * connection per round). A single send is not enough on a live session: it
     * failed over 20 s of watching, twice, while a one-second burst landed on the
     * 3rd and 4th shot. Two sends 100 ms apart in one connection also failed, so
     * what the aircraft wants is separate sends spaced in time.
     *
     * Persistent within a flight session: the change survives until the aircraft
     * is power-cycled for real. A short "reboot" that does not fully cut power
     * leaves it in place, which is why a CE reading is the only proof the switch
     * was lost.
     */
    suspend fun applyFcc(): Boolean {
        DiagLog.info("applyFcc: one frame 07:30 (fcc.json)")
        // Short timeout on purpose: the lock's 3 s default let a second apply QUEUE
        // behind the first and run back to back. Measured on hardware — a keepalive
        // apply, an overlay tap and a session-edge apply once stacked into three
        // consecutive profiles, ~135 connects in a few seconds. An apply that is
        // already running is doing the job; a second one only hammers the bus.
        val lease = PortSessionLock.acquire(DumlWire.PORT_FCC, timeoutMs = 300) ?: run {
            DiagLog.warn("applyFcc: another apply is already running — skipped"); return false
        }
        return try {
            // Honest result: the frame had to leave the socket. A write that never
            // went out is a failed apply — do not report "FCC applied" for it.
            // Cf. FreeFCC 099081c.
            val ok = runner.run(runner.load("fcc.json"), alreadyLeased = true).sent
            if (!ok) DiagLog.warn("applyFcc: frame did not leave the socket — not applied")
            ok
        } finally { lastApplyFinishedMs = System.currentTimeMillis(); lease.close() }
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
