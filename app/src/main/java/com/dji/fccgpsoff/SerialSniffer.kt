package com.dji.fccgpsoff

/**
 * Passive aircraft-serial watcher fed by the hijack-read stream.
 *
 * Every frame the transport delivers (main channel + the aux/hijack reader on
 * DJI Fly's port) is offered here; when one carries the serial — the 00:51
 * reply or the 51:14 broadcast DJI Fly pulls over 40007 — it is cached with a
 * timestamp. Nothing is injected: the serial is caught only when it flows past
 * on its own, so it appears once the aux/hijack capture is running (that is the
 * reader that sees 40007). The served page polls [statusJson] every ~10 s.
 *
 * [offer] runs on the native RX thread — kept allocation-light and non-throwing.
 */
object SerialSniffer {

    @Volatile var serial: String = ""; private set
    @Volatile private var lastSeen = 0L
    @Volatile private var route = -1

    /** Feed one delivered frame; updates the cache if it carries a serial. */
    fun offer(cmdSet: Int, cmdId: Int, payload: ByteArray, route: Int) {
        val hit = runCatching { AircraftSerial.sniff(cmdSet, cmdId, payload) }.getOrDefault("")
        if (hit.isEmpty()) return
        val isNew = hit != serial
        serial = hit
        lastSeen = System.currentTimeMillis()
        this.route = route
        if (isNew) {
            DiagLog.info("aircraft serial (passive): $hit [${if (route == 1) "aux" else "main"}]")
            AircraftSession.onSerial(hit)      // wipe stale per-drone state if the aircraft changed
        }
    }

    /** `{"serial":..,"ageMs":..,"route":0|1}` — serial "" and ageMs -1 if never seen. */
    fun statusJson(): String {
        val s = serial   // serial chars are [0-9A-Z]: no JSON escaping needed
        if (s.isEmpty()) return "{\"serial\":\"\",\"ageMs\":-1,\"route\":-1}"
        val age = System.currentTimeMillis() - lastSeen
        return "{\"serial\":\"$s\",\"ageMs\":$age,\"route\":$route}"
    }
}
