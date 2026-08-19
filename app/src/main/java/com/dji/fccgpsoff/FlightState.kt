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

    /**
     * One of the three values this object holds, so a caller can read them ONE AT
     * A TIME and keep going until each has actually answered.
     *
     * Added 2026-08-19 with the "Read state" fix: [refresh] reads all three in one
     * pass and gives up on whichever did not answer, which is what left the panel
     * sitting on "reading…" with nothing behind it. The UI now retries per item,
     * and per item it needs to know what is still missing.
     */
    enum class Item(val label: String) {
        LED("LED"), GPS("GPS"), MODE("mode");

        fun paramName(): String = when (this) {
            LED -> ParameterAddress.FOREARM_LED.name()
            GPS -> ParameterAddress.GPS_ENABLE.name()
            MODE -> ParameterAddress.FLIGHT_MODE.name()
        }
    }

    /** Which of the three have never answered — what a "keep reading" loop still owes. */
    fun missing(): List<Item> = Item.values().filter {
        when (it) { Item.LED -> ledOn == null; Item.GPS -> gpsOn == null; Item.MODE -> cine == null }
    }

    /**
     * Read exactly ONE value. Returns true when it answered.
     *
     * Same accounting as [refresh] for the value that did answer (it also proves a
     * drone is on the link), but a silent read here is NOT recorded as
     * `connected = false`: this is a per-item retry, and one unanswered item among
     * three says nothing about the link. Only [refresh], which asks for all three,
     * is entitled to that verdict.
     */
    suspend fun refreshOne(item: Item): Boolean {
        if (!ForegroundGate.readsAllowed()) return false
        val v = ParamRead.read(item.paramName()) ?: return false
        when (item) {
            Item.LED -> ledOn = valueOn(v)
            Item.GPS -> gpsOn = valueOn(v)
            Item.MODE -> if (v.isEmpty()) return false
                         else cine = (v[0].toInt() and 0xFF) == (ParameterAddress.MODE_CINE.toInt() and 0xFF)
        }
        readsWork = true; connected = true; probed = true
        lastMs = System.currentTimeMillis()
        return true
    }

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

    /**
     * Record that a full read round asked for every value and got nothing back while
     * the read gate stayed open — which is the same verdict [refresh] reaches, and
     * the only honest one: the flight controller answers a hash read whenever a drone
     * is linked, so total silence means no drone on the link.
     *
     * Separate from [refreshOne] on purpose: one silent item among three says nothing
     * about the link, so only a caller that asked for ALL of them may conclude this.
     */
    fun markSilent() {
        connected = false; probed = true
        lastMs = System.currentTimeMillis()
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
