package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The string half of the joined-name fix (2026-08-20).
 *
 * The hashes quoted here were computed from the algorithm in `native/duml_core.cpp`
 * (`name + "_0"`, then `h = ((h << 8) | b) % 0xFFFFFFFB`) and cross-checked against a
 * real controller log: an Air 3 log carries `03:F8` frames at `9d8a8881` for
 * `gps_enable` and at `c2e4c359` for `gps_enable|g_config.gps_cfg.gps_enable`, which is
 * what proves the three spellings are three different addresses. The hash itself is
 * native and not exercised here; this pins the name handling that decides WHICH string
 * gets hashed.
 */
class ParamNameTest {

    private val joined = "gps_enable|g_config.gps_cfg.gps_enable"

    @Test fun plainNameIsItsOwnOnlyPart() {
        assertEquals(listOf("forearm_led_ctrl"), ParamName.parts("forearm_led_ctrl"))
        assertFalse(ParamName.isJoined("forearm_led_ctrl"))
    }

    @Test fun joinedNameSplitsInFirmwareOrder() {
        assertEquals(listOf("gps_enable", "g_config.gps_cfg.gps_enable"), ParamName.parts(joined))
        assertTrue(ParamName.isJoined(joined))
    }

    @Test fun blankPartsAreDropped() {
        // A truncated 03:E1 name can end on the separator; that is not an empty alias.
        assertEquals(listOf("gps_enable"), ParamName.parts("gps_enable|"))
        assertEquals(listOf("a", "b"), ParamName.parts(" a | b "))
        assertEquals(emptyList<String>(), ParamName.parts("   "))
    }

    @Test fun plainNameCostsExactlyOneCandidate() {
        // The Lito X1 path must not grow: one candidate means one ask per window, which is
        // byte-for-byte what it sent before.
        assertEquals(listOf("fswitch_selection"), ParamName.candidates("fswitch_selection"))
    }

    @Test fun joinedNameYieldsJoinedFormFirstThenParts() {
        assertEquals(
            listOf(joined, "gps_enable", "g_config.gps_cfg.gps_enable"),
            ParamName.candidates(joined)
        )
    }

    /** No spelling is asked for twice — a duplicate would be a wasted request in every
     *  window for the rest of the session. The joined form still counts as its own
     *  spelling here, because `x|x` hashes to a different address than `x` does. */
    @Test fun candidatesAreNotRepeated() {
        assertEquals(listOf("x|x", "x"), ParamName.candidates("x|x"))
        assertEquals(listOf("a|b|a", "a", "b"), ParamName.candidates("a|b|a"))
    }

    @Test fun emptyNameHasNoCandidates() {
        assertEquals(emptyList<String>(), ParamName.candidates("  "))
    }

    /** The check that used to throw away every good `03:F7` reply on an Air 3: the board
     *  answers with its canonical joined name, which is not the string that was asked. */
    @Test fun boardsCanonicalNameMatchesTheAliasWeAskedFor() {
        assertTrue(ParamName.sameParam(joined, "gps_enable"))
        assertTrue(ParamName.sameParam("gps_enable", joined))
        assertTrue(ParamName.sameParam(joined, "g_config.gps_cfg.gps_enable"))
        assertTrue(ParamName.sameParam(joined, joined))
    }

    @Test fun differentParametersDoNotMatch() {
        assertFalse(ParamName.sameParam(joined, "forearm_led_ctrl"))
        assertFalse(ParamName.sameParam("gps_enable", "gps_enable_2"))
        assertFalse(ParamName.sameParam("", "gps_enable"))
    }

    @Test fun twoJoinedNamesMatchOnAnySharedPart() {
        assertTrue(ParamName.sameParam("gps_enable|x.y", "z.w|gps_enable"))
    }

    @Test fun displayPrefersTheQualifiedHalf() {
        assertEquals("g_config.gps_cfg.gps_enable", ParamName.display(joined))
        assertEquals("fswitch_selection", ParamName.display("fswitch_selection"))
    }

    /** [ParameterAddress.key] builds the joined form; a same-name pair must not become
     *  `x|x`, or every read would carry a pointless second ask. */
    @Test fun logicalParameterKeyMatchesTheFirmwareJoinedForm() {
        assertEquals(joined, ParameterAddress.GPS_ENABLE.key)
        assertEquals("forearm_led_ctrl|g_config.misc_cfg.forearm_lamp_ctrl", ParameterAddress.FOREARM_LED.key)
        assertEquals("fswitch_selection", ParameterAddress.FLIGHT_MODE.key)
        assertEquals(1, ParamName.candidates(ParameterAddress.FLIGHT_MODE.key).size)
        assertEquals(3, ParamName.candidates(ParameterAddress.GPS_ENABLE.key).size)
    }
}
