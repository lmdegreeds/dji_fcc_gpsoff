package com.dji.fccgpsoff

/**
 * Kotlin mirror of the native DUML CRCs (`duml_core.cpp`), so the host side can
 * *validate* frames it reads off a socket — not just trust the structure. Used by
 * [WrappedFrames] to reject a corrupt/misaligned inner frame before matching it
 * to a request, and by the golden tests to build byte-exact fixtures.
 *
 * CRC-8: reflected poly 0x8C, init 0x77, over the first 3 header bytes.
 * CRC-16: reflected poly 0x8408, init 0x3692, over everything before the trailer.
 * These constants and the table build are identical to the native side; a golden
 * test asserts a known frame matches byte-for-byte.
 */
object DumlCrc {

    private val T8 = IntArray(256)
    private val T16 = IntArray(256)

    init {
        for (i in 0..255) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8C else c ushr 1 }
            T8[i] = c and 0xFF
        }
        for (i in 0..255) {
            var c = i
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0x8408 else c ushr 1 }
            T16[i] = c and 0xFFFF
        }
    }

    fun crc8(d: ByteArray, off: Int = 0, len: Int = d.size): Int {
        var c = 0x77
        for (i in off until off + len) c = T8[(c xor (d[i].toInt() and 0xFF)) and 0xFF]
        return c and 0xFF
    }

    fun crc16(d: ByteArray, off: Int = 0, len: Int = d.size): Int {
        var c = 0x3692
        for (i in off until off + len) c = (T16[(c xor (d[i].toInt() and 0xFF)) and 0xFF] xor (c ushr 8)) and 0xFFFF
        return c and 0xFFFF
    }
}
