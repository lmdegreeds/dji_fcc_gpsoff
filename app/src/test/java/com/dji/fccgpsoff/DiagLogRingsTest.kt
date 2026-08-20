package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The two-ring split (2026-08-20) — the first tests [DiagLog] has ever had.
 *
 * The property that matters is not "there are two rings", it is that **bus traffic cannot
 * evict a session event**. Before the split, one live session left 881 aux frames in a
 * 3000-entry ring and no keepalive history at all: the lines a session is diagnosed from
 * were the first casualties of the lines nobody reads. The suppression in `rxFrame` slowed
 * that down; only the split stops it.
 */
class DiagLogRingsTest {

    @Before fun clean() = DiagLog.clear()

    @Test fun wireTrafficCannotEvictASessionEvent() {
        DiagLog.info("the line a report depends on")
        // Far more wire lines than the wire ring can hold. Under one shared ring this
        // would have pushed the INFO line out long ago.
        repeat(6000) { DiagLog.tx(40007, "read x 03:F8", byteArrayOf(0x55, it.toByte())) }
        assertTrue("the event must survive any amount of traffic",
            DiagLog.dump().contains("the line a report depends on"))
    }

    /**
     * A dump reads as ONE story: the split is an implementation detail of eviction, not a
     * grouping the reader has to reassemble.
     *
     * The merge orders by millisecond timestamp, and lines written inside the same
     * millisecond may come out in either ring's order — deliberately, since the alternative
     * is a sequence number on all 8000 entries to tie-break lines whose order nobody can
     * act on. The waits here are what make the timestamps distinct, not a hidden
     * requirement of the code.
     */
    @Test fun theRingsAreMergedInTimestampOrder() {
        DiagLog.info("first")
        Thread.sleep(3)
        DiagLog.tx(40008, "write y", byteArrayOf(1))
        Thread.sleep(3)
        DiagLog.warn("third")
        val body = DiagLog.dump()
        val iFirst = body.indexOf("first")
        val iWire = body.indexOf("write y")
        val iThird = body.indexOf("third")
        assertTrue("all three present", iFirst >= 0 && iWire >= 0 && iThird >= 0)
        assertTrue("events and wire lines interleave by time, not by ring", iFirst < iWire)
        assertTrue(iWire < iThird)
    }

    /** A truncated log used to be byte-for-byte indistinguishable from a complete one. */
    @Test fun aDumpSaysWhenItHasLostLines() {
        repeat(6000) { DiagLog.tx(40007, "noise", byteArrayOf(0)) }
        val (events, wire) = DiagLog.dropped()
        assertEquals("no event was dropped", 0L, events)
        assertTrue("the wire ring dropped what it could not hold", wire > 0)
        assertTrue("and the dump says so", DiagLog.dump().contains("evicted"))
    }

    @Test fun aCleanSessionSaysNothingAboutEviction() {
        DiagLog.info("quiet")
        assertFalse(DiagLog.dump().contains("evicted"))
    }

    /** Lines used to carry `HH:mm:ss.SSS` and nothing else, so a shared log could not be
     *  tied to a day, ordered against a second log, or lined up with a flight record. */
    @Test fun linesCarryADate() {
        DiagLog.info("dated")
        val line = DiagLog.tail(1)
        assertTrue("expected a MM-dd prefix, got: $line", Regex("^\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} ").containsMatchIn(line))
    }

    /** A 1023-byte frame is 2046 hex characters and must not own a ring slot. */
    @Test fun anEnormousLineIsCappedAndSaysSo() {
        DiagLog.info("x".repeat(5000))
        val line = DiagLog.tail(1)
        assertTrue(line.length < 1100)
        assertTrue(line.contains("chars)"))
    }

    /** The header is what lets several process lifetimes share one file and still be read
     *  apart, and what carries the reboot detector. */
    @Test fun theHeaderIdentifiesTheSessionAndTheDeviceUptime() {
        val h = DiagLog.header()
        assertTrue(h.contains(DiagLog.sessionId))
        assertTrue(h.contains("device up"))
    }
}
