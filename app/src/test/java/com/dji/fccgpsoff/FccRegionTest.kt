package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Off-device tests for the radio-country substitution the FCC apply runs through.
 *
 * The country is the whole switch (`doc/fcc-minimal-sequence.md`), so getting the
 * two ASCII fields of the 07:30 payload right — and touching nothing else — is
 * what makes a region choice safe rather than a way to corrupt a frame that was
 * measured on hardware.
 */
class FccRegionTest {

    /** The shipped `fcc.json`, verbatim in shape: the AU channel-group frame plus
     *  the SDR register write that must come out untouched. */
    private fun shipped(): ProfileRunner.Profile = ProfileRunner.parse("""{
        "name": "FCC", "sender": 130, "cmd_type": 32, "rounds": 8, "port": 40009,
        "frames": [
            {"s": 7, "i": 48, "d": 9, "p": "41550000415500000100", "note": "WIFI channel group AU"},
            {"s": 9, "i": 39, "d": 9, "p": "00024800ffff0200000000", "note": "SDR reg setForceFcc"}
        ]
    }""", "fcc.json")

    @Test fun rewritesBothCountryFieldsAndNothingElse() {
        val p = FccRegion.patch(shipped(), FccRegion.UNITED_STATES)
        // 55 53 = "US" at byte 0 and again at byte 4; the pads and the trailing 01 00 stay.
        assertArrayEquals(
            DumlWire.hex("55530000555300000100"), p.frames[0].payload)
        // The SDR register frame is not a country frame and must be byte-identical.
        assertArrayEquals(
            DumlWire.hex("00024800ffff0200000000"), p.frames[1].payload)
    }

    @Test fun keepsCadenceAndAddressing() {
        val p = FccRegion.patch(shipped(), FccRegion.RUSSIA)
        assertEquals(8, p.rounds)               // eight sends, one second apart — measured
        assertEquals(40009, p.port)
        assertEquals(0x07, p.frames[0].cmdSet)
        assertEquals(0x30, p.frames[0].cmdId)
        assertEquals(9, p.frames[0].dst)
        assertEquals(130, p.frames[0].sender)
        assertEquals(2, p.frames.size)
    }

    /** AU is the code every hardware-confirmed run used, so patching to it must be
     *  a no-op on the shipped payload — not a subtly different frame. */
    @Test fun defaultRegionLeavesTheShippedPayloadAlone() {
        val p = FccRegion.patch(shipped(), FccRegion.DEFAULT)
        assertEquals("AU", FccRegion.DEFAULT.code)
        assertArrayEquals(DumlWire.hex("41550000415500000100"), p.frames[0].payload)
    }

    /** A payload that does not carry ASCII letters where the country belongs is not
     *  the frame we think it is, so it is left alone rather than patched on a guess. */
    @Test fun leavesAnUnexpectedPayloadUntouched() {
        val odd = ProfileRunner.parse(
            """{"frames":[{"s":7,"i":48,"d":9,"p":"0000000000000000"}]}""", "x.json")
        val p = FccRegion.patch(odd, FccRegion.CHINA)
        assertArrayEquals(DumlWire.hex("0000000000000000"), p.frames[0].payload)
    }

    @Test fun patchDoesNotMutateTheLoadedProfile() {
        val src = shipped()
        val before = src.frames[0].payload.copyOf()
        FccRegion.patch(src, FccRegion.MALAYSIA)
        assertArrayEquals(before, src.frames[0].payload)
    }

    @Test fun codeLookupIsCaseInsensitiveAndFallsBackToDefault() {
        assertSame(FccRegion.NETHERLANDS, FccRegion.of("nl"))
        assertSame(FccRegion.BOLIVIA, FccRegion.of(" BO "))
        assertSame(FccRegion.DEFAULT, FccRegion.of("ZZ"))
        assertSame(FccRegion.DEFAULT, FccRegion.of(null))
    }

    /** Every code is exactly the two uppercase ASCII letters the payload has room
     *  for — a longer or lower-case one would silently write the wrong bytes. */
    @Test fun everyCodeIsTwoUppercaseLetters() {
        for (r in FccRegion.values()) {
            assertEquals(r.name, 2, r.code.length)
            assertEquals(r.name, r.code.uppercase(), r.code)
            assertNotEquals(r.name, "", r.label)
        }
    }
}
