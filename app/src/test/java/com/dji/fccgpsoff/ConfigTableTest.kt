package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoder tests for the Config Table commands. Payloads are either verbatim captures
 * from `doc/config-table-commands.md` or hand-built to isolate one field.
 */
class ConfigTableTest {

    private fun hex(s: String) = DumlWire.hex(s.replace(" ", ""))

    // ---------------------------------------------------------------- 03:F7

    /** The capture recorded in doc/config-table-commands.md for `forearm_led_ctrl`. */
    @Test fun paramInfoDecodesTheCapturedForearmLedFrame() {
        val pl = hex(
            "00" + "0000" + "0100" + "0700" +
                "00000000" + "ff000000" + "ef000000" +
                "666f726561726d5f6c65645f6374726c00"
        )
        val info = ConfigTable.parseParamInfo(pl) as ConfigTable.Info.Ok
        assertEquals("forearm_led_ctrl", info.name)
        assertEquals(0, info.typeId)
        assertEquals("U8", info.typeName)
        assertEquals(1, info.size)
        assertEquals(7, info.attribute)
        assertEquals("0", info.min)
        assertEquals("255", info.max)
        assertEquals("239", info.def)
    }

    /** `c1_regulatory_restriction` on a Lito X1: a one-byte status-3 answer, which is a
     *  real reply meaning "no such parameter" — not silence, and not a decode failure. */
    @Test fun statusThreeIsAnAnswerNotAFailure() {
        val info = ConfigTable.parseParamInfo(byteArrayOf(3))
        assertEquals(ConfigTable.Info.Absent(ConfigTable.ST_NO_SUCH_PARAM), info)
    }

    @Test fun paramInfoRejectsAShortOkPayloadInsteadOfInventingFields() {
        // status 0 but truncated mid-struct: must be null, never a half-decoded Ok
        assertNull(ConfigTable.parseParamInfo(hex("00" + "0000" + "0100" + "0700" + "00000000")))
    }

    // ---------------------------------------------------------------- the trap

    /**
     * F7 orders its three limits min, max, def. E1 orders them def, min, max. A shared
     * "read three limits" helper would silently swap default and minimum, and on a U8
     * parameter whose min is 0 the result would still look plausible. Both layouts are
     * built here with three DIFFERENT values so a swap cannot hide.
     */
    @Test fun f7AndE1PutTheirLimitsInDifferentOrder() {
        // F7: status | type U8 | size 1 | attr 0 | MIN=1 | MAX=2 | DEF=3 | "p"
        val f7 = hex("00" + "0000" + "0100" + "0000" + "01000000" + "02000000" + "03000000" + "7000")
        val a = ConfigTable.parseParamInfo(f7) as ConfigTable.Info.Ok
        assertEquals("min", "1", a.min)
        assertEquals("max", "2", a.max)
        assertEquals("def", "3", a.def)

        // E1: status | table 0 | index 7 | type U8 | size 1 | DEF=3 | MIN=1 | MAX=2 | "p"
        val e1 = hex("0000" + "0000" + "0700" + "0000" + "0100" + "03000000" + "01000000" + "02000000" + "7000")
        val b = ConfigTable.parseItem(e1)!!
        assertEquals("min", "1", b.min)
        assertEquals("max", "2", b.max)
        assertEquals("def", "3", b.def)
        assertEquals(7, b.index)
        assertEquals("p", b.name)
    }

    // ---------------------------------------------------------------- limit union

    /** The limit field is a fixed 4 bytes, so a narrow SIGNED type must be sign-extended
     *  from its own width — read as unsigned 32-bit, an I16 -999 would print 4294966297. */
    @Test fun signedLimitsSignExtendFromTheDeclaredWidth() {
        // type I16 (5), size 2, min = -999 stored sign-extended into 32 bits
        val pl = hex("00" + "0500" + "0200" + "0000" + "19fcffff" + "e7030000" + "00000000" + "7800")
        val info = ConfigTable.parseParamInfo(pl) as ConfigTable.Info.Ok
        assertEquals("I16", info.typeName)
        assertEquals("-999", info.min)
        assertEquals("999", info.max)
    }

