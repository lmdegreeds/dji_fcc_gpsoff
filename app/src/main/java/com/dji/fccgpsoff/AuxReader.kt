package com.dji.fccgpsoff

/**
 * Reference-counted owner of the single native aux/hijack reader on DJI Fly's
 * port (40007). Two independent features now want that reader — [DumlCapture]
 * (the in-browser .pcap) and [DroneLinkProbe] (the honest link detector) — and
 * the native side has exactly one aux socket. Without a refcount, whichever
 * feature stops first would `nativeStopAux()` out from under the other.
 *
 * `acquire()` starts the reader on the 0→1 edge; `release()` stops it on the
 * 1→0 edge; in between it is a no-op that just moves the count. Only port 40007
 * is supported (RC2's video mirror); a mismatched port request while already held
 * keeps the running one and warns.
 *
 * Concurrency: the guarded native calls block (`nativeStartAux` ~2 s handover,
 * `nativeStopAux` joins the RX thread), so we hold [lock] across them — which is
 * safe here because the RX hot path (frame delivery) does NOT take this lock
 * (it feeds [DumlCapture]/[DroneLink], never [AuxReader]). Callers are the diag
 * server and the keepalive coroutine; serializing them for the transition is fine.
 *
 * [startFn]/[stopFn] are injectable so the refcount edges are unit-testable
 * without the native library.
 */
object AuxReader {

    const val PORT = DumlCapture.DEFAULT_AUX_PORT   // 40007

    /** Native hooks — overridable in tests. Return value = the port, or -1. */
    @Volatile var startFn: (Int) -> Int = { DumlNative.nativeStartAux(it) }
    @Volatile var stopFn: () -> Unit = { DumlNative.nativeStopAux() }

    private val lock = Any()
    private var count = 0
    @Volatile private var startedPort = -1

    /** True while the reader is (intended to be) up. */
    val active: Boolean get() = synchronized(lock) { count > 0 }
    /** Current holder count (diagnostics/tests). */
    val holders: Int get() = synchronized(lock) { count }

    /**
     * Take a lease on the aux reader; starts it on the first lease. Returns the
     * connected port (or -1 if the native handover never connected). Safe to call
     * repeatedly; balance every acquire with one [release].
     */
    fun acquire(port: Int = PORT): Int = synchronized(lock) {
        if (count > 0) {
            if (port != PORT) DiagLog.warn("aux reader already on $PORT — ignoring request for $port")
            count++
            return startedPort
        }
        startedPort = runCatching { startFn(port) }.getOrDefault(-1)
        count = 1
        DiagLog.info("aux reader started on ${if (startedPort >= 0) startedPort else "connect-failed"} (holders=1)")
        startedPort
    }

    /** Drop a lease; stops the reader on the last release. Extra releases are ignored. */
    fun release() = synchronized(lock) {
        if (count == 0) { DiagLog.warn("aux reader release with no holders — ignored"); return }
        count--
        if (count == 0) {
            runCatching { stopFn() }
            startedPort = -1
            DiagLog.info("aux reader stopped (holders=0)")
        }
    }
}
