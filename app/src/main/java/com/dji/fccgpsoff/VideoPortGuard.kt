package com.dji.fccgpsoff

import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap

/**
 * One place that can stop **every** read on DJI Fly's video-mirror port (40007) at once.
 *
 * Until now the protection was cooperative: each reader polled [ForegroundGate] in its own
 * loop and stopped when it noticed. That is not instant, and the gap is exactly where the
 * damage happens. Three reasons it lags:
 *
 *  - a blocking `read()` learns nothing until it returns (up to the socket timeout);
 *  - each loop polls at its own cadence — 120 ms in [ParamRead], 500 ms in [DroneLinkProbe];
 *  - the gate itself only closes when the accessibility window event arrives, which is
 *    *after* DJI Fly is already up and re-establishing its session.
 *
 * Landing a read in that window costs Fly its link for several seconds — observed on
 * hardware as a red "not connected to the mobile device" right after switching to Fly, and
 * as Fly bouncing back to its connect-the-drone screen.
 *
 * So: every socket opened on 40007 registers here, and when Fly takes the foreground they
 * are **closed**, not asked to stop. A blocked read throws at once, the window walk ends,
 * and the port is Fly's. The cooperative gate checks stay where they are — they prevent
 * new reads from starting; this ends the ones already in flight.
 */
object VideoPortGuard {

    /** The port this guards. Nothing else is Fly's video mirror. */
    const val PORT = DumlWire.PORT_VIDEO_MIRROR

    private val open = ConcurrentHashMap.newKeySet<java.net.Socket>()

    /** Extra stoppers for things that are not a plain socket — the native aux reader.
     *  Registered once at startup; each is invoked on [closeAll]. */
    private val stoppers = ConcurrentHashMap.newKeySet<() -> Unit>()

    fun addStopper(f: () -> Unit) { stoppers.add(f) }

    /** Live (still-open) registered sockets. Prunes as it counts. */
    val openCount: Int get() { prune(); return open.size }

    /**
     * Put [s] under the guard, but only when [port] is the video mirror — a socket on
     * 40008/40009 is not Fly's and must not be torn down with it.
     *
     * Callers do not have to deregister: the registry drops sockets once they are closed,
     * which every caller already does via `use`. That keeps this a two-line change at each
     * read site instead of a restructure, and there is nothing to leak if a path exits by
     * an early `return` — which several of them do.
     */
    fun register(port: Int, s: java.net.Socket): Closeable {
        if (port != PORT) return Closeable { }
        prune()
        open.add(s)
        return Closeable { open.remove(s) }
    }

    private fun prune() { open.removeAll { it.isClosed } }

    /**
     * Close every registered 40007 socket now.
     *
     * Safe to call from any thread and at any time — closing an already-closed socket is a
     * no-op, and a reader whose socket disappears sees an exception it already treats as
     * "the window is over". Never throws.
     */
    fun closeAll(reason: String) {
        prune()
        val n = open.size
        if (n > 0) DiagLog.info("40007: closing $n open read socket(s) — $reason")
        for (c in open) {
            open.remove(c)
            runCatching { c.close() }
        }
        for (s in stoppers) runCatching { s() }
    }
}
