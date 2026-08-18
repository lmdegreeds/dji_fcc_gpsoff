package com.dji.fccgpsoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Ask the aircraft for its serial (GENERAL 00:51) instead of holding a port
 * open and hoping the identity broadcast lands inside the window.
 *
 * Ported from SkylabFCCfree v1.5.74 (AircraftSerialQuery.kt). The request kicks
 * the board so it answers at once; the answer is read straight off 40007.
 *
 * Why NOT DumlBus.sendOnce here: native send_once returns the FIRST inner frame
 * that parses off the socket, but 40007 is DJI Fly's telemetry mirror — the
 * first frame is almost always unrelated (00:99, 23:b2, …), never our 00:51
 * reply, so the serial was dropped as "unparseable" every time.
 *
 * Skylab catches the right frame with validateResponse: over each frame in the
 * read window it matches the response bit (cmdType & 0x80), the request seq, the
 * reversed sender/receiver routing and the same cmdSet/cmdId. We do the same —
 * walk the window and take the 00:51 response — then fall back to a regex over
 * the raw bytes, which also picks up the passive 51:14 broadcast.
 *
 * Confirmed live on Lito X1 (2026-08-09): serial 1581EXAMPLE000000000 arrived
 * both as the 00:51 reply (`00 14 00` + 20 ASCII) and as 51:14.
 */
object AircraftSerial {

    /** Selector that returns the bare aircraft serial in the 00:51 reply. */
    const val FIELD_AIRCRAFT_SERIAL = 0x04

    private const val HOST = "127.0.0.1"
    // 40007 is DJI Fly's FPV mirror, so every connection here risks a video blip.
    // We keep the touch minimal: the board answers 00:51 within tens of ms, and
    // probeOnce() closes the socket the instant extract() sees the serial — it
    // does NOT hold the window open. The window is only the ceiling for a missed
    // request (~1 in 3 on 40007); a short window + a retry beats one long hold.
    private const val READ_WINDOW_MS = 400
    const val DEFAULT_ATTEMPTS = 3

    // A full DJI factory serial starts "1581" and is 16-22 chars. Boundaries stop
    // it gluing onto adjacent hex; the short 03:44 tail never matches.
    private val FULL_SERIAL_REGEX = Regex("(?<![0-9A-Z])1581[0-9A-Z]{12,18}(?![0-9A-Z])")

    /** Build the WRAPPED 00:51 request (40007 is the wrapped/video-mirror route). */
    private fun request(field: Int = FIELD_AIRCRAFT_SERIAL): ByteArray {
        val inner = DumlNative.nativeBuildFrame(
            DumlWire.SENDER_APP0,   // sender 0x02
            DumlWire.DST_FLIGHT,    // 0x03 flight controller
            DumlWire.CT_ACK,        // 0x40 request-with-ack
            0x00,                   // cmdSet GENERAL
            0x51,                   // cmdId GetSerial
            byteArrayOf(field.toByte())
        )
        return DumlWire.wrap(inner)
    }

    // A full serial begins "1581" — a 4-byte guard so the per-frame sniffer only
    // pays for the regex on the rare frame that could actually carry one.
    private val SN_PREFIX = byteArrayOf('1'.code.toByte(), '5'.code.toByte(), '8'.code.toByte(), '1'.code.toByte())
    private fun hasSerialPrefix(b: ByteArray): Boolean {
        if (b.size < 4) return false
        outer@ for (i in 0..b.size - 4) {
            for (j in 0..3) if (b[i + j] != SN_PREFIX[j]) continue@outer
            return true
        }
        return false
    }

    /**
     * Passive one-frame extraction for the hijack-read sniffer: pull the serial
     * out of a single delivered DUML frame — the 00:51 reply payload, or any
     * frame whose bytes carry the ASCII serial (the 51:14 broadcast DJI Fly
     * pulls over 40007). Returns "" if this frame has no serial. Cheap: the
     * regex runs only when the "1581" prefix is present.
     */
    fun sniff(cmdSet: Int, cmdId: Int, payload: ByteArray): String {
        if (cmdSet == 0x00 && cmdId == 0x51) {
            val s = parseReply(payload)
            if (s.isNotEmpty()) return s
        }
        if (!hasSerialPrefix(payload)) return ""
        return FULL_SERIAL_REGEX.find(String(payload, Charsets.ISO_8859_1))?.value.orEmpty()
    }

