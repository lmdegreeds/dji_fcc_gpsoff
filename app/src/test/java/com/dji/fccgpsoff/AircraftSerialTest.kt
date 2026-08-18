package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Test

/** Off-device tests for the pure serial parsing/extraction (no sockets). */
class AircraftSerialTest {

    private val SN = "1581EXAMPLE000000000"           // confirmed live on Lito X1

    @Test fun parseReplyExtractsSerial() {
        assertEquals(SN, AircraftSerial.parseReply(WireFixtures.serialReplyPayload(SN)))
    }

    @Test fun parseReplyNonZeroStatusIsEmpty() {
        val pl = WireFixtures.serialReplyPayload(SN).clone().also { it[0] = 1 }
        assertEquals("", AircraftSerial.parseReply(pl))
    }

    @Test fun parseReplyTruncatedLengthIsEmpty() {
        // declares len far past the buffer
        val pl = byteArrayOf(0, 0xFF.toByte(), 0x00, '1'.code.toByte(), '5'.code.toByte())
        assertEquals("", AircraftSerial.parseReply(pl))
    }

    @Test fun parseReplyToleratesFramingPrefixInsideField() {
        // some firmwares prefix the field with bytes before the serial
        val serialAscii = SN.toByteArray(Charsets.US_ASCII)
        val field = byteArrayOf(0x00, 0x16, 0x20, 0x08) + serialAscii
        val pl = byteArrayOf(0, (field.size and 0xFF).toByte(), (field.size shr 8).toByte()) + field
        assertEquals(SN, AircraftSerial.parseReply(pl))
    }

    @Test fun sniffMatchesOn0051Reply() {
        assertEquals(SN, AircraftSerial.sniff(0x00, 0x51, WireFixtures.serialReplyPayload(SN)))
    }

    @Test fun sniffRegexCatchesBroadcastPayload() {
        // 51:14 broadcast: not a 00:51 reply, but the ASCII serial is in the bytes
        val payload = byteArrayOf(0x0a, 0x0b) + SN.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x0c)
        assertEquals(SN, AircraftSerial.sniff(0x51, 0x14, payload))
    }

    @Test fun sniffNoSerialIsEmpty() {
        assertEquals("", AircraftSerial.sniff(0x03, 0x44, byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun extractFromWrapped0051Response() {
        val stream = WireFixtures.wrapped(0x80, 0x00, 0x51, WireFixtures.serialReplyPayload(SN))
        assertEquals(SN, AircraftSerial.extract(stream))
    }

    @Test fun extractFallsBackToRegexForBroadcast() {
        // a 51:14 frame in the stream (predicate misses it) — regex fallback finds it
        val stream = WireFixtures.wrapped(0x80, 0x51, 0x14, SN.toByteArray(Charsets.US_ASCII))
        assertEquals(SN, AircraftSerial.extract(stream))
    }

    @Test fun extractEmptyWhenAbsent() {
        val stream = WireFixtures.wrapped(0x80, 0x00, 0x51, byteArrayOf(0, 2, 0, 'A'.code.toByte(), 'B'.code.toByte()))
        assertEquals("", AircraftSerial.extract(stream))
    }

    // --- response-only (live) extraction: must ignore the sticky 51:14 cache ---

    @Test fun responseOnlyAcceptsGenuine0051Reply() {
        val stream = WireFixtures.wrapped(0x80, 0x00, 0x51, WireFixtures.serialReplyPayload(SN))
        assertEquals(SN, AircraftSerial.extractResponseOnly(stream))
    }

    @Test fun responseOnlyIgnores5114Broadcast() {
        // The controller keeps broadcasting 51:14 with the serial after power-off.
        val stream = WireFixtures.wrapped(0x80, 0x51, 0x14, SN.toByteArray(Charsets.US_ASCII))
        assertEquals("", AircraftSerial.extractResponseOnly(stream))   // no false positive…
        assertEquals(SN, AircraftSerial.extract(stream))               // …but lenient extract still finds it
    }

    @Test fun responseOnlyIgnoresNonResponse0051() {
        // A 00:51 REQUEST (no response bit) carrying serial-shaped bytes must not count.
        val stream = WireFixtures.wrapped(0x40, 0x00, 0x51, WireFixtures.serialReplyPayload(SN))
        assertEquals("", AircraftSerial.extractResponseOnly(stream))
    }
}
