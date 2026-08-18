package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Tests for identity-slot routing and source-priority (rank) resolution. */
class AircraftIdentityTest {

    private val aircraft = AircraftModelCatalog.ALL.first { it.kind == AircraftModelCatalog.Kind.AIRCRAFT }
    private val aircraft2 = AircraftModelCatalog.ALL.first { it.kind == AircraftModelCatalog.Kind.AIRCRAFT && it.code != aircraft.code }
    private val remote = AircraftModelCatalog.ALL.first { it.kind == AircraftModelCatalog.Kind.REMOTE }

    @Before fun clear() { AircraftIdentity.drone.clear(); AircraftIdentity.rc.clear() }

    @Test fun routesByKind() {
        assertTrue(AircraftIdentity.publish(aircraft.code, null, AircraftIdentity.Source.DUML))
        assertTrue(AircraftIdentity.publish(remote.code, null, AircraftIdentity.Source.PROP))
        assertEquals(aircraft.code, AircraftIdentity.drone.code)
        assertEquals(remote.code, AircraftIdentity.rc.code)
    }

    @Test fun weakerSourceDoesNotOverwriteStronger() {
        AircraftIdentity.publish(aircraft.code, null, AircraftIdentity.Source.UI)
        // CACHE (rank 0) must not replace a UI (rank 4) drone with a different model
        assertFalse(AircraftIdentity.publish(aircraft2.code, null, AircraftIdentity.Source.CACHE))
        assertEquals(aircraft.code, AircraftIdentity.drone.code)
    }

    @Test fun strongerSourceOverwritesWeaker() {
        AircraftIdentity.publish(aircraft.code, null, AircraftIdentity.Source.PASSIVE)
        assertTrue(AircraftIdentity.publish(aircraft2.code, null, AircraftIdentity.Source.UI))
        assertEquals(aircraft2.code, AircraftIdentity.drone.code)
    }

    @Test fun cacheFillsEmptySlot() {
        assertTrue(AircraftIdentity.publish(aircraft.code, null, AircraftIdentity.Source.CACHE))
        assertEquals(aircraft.code, AircraftIdentity.drone.code)
    }

    @Test fun unknownCodeRejected() {
        assertFalse(AircraftIdentity.publish("zz999", null, AircraftIdentity.Source.UI))
    }
}
