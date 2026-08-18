package com.dji.fccgpsoff

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Off-device tests for the read gate's foreground logic. */
class ForegroundGateTest {

    @Before fun reset() {
        ForegroundGate.ownPackage = "com.dji.fccgpsoff"
        ForegroundGate.clearExpectedFly()
        ForegroundGate.onWindow("com.dji.fccgpsoff")  // start from a non-Fly state
    }

    @Test fun readsAllowedWhenOurAppForeground() {
        ForegroundGate.onWindow("com.dji.fccgpsoff")
        assertTrue(ForegroundGate.readsAllowed())
        assertNull(ForegroundGate.blockReason())
        assertFalse(ForegroundGate.isFlyForeground)
    }

    @Test fun readsBlockedWhenDjiFlyForeground() {
        ForegroundGate.onWindow("dji.go.v5")
        assertFalse(ForegroundGate.readsAllowed())
        assertTrue(ForegroundGate.isFlyForeground)
        assertNotNull(ForegroundGate.blockReason())
    }

    @Test fun readsBlockedWhenPilotForeground() {
        ForegroundGate.onWindow("com.dji.industry.pilot")
        assertFalse(ForegroundGate.readsAllowed())
    }

    @Test fun switchingAwayFromFlyReenablesReads() {
        ForegroundGate.onWindow("dji.go.v5")
        assertFalse(ForegroundGate.readsAllowed())
        ForegroundGate.onWindow("com.dji.fccgpsoff")
        assertTrue(ForegroundGate.readsAllowed())
    }

    @Test fun thirdPartyAppCountsAsSafe() {
        // A non-DJI app in the foreground means Fly is backgrounded → reads safe.
        ForegroundGate.onWindow("com.android.settings")
        assertTrue(ForegroundGate.readsAllowed())
    }

    // ---- pre-emptive block: we know Fly is coming before the window event says so ----

    /**
     * The window event arrives only after the switch has happened, and it lags. A read
     * landing in that gap is what made DJI Fly drop its link for several seconds. When the
     * switch is ours to make, the gate must shut first.
     */
    @Test fun expectingFlyBlocksReadsBeforeAnyWindowEvent() {
        assertTrue(ForegroundGate.readsAllowed())
        ForegroundGate.expectFlyForeground()
        assertFalse("reads must stop the moment we ask for Fly", ForegroundGate.readsAllowed())
        assertNotNull(ForegroundGate.blockReason())
        // Still nothing has told us Fly is in front — the block is our own doing.
        assertFalse(ForegroundGate.isFlyForeground)
    }

    @Test fun aConfirmedFlyWindowKeepsReadsBlocked() {
        ForegroundGate.expectFlyForeground()
        ForegroundGate.onWindow("dji.go.v5")          // the switch completed
        assertFalse(ForegroundGate.readsAllowed())
        assertTrue(ForegroundGate.isFlyForeground)
    }

    /** If the switch never happens — the launch failed, or the user came straight back —
     *  a real window event must lift the block rather than let it sit out its timeout. */
    @Test fun aConfirmedNonFlyWindowLiftsThePreemptiveBlock() {
        ForegroundGate.expectFlyForeground()
        assertFalse(ForegroundGate.readsAllowed())
        ForegroundGate.onWindow("com.android.settings")
        assertTrue(ForegroundGate.readsAllowed())
    }

    @Test fun clearingTheExpectationLiftsTheBlock() {
        ForegroundGate.expectFlyForeground()
        assertFalse(ForegroundGate.readsAllowed())
        ForegroundGate.clearExpectedFly()
        assertTrue(ForegroundGate.readsAllowed())
    }

    /** The block is a fallback, not a latch: without confirmation it expires on its own. */
    @Test fun thePreemptiveBlockExpiresOnItsOwn() {
        ForegroundGate.expectFlyForeground(ms = 40)
        assertFalse(ForegroundGate.readsAllowed())
        Thread.sleep(80)
        assertTrue("a switch that never lands must not block reads forever",
            ForegroundGate.readsAllowed())
    }
}
