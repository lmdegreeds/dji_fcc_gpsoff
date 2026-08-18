package com.dji.fccgpsoff

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Off-device tests for the pure profile JSON parse (split out of load()). */
class ProfileRunnerTest {

    @Test fun parsesFramesAndDefaults() {
        val json = """{
            "sender": 130, "cmd_type": 32, "rounds": 2, "port": 40009,
            "frames": [
                {"s": 22, "i": 136, "d": 18, "p": "030100", "note": "enter"},
                {"s": 9, "i": 39, "d": 9, "p": "00024800ffff0200000000"}
            ]
        }"""
        val p = ProfileRunner.parse(json, "fcc.json")
        assertEquals(40009, p.port)
        assertEquals(2, p.rounds)
        assertEquals(2, p.frames.size)
        assertEquals(22, p.frames[0].cmdSet)
        assertEquals(136, p.frames[0].cmdId)
        assertEquals(18, p.frames[0].dst)
        assertArrayEquals(byteArrayOf(0x03, 0x01, 0x00), p.frames[0].payload)
        // top-level sender / cmd_type flow into each frame
        assertEquals(130, p.frames[1].sender)
        assertEquals(32, p.frames[1].cmdType)
    }

    @Test fun defaultsWhenOmitted() {
        val p = ProfileRunner.parse("""{"frames":[{"s":3,"i":249,"d":3}]}""", "x.json")
        assertEquals(1, p.rounds)               // default
        assertEquals(DumlWire.PORT_FCC, p.port) // default
        assertEquals(0, p.frames[0].payload.size)
    }

    @Test fun fieldOutOfRangeThrowsWithFrameIndex() {
        val json = """{"frames":[{"s":3,"i":249,"d":3},{"s":256,"i":1,"d":1}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ProfileRunner.parse(json, "x.json") }
        assertTrue(e.message!!.contains("frame 1"))
        assertTrue(e.message!!.contains("out of range"))
    }

    @Test fun badHexPayloadThrowsWithContext() {
        val json = """{"frames":[{"s":3,"i":249,"d":3,"p":"0a1"}]}"""
        val e = assertThrows(IllegalArgumentException::class.java) { ProfileRunner.parse(json, "x.json") }
        assertTrue(e.message!!.contains("bad payload"))
    }

    @Test fun missingFramesArrayThrows() {
        assertThrows(Exception::class.java) { ProfileRunner.parse("""{"name":"x"}""", "x.json") }
    }
}
