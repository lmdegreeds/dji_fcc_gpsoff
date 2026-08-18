package com.dji.fccgpsoff

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Off-device tests for the link-liveness logic (the RX-telemetry gate).
 *
 * [LinkState] is a process singleton, so state (frame count, last-RX stamp)
 * carries across test methods. Each test therefore starts the injected clock a
 * large jump past anything a previous test stamped, so the baseline is always
 * "stale / disconnected" regardless of run order.
 */
class LinkStateTest {

    private var now = 0L

    @Before fun setUp() {
        clockBase += 1_000_000L          // far beyond any lastRx a prior test left
        now = clockBase
        LinkState.clock = { now }
    }

    @After fun tearDown() { LinkState.clock = System::currentTimeMillis }

    @Test fun staleBaselineIsNotConnected() {
        // No fresh frame in the current window ⇒ link down.
        assertFalse(LinkState.connected())
    }

    @Test fun connectedWithinFreshnessWindowThenStale() {
        LinkState.onFrame()
        assertTrue(LinkState.connected())                       // just arrived
        now += LinkState.STALE_MS - 1
        assertTrue(LinkState.connected())                       // still within window
        now += 2
        assertFalse(LinkState.connected())                      // aged out ⇒ link down
    }

    @Test fun onFrameCountsFrames() {
        val before = LinkState.frames
        LinkState.onFrame(); LinkState.onFrame(); LinkState.onFrame()
        assertEquals(before + 3, LinkState.frames)
    }

    @Test fun ageReflectsElapsedSinceLastFrame() {
        LinkState.onFrame()
        assertEquals(0L, LinkState.ageMs())
        now += 1_500
        assertEquals(1_500L, LinkState.ageMs())
    }

    @Test fun reconnectFiresOnlyAfterGapThenFreshFrames() {
        LinkState.onFrame()
        val mark = LinkState.frames

        // Steady stream, no gap observed ⇒ never a reconnect.
        assertFalse(LinkState.reconnected(mark, wasStale = false))

        // Link drops: RX goes stale. The caller records the gap.
        now += LinkState.STALE_MS + 1
        assertFalse(LinkState.connected())
        // A gap alone (no new frames yet) is not a reconnect.
        assertFalse(LinkState.reconnected(mark, wasStale = true))

        // Link comes back: fresh frames arrive after the gap ⇒ reconnect edge.
        LinkState.onFrame()
        assertTrue(LinkState.connected())
        assertTrue(LinkState.reconnected(mark, wasStale = true))

        // Once the caller re-marks at the new frame count, it no longer fires.
        assertFalse(LinkState.reconnected(LinkState.frames, wasStale = true))
    }

    companion object {
        /** Monotonic across test methods so each starts past prior lastRx stamps. */
        private var clockBase = 100_000_000L
    }
}
