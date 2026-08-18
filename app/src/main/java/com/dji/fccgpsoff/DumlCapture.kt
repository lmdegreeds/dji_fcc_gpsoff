package com.dji.fccgpsoff

/**
 * In-memory capture of the frames the transport reads, so a browser on the LAN
 * can pull them and assemble a .pcap client-side (no file is written on device).
 *
 * It records the "hijack read" stream: every frame delivered by the native RX
 * sink — main channel (route 0) and the aux/hijack channel on DJI Fly's port
 * (route 1). Each frame keeps its exact on-the-wire bytes (0x55 .. CRC16) and a
 * monotonic id the browser uses as a cursor to fetch only what is new.
 *
 * Enabling capture also opens the aux reader (DumlNative.nativeStartAux), which
 * is what makes coexistent reading with DJI Fly possible — and is intrusive on
 * 40007. Disabling closes it again.
 */
object DumlCapture {

    // Plain class, not `data class`: it holds a ByteArray, whose generated
    // equals/hashCode would compare by reference and mislead.
    class Rec(val id: Long, val tMicros: Long, val route: Int, val wire: ByteArray)

    // Bound the ring by TOTAL BYTES, not frame count — 40k frames could be tens of
    // MB. 16 MB keeps memory predictable regardless of frame size.
    private const val MAX_BYTES = 16L * 1024 * 1024
    const val DEFAULT_AUX_PORT = 40007             // DJI Fly's video/telemetry mirror on RC2

    private val ring = ArrayDeque<Rec>(1024)
    private var ringBytes = 0L
    @Volatile private var nextId = 1L
    @Volatile private var dropped = 0L
    @Volatile var capturing = false; private set
    @Volatile var auxPort = DEFAULT_AUX_PORT; private set

    // wall-clock anchor so pcap timestamps are real yet sub-millisecond ordered
    private var baseMicros = 0L
    private var baseNanos = 0L

    /** Turn capture on: reset the buffer and open the aux/hijack reader on [port]. */
    fun start(port: Int = DEFAULT_AUX_PORT): String {
        synchronized(this) {
            auxPort = port
            ring.clear(); ringBytes = 0L; nextId = 1L; dropped = 0L
            baseMicros = System.currentTimeMillis() * 1000L
            baseNanos = System.nanoTime()
            capturing = true
        }
        // Open the aux reader OUTSIDE our lock: acquire() blocks on the ~2s native
        // handover, and the RX thread it spawns calls offer(), which takes THIS
        // lock — holding it here would stall (or, on stop, deadlock against join()).
        // Shared via AuxReader so DroneLinkProbe and capture don't stop each other's
        // reader; refcounted, so this is a no-op start if the probe already holds it.
        val got = AuxReader.acquire(port)
        // The MAIN channel is on-demand now, and capture is the only thing that still
        // reads route-0 frames — so it is capture's job to raise it. Traced across all
        // eleven route-0 consumers: every other one either rejects route 0 outright
        // (DroneLink), is proven-empty on 40009 (HomePointMonitor: 0 frames), reads a
        // carrier that only exists on 40007 (serial/model), is the documented
        // LinkState false positive, or is dead code. Keeping the channel up
        // permanently meant ~19 reconnects/minute competing for a broker slot to feed
        // consumers that discard the frames. Ref-counted, so a manual /connect that
        // already holds it is undisturbed.
        DumlTransport.acquire()
        val auxMsg = if (got >= 0) "aux hijack on $got" else "aux connect failed (Fly may hold $port)"
        DiagLog.info("capture started: $auxMsg — main channel up, recording main+aux frames")
        return auxMsg
    }

    /** Turn capture off and close the aux reader (main channel is untouched). */
    fun stop(): String {
        val seen: Long; val drop: Long
        synchronized(this) { capturing = false; seen = nextId - 1; drop = dropped }
        // Close the aux reader OUTSIDE the lock: the native stop joins the RX
        // thread, which may be blocked in offer() waiting for this lock — holding
        // it here deadlocks (the hang that made "Disable capture" do nothing).
        // release() only really stops the reader if DroneLinkProbe isn't also holding it.
        AuxReader.release()
        DumlTransport.release()          // last holder drops the main channel again
        DiagLog.info("capture stopped ($seen frames seen, $drop dropped)")
        return "stopped"
    }

    /** Called from the native RX thread for every delivered frame. Fast + safe. */
    @Synchronized fun offer(route: Int, wire: ByteArray) {
        if (!capturing) return
        val micros = baseMicros + (System.nanoTime() - baseNanos) / 1000L
        ring.addLast(Rec(nextId++, micros, route, wire))
        ringBytes += wire.size
        while (ringBytes > MAX_BYTES && ring.isNotEmpty()) { ringBytes -= ring.removeFirst().wire.size; dropped++ }
    }

    /**
     * JSON array of up to [limit] records with id > [since] (browser cursor poll).
     *
     * Snapshots the matching records under the lock, then serializes OUTSIDE it:
     * holding the lock while building a big hex string starves the RX thread's
     * [offer] (they share this monitor), which stalled large responses long
     * enough for the client to time out and read an empty body. The default page
     * is bounded so one poll never serializes the whole ring — the browser keeps
     * a cursor and pages the rest.
     */
    fun sinceJson(since: Long, limit: Int = 2_000): String {
        val snap = ArrayList<Rec>(minOf(limit, 1024))
        synchronized(this) {
            for (r in ring) {
                if (r.id <= since) continue
                snap.add(r)
                if (snap.size >= limit) break
            }
        }
        val sb = StringBuilder(snap.size * 96 + 16).append('[')
        for ((i, r) in snap.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(r.id)
                .append(",\"t\":").append(r.tMicros)
                .append(",\"r\":").append(r.route)
                .append(",\"h\":\"").append(DumlWire.toHex(r.wire)).append("\"}")
        }
        return sb.append(']').toString()
    }

    /** JSON status object for the UI. */
    @Synchronized fun statusJson(): String {
        val firstId = ring.firstOrNull()?.id ?: 0L
        val lastId = if (nextId > 1) nextId - 1 else 0L
        val auxUp = runCatching { DumlNative.nativeAuxRunning() }.getOrDefault(false)
        return "{\"capturing\":$capturing,\"auxRunning\":$auxUp,\"auxPort\":$auxPort," +
            "\"buffered\":${ring.size},\"firstId\":$firstId,\"lastId\":$lastId,\"dropped\":$dropped}"
    }
}