    @Test fun floatLimitsDecodeAsIeee754() {
        // type F32 (8), size 4, min 0.0, max 1000.0 (0x447a0000), def 0.5 (0x3f000000)
        val pl = hex("00" + "0800" + "0400" + "0000" + "00000000" + "00007a44" + "0000003f" + "7800")
        val info = ConfigTable.parseParamInfo(pl) as ConfigTable.Info.Ok
        assertEquals("F32", info.typeName)
        assertEquals("1000.0", info.max)
        assertEquals("0.5", info.def)
    }

    /** A U32 limit uses the full four bytes — `ce_country_type` maxes at 2000. */
    @Test fun wideUnsignedLimitUsesAllFourBytes() {
        val pl = hex("00" + "0200" + "0400" + "0300" + "00000000" + "d0070000" + "00000000" + "7800")
        val info = ConfigTable.parseParamInfo(pl) as ConfigTable.Info.Ok
        assertEquals("U32", info.typeName)
        assertEquals("2000", info.max)
    }

    // ---------------------------------------------------------------- 03:E0

    @Test fun tableAttrDecodesTheCapturedLitoX1Table() {
        // status 0 | table 0 | crc 0x2ae1a5ad | entries 1594
        val a = ConfigTable.parseTableAttr(hex("0000" + "0000" + "ada5e12a" + "3a060000"))!!
        assertEquals(0, a.status)
        assertEquals(0, a.tableNo)
        assertEquals(0x2ae1a5adL, a.entriesCrc)
        assertEquals(1594, a.entriesNum)
    }

    /** Tables 1..5 answer with a two-byte status 9. That must decode, not be dropped. */
    @Test fun tableAttrKeepsANonZeroStatusEvenWhenTheBodyIsAbsent() {
        val a = ConfigTable.parseTableAttr(hex("0900"))!!
        assertEquals(ConfigTable.ST_NO_SUCH_TABLE, a.status)
        assertEquals(0, a.entriesNum)
    }

    // ---------------------------------------------------------------- 03:E1

    /** An empty slot answers `0e00` — two bytes, echoing nothing. It is a normal outcome
     *  for roughly 40% of the 1594 slots, so it must decode as a status, not as garbage. */
    @Test fun emptySlotDecodesAsAStatusNotAnError() {
        val item = ConfigTable.parseItem(hex("0e00"))!!
        assertEquals(ConfigTable.ST_EMPTY_SLOT, item.status)
        assertEquals("", item.name)
    }

    // ---------------------------------------------------------------- 03:FA

    @Test fun resetCarriesTheValueItResetTo() {
        // the captured reply: status 0 | hash 4e9115f3 | new value 0xEF
        val r = ConfigTable.parseReset(hex("00" + "4e9115f3" + "ef"))!!
        assertEquals(0, r.status)
        assertTrue(r.hash.contentEquals(hex("4e9115f3")))
        assertTrue(r.value.contentEquals(byteArrayOf(0xEF.toByte())))
    }

    // ---------------------------------------------------------------- totality

    /**
     * Every parser is fed each truncation of a valid frame. None may throw: these bytes
     * come off a bus shared with DJI Fly, and a decoder that threw would take down the
     * window walk around it.
     */
    @Test fun everyParserSurvivesEveryTruncation() {
        val samples = listOf(
            hex("00" + "0000" + "0100" + "0700" + "00000000" + "ff000000" + "ef000000" + "61620063"),
            hex("0000" + "0000" + "0700" + "0000" + "0100" + "03000000" + "01000000" + "02000000" + "6100"),
            hex("0000" + "0000" + "ada5e12a" + "3a060000"),
            hex("00" + "4e9115f3" + "ef"),
        )
        for (s in samples) {
            for (n in 0..s.size) {
                val cut = s.copyOfRange(0, n)
                ConfigTable.parseParamInfo(cut)
                ConfigTable.parseItem(cut)
                ConfigTable.parseTableAttr(cut)
                ConfigTable.parseReset(cut)
            }
        }
    }

    @Test fun asciizStopsAtNulAndAtNonPrintableBytes() {
        assertEquals("abc", ConfigTable.asciiz(hex("616263006465"), 0))
        assertEquals("ab", ConfigTable.asciiz(hex("6162ff6364"), 0))
        assertEquals("", ConfigTable.asciiz(hex("616263"), 99))
    }
}
