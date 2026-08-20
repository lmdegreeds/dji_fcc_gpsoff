package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * The rule that stops a control being dragged back to a stale reading (2026-08-20).
 *
 * The reported symptom was "the GPS switch sometimes does not react and does not change
 * position". The cause was a three-second render tick that re-asserted the cached value
 * onto the thumb between the tap and the read-back. The fix is a pending intent that the
 * render reads instead of the cache, and it has exactly three ways to end: confirmed,
 * contradicted after the board has had time to settle, or abandoned because the frames
 * never left the socket. Each is pinned here.
 */
class FlightStatePendingTest {

    private val gps = FlightState.Item.GPS

    @Before fun clean() = FlightState.reset()

    @Test fun withNothingPendingTheControlShowsTheAircraft() {
        FlightState.observe(gps, true)
        assertEquals(true, FlightState.shown(gps))
        assertEquals(true, FlightState.value(gps))
        assertNull(FlightState.wanted(gps))
    }

    /** The whole point: a read that still carries the OLD value must not move the thumb. */
    @Test fun aPendingWriteOutranksAStaleReading() {
        FlightState.observe(gps, true)          // aircraft says on
        FlightState.markWritten(gps, false)     // user asks for off
        assertEquals(false, FlightState.shown(gps))

        // The render tick fires, and so does a read-back that has not caught up yet.
        FlightState.observe(gps, true)
        assertEquals("the thumb must stay where the user put it inside the settle window",
            false, FlightState.shown(gps))
        assertEquals("the label must keep showing what the aircraft actually said",
            true, FlightState.value(gps))
    }

    @Test fun aConfirmingReadEndsThePendingWrite() {
        FlightState.observe(gps, true)
        FlightState.markWritten(gps, false)
        FlightState.observe(gps, false)
        assertNull(FlightState.wanted(gps))
        assertEquals(false, FlightState.shown(gps))
    }

    /** Past the settle window a contradiction is a verdict, not a race: the control has to
     *  go back to the truth rather than sit on a write that never took. */
    @Test fun aContradictionPastTheSettleWindowGivesTheAircraftBackTheControl() {
        FlightState.observe(gps, true)
        FlightState.markWritten(gps, false, System.currentTimeMillis() - 5_000)
        FlightState.observe(gps, true)
        assertNull(FlightState.wanted(gps))
        assertEquals(true, FlightState.shown(gps))
    }

    /**
     * A write from the floating panel happens while DJI Fly is in front, so no read is
     * possible until the user comes back to this app — minutes or hours later. A
     * difference seen then is not evidence about the write, and must not be reported as
     * one; the intent is simply dropped in favour of what the aircraft says.
     */
    @Test fun aVeryOldPendingWriteIsDroppedWithoutAVerdict() {
        FlightState.observe(gps, true)
        FlightState.markWritten(gps, false, System.currentTimeMillis() - 10 * 60_000)
        FlightState.observe(gps, true)
        assertNull(FlightState.wanted(gps))
        assertEquals(true, FlightState.shown(gps))
    }

    @Test fun aWriteThatNeverLeftTheSocketIsNotHeld() {
        FlightState.observe(gps, true)
        FlightState.markWritten(gps, false)
        FlightState.clearWritten(gps)
        assertNull(FlightState.wanted(gps))
        assertEquals(true, FlightState.shown(gps))
    }

    /** A blind aircraft (no read ever answers) must keep the user's choice on screen —
     *  there is no evidence to move it, and inventing one would be the same lie in reverse. */
    @Test fun withNoReadingAtAllTheControlKeepsTheIntent() {
        FlightState.markWritten(gps, false)
        assertEquals(false, FlightState.shown(gps))
        assertNull(FlightState.value(gps))
    }

    @Test fun aChangedAircraftDropsPendingIntentsToo() {
        FlightState.markWritten(gps, false)
        FlightState.reset()
        assertNull(FlightState.wanted(gps))
        assertNull(FlightState.shown(gps))
    }

    /** `missing()` drives the "read state" retry loop; a pending write is not a reading and
     *  must not make an unread item look answered. */
    @Test fun aPendingWriteDoesNotCountAsAReading() {
        FlightState.markWritten(gps, false)
        assertEquals(true, FlightState.missing().contains(gps))
    }
}
