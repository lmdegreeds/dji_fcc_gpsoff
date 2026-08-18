package com.dji.fccgpsoff

/**
 * The identity of the aircraft currently on the link, keyed by its factory
 * serial, with a monotonic [epoch] that bumps every time the serial changes.
 *
 * Why this exists: serial, model identity, name-variant and live [FlightState]
 * lived in independent singletons that were never reset. Plugging in a *different*
 * drone would keep the previous one's model, variant and LED/GPS readings — and
 * even persist the old model under the new serial. This is the single choke point
 * that notices "the serial changed" and wipes everything per-drone so nothing
 * leaks across a swap.
 */
object AircraftSession {

    @Volatile var serial: String = ""; private set
    /** Bumped on every aircraft change; callers can tag data with the epoch it
     *  belongs to and discard anything stale. */
    @Volatile var epoch: Int = 0; private set

    /**
     * Report a freshly-observed serial (from the active probe or the passive
     * sniffer). The first serial just adopts it; a *different* serial means a new
     * aircraft — clear cached identity, live state and the probed name-variant so
     * they are re-established for the new drone.
     */
    @Synchronized fun onSerial(newSerial: String) {
        if (newSerial.isBlank() || newSerial == serial) return
        val hadPrevious = serial.isNotEmpty()
        serial = newSerial
        epoch++
        if (hadPrevious) {
            DiagLog.info("aircraft changed → $newSerial (epoch $epoch): clearing cached identity + live state")
            AircraftIdentity.drone.clear()
            FlightState.reset()
            StartupProbe.resetForNewDrone()
            ParamMeta.clear()      // metadata is per-firmware; a different aircraft invalidates it
        }
    }
}
