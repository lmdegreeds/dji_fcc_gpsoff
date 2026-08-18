package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Off-device tests for the pure 03:F8 response value parsing. */
class ParamReadTest {

    @Test fun nonZeroStatusIsNull() {
        assertNull(ParamRead.parseValue(byteArrayOf(1, 0xaa.toByte(), 0xbb.toByte())))
    }

    @Test fun emptyIsNull() {
        assertNull(ParamRead.parseValue(byteArrayOf()))
    }

    @Test fun stripsStatusAndFourByteHash() {
        // status(0) + hash(4) + value(2)
        val pl = byteArrayOf(0, 0x11, 0x22, 0x33, 0x44, 0x0a, 0x0b)
        assertArrayEquals(byteArrayOf(0x0a, 0x0b), ParamRead.parseValue(pl))
    }

    @Test fun shortReplyStripsOnlyStatus() {
        // too short to contain a 4-byte hash: status(0) + value(2)
        val pl = byteArrayOf(0, 0x07, 0x09)
        assertArrayEquals(byteArrayOf(0x07, 0x09), ParamRead.parseValue(pl))
    }

    @Test fun statusOnlyIsEmptyValue() {
        assertArrayEquals(byteArrayOf(), ParamRead.parseValue(byteArrayOf(0)))
    }
}
