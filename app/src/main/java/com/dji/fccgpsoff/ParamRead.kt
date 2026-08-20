package com.dji.fccgpsoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Read a flight-controller parameter value by NAME, using the same route that
 * makes [AircraftSerial] work on RC2: send ReadParamValByHash (03:F8) to the
 * flight controller wrapped on 40007, then WALK the read window for the matching
 * 03:F8 response — plain sendOnce returns Fly's first telemetry frame, never our
 * reply, which is why 40008 reads come back "no answer".
 *
 * That open question — whether the board routes a param reply back to us at all — is now
 * **answered: it does.** Measured on a Lito X1 v400 with DJI Fly running in the background,
 * a single 1500 ms window on wrapped 40007 returns the reply about 70% of the time (and
 * near-always with Fly stopped). `doc/drone-link-detection.md` §4 marks `03:F8` as "never
 * routed", but that test was run against **40008**, which is a write-only sink returning
 * zero frames — the wrapped 40007 route this class uses behaves differently.
 *
 * Losses are per-WINDOW, not per-request: across 24 windows carrying 2 and 3 requests each,
 * every window returned either all of its replies or none. Retries are therefore what buys
 * reliability here, not sending fewer things at once.
 */
object ParamRead {

    private const val HOST = "127.0.0.1"
    // Short, self-terminating window (RC2 shape). 40007 is DJI Fly's video-mirror port, so a
    // read that overlaps a switch to Fly must end on its own quickly and quietly — 500 ms
    // ends within the switch's own settle and never lingers into Fly's re-establish. The
    // request is sent ONCE per window (no resend); a switch to Fly cancels further attempts
    // via the readsAllowed() gate rather than by force-closing the socket from outside.
    private const val READ_WINDOW_MS = 500
    const val DEFAULT_ATTEMPTS = 3

    // Poll granularity inside a window. Deliberately much shorter than the window:
    // a socket timeout here means the stream went QUIET, which must not end the
    // window — see the note in [probeOnce].
    private const val SOCKET_POLL_MS = 120
    // A wrapped 40007 envelope is 8 header bytes plus at most a 1023-byte inner
    // frame, so a reply can straddle at most this much of a chunk boundary.
    private const val MAX_WRAPPED = 8 + 1023
    // Ceiling on bytes held while walking a window. 40007 also carries DJI Fly's
    // video mirror, so the stream can be big; only the tail can still hold an
    // unscanned reply, so older bytes are dropped instead of grown into.
    private const val MAX_SCAN_BYTES = 256 * 1024

    /** Build the WRAPPED 03:F8 read request for [hash] (40007 route). */
    private fun request(hash: ByteArray): ByteArray {
        val inner = DumlNative.nativeBuildFrame(
            DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
            DumlWire.CMDSET_FLYC, DumlWire.CMDID_READ_PARAM_HASH, hash
        )
        return DumlWire.wrap(inner)
    }

    /**
     * The value bytes of a 03:F8 response payload. The by-hash response is
     * `[status:u8][hash:4][value:N]`; return the value (everything past the hash)
     * when status==0, else null. Falls back to "whole payload minus a 1-byte
     * status" if it is too short to contain a hash.
     */
    fun parseValue(pl: ByteArray): ByteArray? {
        if (pl.isEmpty()) return null
        if ((pl[0].toInt() and 0xFF) != 0) return null          // non-zero status = read failed
        return when {
            pl.size >= 5 -> pl.copyOfRange(5, pl.size)           // status + 4-byte hash + value
            pl.size >= 2 -> pl.copyOfRange(1, pl.size)           // status + value
            else -> ByteArray(0)
        }
    }

