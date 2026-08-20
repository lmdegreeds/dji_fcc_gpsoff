package com.dji.fccgpsoff

/**
 * Read-only probes for on-controller debugging.
 *
 * Caveat: on RC2 the proxy routes replies to the session owner (DJI Fly), so an
 * injected read normally returns nothing. "no answer" means *no route back*, not
 * "parameter absent" — use it to confirm the name/hash we send, not the value.
 */
object Diagnostics {

    /**
     * Read a logical parameter and report it + the value.
     *
     * Reads by the logical KEY, not by the profile's guess at the spelling: [ParamRead]
     * puts every address the parameter can have into one window, so this says what the
     * aircraft answers to rather than what a stored profile assumed (2026-08-20).
     */
    suspend fun readKnown(label: String, addr: ParameterAddress): String {
        if (!ForegroundGate.readsAllowed())
            return "$label: not asked — " + (ForegroundGate.blockReason() ?: "read gate closed")
        // ONE window per parameter, not the default three: this endpoint reads four of them
        // in a row on 40007, which is DJI Fly's video mirror, and a diagnostic must not cost
        // twelve windows there. A single miss is reported as a miss.
        val asked = ParamAlias.known(addr.key)?.let { listOf(it) } ?: ParamAlias.order(addr.key)
        val v = ParamRead.read(addr.key, attempts = 1)
        val n = addr.name()
        return if (v == null)
            "$label: no answer in one window · asked " + asked.joinToString(", ") { ParamName.tag(it) } +
                " · a write would go to ${ParamName.tag(n)}"
        else "$label (${ParamName.tag(n)}" +
            (if (addr.nameIsMeasured()) ", measured" else ", from the profile") + "): ${DumlWire.toHex(v)}"
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
