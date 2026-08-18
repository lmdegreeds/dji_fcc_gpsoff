package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden tests: the Kotlin CRC mirror must reproduce the CRCs of real, on-wire
 * frames from the README byte-for-byte (same as the native side). If these pass,
 * [WrappedFrames]' CRC validation accepts genuine frames and rejects corrupt ones.
 */
class DumlCrcTest {

    private fun assertFrameCrcs(hex: String, wantCrc8: Int) {
        val f = DumlWire.hex(hex)
        assertEquals(wantCrc8, DumlCrc.crc8(f, 0, 3))
        val total = f.size
        val trailer = (f[total - 2].toInt() and 0xFF) or ((f[total - 1].toInt() and 0xFF) shl 8)
        assertEquals(trailer, DumlCrc.crc16(f, 0, total - 2))
    }

    // LED off: 55 12 04 c7 ... dc 47  (README §3)
    @Test fun ledOffFrame() = assertFrameCrcs("551204c7020300004003f94e9115f300dc47", 0xc7)

    // GPS off: 55 12 04 c7 ... e1 13  (README §3)
    @Test fun gpsOffFrame() = assertFrameCrcs("551204c7020300004003f99d8a888100e113", 0xc7)

    // setForceFcc frame 9: 55 18 04 20 ... 5e 75  (README §4)
    @Test fun setForceFccFrame() = assertFrameCrcs("551804208209000020092700024800ffff02000000005e75", 0x20)
}
