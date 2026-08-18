package com.dji.fccgpsoff

import java.util.concurrent.atomic.AtomicLong

/**
 * Passive CHANNEL-liveness tracker — "is our native channel receiving anything?",
 * not "is a port open?".
 *
 * ⚠️ NOT a drone-presence signal. Proven on RC2 (see `doc/drone-link-detection.md`):
 * the main channel (40009) streams the controller's own housekeeping (`06:AE`,
 * `00:81`) byte-identically whether or not a drone is linked, so [connected] is
 * true even with the aircraft off. For "is an aircraft actually linked?" use
 * [DroneLink] (live FLYC OSD on the aux channel). This object stays as the health
 * signal for the native RX socket itself.
 *
 * The distinction matters: on a DJI smart controller a loopback DUML proxy port
 * can *accept a TCP connection while the aircraft is not linked* and silently
 * drop the frames you write (djiparam's `Duml.probe()` documents exactly this on
 * rc331; FreeFCC's connect-by-open-port is why its keepalive fires into a dead
 * link). So an open socket is a false "connected". The reliable signal on RC2 is
 * that device→app telemetry is *arriving*: OSD frames (03:44 and friends) stream
 * the whole time an aircraft is linked, while injected reads do not route back —
 * so we watch the inbound stream, not a reply.
 *
 * Fed from [DumlNative.onNativeFrame] (same RX thread as [HomePointMonitor] /
 * [SerialSniffer]); the frames delivered there have already passed the native
 * echo/dup filter, so every [onFrame] is genuine device traffic. Nothing is
 * injected — an idle watcher puts zero frames on the bus. This is the app's
 * analogue of djiparam's `isUp()` (RX within a freshness window).
 *
 * Pure Kotlin with an injectable [clock] so the freshness / reconnect-edge logic
 * is unit-testable off-device.
 */
object LinkState {

    /** RX freshness window: telemetry seen within this many ms ⇒ linked. Matches
     *  djiparam's 4 s liveness horizon. */
    const val STALE_MS = 4_000L

    /** Overridable time source (ms). Real time in production; fixed in tests. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    /** Total inbound frames seen — distinguishes "link down" from "never any RX".
     *  Atomic: the main and aux RX threads can both deliver concurrently. */
    private val rxCount = AtomicLong(0)
    val frames: Long get() = rxCount.get()

    @Volatile private var lastRxMs = 0L

    /** Feed one delivered frame (called on the native RX thread — keep it cheap). */
    fun onFrame() {
        rxCount.incrementAndGet()
        lastRxMs = clock()
    }

    /** True when device telemetry arrived within [staleMs] — the "aircraft is on
     *  the link" signal the keepalive gates its first Apply FCC on. */
    fun connected(staleMs: Long = STALE_MS): Boolean {
        val last = lastRxMs
        return last != 0L && clock() - last < staleMs
    }

    /** ms since the last inbound frame, or -1 if none ever. */
    fun ageMs(): Long = if (lastRxMs == 0L) -1 else clock() - lastRxMs

    /**
     * Reconnect-edge detector for "one Apply per (re)link". A caller remembers the
     * [frames] count it last acted on as [mark]; this returns true once the link
     * has been *stale and then fresh again* AND new frames have arrived since
     * [mark] — i.e. telemetry stopped (link dropped) and resumed (relinked),
     * which happens even indoors where no home point is ever recorded.
     *
     * [wasStale] carries the "we observed a gap" state between polls (the caller
     * owns it), so a steady, never-interrupted stream never reports a reconnect.
     */
    fun reconnected(mark: Long, wasStale: Boolean): Boolean =
        wasStale && connected() && frames > mark

    fun statusJson(): String =
        "{\"connected\":${connected()},\"frames\":$frames,\"ageMs\":${ageMs()}}"
}
