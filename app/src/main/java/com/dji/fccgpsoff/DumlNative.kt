package com.dji.fccgpsoff

/**
 * JNI surface of libduml-core, the native loopback DUML transport.
 *
 * Frames arrive from native via [onNativeFrame], the RX sink. Everything else
 * is a thin call-down.
 */
object DumlNative {

    init { System.loadLibrary("duml-core") }

    /** Connect to the first working loopback DUML proxy; returns the port or -1. */
    external fun nativeStart(): Int
    external fun nativeStop()

    /**
     * Start the aux/hijack reader: a second concurrent read socket on [port]
     * (the port DJI Fly holds — 40007 on RC2). Frames it parses share the main
     * channel's seq dedup, so overlap is counted as dupCross and delivered once.
     * Returns the port on success, or -1 if the handover never connected.
     * Intrusive: a live 40007 connection can perturb DJI Fly's video.
     */
    external fun nativeStartAux(port: Int): Int
    external fun nativeStopAux()
    external fun nativeAuxRunning(): Boolean

    /** Fire-and-forget one frame. receiver = dst TTII. */
    external fun nativeSend(receiver: Int, cmdType: Int, cmdSet: Int, cmdId: Int, payload: ByteArray?): Boolean

    /** Send and await a same-seq response within [timeoutMs]; null on timeout. */
    external fun nativeRequest(receiver: Int, cmdType: Int, cmdSet: Int, cmdId: Int, payload: ByteArray?, timeoutMs: Int): ByteArray?

    /** 4-byte little-endian FlyController parameter hash of [name]. */
    external fun nativeParamHash(name: String): ByteArray

    /** Build a wire DUML frame (0x55 + CRC8/16) without sending. */
    external fun nativeBuildFrame(sender: Int, receiver: Int, cmdType: Int, cmdSet: Int, cmdId: Int, payload: ByteArray?): ByteArray

    /** One-shot: connect to 127.0.0.1:port, send [wire], read a reply within [readMs], close. */
    external fun nativeSendOnce(port: Int, wire: ByteArray, readMs: Int): ByteArray?

    /**
     * Like [nativeSendOnce], but walks the read window and returns the payload of
     * the first frame whose cmdSet/cmdId match [wantSet]/[wantId] (−1 = any), so a
     * reply is picked out of interleaved telemetry rather than the first frame
     * that arrives. Null if none matched in the window.
     */
    external fun nativeSendOnceMatch(port: Int, wire: ByteArray, readMs: Int, wantSet: Int, wantId: Int): ByteArray?

    /**
     * One-shot WRITE with an honest result: connect + send [wire], close. Returns
     * true only if connect and the full write both succeeded — the signal for
     * "the apply frame actually went out", independent of any reply (which does
     * not route back on RC2).
     */
    external fun nativeSendFrame(port: Int, wire: ByteArray): Boolean

    /**
     * Write several already-built frames over ONE connection, [gapMs] apart.
     * Returns how many were fully written, or -1 if the connect failed.
     *
     * The 40009 broker serves one client at a time and evicts the incumbent on
     * every new connect, so a frame-per-socket profile knocks its own frames out
     * mid-flight (see `Transport::send_many`). Use this for any multi-frame write.
     */
    external fun nativeSendMany(port: Int, frames: Array<ByteArray>, gapMs: Int): Int

    /** Send one wire frame straight onto the controller's DUSS message bus
     *  (abstract unix socket `/duss/mb/0x205`) — the firmware-bus diagnostic path,
     *  used only by the diag server's duss endpoints. See `doc/DUSS-HARDWARE-FINDINGS.md`. */
    external fun nativeDussSend(wire: ByteArray): Boolean

    /**
     * DUSS firmware-bus probe: sweep connect() across {DGRAM,STREAM} ×
     * {abstract,pathname} to [peer] and report OK / errno for each — discovers the
     * router mailbox's real namespace + socket type. See [DussBus].
     */
    external fun nativeDussProbe(peer: String): String

    /**
     * One full DUSS transaction (REPORT/FIRMWARE-BUS-DUSS.md §3): socket → optional
     * bind of abstract source → SO_RCVTIMEO → connect [peer] → send [wire] → read a
     * reply matching [wantSet]/[wantId] (−1 = any). [flags]: bit0 SOCK_DGRAM,
     * bit1 peer-in-abstract-ns, bit2 bind-source. Returns a `k=v … reply=HEX` trace.
     */
    external fun nativeDussXact(flags: Int, peer: String, source: String, wire: ByteArray, readMs: Int, wantSet: Int, wantId: Int): String

    /** Diagnostics: is 127.0.0.1:port open right now? */
    external fun nativeProbePort(port: Int): Boolean

    /** Diagnostics: transport counters as "rx=.. tx=.. matched=.. ...". */
    external fun nativeStats(): String

    /**
     * Optional host-side observer of frames delivered by the transport.
     * [route] is 0 for the main channel, 1 for the hijacked aux channel; [wire]
     * is the exact on-the-wire frame (0x55 .. CRC16) for verbatim capture.
     */
    @Volatile var observer: ((sender: Int, receiver: Int, cmdSet: Int, cmdId: Int, payload: ByteArray, route: Int, wire: ByteArray) -> Unit)? = null

    /** Called from the native RX thread. Keep it fast and non-throwing. */
    @JvmStatic
    fun onNativeFrame(sender: Int, receiver: Int, cmdSet: Int, cmdId: Int, payload: ByteArray, route: Int, wire: ByteArray) {
        DiagLog.rxFrame(sender, receiver, cmdSet, cmdId, payload, route)
        LinkState.onFrame()          // channel liveness: SOME telemetry is arriving (incl. RC housekeeping)
        DroneLink.offer(cmdSet, cmdId, route)   // honest link: only live FLYC OSD (aux/40007) counts
        DumlCapture.offer(route, wire)
        SerialSniffer.offer(cmdSet, cmdId, payload, route)
        ModelSniffer.offer(cmdSet, cmdId, payload, route)
        HomePointMonitor.offer(cmdSet, cmdId, payload)
        RadioLinkMonitor.offer(cmdSet, cmdId, payload)   // diagnostic: find a passive drone-link signal
        observer?.invoke(sender, receiver, cmdSet, cmdId, payload, route, wire)
    }
}
