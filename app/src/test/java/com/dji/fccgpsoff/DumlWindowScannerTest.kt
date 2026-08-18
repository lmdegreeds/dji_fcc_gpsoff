package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scan buffer is the part of the window loop most likely to hide a subtle bug — a
 * reply straddling two socket reads, or one arriving after the buffer has been truncated.
 * Both were fixed once in [ParamRead] and were untested until now.
 */
class DumlWindowScannerTest {

    private fun reply(seq: Int) =
        WireFixtures.wrapped(0x80, 0x03, 0xF8, DumlWire.hex("004e9115f300"), seq = seq)

    /** Collect every frame the scanner delivers, counting each as one "hit". */
    private fun DumlWindow.Scanner.collect(into: MutableList<WrappedFrames.Inner>) =
        drain { inner -> into.add(inner); 1 }

    @Test fun findsAReplySplitAcrossTwoFeedsAtEveryOffset() {
        val frame = reply(0x1234)
        for (cut in 0..frame.size) {
            val s = DumlWindow.Scanner()
            val seen = mutableListOf<WrappedFrames.Inner>()
            s.feed(frame, 0, cut)
            s.collect(seen)                              // first read: usually incomplete
            s.feed(frame, cut, frame.size - cut)
            s.collect(seen)                              // second read completes it
            assertEquals("split at $cut", 1, seen.count { it.cmdId == 0xF8 && it.seq == 0x1234 })
        }
    }

    @Test fun deliversEveryFrameInTheWindowNotJustTheFirst() {
        // The rolled-back readMany lost every parameter after the first because the walker
        // stops at its first match. The scanner must not.
        val s = DumlWindow.Scanner()
        val seen = mutableListOf<WrappedFrames.Inner>()
        s.feed(reply(1) + reply(2) + reply(3))
        s.collect(seen)
        assertEquals(listOf(1, 2, 3), seen.filter { it.cmdId == 0xF8 }.map { it.seq })
    }

    @Test fun aFrameIsNeverDeliveredTwiceAcrossSuccessiveDrains() {
        val s = DumlWindow.Scanner()
        val seen = mutableListOf<WrappedFrames.Inner>()
        s.feed(reply(7))
        s.collect(seen)
        s.collect(seen)                                  // nothing new arrived
        s.feed(reply(8))
        s.collect(seen)
        assertEquals(listOf(7, 8), seen.filter { it.cmdId == 0xF8 }.map { it.seq })
    }

    @Test fun aReplyArrivingAfterTheCapIsStillFoundInTheRetainedTail() {
        // 40007 also carries DJI Fly's video mirror, so the stream can far outrun the cap.
        // Only the tail can still hold an unscanned reply; the head is dropped, not grown into.
        val s = DumlWindow.Scanner()
        val seen = mutableListOf<WrappedFrames.Inner>()
        val noise = ByteArray(64 * 1024) { 0x11 }
        repeat(8) { s.feed(noise); s.collect(seen) }     // push well past MAX_SCAN_BYTES
        s.feed(reply(0x2222))
        s.collect(seen)
        assertTrue("reply after truncation", seen.any { it.cmdId == 0xF8 && it.seq == 0x2222 })
    }

    @Test fun garbageBetweenFramesIsSkipped() {
        val s = DumlWindow.Scanner()
        val seen = mutableListOf<WrappedFrames.Inner>()
        s.feed(ByteArray(37) { 0x55 } + reply(9) + ByteArray(11) { 0x55 })
        s.collect(seen)
        assertEquals(listOf(9), seen.filter { it.cmdId == 0xF8 }.map { it.seq })
    }
}
