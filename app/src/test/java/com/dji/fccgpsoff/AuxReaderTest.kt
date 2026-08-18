package com.dji.fccgpsoff

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Off-device tests for the refcounted aux-reader owner: the native start/stop must
 * fire only on the 0→1 and 1→0 edges, so [DumlCapture] and [DroneLinkProbe] can
 * hold overlapping leases without cutting each other's reader.
 */
class AuxReaderTest {

    private val starts = AtomicInteger(0)
    private val stops = AtomicInteger(0)

    @Before fun setUp() {
        // Drain any leftover holders from a prior test, then install counting hooks.
        repeat(8) { AuxReader.release() }
        starts.set(0); stops.set(0)
        AuxReader.startFn = { starts.incrementAndGet(); it }   // "connected" on the given port
        AuxReader.stopFn = { stops.incrementAndGet() }
    }

    @After fun tearDown() {
        repeat(8) { AuxReader.release() }
        AuxReader.startFn = { DumlNative.nativeStartAux(it) }
        AuxReader.stopFn = { DumlNative.nativeStopAux() }
    }

    @Test fun startsOnceStopsOnceAcrossOverlappingLeases() {
        assertEquals(AuxReader.PORT, AuxReader.acquire())      // 0→1: start
        assertTrue(AuxReader.active)
        AuxReader.acquire()                                    // 1→2: no start
        assertEquals(2, AuxReader.holders)
        assertEquals(1, starts.get())
        assertEquals(0, stops.get())

        AuxReader.release()                                    // 2→1: no stop
        assertTrue(AuxReader.active)
        assertEquals(0, stops.get())
        AuxReader.release()                                    // 1→0: stop
        assertFalse(AuxReader.active)
        assertEquals(1, starts.get())
        assertEquals(1, stops.get())
    }

    @Test fun extraReleaseIsIgnored() {
        AuxReader.release()                                    // no holders — ignored
        assertEquals(0, stops.get())
        assertFalse(AuxReader.active)
    }

    @Test fun reacquireStartsAgain() {
        AuxReader.acquire(); AuxReader.release()
        AuxReader.acquire(); AuxReader.release()
        assertEquals(2, starts.get())
        assertEquals(2, stops.get())
    }
}
