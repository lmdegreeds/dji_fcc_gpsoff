package com.dji.fccgpsoff

/**
 * Wire-level constants and helpers shared by the feature layer.
 *
 * All addressing is by NAME (parameter hash computed at runtime) or by hardware
 * register — never by table/param index, because indices differ across models
 * and firmwares. The same name yields the same hash on every aircraft.
 */
object DumlWire {

    // Loopback DUML proxy ports on a DJI smart controller (RC2 / RC Pro 2).
    const val PORT_FCC = 40009      // main proxy: FCC, region, device info
    const val PORT_INJECT = 40008   // FLYC param inject (LED/GPS) — UNWRAPPED.
                                    // Use this, NOT 40007: on RC2, 40007 is DJI Fly's FPV
                                    // video mirror and extra connections there drop the link
                                    // (per lmdegreeds/dji_gpsoff Transport notes).
    // 40007 = DJI Fly's FPV video-mirror route (wrapped). NEVER write here (drops
    // Fly's video); it's the read/serial/probe route only. PORT_LED is the old,
    // misleading name — kept as an alias so existing call sites still compile.
    const val PORT_VIDEO_MIRROR = 40007
    const val PORT_LED = PORT_VIDEO_MIRROR

    // cmd_type (packet_type | ack_type | encrypt_type). Encryption bit stays 0.
    const val CT_NO_ACK = 0x00           // Request, NO_ACK_NEEDED
    const val CT_ACK_BEFORE = 0x20       // Request, ACK_BEFORE_EXEC (0x20)
    const val CT_ACK = 0x40              // Request with ACK (0x40)

    // Common senders / receivers (TTII).
    const val SENDER_APP4 = 130          // MOBILE_APP index 4
    const val SENDER_APP0 = 2            // MOBILE_APP index 0
    const val DST_FLIGHT = 3             // flight controller
    const val CMDSET_FLYC = 3
    const val CMDID_READ_PARAM_HASH = 0xF8   // ReadParamValByHash (2015)
    const val CMDID_WRITE_PARAM_HASH = 0xF9  // WriteParamValByHash (2015)

    /**
     * 8-byte outer envelope of the 40007 route (from Skylab):
     * [0x55][0xCC][0x30][0x75][len LE 4][inner DUML frame].
     *
     * No feature uses it — LED/GPS/param writes go unwrapped to 40008. Kept for
     * hand experiments on 40007 via the diag server.
     */
    fun wrap(inner: ByteArray): ByteArray {
        val out = ByteArray(8 + inner.size)
        out[0] = 0x55; out[1] = 0xCC.toByte(); out[2] = 0x30; out[3] = 0x75
        val len = inner.size
        out[4] = (len and 0xFF).toByte()
        out[5] = ((len ushr 8) and 0xFF).toByte()
        out[6] = ((len ushr 16) and 0xFF).toByte()
        out[7] = ((len ushr 24) and 0xFF).toByte()
        System.arraycopy(inner, 0, out, 8, inner.size)
        return out
    }

    /**
     * Stamp a sequence number into an already-built inner frame.
     *
     * `nativeBuildFrame` always emits seq 0 and exposes no way to set it, but the header
     * CRC-8 covers only bytes 0..2, so changing the seq at bytes 6..7 leaves it untouched
     * and only the CRC-16 trailer has to be recomputed — which [DumlCrc] already does in
     * pure Kotlin. No native change is needed.
     *
     * Needed because some replies echo nothing that identifies the request: `03:F7`'s
     * "no such parameter" answer is a single status byte, and `03:E1`'s empty-slot answer
     * is two. Those can only be matched to their request by sequence number. Replies that
     * do echo content (a hash, a table+index) should be correlated on that instead.
     *
     * Returns a copy; the input is not modified. Frames shorter than a header + trailer
     * are returned unchanged.
     */
    fun withSeq(inner: ByteArray, seq: Int): ByteArray {
        if (inner.size < 13) return inner
        val out = inner.copyOf()
        out[6] = (seq and 0xFF).toByte()
        out[7] = ((seq ushr 8) and 0xFF).toByte()
        val c = DumlCrc.crc16(out, 0, out.size - 2)
        out[out.size - 2] = (c and 0xFF).toByte()
        out[out.size - 1] = ((c ushr 8) and 0xFF).toByte()
        return out
    }

    /**
     * Parse a hex string to bytes. Tolerant of any whitespace between bytes;
     * strict about content — an odd length or a non-hex character throws
     * [IllegalArgumentException] with a clear message (the old code threw
     * StringIndexOutOfBounds / NumberFormatException, or silently dropped the last
     * nibble). Callers on untrusted input (diag `/send`) wrap this in runCatching.
     */
    fun hex(s: String): ByteArray {
        val c = s.filterNot { it.isWhitespace() }
        if (c.isEmpty()) return ByteArray(0)
        require(c.length % 2 == 0) { "hex string has odd length ${c.length}" }
        return ByteArray(c.length / 2) {
            val hi = c[it * 2].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("invalid hex char '${c[it * 2]}'")
            val lo = c[it * 2 + 1].digitToIntOrNull(16)
                ?: throw IllegalArgumentException("invalid hex char '${c[it * 2 + 1]}'")
            ((hi shl 4) or lo).toByte()
        }
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Lowercase hex, table-encoded — no per-byte String.format allocation (this
     *  runs for every logged frame, including high-rate telemetry). */
    fun toHex(b: ByteArray): String {
        val c = CharArray(b.size * 2)
        for (i in b.indices) {
            val v = b[i].toInt() and 0xFF
            c[i * 2] = HEX[v ushr 4]
            c[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(c)
    }
}
