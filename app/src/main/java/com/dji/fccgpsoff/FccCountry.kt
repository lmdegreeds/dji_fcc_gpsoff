package com.dji.fccgpsoff

/**
 * Read-only radio-country probe (07:19) — the cheap "is FCC still on?" question
 * that makes a keepalive loop nearly free (ported from Skylab's FccCountryRegion).
 *
 * fcc.json writes the country as two ASCII letters (the 41 55 = "AU" inside the
 * 07:30 channel groups), so a controller that still answers the code we wrote has
 * not been pushed back to CE and needs no re-write. One short frame per tick
 * instead of replaying 21 frames x 2 rounds.
 *
 * Which code that is is the user's choice since 2026-08-19 — see [FccRegion] —
 * so the expected answer is [target], not a constant.
 *
 * A null is "no answer", NOT "not [target]": injected reads do not always route back
 * on RC2 (see [ParameterAddress.read]), so callers must treat the two apart.
 */
object FccCountry {

    /** What the FCC apply currently writes — the selected [FccRegion]'s code. */
    val target: String get() = AppState.fccRegion.code

    private const val SENDER = 0x2A     // Skylab's country-query sender index
    private const val DST = 0x09        // SDR / radio
    private const val CMDSET = 0x07
    private const val CMDID = 0x19
    private const val READ_MS = 500

    /** One 07:19 query on 40009. Null = no reply, or our own port lock is busy. */
    suspend fun read(): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val lease = PortSessionLock.acquire(DumlWire.PORT_FCC, timeoutMs = 1_000) ?: return@withContext null
        try {
            val wire = DumlNative.nativeBuildFrame(SENDER, DST, DumlWire.CT_ACK, CMDSET, CMDID, ByteArray(0))
            // Match the reply by command (07:19) — otherwise the first telemetry
            // frame on 40009 could be misread as a country and trigger a needless
            // FCC replay in the keepalive loop.
            parse(DumlBus.sendOnceMatch(DumlWire.PORT_FCC, wire, READ_MS, CMDSET, CMDID, "07:19 country?"))
        } finally { lease.close() }
    }

    /** Two uppercase ASCII letters, after the optional 0x00 status byte. */
    fun parse(payload: ByteArray?): String? {
        if (payload == null) return null
        val off = if (payload.size >= 3 && payload[0] == 0x00.toByte()) 1 else 0
        if (payload.size < off + 2) return null
        val a = payload[off].toInt() and 0xFF
        val b = payload[off + 1].toInt() and 0xFF
        if (a !in 65..90 || b !in 65..90) return null
        return String(byteArrayOf(a.toByte(), b.toByte()), Charsets.US_ASCII)
    }
}