    /**
     * Walk a 40007 window for the 03:F8 response whose echoed hash matches [hash] — the FC
     * (and DJI Fly) poll many params, so the hash MUST match or we would return some other
     * parameter's value. Returns the RAW payload of our reply.
     */
    private fun extract(stream: ByteArray, hash: ByteArray, len: Int = stream.size): ByteArray? =
        WrappedFrames.walk(stream, len) { fr ->
            if (fr.cmdSet == DumlWire.CMDSET_FLYC && fr.cmdId == DumlWire.CMDID_READ_PARAM_HASH && fr.isResponse) {
                val pl = fr.payload
                if (pl.size >= 5 && pl[0].toInt() == 0 &&
                    pl[1] == hash[0] && pl[2] == hash[1] && pl[3] == hash[2] && pl[4] == hash[3]
                ) pl else null
            } else null
        }

    /**
     * Ask once and walk the 40007 window for our reply.
     *
     * Two things used to make this fail about half the time whenever the bus was
     * quiet (a stale link, no video flowing):
     *
     *  - the socket timeout was caught by the same `catch` as a real disconnect
     *    and turned into `break`, so a LULL in the stream ended the window early —
     *    the read reported "no answer" with most of its window unspent. A timeout
     *    now just means "nothing yet", and only a genuine EOF/error stops the walk.
     *
     * The request is sent ONCE per window (no in-window resend): keeping a socket
     * actively re-writing on Fly's 40007 across a switch is exactly the lingering,
     * noisy presence we want gone. Reliability comes from the outer per-attempt
     * retries in [readRaw], each of which re-checks the foreground gate first.
     *
     * The stream is also walked in place now (a growing ByteArray scanned from a
     * retained offset) instead of an `ArrayList<Byte>` re-copied on every chunk,
     * which was quadratic and could burn the window on its own while DJI Fly's
     * video mirror was pushing bytes.
     */
    /** Why the last window stopped — for the "no answer" line. A window the peer closed
     *  after 40 ms and a window that ran its full 500 ms in silence look identical in a
     *  log otherwise, and they are different faults (2026-08-20). */
    @Volatile private var lastWindowEnd = "elapsed"

