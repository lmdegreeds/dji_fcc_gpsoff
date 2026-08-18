package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Off-device tests for the shared 40007 envelope walker. */
class WrappedFramesTest {

    @Test fun findsSingleFrameFields() {
        val stream = WireFixtures.wrapped(0x80, 0x03, 0xF8, byteArrayOf(0, 1, 2, 3, 4))
        val got = WrappedFrames.walk(stream) { it }
        assertEquals(0x80, got!!.cmdType)
        assertEquals(0x03, got.cmdSet)
        assertEquals(0xF8, got.cmdId)
        assertEquals(true, got.isResponse)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), got.payload)
    }

    @Test fun requestBitClearIsNotResponse() {
        val stream = WireFixtures.wrapped(0x40, 0x03, 0xF8, byteArrayOf(9))
        assertEquals(false, WrappedFrames.walk(stream) { it }!!.isResponse)
    }

    @Test fun walksMultipleFramesInOrder() {
        val stream = WireFixtures.wrapped(0x80, 0x00, 0x51, byteArrayOf(1)) +
            WireFixtures.wrapped(0x80, 0x03, 0xF8, byteArrayOf(2))
        val ids = ArrayList<Int>()
        val r: Int? = WrappedFrames.walk(stream) { ids.add(it.cmdId); null }
        assertNull(r)
        assertEquals(listOf(0x51, 0xF8), ids)
    }

    @Test fun stopsEarlyOnNonNull() {
        val stream = WireFixtures.wrapped(0x80, 0x00, 0x51, byteArrayOf(1)) +
            WireFixtures.wrapped(0x80, 0x03, 0xF8, byteArrayOf(2))
        val seen = ArrayList<Int>()
        val hit = WrappedFrames.walk(stream) { seen.add(it.cmdId); if (it.cmdId == 0x51) "first" else null }
        assertEquals("first", hit)
        assertEquals(listOf(0x51), seen)                 // did not visit the second frame
    }

    @Test fun skipsLeadingAndTrailingGarbage() {
        val stream = byteArrayOf(0x11, 0x22, 0x55, 0x00) +
            WireFixtures.wrapped(0x80, 0x00, 0x51, byteArrayOf(7)) +
            byteArrayOf(0x55, 0xCC.toByte(), 0x30)       // truncated envelope tail
        val got = WrappedFrames.walk(stream) { it }
        assertEquals(0x51, got!!.cmdId)
        assertArrayEquals(byteArrayOf(7), got.payload)
    }

    @Test fun returnsNullOnNoMatch() {
        assertNull(WrappedFrames.walk(byteArrayOf(1, 2, 3, 4, 5)) { it })
    }

    @Test fun exposesSenderReceiverSeq() {
        val stream = WireFixtures.wrapped(0x80, 0x03, 0xF8, byteArrayOf(1), sender = 3, receiver = 2, seq = 0x1234)
        val got = WrappedFrames.walk(stream) { it }!!
        assertEquals(3, got.sender)
        assertEquals(2, got.receiver)
        assertEquals(0x1234, got.seq)
    }

    @Test fun corruptCrcFrameIsSkipped() {
        val stream = WireFixtures.wrapped(0x80, 0x00, 0x51, byteArrayOf(7, 8, 9))
        stream[stream.size - 3] = (stream[stream.size - 3].toInt() xor 0xFF).toByte()  // flip a payload byte → CRC16 fails
        assertNull(WrappedFrames.walk(stream) { it })
    }
}
