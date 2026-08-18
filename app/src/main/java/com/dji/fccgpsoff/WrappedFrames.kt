package com.dji.fccgpsoff

/**
 * One walker for the 40007 "wrapped" stream — `[55 CC 30 75][len32 LE][inner DUML
 * frame]` — that DJI Fly's video-mirror route carries. It replaces three
 * near-verbatim copies of this envelope+bounds parse that lived in
 * [AircraftSerial], [ParamRead] and [AircraftModelProbe]; each now supplies only
 * the cmdSet/cmdId predicate and the payload decode it cares about.
 *
 * The bounds are exactly the originals: outer length `fl in 13..1023` and inner
 * DUML total `total in 13..fl`, so behaviour on the wire is unchanged.
 */
object WrappedFrames {

    /** A decoded, CRC-valid inner DUML frame lifted out of the 40007 envelope. */
    class Inner(
        val sender: Int, val receiver: Int, val seq: Int,
        val cmdType: Int, val cmdSet: Int, val cmdId: Int, val payload: ByteArray
    ) {
        /** DUML response bit (cmdType & 0x80): true for a reply, false for a request. */
        val isResponse: Boolean get() = (cmdType and 0x80) != 0
    }

    /**
     * Walk [stream] (first [len] bytes) and call [onFrame] for each **CRC-valid**
     * inner frame in envelope order. Return a non-null value from [onFrame] to stop
     * early and hand that value back; returns null if nothing matched.
     *
     * Frames whose CRC8 or CRC16 don't check out are skipped, not delivered — so a
     * corrupt or misaligned frame can never be matched to a request (the three
     * former copies of this walker trusted structure alone).
     */
    fun <R : Any> walk(stream: ByteArray, len: Int = stream.size, onFrame: (Inner) -> R?): R? =
        walkIndexed(stream, len) { inner, _ -> onFrame(inner) }

    /**
     * As [walk], but [onFrame] also receives the offset just past the envelope it came
     * from. A caller that scans a growing buffer needs that to know how far it has
     * consumed — without it the only safe re-scan is an overlap, which delivers frames in
     * the overlap twice (harmless when you stop at the first match, wrong when you are
     * collecting every reply in a window).
     */
    fun <R : Any> walkIndexed(stream: ByteArray, len: Int = stream.size, onFrame: (Inner, Int) -> R?): R? {
        var i = 0
        while (i + 8 <= len) {
            if (stream[i] == 0x55.toByte() && stream[i + 1] == 0xCC.toByte() &&
                stream[i + 2] == 0x30.toByte() && stream[i + 3] == 0x75.toByte()
            ) {
                val fl = (stream[i + 4].toInt() and 0xFF) or ((stream[i + 5].toInt() and 0xFF) shl 8) or
                    ((stream[i + 6].toInt() and 0xFF) shl 16) or ((stream[i + 7].toInt() and 0xFF) shl 24)
                val start = i + 8
                if (fl in 13..1023 && start + fl <= len) {
                    val total = (stream[start + 1].toInt() and 0xFF) or ((stream[start + 2].toInt() and 0x03) shl 8)
                    if (total in 13..fl && validCrc(stream, start, total)) {
                        val inner = Inner(
                            sender = stream[start + 4].toInt() and 0xFF,
                            receiver = stream[start + 5].toInt() and 0xFF,
                            seq = (stream[start + 6].toInt() and 0xFF) or ((stream[start + 7].toInt() and 0xFF) shl 8),
                            cmdType = stream[start + 8].toInt() and 0xFF,
                            cmdSet = stream[start + 9].toInt() and 0xFF,
                            cmdId = stream[start + 10].toInt() and 0xFF,
                            payload = stream.copyOfRange(start + 11, start + total - 2)
                        )
                        onFrame(inner, start + fl)?.let { return it }
                    }
                    i = start + fl
                    continue
                }
            }
            i++
        }
        return null
    }

    /** Validate the inner frame's CRC8 (header) and CRC16 (trailer, LE). */
    private fun validCrc(stream: ByteArray, start: Int, total: Int): Boolean {
        if (DumlCrc.crc8(stream, start, 3) != (stream[start + 3].toInt() and 0xFF)) return false
        val want = (stream[start + total - 2].toInt() and 0xFF) or ((stream[start + total - 1].toInt() and 0xFF) shl 8)
        return DumlCrc.crc16(stream, start, total - 2) == want
    }
}
