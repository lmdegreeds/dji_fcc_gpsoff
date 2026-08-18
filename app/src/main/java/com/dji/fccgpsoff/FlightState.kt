package com.dji.fccgpsoff

/**
 * Live LED / GPS / flight-mode state, read back by hash over the 40007 window
 * ([ParamRead]) whenever OUR app owns the foreground (so we never touch 40007
 * while DJI Fly is on screen). Reads are racy, so each value is only replaced
 * when a read actually answers; a value stays null until first seen.
 *
 * [readsWork] latches true once any param answers, and is what the UI uses to
 * decide whether to show live state or fall back to "write-only" (blind) — if no
 * read ever answers, the state panel is disabled with a note, per the app's
 * honesty rule.
 */
object FlightState {

    @Volatile var ledOn: Boolean? = null; private set
    @Volatile var gpsOn: Boolean? = null; private set
    @Volatile var cine: Boolean? = null; private set        // true = Cine(12), false = ATTI(3)
    @Volatile var readsWork = false; private set
    @Volatile var probed = false; private set               // a refresh has completed at least once
    @Volatile var lastMs = 0L; private set
    /** The flight controller answers a hash read ONLY when a drone is linked, so
     *  this is our "drone connected" signal: true = answered this cycle, false =
     *  probed but silent (no drone / link down), null = not probed (gate closed). */
    @Volatile var connected: Boolean? = null; private set

    /** Read LED/GPS/mode, each with ParamRead's own retries — the reliable path (the
     *  FC's replies on 40007 are racy, so per-param retries matter). Reads abort the
     *  instant DJI Fly takes the foreground (ParamRead's strict gate). `connected` =
     *  "a FLYC read answered" ⇒ a drone is on the link (the FC only replies when linked). */
    suspend fun refresh(): Boolean {
        if (!ForegroundGate.readsAllowed()) return false
        var any = false
        ParamRead.read(ParameterAddress.FOREARM_LED.name())?.let { ledOn = valueOn(it); readsWork = true; any = true }
        ParamRead.read(ParameterAddress.GPS_ENABLE.name())?.let { gpsOn = valueOn(it); readsWork = true; any = true }
        ParamRead.read(ParameterAddress.FLIGHT_MODE.name())?.let {
            if (it.isNotEmpty()) { cine = (it[0].toInt() and 0xFF) == (ParameterAddress.MODE_CINE.toInt() and 0xFF); readsWork = true; any = true }
        }
        connected = any
        probed = true
        lastMs = System.currentTimeMillis()
        return any
    }

    /** Drop all live state — called when the linked aircraft changes so readings
     *  from the previous drone don't linger on the new one. */
    fun reset() {
        ledOn = null; gpsOn = null; cine = null
        readsWork = false; probed = false; connected = null; lastMs = 0L
    }

    private fun valueOn(v: ByteArray): Boolean = v.isNotEmpty() && (v[0].toInt() and 0xFF) != 0

    fun statusJson(): String =
        "{\"led\":${jb(ledOn)},\"gps\":${jb(gpsOn)},\"cine\":${jb(cine)},\"connected\":${jb(connected)}," +
            "\"readsWork\":$readsWork,\"probed\":$probed,\"ageMs\":${if (lastMs == 0L) -1 else System.currentTimeMillis() - lastMs}}"

    private fun jb(b: Boolean?): String = b?.toString() ?: "null"
}
