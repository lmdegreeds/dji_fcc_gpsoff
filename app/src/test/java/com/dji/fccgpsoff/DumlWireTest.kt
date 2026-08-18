package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Off-device tests for the hex parser/printer and the 40007 envelope wrapper. */
class DumlWireTest {

    @Test fun hexParsesValidLowerAndUpper() {
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte(), 0x00, 0x12), DumlWire.hex("0aFF0012"))
    }

    @Test fun hexIgnoresAllWhitespace() {
        val want = byteArrayOf(0x55, 0x12, 0x04, 0xc7.toByte())
        assertArrayEquals(want, DumlWire.hex("55 12 04 c7"))
        assertArrayEquals(want, DumlWire.hex("55\t12\n04\r c7"))
    }

    @Test fun hexEmptyIsEmpty() {
        assertEquals(0, DumlWire.hex("").size)
        assertEquals(0, DumlWire.hex("   ").size)
    }

    @Test fun hexOddLengthThrows() {
        val e = assertThrows(IllegalArgumentException::class.java) { DumlWire.hex("abc") }
        assertTrue(e.message!!.contains("odd length"))
    }

    @Test fun hexInvalidCharThrows() {
        val e = assertThrows(IllegalArgumentException::class.java) { DumlWire.hex("zz") }
        assertTrue(e.message!!.contains("invalid hex"))
    }

    @Test fun hexToHexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte(), 0x10)
        assertArrayEquals(bytes, DumlWire.hex(DumlWire.toHex(bytes)))
    }

    @Test fun wrapPrependsEnvelopeWithLenLE() {
        val inner = byteArrayOf(0x01, 0x02, 0x03)
        val w = DumlWire.wrap(inner)
        assertArrayEquals(byteArrayOf(0x55, 0xCC.toByte(), 0x30, 0x75), w.copyOfRange(0, 4))
        // length is inner.size, little-endian, in bytes 4..7
        assertArrayEquals(byteArrayOf(0x03, 0x00, 0x00, 0x00), w.copyOfRange(4, 8))
        assertArrayEquals(inner, w.copyOfRange(8, w.size))
    }
}
