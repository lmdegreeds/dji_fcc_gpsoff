package com.dji.fccgpsoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The one socket loop for wrapped 40007 request/response work.
 *
 * Before this existed the loop was copy-pasted three times ([ParamRead], [AircraftSerial],
 * [AircraftModelProbe]) with genuinely different behaviour, and adding `03:F7`/`03:FA`/
 * `03:E0` would have made a fourth. Only [ParamRead]'s copy had all the fixes; this is
 * that copy, generalised over the request and the accept predicate.
 *
 * **Five properties below were each a real bug. Do not "simplify" them away.**
 *
 *  1. A socket timeout means the stream went QUIET, not closed — it must not end the
 *     window. Only a genuine EOF (`read` < 0) stops the walk. Ending early on a lull
 *     used to report "no answer" with most of the window unspent.
 *  2. The stream is scanned in place from a retained offset, re-scanning from a frame's
 *     worth before the last scan, so a reply split across two chunk reads is still found.
 *     (The original `ArrayList<Byte>` re-copy was quadratic and could burn the window on
 *     its own while DJI Fly's video mirror was pushing bytes.)
 *  3. Past [MAX_SCAN_BYTES] the head is dropped and only the tail retained — 40007 also
 *     carries video, so the stream can be big and only the tail can still hold a reply.
 *  4. A silent window re-asks on the socket already held, every [RESEND_MS]. On a shared
 *     port a dropped request is simply never answered; re-asking costs one frame and no
 *     reconnect. **Only unanswered asks are re-sent** — re-sending answered ones would
 *     turn the window into a flood on Fly's video port.
 *  5. [ForegroundGate] is re-checked on EVERY loop iteration, not once. That mid-window
 *     abort — not socket economy — is what bounds the video blip; see the S5 defect in
 *     `doc/fcc-autoapply-tests.md`.
 *
 * **On batching several asks into one window.** Measured on a Lito X1 v400 with DJI Fly
 * running in the background: losses are *window-level*, never per-request — across 24
 * windows carrying 2 and 3 requests, every window returned either all of its replies or
 * none, and a paired ask answered in 91% of windows against a 70% solo baseline. So the
 * wire supports batching. **That does not license re-batching [FlightState]**: a previous
 * `readMany` was rolled back on hardware (`doc/fcc-autoapply-tests.md`, "Фикс S5-дефекта")
 * and per-parameter reads are the configuration that was re-verified. The likely cause of
 * that regression is visible in [WrappedFrames.walk] — it stops at the FIRST match, so a
 * naive batch returns the first parameter and silently loses the rest. This class walks
 * every frame in the window instead (see [drain]).
 */
object DumlWindow {

    private const val HOST = "127.0.0.1"
    /** Poll granularity. Deliberately much shorter than the window — see property 1. */
    private const val SOCKET_POLL_MS = 120
    private const val RESEND_MS = 250
    /** A wrapped envelope is 8 header bytes plus at most a 1023-byte inner frame, so a
     *  reply can straddle at most this much of a chunk boundary. */
    private const val MAX_WRAPPED = 8 + 1023
    private const val MAX_SCAN_BYTES = 256 * 1024
    private const val CONNECT_TIMEOUT_MS = 400

    /**
     * One request and the predicate that recognises its reply.
     *
     * [accept] receives every CRC-valid inner frame in the window and returns the payload
     * it wants, or null. Correlate on something the reply actually carries — the echoed
     * hash for `03:F8`/`03:FA`, the echoed table+index for `03:E1`, the returned name for
     * `03:F7` — or on the sequence number. Matching cmd_set/cmd_id alone is not enough:
     * other clients poll the same commands on this bus.
     */
    class Ask(val tag: String, val wire: ByteArray, val accept: (WrappedFrames.Inner) -> ByteArray?)

    /**
     * Send every ask on ONE socket and walk the reply stream for [windowMs].
     *
     * Returns one slot per ask, in order — never a single nullable, so a partially
     * answered window can't be read as a total failure. A null slot is "no answer",
     * which on this bus means "no route back", never "absent".
     */
    suspend fun collect(
        port: Int,
        asks: List<Ask>,
        windowMs: Int,
        resendMs: Int = RESEND_MS,
    ): Array<ByteArray?> = withContext(Dispatchers.IO) {
        val out = arrayOfNulls<ByteArray>(asks.size)
        if (asks.isEmpty()) return@withContext out
        for (a in asks) DiagLog.tx(port, a.tag, a.wire)
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS)
                VideoPortGuard.register(port, s)                // closed from outside if Fly takes the front
                s.soTimeout = SOCKET_POLL_MS
                val out0 = s.getOutputStream()
                for (a in asks) out0.write(a.wire)
                out0.flush()
                val ins = s.getInputStream()
                val end = System.currentTimeMillis() + windowMs
                var nextResend = System.currentTimeMillis() + resendMs
                val scan = Scanner()
                var pending = asks.size
                while (pending > 0 && System.currentTimeMillis() < end) {
                    if (!ForegroundGate.readsAllowed()) break     // property 5: strict mid-window abort
                    val r = try { ins.read(scan.buf, scan.used, scan.free()) }
                        catch (e: java.net.SocketTimeoutException) { 0 }   // property 1: quiet, not closed
                        catch (e: Exception) { -1 }
                    if (r < 0) break
                    if (r > 0) {
                        scan.grew(r)
                        pending -= scan.drain { inner ->
                            var hits = 0
                            for (i in asks.indices) {
                                if (out[i] != null) continue
                                val got = runCatching { asks[i].accept(inner) }.getOrNull() ?: continue
                                out[i] = got
                                hits++
                            }
                            hits
                        }
                    } else if (System.currentTimeMillis() >= nextResend) {
                        // property 4: re-ask ONLY what is still open
                        for (i in asks.indices) if (out[i] == null) out0.write(asks[i].wire)
                        out0.flush()
                        nextResend = System.currentTimeMillis() + resendMs
                    }
                    scan.ensureRoom()
                }
            }
        } catch (e: Exception) {
            DiagLog.err("window $port ${asks.firstOrNull()?.tag ?: ""}: ${e.message}")
        }
        out
    }

    /** Convenience for the common single-request case. */
    suspend fun ask(port: Int, ask: Ask, windowMs: Int): ByteArray? =
        collect(port, listOf(ask), windowMs)[0]

    /**
     * The growable, self-truncating scan buffer (properties 2 and 3), kept separate so the
     * chunk-boundary and cap behaviour can be unit-tested without a socket.
     */
    internal class Scanner {
        var buf = ByteArray(16 * 1024); private set
        var used = 0; private set
        /** Offset just past the last COMPLETE frame handed to [drain]'s callback. Scanning
         *  resumes here, so a frame is delivered exactly once and one straddling a read
         *  boundary is simply picked up whole on the next pass. */
        private var delivered = 0

        fun free(): Int = buf.size - used
        fun grew(n: Int) { used += n }

        /** Append bytes the way a socket read would. Used by tests; the socket path reads
         *  straight into [buf] to avoid the extra copy. */
        fun feed(src: ByteArray, off: Int = 0, len: Int = src.size) {
            var i = 0
            while (i < len) {
                ensureRoom()
                val n = minOf(free(), len - i)
                if (n <= 0) return
                System.arraycopy(src, off + i, buf, used, n)
                used += n
                i += n
            }
        }

        /** Make room for the next read: grow, or drop the head and keep the tail. */
        fun ensureRoom() {
            if (used < buf.size) return
            if (buf.size >= MAX_SCAN_BYTES) {
                // Only the tail can still hold an unscanned reply, so drop the head rather
                // than grow into it. Keep enough for a maximum-size frame that is still
                // arriving, and slide the delivered watermark with it.
                val keep = minOf(MAX_WRAPPED, used)
                System.arraycopy(buf, used - keep, buf, 0, keep)
                val dropped = used - keep
                used = keep
                delivered = maxOf(0, delivered - dropped)
            } else {
                buf = buf.copyOf(buf.size * 2)
            }
        }

        /**
         * Visit every CRC-valid inner frame that has arrived since the last call and return
         * how many matches [onFrame] reported.
         *
         * Two things this does that [WrappedFrames.walk] alone does not:
         *  - it walks the WHOLE window rather than stopping at the first match, so a batch
         *    doesn't silently lose every reply after the first;
         *  - it resumes from the end of the last complete frame, so nothing is delivered
         *    twice — which matters as soon as a caller counts results rather than taking
         *    the first hit.
         */
        fun drain(onFrame: (WrappedFrames.Inner) -> Int): Int {
            if (delivered >= used) return 0
            val from = delivered
            var hits = 0
            WrappedFrames.walkIndexed(buf.copyOfRange(from, used)) { inner, end ->
                hits += onFrame(inner)
                delivered = from + end
                null                                   // never stop early: see the doc above
            }
            return hits
        }
    }
}