    /**
     * Serial from a 00:51 reply payload (`status:u8, len:u16 LE, ASCII`), or "".
     *
     * The serial is FOUND inside the declared field, not required to be the whole
     * of it: some firmwares prefix it with framing bytes (e.g. `00 16 20 08`)
     * inside the same field, and a whole-field match (`.matches`) dropped those
     * replies. Aligned with SkylabFCCfree f87a14b (findFullAircraftSerial).
     */
    fun parseReply(pl: ByteArray): String {
        if (pl.size < 3 || (pl[0].toInt() and 0xFF) != 0) return ""
        val len = (pl[1].toInt() and 0xFF) or ((pl[2].toInt() and 0xFF) shl 8)
        if (len <= 0 || 3 + len > pl.size) return ""
        val s = String(pl, 3, len, Charsets.US_ASCII)
        return FULL_SERIAL_REGEX.find(s)?.value.orEmpty()
    }

    /**
     * Serial from a GENUINE 00:51 response frame only — no regex fallback. This is
     * the "is the aircraft answering right now?" signal: after the drone powers off
     * the controller keeps broadcasting a sticky `51:14` with the last serial for
     * minutes (proven, `doc/drone-link-detection.md` Q-B1), which [extract]'s regex
     * would happily return as a false positive. This path ignores it.
     */
    fun extractResponseOnly(stream: ByteArray, len: Int = stream.size): String =
        WrappedFrames.walk(stream, len) { fr ->
            if (fr.cmdSet == 0x00 && fr.cmdId == 0x51 && fr.isResponse)
                parseReply(fr.payload).ifEmpty { null }
            else null
        } ?: ""

    /**
     * Pull the serial out of a raw 40007 window: prefer the real 00:51 response
     * frame, else regex the whole buffer (catches the 51:14 broadcast). Use for
     * passive identity, where any serial — including the cached broadcast — is fine.
     */
    fun extract(stream: ByteArray, len: Int = stream.size): String {
        val resp = extractResponseOnly(stream, len)
        if (resp.isNotEmpty()) return resp
        // Fallback: the serial as ASCII anywhere in the window (00:51 or 51:14).
        return FULL_SERIAL_REGEX.find(String(stream, 0, len, Charsets.ISO_8859_1))?.value.orEmpty()
    }

    /** Send the 00:51 request on 40007, read the whole window, extract the serial.
     *  [liveOnly] requires a genuine 00:51 response (ignores the cached 51:14). */
    private fun probeOnce(windowMs: Int, liveOnly: Boolean): String {
        val wire = request()
        DiagLog.tx(DumlWire.PORT_LED, "serial 00:51", wire)
        fun pick(b: ByteArray) = if (liveOnly) extractResponseOnly(b) else extract(b)
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, DumlWire.PORT_LED), 400)
                s.soTimeout = 300
                s.getOutputStream().apply { write(wire); flush() }
                val end = System.currentTimeMillis() + windowMs
                val out = ArrayList<Byte>(8192)
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val r = try { s.getInputStream().read(buf) } catch (e: Exception) { -1 }
                    if (r < 0) break
                    for (k in 0 until r) out.add(buf[k])
                    val hit = pick(out.toByteArray())
                    if (hit.isNotEmpty()) return hit   // stop as soon as the serial appears
                }
                pick(out.toByteArray())
            }
        } catch (e: Exception) {
            DiagLog.err("serial probe: ${e.message}"); ""
        }
    }

    /** Query with bounded retries; "" if the serial never appears in the window.
     *  Gated: refuses to open 40007 while DJI Fly is the active window. [liveOnly]
     *  requires a real 00:51 reply (a live aircraft), not the sticky 51:14 cache. */
    suspend fun read(attempts: Int = DEFAULT_ATTEMPTS, liveOnly: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!ForegroundGate.readsAllowed()) { DiagLog.warn("serial read skipped: DJI Fly active"); return@withContext "" }
        repeat(attempts) {
            val serial = probeOnce(READ_WINDOW_MS, liveOnly)
            if (serial.isNotEmpty()) return@withContext serial
            delay(80)
        }
        ""
    }

    /** Live presence check: serial only from a genuine 00:51 reply (drone answering
     *  right now), never the cached 51:14 broadcast. */
    suspend fun readLive(attempts: Int = DEFAULT_ATTEMPTS): String = read(attempts, liveOnly = true)
}
