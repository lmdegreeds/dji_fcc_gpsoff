package com.dji.fccgpsoff

/**
 * Central send path so every DUML frame is logged (TX + any reply RX) in one
 * place. Feature code calls this instead of DumlNative.nativeSendOnce directly.
 */
object DumlBus {

    /** One-shot send on [port]; logs TX and (if any) the reply. */
    fun sendOnce(port: Int, wire: ByteArray, readMs: Int, tag: String): ByteArray? {
        DiagLog.tx(port, tag, wire)
        val reply = DumlNative.nativeSendOnce(port, wire, readMs)
        if (reply != null) DiagLog.rx(port, "$tag<reply", reply)
        return reply
    }

    /**
     * One-shot send that returns only the payload of a reply matching
     * [wantSet]/[wantId] — telemetry sharing the socket is walked past, not
     * mistaken for the answer. Use for unwrapped reads that must be correlated
     * (e.g. the 07:19 country query).
     */
    fun sendOnceMatch(port: Int, wire: ByteArray, readMs: Int, wantSet: Int, wantId: Int, tag: String): ByteArray? {
        DiagLog.tx(port, tag, wire)
        val reply = DumlNative.nativeSendOnceMatch(port, wire, readMs, wantSet, wantId)
        if (reply != null) DiagLog.rx(port, "$tag<reply", reply)
        return reply
    }

    /**
     * One-shot WRITE on [port]; logs TX and returns whether the frame actually
     * went out (connect + full write). Use this — not [sendOnce] — when the
     * caller needs to know the send succeeded, e.g. to report an honest
     * apply/restore result rather than "queued a socket that may have failed".
     */
    fun sendFrame(port: Int, wire: ByteArray, tag: String): Boolean {
        DiagLog.tx(port, tag, wire)
        val ok = DumlNative.nativeSendFrame(port, wire)
        if (!ok) DiagLog.warn("$tag: send failed (no connect/write on $port)")
        return ok
    }

    /**
     * Write a whole batch of frames over ONE connection — the send path for
     * anything that emits more than a couple of frames.
     *
     * Why this exists (measured on RC 2, `doc/drone-link-detection.md`): the 40009
     * broker keeps one client at a time and every fresh connect evicts the last
     * one. An FCC apply used to be 45 back-to-back connects, so its own frames
     * raced each other — during an apply our persistent channel was evicted 76
     * times in 9 s, windows collapsing to 0-70 ms, against calm 2-4 s windows
     * between applies. Each eviction can drop a frame the broker had not yet
     * forwarded, and the write still looks successful because the bytes reached
     * the socket. Sending the batch down one socket removes that race.
     *
     * Logs every frame (so the diag log reads as before) and returns how many
     * actually went out; [tags] is parallel to [frames].
     */
    fun sendMany(port: Int, frames: List<ByteArray>, gapMs: Int, tags: List<String>): Int {
        if (frames.isEmpty()) return 0
        for ((i, w) in frames.withIndex()) DiagLog.tx(port, tags.getOrElse(i) { "" }, w)
        val n = DumlNative.nativeSendMany(port, frames.toTypedArray(), gapMs)
        when {
            n < 0 -> DiagLog.warn("sendMany: no connect on $port — ${frames.size} frames not sent")
            n < frames.size -> DiagLog.warn("sendMany: only $n/${frames.size} frames written on $port")
        }
        return n
    }

    /**
     * Diagnostics: which loopback DUML ports are open right now.
     *
     * Note 40007 IS probed (connect + immediate close) because knowing it is up
     * is useful; that momentary connection can blip DJI Fly's video. Nothing
     * else in the app touches 40007.
     */
    fun probePorts(): String {
        val ports = intArrayOf(40009, 40008, 40007, 8901, 8902, 8903, 8904)
        val open = ports.filter { DumlNative.nativeProbePort(it) }
        val s = if (open.isEmpty()) "none open (link the aircraft / run on the RC)" else open.joinToString(", ")
        DiagLog.info("port scan: $s")
        return s
    }

    fun stats(): String = DumlNative.nativeStats().also { DiagLog.info("stats: $it") }
}
