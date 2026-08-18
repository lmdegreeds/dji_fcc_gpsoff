package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Off-device tests for value encode/decode and catalog JSON loading. */
class ParamCatalogTest {

    @Test fun encodeDecimalLittleEndian() {
        assertArrayEquals(byteArrayOf(0x02, 0x01), ParamCatalog.encode("258", 2))   // 0x0102 LE
        assertArrayEquals(byteArrayOf(0xFF.toByte()), ParamCatalog.encode("255", 1))
    }

    @Test fun encodeHexPrefix() {
        assertArrayEquals(byteArrayOf(0x0a, 0xff.toByte()), ParamCatalog.encode("0x0aff", 4))
    }

    @Test fun encodeRejectsOddHexAndGarbage() {
        assertNull(ParamCatalog.encode("0xabc", 2))     // odd nibble count
        assertNull(ParamCatalog.encode("not-a-number", 1))
    }

    @Test fun encodeWidthClampedToOneWhenOutOfRange() {
        assertEquals(1, ParamCatalog.encode("5", 99)!!.size)
    }

    @Test fun decodeLittleEndianToUnsigned() {
        assertEquals("258", ParamCatalog.decode(byteArrayOf(0x02, 0x01)))
        assertEquals("255", ParamCatalog.decode(byteArrayOf(0xFF.toByte())))
    }

    @Test fun loadParsesAndSkipsEmptyNames() {
        val json = """[
            {"name":"ce_regulatory_level","value":"1","type_id":2,"min":"0","max":"255","default":"255"},
            {"name":"","value":"9"},
            {"name":"gps_enable","value":"1"}
        ]"""
        assertEquals(2, ParamCatalog.load(json, "test.dhp"))
        assertEquals("test.dhp", ParamCatalog.sourceName)
    }

    private fun def(min: String = "", max: String = "", typeId: Int = 0) =
        ParamCatalog.Def("p", "0", typeId, min, max, "0")

    @Test fun encodeCheckedUsesReadBackWidth() {
        val r = ParamCatalog.encodeChecked(def(max = "65535"), "258", readWidth = 2)
        assertTrue(r is ParamCatalog.Encoded.Ok)
        assertArrayEquals(byteArrayOf(0x02, 0x01), (r as ParamCatalog.Encoded.Ok).bytes)
    }

    @Test fun encodeCheckedInfersWidthFromMax() {
        // max 65535 → 2 bytes even with no read-back
        val r = ParamCatalog.encodeChecked(def(max = "65535"), "1", readWidth = null)
        assertEquals(2, (r as ParamCatalog.Encoded.Ok).bytes.size)
    }

    @Test fun encodeCheckedRefusesUnknownWidth() {
        // no read-back and no numeric max → refuse, do NOT assume width 1
        assertTrue(ParamCatalog.encodeChecked(def(max = ""), "5", readWidth = null) is ParamCatalog.Encoded.Invalid)
    }

    @Test fun encodeCheckedRangeChecked() {
        assertTrue(ParamCatalog.encodeChecked(def(min = "0", max = "10"), "11", readWidth = 1) is ParamCatalog.Encoded.Invalid)
        assertTrue(ParamCatalog.encodeChecked(def(min = "5", max = "10"), "4", readWidth = 1) is ParamCatalog.Encoded.Invalid)
    }

    @Test fun encodeCheckedOverflowRejected() {
        // 300 doesn't fit one byte
        assertTrue(ParamCatalog.encodeChecked(def(max = "65535"), "300", readWidth = 1) is ParamCatalog.Encoded.Invalid)
    }

    @Test fun encodeCheckedHexWidthMustMatchReadBack() {
        assertTrue(ParamCatalog.encodeChecked(def(), "0x0aff", readWidth = 1) is ParamCatalog.Encoded.Invalid)
        assertTrue(ParamCatalog.encodeChecked(def(), "0x0aff", readWidth = 2) is ParamCatalog.Encoded.Ok)
    }

    @Test fun searchIsCaseInsensitiveAndCapped() {
        ParamCatalog.load("""[{"name":"gps_enable"},{"name":"forearm_led_ctrl"},{"name":"GPS_extra"}]""", "s")
        assertEquals(2, ParamCatalog.search("gps").size)
        assertEquals(3, ParamCatalog.search("").size)
        assertEquals(1, ParamCatalog.search("led").size)
    }
}
