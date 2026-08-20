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
     * `41550000415500000100` (fcc.json) — where `4155` is the ASCII country and
     * comes from [AppState.fccRegion] (AU unless the user picked another).
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
        val region = AppState.fccRegion
        DiagLog.info("applyFcc: one frame 07:30 (fcc.json) · region " + region.display())
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
            val ok = runner.run(FccRegion.patch(runner.load("fcc.json"), region), alreadyLeased = true).sent
            if (!ok) DiagLog.warn("applyFcc: frame did not leave the socket — not applied")
            ok
        } finally { lastApplyFinishedMs = System.currentTimeMillis(); lease.close() }
    }

    // Restore CE was removed on purpose: reverting to CE drops 5.8 and needs an
    // aircraft reboot, and this build is FCC-only. The ce_restore.json asset is
    // kept for reference/native, but nothing in the app plays it.

    /**
     * Arm LEDs on/off — name-addressed, UNWRAPPED inject port 40008 (never 40007: that is
     * DJI Fly's video mirror). Few writes, spaced, to keep the shared bus quiet.
     *
     * [by] names WHO asked, and it is not decoration. There are three routes to this
     * function — the Main page, the floating panel and the web dashboard — and until
     * 2026-08-20 only the first of them told [FlightState] that a write had happened. The
     * other two left the cached value describing a state that no longer existed, so the
     * Main page could show a reading nobody had set and its switch could snap to it. Every
     * route now goes through [note], which is the single place a write is registered.
     */
    suspend fun setLed(on: Boolean, by: String = ANON): Boolean =
        note(FlightState.Item.LED, on, if (on) "on" else "off", by,
            ParameterAddress.FOREARM_LED.write(byteArrayOf(if (on) 0xEF.toByte() else 0x00), writes = 2, gapMs = 120))

    /** Master GNSS switch — name-addressed. See [setLed] for what [by] is for. */
    suspend fun setGps(on: Boolean, by: String = ANON): Boolean =
        note(FlightState.Item.GPS, on, if (on) "on" else "off", by,
            ParameterAddress.GPS_ENABLE.write(byteArrayOf(if (on) 1 else 0), writes = 2, gapMs = 120))

    /** Flight mode: Cine (12) when [cine], else ATTI (3). Name-addressed, 40008. */
    suspend fun setFlightMode(cine: Boolean, by: String = ANON): Boolean =
        note(FlightState.Item.MODE, cine, if (cine) "Cine" else "ATTI", by,
            ParameterAddress.FLIGHT_MODE.write(
                byteArrayOf(if (cine) ParameterAddress.MODE_CINE else ParameterAddress.MODE_ATTI),
                writes = 2, gapMs = 120))

    /**
     * Register a live-state write, whoever asked for it.
     *
     * The pending intent is recorded even when the frames failed to leave the socket, then
     * immediately withdrawn — recording and withdrawing is what makes the failure appear in
     * the log as an event rather than as nothing at all. On success the intent stands until
     * a read-back confirms or contradicts it ([FlightState.observe]).
     *
     * The Main page marks the intent a second time, at TAP time; that is deliberate, not a
     * duplicate. The tap has to be held from the instant the finger lifts (a render tick
     * could otherwise fire in between), while the settle window has to be measured from
     * when the frames actually went out. Two different, both correct, timestamps.
     */
    private fun note(item: FlightState.Item, stored: Boolean, word: String, by: String, sent: Boolean): Boolean {
        // Never blindly overwrite the standing intent. Two taps on the same switch race
        // through independent coroutines, and the slower one finishing must not put back
        // the value the user has already changed their mind about (2026-08-20).
        val standing = FlightState.wanted(item)
        when {
            !sent && standing == stored -> FlightState.clearWritten(item)
            !sent -> {}                                     // a newer intent stands; leave it
            standing == null -> FlightState.markWritten(item, stored)   // overlay / dashboard
            standing == stored -> FlightState.retimeCurrent(item)       // our own tap, now sent
            else -> {}                                      // superseded by a newer tap
        }
        DiagLog.info("${item.label} → $word · asked by $by · " +
            (if (sent) "recorded as a pending write; a read-back can still contradict it"
             else "NOT SENT — no frame left the socket, nothing is pending"))
        return sent
    }

    /** Default author. A write whose origin is not named is a write nobody can account for,
     *  so every in-tree caller passes a real one and this exists only to keep the signature
     *  usable from a scratch experiment. */
    private val ANON = "an unnamed caller"


    /** Read HW/LDR/app version (VersionInquiry). Null is the norm on RC2 —
     *  injected reads are not routed back to us. */
    suspend fun deviceInfo(): ByteArray? = runner.run(runner.load("device_info.json")).reply
}
