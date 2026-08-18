package com.dji.fccgpsoff

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One-shot startup detection, run while OUR app owns the foreground so reads are
 * allowed. Order:
 *   1. Serial via the active 00:51 query ([AircraftSerial]).
 *   2. If the serial is already known, restore its model + name-variant from
 *      [DeviceStore] instantly (no re-probe).
 *   3. Otherwise probe which parameter-name variant the drone answers to (Lito
 *      short names vs the g_config.* forms) by a hash-matched read, and set
 *      [AppState.litoMode] accordingly.
 *   4. Persist everything keyed by the serial, and read the live LED/GPS/mode.
 *
 * [readsFailed] is true when the drone is present (serial seen) yet NO parameter
 * read ever answered — the UI uses it to disable read-dependent features and say
 * so, per the app's honesty rule.
 */
object StartupProbe {

    private val running = AtomicBoolean(false)
    @Volatile var readsFailed = false; private set
    @Volatile var serial = ""; private set
    @Volatile var variant: Boolean? = null; private set     // true = Lito names

    /** Clear per-drone probe results so the next [run] re-detects them for a newly
     *  connected aircraft (called by [AircraftSession] on a serial change). */
    fun resetForNewDrone() { readsFailed = false; variant = null; serial = "" }

    suspend fun run(ctx: Context) {
        if (!running.compareAndSet(false, true)) return   // atomic guard against concurrent probes
        try {
            serial = AircraftSerial.read()
            AircraftSession.onSerial(serial)              // reset cached state if this is a different drone
            val known = if (serial.isNotEmpty()) DeviceStore.variant(ctx, serial) else null

            // Restore a cached model so it shows immediately, even before Fly is opened.
            if (serial.isNotEmpty()) DeviceStore.model(ctx, serial)?.let { (code, name) ->
                AircraftIdentity.publish(code, name, AircraftIdentity.Source.CACHE)
            }

            // Precedence: a manual choice the user made for THIS aircraft outranks anything
            // a probe can conclude. Before this check the cached value was restored on every
            // run and pushed into AppState, so a manual flip was undone by the next probe
            // without a word.
            val manual = serial.isNotEmpty() && DeviceStore.variantIsManual(ctx, serial)
            variant = if (manual) known else known ?: detectVariant()
            variant?.let { AppState.setLito(ctx, it) }
            if (manual) DiagLog.info("name-variant kept as the manual choice for $serial")

            // If the drone is here but nothing answered, mark reads as unavailable.
            readsFailed = serial.isNotEmpty() && variant == null && !FlightState.readsWork

            if (variant != null || FlightState.readsWork) FlightState.refresh()

            if (serial.isNotEmpty()) DeviceStore.save(
                ctx, serial,
                AircraftIdentity.drone.code.ifEmpty { null },
                AircraftIdentity.drone.name.ifEmpty { null },
                variant
            )
        } catch (e: Exception) {
            DiagLog.err("startup probe: ${e.message}")
        } finally { running.set(false) }
    }

    /**
     * Which name-variant does the drone answer to? true = Lito names, false = the
     * `g_config.*` forms, null = undecided (and then the current profile is left alone).
     *
     * Uses `03:F7` rather than a plain read. Both answer at about the same rate, but F7's
     * reply carries the parameter's canonical NAME, so a positive is self-verifying, and
     * its status 3 is an explicit "no such parameter" — a real answer, where a read can
     * only ever fail to arrive.
     *
     * Budget: measured on a Lito X1 v400 with DJI Fly backgrounded, a single 1500 ms window
     * answers about 70% of the time, so [DETECT_ATTEMPTS] windows put a positive at ~99.9%.
     * The previous 3 × 900 ms was *smaller* than a budget that had already produced a false
     * "absent" during protocol testing.
     *
     * The currently-selected profile is probed first, so the common case ("already correct")
     * settles in one or two windows instead of exhausting the wrong candidate. Detection is
     * skipped entirely when no aircraft is around — it holds a socket on DJI Fly's video
     * port and there is nothing to learn.
     */
    private suspend fun detectVariant(): Boolean? {
        if (serial.isEmpty() && !FlightState.readsWork) return null
        val names = ParameterAddress.FOREARM_LED.names
        if (names.size < 2) return null
        val order = if (AppState.litoMode) listOf(0, 1) else listOf(1, 0)
        var confirmedAbsent: Int? = null
        for (i in order) {
            when (val info = ParamMeta.info(names[i], attempts = DETECT_ATTEMPTS)) {
                is ConfigTable.Info.Ok -> {
                    DiagLog.info("name-variant: '${info.name}' confirmed by 03:F7 → " +
                        if (i == 0) "Lito names" else "g_config.* names")
                    return i == 0
                }
                is ConfigTable.Info.Absent -> confirmedAbsent = i
                null -> {}                       // no route back — not evidence either way
            }
        }
        // No positive. A CONFIRMED absence still rules one spelling out, and the pair is
        // exhaustive by construction, so switching beats keeping a profile the aircraft has
        // just told us is wrong — every write under it would be a silent no-op.
        confirmedAbsent?.let {
            val other = if (it == 0) false else true
            DiagLog.warn("name-variant: '${names[it]}' does not exist on this aircraft; " +
                "switching to '${names[if (it == 0) 1 else 0]}' on that negative alone")
            return other
        }
        return null
    }

    /** ~99.9% chance of a positive at the measured 70%-per-window answer rate. */
    private const val DETECT_ATTEMPTS = 6

    /** Persist the currently-known model against the CURRENT session serial
     *  (called when the model becomes known live, e.g. from DJI Fly's screen).
     *  Keying off [AircraftSession.serial] — not a possibly-stale local — makes it
     *  impossible to save a drone's model under a different drone's serial. */
    fun rememberModel(ctx: Context) {
        val s = AircraftSession.serial.ifEmpty { SerialSniffer.serial }
        if (s.isNotEmpty() && AircraftIdentity.drone.code.isNotEmpty())
            DeviceStore.save(ctx, s, AircraftIdentity.drone.code, AircraftIdentity.drone.name, variant)
    }
}
