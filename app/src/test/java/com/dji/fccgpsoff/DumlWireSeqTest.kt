package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * [DumlWire.withSeq] stamps a sequence number into a frame the native builder already
 * produced (it always emits seq 0 and takes no seq argument). Correctness matters because
 * two replies in this family — `03:F7`'s "no such parameter" and `03:E1`'s "empty slot" —
 * echo nothing else that could tie them to a request.
 */
class DumlWireSeqTest {

    @Test fun matchesAFrameBuiltWithThatSeqFromScratch() {
        // Same frame twice: built with seq 0 then stamped, vs built with the seq directly.
        val payload = DumlWire.hex("4e9115f3")
        val stamped = DumlWire.withSeq(
            WireFixtures.inner(0x40, 0x03, 0xF7, payload, sender = 2, receiver = 3, seq = 0),
            0x4321
        )
        val direct = WireFixtures.inner(0x40, 0x03, 0xF7, payload, sender = 2, receiver = 3, seq = 0x4321)
        assertArrayEquals(direct, stamped)
    }

    @Test fun theStampedFrameStillPassesTheWalkersCrcCheck() {
        val inner = DumlWire.withSeq(
            WireFixtures.inner(0x80, 0x03, 0xF7, DumlWire.hex("03"), seq = 0), 0x7ffe
        )
        val found = WrappedFrames.walk(DumlWire.wrap(inner)) { it }
        assertNotNull("a stamped frame must survive CRC validation", found)
        assertEquals(0x7ffe, found!!.seq)
        assertEquals(0xF7, found.cmdId)
    }

    /** CRC-8 covers only bytes 0..2, so stamping the seq at 6..7 must leave it untouched —
     *  that is precisely why no native change is needed to get correlatable frames. */
    @Test fun leavesTheHeaderCrc8Untouched() {
        val original = WireFixtures.inner(0x40, 0x03, 0xE1, DumlWire.hex("00000700"), seq = 0)
        val stamped = DumlWire.withSeq(original, 0x5555)
        assertEquals(original[3], stamped[3])
        assertEquals(DumlCrc.crc8(stamped, 0, 3), stamped[3].toInt() and 0xFF)
    }

    @Test fun doesNotModifyTheInput() {
        val original = WireFixtures.inner(0x40, 0x03, 0xF7, DumlWire.hex("4e9115f3"), seq = 0)
        val copy = original.copyOf()
        DumlWire.withSeq(original, 0x1111)
        assertArrayEquals("input must not be mutated", copy, original)
    }

    @Test fun returnsShortInputUnchangedInsteadOfThrowing() {
        val tiny = byteArrayOf(0x55, 0x05, 0x04)
        assertArrayEquals(tiny, DumlWire.withSeq(tiny, 9))
        assertArrayEquals(ByteArray(0), DumlWire.withSeq(ByteArray(0), 9))
    }
}
