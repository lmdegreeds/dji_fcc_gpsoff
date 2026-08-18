package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Off-device tests for the model catalog: table integrity, flag decoding,
 *  code normalization, and DJI-Fly-screen name resolution. */
class AircraftModelCatalogTest {

    @Test fun tableHasAllRows() {
        assertEquals(101, AircraftModelCatalog.ALL.size)
        assertEquals(70, AircraftModelCatalog.ALL.count { it.kind == AircraftModelCatalog.Kind.AIRCRAFT })
        assertEquals(27, AircraftModelCatalog.ALL.count { it.kind == AircraftModelCatalog.Kind.REMOTE })
        assertEquals(4, AircraftModelCatalog.ALL.count { it.kind == AircraftModelCatalog.Kind.PROBE })
    }

    @Test fun codesAreUnique() {
        assertEquals(AircraftModelCatalog.ALL.size, AircraftModelCatalog.byCodeMap.size)
    }

    @Test fun flagsDecodeToCapabilities() {
        // 240 = AOA only (most drones)
        AircraftModelCatalog.byCode("wm260")!!.let {
            assertEquals("MAVIC 3", it.name)
            assertTrue(it.allowAOA)
            assertFalse(it.canUseVPN)
            assertFalse(it.hasEndpoints)
        }
        // 48 = VPN-only (Mini 4/5 Pro, Lito) — the case one fixed fcc.json misses
        AircraftModelCatalog.byCode("wa151")!!.let {
            assertEquals("DJI Lito X1", it.name)
            assertFalse(it.allowAOA)
            assertTrue(it.canUseVPN)
        }
        // 224 = AOA + special endpoints (Mavic 3 Classic/Pro, Phantom 4, Inspire)
        AircraftModelCatalog.byCode("wm2605")!!.let {
            assertTrue(it.allowAOA)
            assertTrue(it.hasEndpoints)
        }
        // 112 = AOA + VPN (NEO, FLIP, RC 2)
        AircraftModelCatalog.byCode("wa521")!!.let {
            assertTrue(it.allowAOA)
            assertTrue(it.canUseVPN)
        }
    }

    @Test fun normalizeHandlesFcAndCodes() {
        assertEquals("wm220", AircraftModelCatalog.normalize("fc220"))   // FLYC "fcNNN" -> wm
        assertEquals("wm310", AircraftModelCatalog.normalize("FC6310"))  // last 3 digits
        assertEquals("wm260", AircraftModelCatalog.normalize("WM260"))   // already a code
        assertEquals("wa151", AircraftModelCatalog.normalize("  wa151 "))
        assertEquals("", AircraftModelCatalog.normalize(null))
    }

    @Test fun fromVersionFieldSpecialCases() {
        assertEquals("wm330", AircraftModelCatalog.fromVersionField("ver.a"))
        assertEquals("wm330", AircraftModelCatalog.fromVersionField("DJI P1 S something"))
        assertEquals("wm260", AircraftModelCatalog.fromVersionField("wm260 01.00.0000"))
        assertNull(AircraftModelCatalog.fromVersionField("   "))
    }

    @Test fun findOnScreenMatchesCommercialNames() {
        assertEquals("wa234", AircraftModelCatalog.findOnScreen(listOf("DJI Air 3S"))?.code)
        assertEquals("wa233", AircraftModelCatalog.findOnScreen(listOf("DJI Air 3"))?.code)
        assertEquals("wa140", AircraftModelCatalog.findOnScreen(listOf("DJI Mini 4 Pro"))?.code)
    }

    @Test fun findOnScreenPrefersMoreSpecificName() {
        // "Mavic 3 Pro" must beat the shorter "Mavic 3" prefix.
        assertEquals("wm261", AircraftModelCatalog.findOnScreen(listOf("DJI Mavic 3 Pro"))?.code)
        assertEquals("wm260", AircraftModelCatalog.findOnScreen(listOf("DJI Mavic 3"))?.code)
    }

    @Test fun findOnScreenMatchesExplicitCode() {
        assertEquals("wm260", AircraftModelCatalog.findOnScreen(listOf("model WM260"))?.code)
        assertEquals("wm1605", AircraftModelCatalog.findOnScreen(listOf("wm1605"))?.code)  // 4-digit token path
    }

    @Test fun findOnScreenUsesAlias() {
        assertEquals("wm265e", AircraftModelCatalog.findOnScreen(listOf("DJI Mavic 3E"))?.code)
    }

    @Test fun findOnScreenReturnsNullForNoise() {
        assertNull(AircraftModelCatalog.findOnScreen(listOf("Settings", "Battery 87%", "GPS 12")))
    }

    @Test fun findOnScreenIgnoresMultiModelPicker() {
        // DJI Fly's "select drone model" list names many models at once — picking
        // one (e.g. NEO) would mislabel whatever is actually connected.
        assertNull(AircraftModelCatalog.findOnScreen(
            listOf("NEO", "DJI Air 3", "Mini 4 Pro", "DJI Lito X1")))
    }

    @Test fun findOnScreenMatchesSingleModelAmongNoise() {
        assertEquals("wa521", AircraftModelCatalog.findOnScreen(
            listOf("Settings", "NEO", "Battery 87%", "GPS 12"))?.code)
    }

    @Test fun wordBoundaryStopsFalseMatch() {
        // "AIR 3" inside "AIR 3S" must not resolve to wa233.
        assertEquals("wa234", AircraftModelCatalog.findOnScreen(listOf("Air 3S"))?.code)
    }
}
