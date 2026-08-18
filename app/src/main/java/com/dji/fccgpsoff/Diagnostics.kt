package com.dji.fccgpsoff

/**
 * Read-only probes for on-controller debugging.
 *
 * Caveat: on RC2 the proxy routes replies to the session owner (DJI Fly), so an
 * injected read normally returns nothing. "no answer" means *no route back*, not
 * "parameter absent" — use it to confirm the name/hash we send, not the value.
 */
object Diagnostics {

    /** Read the profile-selected name of a logical parameter and report it + the value. */
    suspend fun readKnown(label: String, addr: ParameterAddress): String {
        val n = addr.name()
        val v = addr.read()
        return if (v == null) "$label: no answer (sent $n, hash ${DumlWire.toHex(DumlNative.nativeParamHash(n))})"
               else "$label ($n): ${DumlWire.toHex(v)}"
    }

    /** Read an arbitrary parameter by exact name (hash computed at runtime). */
    suspend fun readByName(name: String): String {
        val v = ParameterAddress(name).read()
        return if (v == null) "$name: no answer" else "$name = ${DumlWire.toHex(v)} (hash ${DumlWire.toHex(DumlNative.nativeParamHash(name))})"
    }

    suspend fun readAllKnown(): String = buildString {
        appendLine(readKnown("regulatory", ParameterAddress.REGULATORY))
        appendLine(readKnown("gps_enable", ParameterAddress.GPS_ENABLE))
        appendLine(readKnown("forearm_led", ParameterAddress.FOREARM_LED))
        append(readKnown("sdr_lost_never", ParameterAddress.SDR_LOST_NEVER))
    }
}
