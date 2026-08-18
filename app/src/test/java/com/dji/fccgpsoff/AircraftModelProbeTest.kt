package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Off-device tests for the pure VersionInquiry (00:01) reply parsing. */
class AircraftModelProbeTest {

    @Test fun resolvesCodeFromOffset16Field() {
        val pl = ByteArray(16) + "wm260 01.00.0000".toByteArray(Charsets.US_ASCII)
        assertEquals("wm260", AircraftModelProbe.parseVersionReply(pl))
    }

    @Test fun resolvesCodeFromAsciiRunFallback() {
        val pl = "fw wm260 build".toByteArray(Charsets.US_ASCII)
        assertEquals("wm260", AircraftModelProbe.parseVersionReply(pl))
    }

    @Test fun emptyIsNull() {
        assertNull(AircraftModelProbe.parseVersionReply(ByteArray(0)))
    }

    @Test fun noKnownCodeIsNull() {
        assertNull(AircraftModelProbe.parseVersionReply("no model here at all".toByteArray()))
    }
}