    private fun probeOnce(name: String, hash: ByteArray, windowMs: Int): ByteArray? {
        val wire = request(hash)
        DiagLog.tx(DumlWire.PORT_LED, "read ${ParamName.tag(name)} 03:F8", wire)
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, DumlWire.PORT_LED), 400)
                s.soTimeout = SOCKET_POLL_MS
                val out = s.getOutputStream()
                out.write(wire); out.flush()
                val ins = s.getInputStream()
                val end = System.currentTimeMillis() + windowMs
                var buf = ByteArray(16 * 1024)
                var used = 0
                var scanned = 0
                lastWindowEnd = "elapsed"
                while (System.currentTimeMillis() < end) {
                    // strict: stop mid-window if DJI Fly takes the front
                    if (!ForegroundGate.readsAllowed()) { lastWindowEnd = "read gate shut mid-window"; break }
                    if (used == buf.size) {
                        if (buf.size >= MAX_SCAN_BYTES) {
                            System.arraycopy(buf, used - MAX_WRAPPED, buf, 0, MAX_WRAPPED)
                            used = MAX_WRAPPED; scanned = 0
                        } else buf = buf.copyOf(buf.size * 2)
                    }
                    val r = try { ins.read(buf, used, buf.size - used) }
                        catch (e: java.net.SocketTimeoutException) { 0 }   // quiet, not closed
                        catch (e: Exception) { -1 }
                    // peer closed: nothing more is coming
                    if (r < 0) { lastWindowEnd = "the port closed on us after ${System.currentTimeMillis() - (end - windowMs)} ms"; break }
                    if (r > 0) {
                        used += r
                        // Re-scan from a frame's worth before the last scan so a reply
                        // split across two chunks is still found.
                        val from = maxOf(0, scanned - MAX_WRAPPED)
                        extract(buf.copyOfRange(from, used), hash)?.let { return it }
                        scanned = used
                    }
                    // r == 0 → quiet poll tick; keep walking the window, do not resend.
                }
                null
            }
        } catch (e: Exception) { DiagLog.err("param read $name: ${e.message}"); null }
    }

    /**
     * RAW 03:F8 response payload for [name] with bounded retries; null if unanswered.
     *
     * Re-checks the foreground gate before EACH attempt, so a switch to DJI Fly stops
     * the retry burst at once instead of finishing all attempts on its video port.
     *
     * **ONE request per window. Measured on hardware 2026-08-20** (Lito X1 over RC 2,
     * app 1.1.2): putting a parameter's three candidate spellings into a single `03:F8`
     * window returned NOTHING, 16 windows out of 16 — including `forearm_led_ctrl`, whose
     * hash answered in 25 ms when it was the only ask in the window. Single-ask windows in
     * the same session answered 12 of 17, the documented ~70%. `DumlWindow.collect`'s
     * measured batching does not transfer here: it holds a 1500 ms window and RE-SENDS
     * every unanswered ask each 250 ms, while this path deliberately sends once into 500 ms
     * and never re-sends, because a socket that keeps writing on DJI Fly's video port
     * across an app switch is the thing that costs the user their link.
     *
     * A name with several spellings is therefore settled FIRST, by `03:F7` through
     * [ParamAlias] — that route does batch correctly, its negative is real, and it is
     * exactly what makes the "Re-probe" button work. Only if that cannot settle it do the
     * spellings get one window EACH across the retry budget, rather than one spelling
     * getting all of them.
     *
     * Every outcome is logged, because until 2026-08-20 an answered read and an unanswered
     * one looked identical in a shared log: only the TX went in, and telling them apart
     * meant counting retry lines and measuring the gaps between them.
     */
    suspend fun readRaw(name: String, attempts: Int = DEFAULT_ATTEMPTS): ByteArray? = withContext(Dispatchers.IO) {
        // Settle the spelling on the route that can settle it, before spending read windows
        // guessing. Opens nothing for a plain name, for an already-measured one, or with the
        // read gate shut.
        // Two attempts, not three: this runs in front of a value the user is waiting for,
        // and a third 1500 ms window buys little once two have gone unanswered.
        if (ParamAlias.known(name) == null && ParamName.isJoined(name)) ParamAlias.resolve(name, attempts = 2)
        val cands = ParamAlias.known(name)?.let { listOf(it) } ?: ParamAlias.order(name)
        val hashes = cands.map { DumlNative.nativeParamHash(it) }
        val t0 = System.currentTimeMillis()
        var tried = 0
        repeat(attempts) {
            if (!ForegroundGate.readsAllowed()) {
                // strict: bail between attempts (and up front)
                ReadStats.gateBlocked()
                // "Never asked" and "asked and got nothing" must not read the same. When
                // `tried` is still 0 no window was ever opened, so the silence is ours.
                DiagLog.info(if (tried == 0)
                    "read ${ParamName.tag(cands[0])} — NOT ASKED, no window opened: " +
                        (ForegroundGate.blockReason() ?: "read gate closed")
                else
                    "read ${ParamName.tag(cands[0])} — gave up after $tried/$attempts: " +
                        (ForegroundGate.blockReason() ?: "read gate closed"))
                return@withContext null
            }
            // One spelling per window, rotating — so an unresolved name spends its retry
            // budget covering its candidates instead of hammering one guess three times.
            val idx = tried % cands.size
            tried++
            val pl = probeOnce(cands[idx], hashes[idx], READ_WINDOW_MS)
            if (pl != null) {
                ReadStats.answered()
                if (cands.size > 1) ParamAlias.note(name, cands[idx])
                val v = parseValue(pl)
                DiagLog.info("read ${ParamName.tag(cands[idx])} = " +
                    (v?.let { "${DumlWire.toHex(it)} (${it.size} B)" } ?: "status ${pl[0].toInt() and 0xFF}") +
                    " · ${System.currentTimeMillis() - t0} ms · attempt $tried/$attempts")
                return@withContext pl
            }
            // Counted per WINDOW, not per read: a read that needed three attempts opened
            // three windows, and the answer rate the retry budgets are tuned to is a
            // per-window figure.
            ReadStats.silent()
            delay(80)
        }
        DiagLog.info("read ${ParamName.tag(cands[0])} — NO ANSWER" +
            (if (cands.size > 1) " on any of ${cands.size} spelling(s), one window each" else "") +
            " · $tried attempt(s) · ${System.currentTimeMillis() - t0} ms · last window: $lastWindowEnd " +
            "(silence on this bus is 'no route back', not 'absent' — use 03:F7 to tell them apart)")
        null
    }

    /** Parsed value bytes for [name] (status/hash stripped), or null. */
    suspend fun read(name: String, attempts: Int = DEFAULT_ATTEMPTS): ByteArray? =
        readRaw(name, attempts)?.let { parseValue(it) }

    /**
     * Read MANY values in one pass — several `03:F8` asks per 40007 window instead
     * of a window each.
     *
     * The wire supports this and it was measured: across 24 windows carrying 2 and 3
     * requests, every window returned either ALL of its replies or none, and a paired
     * ask answered in 91% of windows against a 70% solo baseline (see [DumlWindow]).
     * [ParamTable]'s index walk already reads `03:E1` this way, 32 asks to a window;
     * this is the same shape for values, so a catalog that loads in a second no longer
     * needs a minute of one-at-a-time reads to show what the aircraft actually holds.
     *
     * Correlation is the ECHOED HASH, not the sequence number: the reply carries the
     * hash it answers, every ask in a chunk has a different one, and other clients
     * poll `03:F8` on this bus constantly. A non-zero status is not accepted — the
     * payload then carries no hash to match on — so a refused read simply reads as
     * unanswered, exactly as it does for a single read.
     *
     * Losses are per-window, so a second pass re-asks only what is still missing.
     * [onProgress] fires per chunk; [ForegroundGate] is honoured between chunks and,
     * inside [DumlWindow], mid-window.
     */
    suspend fun readMany(
        names: List<String>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        val out = HashMap<String, ByteArray>()
        val wanted = names.distinct()
        if (wanted.isEmpty()) return@withContext out
        // Learn the aircraft's naming convention ONCE before spending sixty windows on
        // it. Firmwares are consistent about which half of a joined `a|b` name they
        // index, so one decisive 03:F7 round on a single representative name settles the
        // spelling for the whole catalog — far cheaper than putting every candidate of
        // every row into the batch. When nothing is joined this does nothing at all.
        if (ParamAlias.preferred < 0) wanted.firstOrNull { ParamName.isJoined(it) }
            ?.let { ParamAlias.resolve(it) }
        var todo = wanted
        repeat(BATCH_PASSES) { pass ->
            if (todo.isEmpty()) return@repeat
            val missed = ArrayList<String>()
            // Pass 1 asks the ONE spelling we believe in; the re-ask pass widens to every
            // candidate, so a name whose convention differs from the rest still gets its
            // chance without doubling the traffic for the ones that don't. Chunking is by
            // ASK, not by name — a widened pass must still put only [BATCH_DEPTH] requests
            // in a window, or the batch that was measured is not the batch being sent.
            val plan = ArrayList<Pair<String, String>>(todo.size)     // name -> spelling to ask
            for (name in todo) {
                // A spelling MEASURED for this exact name beats the catalog-wide hint, and
                // it is then the only one worth asking. Same precedence as [readRaw]; the
                // two disagreeing would have made a bulk read miss rows a single read finds.
                val known = ParamAlias.known(name)
                when {
                    known != null -> plan.add(name to known)
                    pass == 0 -> plan.add(name to ParamAlias.order(name).first())
                    else -> for (sp in ParamAlias.order(name)) plan.add(name to sp)
                }
            }
            for (chunk in plan.chunked(BATCH_DEPTH)) {
                if (!ForegroundGate.readsAllowed()) {
                    DiagLog.warn("readMany: stopped — ${ForegroundGate.blockReason()}")
                    return@withContext out
                }
                val asks = chunk.map { (_, sp) ->
                    val hash = DumlNative.nativeParamHash(sp)
                    DumlWindow.Ask("read ${ParamName.tag(sp)} 03:F8", request(hash)) { fr ->
                        if (fr.cmdSet != DumlWire.CMDSET_FLYC ||
                            fr.cmdId != DumlWire.CMDID_READ_PARAM_HASH || !fr.isResponse
                        ) null
                        else fr.payload.takeIf { pl ->
                            pl.size >= 5 && pl[0].toInt() == 0 &&
                                pl[1] == hash[0] && pl[2] == hash[1] && pl[3] == hash[2] && pl[4] == hash[3]
                        }
                    }
                }
                val got = DumlWindow.collect(DumlWire.PORT_LED, asks, BATCH_WINDOW_MS)
                // One collect is one window, so it is one outcome for the answer rate —
                // whole windows are what this bus loses.
                if (got.any { it != null }) ReadStats.answered() else ReadStats.silent()
                for (a in chunk.indices) {
                    val v = got[a]?.let { parseValue(it) } ?: continue
                    val (name, sp) = chunk[a]
                    if (out.containsKey(name)) continue
                    out[name] = v
                    if (sp != name) ParamAlias.note(name, sp)
                }
                onProgress(out.size, wanted.size)
                delay(BATCH_GAP_MS)
            }
            for (n in todo) if (!out.containsKey(n)) missed.add(n)
            todo = missed
            if (todo.isNotEmpty()) DiagLog.info("readMany: ${todo.size} of ${wanted.size} unanswered — re-asking" +
                (if (todo.any { ParamName.isJoined(it) }) " (widening to every spelling)" else ""))
        }
        DiagLog.info("readMany: ${out.size}/${wanted.size} values read")
        out
    }

    /** Asks per window. Half of [ParamTable]'s 32 for `03:E1`: a value reply is the
     *  wider frame of the two, and this runs while DJI Fly is merely backgrounded. */
    private const val BATCH_DEPTH = 16
    private const val BATCH_WINDOW_MS = 1500
    /** Losses are per-WINDOW, so one re-ask of the missing set recovers most of a
     *  window that dropped whole. A third pass buys little for the traffic. */
    private const val BATCH_PASSES = 2
    private const val BATCH_GAP_MS = 120L

    /** A read-back that answered, with how long after the write it was taken. */
    class ReadBack(val value: ByteArray, val afterMs: Int)

    // How long to wait between read-backs, and how many to try before giving up.
    // A match returns immediately, so the normal cost is one extra read. Each poll
    // uses a SINGLE-attempt read: this loop already provides the retries, and
    // nesting [DEFAULT_ATTEMPTS] inside it would push the worst case past the diag
    // server's 15 s socket timeout.
    private const val CONFIRM_STEP_MS = 350L
    private const val CONFIRM_ATTEMPTS = 2

    /**
     * Poll the read-back after a write until it matches [want], or the budget runs
     * out. Shared by both editors so they report writes the same way.
     *
     * Measured live on a Lito X1 over RC2 (`forearm_led_ctrl`): the write lands,
     * but this read keeps answering with the OLD value for ~300-500 ms afterwards,
     * and individual reads drop out ("no answer") outright. A single read at
     * +300 ms therefore reported writes that had genuinely succeeded as
     * "read-back != written" — a false negative on nearly every write.
     *
     * Returns the last read that answered (so a real mismatch is still reported
     * with its value), or null if nothing came back at all.
     */
    suspend fun confirmWrite(name: String, want: ByteArray): ReadBack? {
        var last: ReadBack? = null
        var waited = 0
        repeat(CONFIRM_ATTEMPTS) {
            delay(CONFIRM_STEP_MS)
            waited += CONFIRM_STEP_MS.toInt()
            val v = read(name, attempts = 1)
            if (v != null) {
                last = ReadBack(v, waited)
                if (v.contentEquals(want)) return last
            }
        }
        return last
    }
}
