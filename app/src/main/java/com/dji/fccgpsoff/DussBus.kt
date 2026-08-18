package com.dji.fccgpsoff

import java.io.File

/**
 * The DUSS "firmware bus" write path — a diagnostic re-implementation of
 * REPORT/FIRMWARE-BUS-DUSS.md, for verifying on real hardware whether an ordinary
 * app on a DJI Android controller can reach the internal DUML mailbox bus under
 * `/duss/mb/` (router `0x205`, our source `0x1e00`).
 *
 * Everything here is exploratory: the report marks the peer namespace, socket
 * type, exact source bytes, and SELinux access as **[inference]**, so the calls
 * sweep the plausible variants and surface every `errno` instead of assuming.
 * The heavy lifting (AF_UNIX bind/connect/recvfrom + errno) is in native
 * [DumlNative.nativeDussProbe]/[DumlNative.nativeDussXact]; the `/proc/net/unix`
 * scan (report §9) is done here since it is just reading a text file.
 */
object DussBus {

    /** The router mailbox we connect to (report §1). */
    const val PEER = "/duss/mb/0x205"
    /** Our source mailbox — bound so the router can route replies back (report §2). */
    const val SOURCE = "/duss/mb/0x1e00"
    /**
     * DUML SRC byte to put in requests so the router routes the reply back to our
     * mailbox. Found on hardware (2026-08-17): the router replies by reply-DST =
     * request-SRC, and mailbox `0x1e00` answers to DUML address `0x1e` (the high
     * byte of the mailbox name). Sending any other SRC (e.g. 0x82 MOBILE_APP) sent
     * the reply elsewhere — DJI Fly owns MOBILE_APP — so we never saw it.
     */
    const val SRC_ADDR = 0x1e
    /** Default request/response window; the original passes 350 ms (report §6). */
    const val READ_MS = 350

    // nativeDussXact flag bits.
    private const val F_DGRAM = 1        // SOCK_DGRAM (report) vs SOCK_STREAM
    private const val F_PEER_ABSTRACT = 2 // connect peer in abstract ns vs pathname
    private const val F_BIND_SOURCE = 4  // bind the abstract source before connect
    private const val F_NO_CONNECT = 8   // sendto(peer)+recvfrom(ANY) vs connect+recv

    /**
     * Read `/proc/net/unix` and return the DUSS mailbox sockets the kernel knows
     * about (report §9) — this is the single most useful check: it reveals the
     * REAL socket names, whether each is abstract (`@` prefix) or a pathname, and
     * the socket type, so the guesses in the report can be replaced with facts.
     */
    fun scan(): String {
        val f = File("/proc/net/unix")
        if (!f.canRead()) return "cannot read /proc/net/unix (${if (f.exists()) "permission denied" else "absent"})"
        val hits = ArrayList<String>()
        runCatching {
            f.forEachLine { line ->
                // Path is the last whitespace-separated column; may be absent.
                val path = line.trim().substringAfterLast(' ', "")
                if (!path.contains("duss", ignoreCase = true)) return@forEachLine
                val cols = line.trim().split(Regex("\\s+"))
                // Columns: Num RefCount Protocol Flags Type St Inode Path
                val type = cols.getOrNull(4)?.let { typeName(it) } ?: "?"
                val ns = if (path.startsWith("@")) "abstract" else "pathname"
                hits.add("$path   [$type, $ns]")
            }
        }.onFailure { return "scan error: ${it.message}" }
        if (hits.isEmpty()) return "no /duss/* sockets in /proc/net/unix " +
            "(not on a DJI controller, or the names differ — try /duss/probe)"
        return "DUSS sockets seen by the kernel:\n" + hits.joinToString("\n")
    }

    private fun typeName(hex: String): String = when (hex.trimStart('0').ifEmpty { "0" }) {
        "1" -> "STREAM"; "2" -> "DGRAM"; "5" -> "SEQPACKET"; else -> "type$hex"
    }

    /** Sweep the connect() matrix to [peer] (default the router). */
    fun probe(peer: String = PEER): String =
        DumlNative.nativeDussProbe(peer).also { DiagLog.info("duss probe:\n$it") }

    /**
     * Run one full DUSS transaction with an already-built wire frame.
     *
     * Defaults reflect what the RC hardware actually showed (2026-08-17), which
     * corrected the report's guesses: every /duss/mb mailbox is a **DGRAM abstract**
     * socket (so `peerAbstract=true`, not the report's pathname), and a *connected*
     * DGRAM socket never saw a reply — the router answers from a different mailbox —
     * so `noConnect=true` (sendto+recvfrom ANY) is the mode that can catch it.
     */
    fun xact(
        wire: ByteArray,
        dgram: Boolean = true,
        peerAbstract: Boolean = true,
        bindSource: Boolean = true,
        noConnect: Boolean = true,
        peer: String = PEER,
        source: String = SOURCE,
        readMs: Int = READ_MS,
        wantSet: Int = -1,
        wantId: Int = -1,
        tag: String = "duss"
    ): String {
        val flags = (if (dgram) F_DGRAM else 0) or
            (if (peerAbstract) F_PEER_ABSTRACT else 0) or
            (if (bindSource) F_BIND_SOURCE else 0) or
            (if (noConnect) F_NO_CONNECT else 0)
        DiagLog.tx(0x205, "$tag/xact", wire)
        val trace = DumlNative.nativeDussXact(flags, peer, source, wire, readMs, wantSet, wantId)
        DiagLog.info("$tag <- $trace")
        return trace
    }

    /**
     * The safe smoke test from report §11: a GENERAL VersionInquiry (`00:01`) — no
     * side effects — sent over DUSS, matched back by cmdSet/cmdId. A non-empty
     * `reply=` proves the whole bus round-trips before anything is ever written.
     * Built exactly like `device_info.json`: sender MOBILE_APP, ACK-before-exec,
     * receiver ANY (0), empty payload.
     */
    fun versionInquiry(
        dgram: Boolean = true,
        peerAbstract: Boolean = true,
        bindSource: Boolean = true,
        noConnect: Boolean = true,
        readMs: Int = 500
    ): String {
        val wire = DumlNative.nativeBuildFrame(
            SRC_ADDR, /*recv=*/0, DumlWire.CT_ACK_BEFORE, 0x00, 0x01, ByteArray(0))
        return xact(wire, dgram, peerAbstract, bindSource, noConnect,
            readMs = readMs, wantSet = 0x00, wantId = 0x01, tag = "duss/version")
    }
}
