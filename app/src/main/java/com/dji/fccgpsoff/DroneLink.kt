package com.dji.fccgpsoff

import java.util.concurrent.atomic.AtomicLong

/**
 * HONEST "is an aircraft actually on the link?" signal — the fix for
 * [LinkState]'s false positive.
 *
 * The distinction (proven on RC2 + Lito X1, see `doc/drone-link-detection.md`):
 * the main channel (40009) streams the controller's own housekeeping — `06:AE`,
 * `00:81` ("rc331") — **byte-identical whether or not a drone is linked**, so
 * "any RX ⇒ linked" ([LinkState]) is permanently true even with the aircraft off.
 * The real link marker is **live FLYC OSD**, which surfaces only on the aux
 * channel (40007, route 1): `03:xx` OSD at ~10 Hz and `23:B2` at ~50 Hz appear
 * only while an aircraft is linked; when it powers off only housekeeping and the
 * sticky cached `51:14` serial broadcast remain. So we count *those* frames, not
 * every frame.
 *
 * Fed from [DumlNative.onNativeFrame] alongside [LinkState]/[HomePointMonitor];
 * every delivered frame has already passed the native echo/dup filter. Nothing is
 * injected — an idle watcher puts zero frames on the bus. The aux reader that
 * actually delivers route-1 OSD is gated on "DJI Fly is not foreground" by
 * [DroneLinkProbe], because touching 40007 blips Fly's video (~1 s).
 *
 * Pure Kotlin with an injectable [clock] so the freshness / reconnect-edge logic
 * is unit-testable off-device (mirrors [LinkState]).
 */
object DroneLink {

    /** RX freshness window: OSD seen within this many ms ⇒ aircraft linked. Matches
     *  [LinkState.STALE_MS]; the aux reader may be released while Fly is foreground,
     *  so the last fresh OSD carries the state across that gap. */
    const val STALE_MS = 4_000L

    /** Overridable time source (ms). Real time in production; fixed in tests. */
    @Volatile var clock: () -> Long = System::currentTimeMillis

    /**
     * True for a frame that only a linked aircraft emits — live FLYC OSD
     * (`cmdSet 0x03`) or the video-status `23:B2`. Controller housekeeping
     * (`06:AE`, `51:04`, `51:14`, `0E:66`, `00:81`) is deliberately excluded: it
     * streams with no drone present. Set pinned by `doc/drone-link-detection.md` §3.
     * NOTE: only meaningful for AUX-route frames — on the main channel `cmdSet 0x03`
     * also covers our own `03:F8`/`03:F9` param traffic, so [offer] route-gates.
     */
    fun isDroneOsd(cmdSet: Int, cmdId: Int): Boolean =
        cmdSet == 0x03 || (cmdSet == 0x23 && cmdId == 0xB2)

    /** Route of the aux/hijack reader on 40007 — the only channel that carries
     *  device→app OSD (route 0 is the main channel: RC housekeeping + our own
     *  request/reply echoes). Matches [DumlNative.onNativeFrame]'s `route`. */
    const val AUX_ROUTE = 1

    /** OSD frames seen — distinguishes "link down" from "aux never read". Atomic:
     *  main and aux RX threads can both deliver concurrently. */
    private val osdCount = AtomicLong(0)
    val frames: Long get() = osdCount.get()

    @Volatile private var lastRxMs = 0L

    /** Feed one delivered frame (native RX thread — keep it cheap). Non-OSD frames
     *  are ignored, so only genuine aircraft telemetry moves the link state. */
    fun offer(cmdSet: Int, cmdId: Int, route: Int) {
        if (route != AUX_ROUTE) return              // OSD lives only on 40007 (aux); main is RC housekeeping
        if (!isDroneOsd(cmdSet, cmdId)) return
        osdCount.incrementAndGet()
        lastRxMs = clock()
    }

    /** True when live aircraft OSD arrived within [staleMs] — the honest "aircraft
     *  is on the link" signal (unlike [LinkState.connected], which is true from RC
     *  housekeeping alone). */
    fun connected(staleMs: Long = STALE_MS): Boolean {
        val last = lastRxMs
        return last != 0L && clock() - last < staleMs
    }

    /** ms since the last OSD frame, or -1 if none ever. */
    fun ageMs(): Long = if (lastRxMs == 0L) -1 else clock() - lastRxMs

    /**
     * Reconnect-edge detector for "one action per (re)link", mirroring
     * [LinkState.reconnected]: true once OSD has been *stale and then fresh again*
     * AND new frames have arrived since [mark]. [wasStale] carries the observed-gap
     * state between polls (the caller owns it).
     */
    fun reconnected(mark: Long, wasStale: Boolean): Boolean =
        wasStale && connected() && frames > mark

    fun statusJson(): String =
        "{\"connected\":${connected()},\"frames\":$frames,\"ageMs\":${ageMs()}}"
}
