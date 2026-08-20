package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The answer-rate accounting (2026-08-20).
 *
 * Every retry budget in this app is tuned to one measured figure — about 70% of 40007
 * windows answer with DJI Fly backgrounded — and it was not observable at runtime, so a
 * log could show dozens of attempts without saying whether the bus was normal. What is
 * pinned here is the distinction the whole object exists for: a window that was OPENED and
 * came back empty is evidence about the aircraft, while a read that was never attempted
 * because the foreground gate was shut is evidence about us, and the two must never be
 * averaged together.
 */
class ReadStatsTest {

    @Before fun clean() = ReadStats.reset()

    @Test fun nothingAskedIsSaidPlainly() {
        assertEquals(-1, ReadStats.rate())
        assertTrue(ReadStats.summary().contains("no parameter read"))
    }

    @Test fun theRateCountsOnlyWindowsThatWereActuallyOpened() {
        repeat(7) { ReadStats.answered() }
        repeat(3) { ReadStats.silent() }
        assertEquals(70, ReadStats.rate())
    }

    @Test fun aGateBlockedReadIsNotASilentAircraft() {
        repeat(5) { ReadStats.gateBlocked() }
        assertEquals("nothing was asked, so there is no rate to report", -1, ReadStats.rate())
        assertTrue(ReadStats.summary().contains("read gate shut"))
    }

    @Test fun deniedAddressesAreReportedSeparatelyFromSilence() {
        ReadStats.answered(); ReadStats.denied()
        val s = ReadStats.summary()
        assertTrue(s.contains("denied by 03:F7"))
        assertEquals(100, ReadStats.rate())
    }

    @Test fun theRecentWindowTracksTheLastOutcomes() {
        repeat(30) { ReadStats.silent() }
        repeat(2) { ReadStats.answered() }
        assertTrue("lately must reflect the tail, not the whole session",
            ReadStats.summary().contains("lately 2/20"))
    }

    @Test fun resetClearsEverything() {
        ReadStats.answered(); ReadStats.silent(); ReadStats.gateBlocked(); ReadStats.denied()
        ReadStats.reset()
        assertEquals(-1, ReadStats.rate())
        assertTrue(ReadStats.summary().contains("no parameter read"))
    }
}
