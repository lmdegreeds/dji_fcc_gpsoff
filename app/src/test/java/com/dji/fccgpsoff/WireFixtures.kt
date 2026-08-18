package com.dji.fccgpsoff

/**
 * Test helpers that build 40007 "wrapped" streams — `[55 CC 30 75][len32][inner
 * DUML]` — the way the device does, so [WrappedFrames], [AircraftSerial] and
 * [ParamRead] can be exercised off-device. Inner frames carry **valid CRC8/CRC16**
 * (via [DumlCrc]) so they survive the walker's validation, exactly like a real
 * device frame.
 */
object WireFixtures {

    /** A CRC-valid inner DUML frame with the given fields. */
    fun inner(cmdType: Int, cmdSet: Int, cmdId: Int, payload: ByteArray,
              sender: Int = 3, receiver: Int = 2, seq: Int = 0): ByteArray {
        val total = 11 + payload.size + 2
        val b = ByteArray(total)
        b[0] = 0x55
        b[1] = (total and 0xFF).toByte()
        b[2] = (((total shr 8) and 0x03) or 0x04).toByte()
        b[3] = DumlCrc.crc8(b, 0, 3).toByte()
        b[4] = sender.toByte(); b[5] = receiver.toByte()
        b[6] = (seq and 0xFF).toByte(); b[7] = ((seq shr 8) and 0xFF).toByte()
        b[8] = cmdType.toByte(); b[9] = cmdSet.toByte(); b[10] = cmdId.toByte()
        payload.copyInto(b, 11)
        val c16 = DumlCrc.crc16(b, 0, total - 2)
        b[total - 2] = (c16 and 0xFF).toByte()
        b[total - 1] = ((c16 shr 8) and 0xFF).toByte()
        return b
    }

    /** Wrap one inner frame in the 40007 envelope. */
    fun wrapped(cmdType: Int, cmdSet: Int, cmdId: Int, payload: ByteArray,
                sender: Int = 3, receiver: Int = 2, seq: Int = 0): ByteArray =
        DumlWire.wrap(inner(cmdType, cmdSet, cmdId, payload, sender, receiver, seq))

    /** A 00:51 serial reply payload: status(0) + len16 LE + ASCII serial. */
    fun serialReplyPayload(serial: String): ByteArray {
        val a = serial.toByteArray(Charsets.US_ASCII)
        val b = ByteArray(3 + a.size)
        b[0] = 0
        b[1] = (a.size and 0xFF).toByte()
        b[2] = ((a.size shr 8) and 0xFF).toByte()
        a.copyInto(b, 3)
        return b
    }
}
