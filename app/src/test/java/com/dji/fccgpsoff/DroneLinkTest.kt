package com.dji.fccgpsoff

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Off-device tests for the HONEST link detector — it must count only live FLYC
 * OSD and ignore the controller housekeeping that fooled [LinkState].
 *
 * [DroneLink] is a process singleton, so state carries across methods; each test
 * starts the injected clock far past anything a prior test stamped (as in
 * [LinkStateTest]).
 */
class DroneLinkTest {

    private var now = 0L

    @Before fun setUp() {
        clockBase += 1_000_000L
        now = clockBase
        DroneLink.clock = { now }
    }

    @After fun tearDown() { DroneLink.clock = System::currentTimeMillis }

    @Test fun osdWhitelistMatchesOnlyLinkFrames() {
        assertTrue(DroneLink.isDroneOsd(0x03, 0x43))      // FLYC OSD
        assertTrue(DroneLink.isDroneOsd(0x03, 0x44))      // home-point OSD
        assertTrue(DroneLink.isDroneOsd(0x23, 0xB2))      // video status @50Hz
        assertFalse(DroneLink.isDroneOsd(0x06, 0xAE))     // RC radio housekeeping
        assertFalse(DroneLink.isDroneOsd(0x51, 0x14))     // cached serial broadcast
        assertFalse(DroneLink.isDroneOsd(0x00, 0x81))     // "rc331" identity
        assertFalse(DroneLink.isDroneOsd(0x23, 0xA1))     // 23:xx but not B2
    }

    @Test fun housekeepingDoesNotConnect() {
        val before = DroneLink.frames
        DroneLink.offer(0x06, 0xAE, route = 1)            // housekeeping on aux
        DroneLink.offer(0x51, 0x14, route = 1)            // cached serial broadcast
        DroneLink.offer(0x00, 0x81, route = 0)            // RC identity on main
        assertEquals(before, DroneLink.frames)            // nothing counted
        assertFalse(DroneLink.connected())
    }

    @Test fun osdOnMainRouteIsIgnored() {
        // cmdSet 0x03 on the MAIN channel is our own 03:F8/03:F9 param traffic, not
        // aircraft OSD — must not mark the link up.
        DroneLink.offer(0x03, 0x43, route = 0)
        assertFalse(DroneLink.connected())
        DroneLink.offer(0x03, 0x43, route = DroneLink.AUX_ROUTE)
        assertTrue(DroneLink.connected())
    }

    @Test fun osdConnectsWithinWindowThenGoesStale() {
        DroneLink.offer(0x03, 0x43, route = 1)
        assertTrue(DroneLink.connected())
        now += DroneLink.STALE_MS - 1
        assertTrue(DroneLink.connected())
        now += 2
        assertFalse(DroneLink.connected())                // aged out ⇒ link down
    }

    @Test fun ageReflectsElapsedSinceLastOsd() {
        DroneLink.offer(0x23, 0xB2, route = 1)
        assertEquals(0L, DroneLink.ageMs())
        now += 1_500
        assertEquals(1_500L, DroneLink.ageMs())
    }

    @Test fun reconnectFiresOnlyAfterGapThenFreshOsd() {
        DroneLink.offer(0x03, 0x43, route = 1)
        val mark = DroneLink.frames
        assertFalse(DroneLink.reconnected(mark, wasStale = false))

        now += DroneLink.STALE_MS + 1
        assertFalse(DroneLink.connected())
        assertFalse(DroneLink.reconnected(mark, wasStale = true))   // gap alone isn't a reconnect

        DroneLink.offer(0x03, 0x53, route = 1)
        assertTrue(DroneLink.connected())
        assertTrue(DroneLink.reconnected(mark, wasStale = true))    // fresh OSD after gap ⇒ edge

        assertFalse(DroneLink.reconnected(DroneLink.frames, wasStale = true))
    }

    companion object {
        private var clockBase = 200_000_000L
    }
}
