package com.dji.fccgpsoff

/**
 * Passive home-point watcher — the event source for the event-driven keepalive.
 *
 * OSD_HOME_POINT (03:44) streams from the flight controller the whole time an
 * aircraft is linked; bit 0 of the uint16 at payload offset 20 is "home point
 * recorded". That bit is set exactly when DJI Fly has (re)established the link
 * and re-pushed its region — the one moment the FCC profile is worth replaying.
 * Same field Skylab's HomePointMonitor reads, taken off our own RX sink instead
 * of a second socket.
 *
 * Nothing is injected: [offer] is fed from [DumlNative.onNativeFrame], like
 * [SerialSniffer], so an idle keepalive puts zero frames on the bus. It runs on
 * the native RX thread — allocation-light and non-throwing.
 */
object HomePointMonitor {

    private const val CMDSET = 0x03
    private const val CMDID = 0x44
    private const val FLAGS_OFF = 20        // uint16 LE
    private const val HOME_RECORDED = 0x01  // bit 0

    /** Current state; false until the first 03:44 says otherwise. */
    @Volatile var recorded = false; private set
    /** Frames seen — distinguishes "not recorded" from "no telemetry at all".
     *  Atomic: the main and aux RX threads can both deliver 03:44 concurrently. */
    private val seenCount = java.util.concurrent.atomic.AtomicLong(0)
    val seen: Long get() = seenCount.get()
    @Volatile private var lastEdgeMs = 0L

    /** Feed one delivered frame. */
    fun offer(cmdSet: Int, cmdId: Int, payload: ByteArray) {
        if (cmdSet != CMDSET || cmdId != CMDID) return
        if (payload.size < FLAGS_OFF + 2) return
        seenCount.incrementAndGet()
        val flags = (payload[FLAGS_OFF].toInt() and 0xFF) or ((payload[FLAGS_OFF + 1].toInt() and 0xFF) shl 8)
        val now = (flags and HOME_RECORDED) != 0
        if (now == recorded) return
        recorded = now
        if (now) {
            lastEdgeMs = System.currentTimeMillis()
            DiagLog.info("home point recorded — aircraft linked")
        } else {
            DiagLog.info("home point cleared — link down")
        }
    }

    /** ms since the last 0->1 transition, or -1 if never. */
    fun ageMs(): Long = if (lastEdgeMs == 0L) -1 else System.currentTimeMillis() - lastEdgeMs

    fun statusJson(): String = "{\"recorded\":$recorded,\"frames\":$seen,\"ageMs\":${ageMs()}}"
}
