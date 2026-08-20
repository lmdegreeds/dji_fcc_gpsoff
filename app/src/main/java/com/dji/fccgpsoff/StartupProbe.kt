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
 *
 * **Provenance (2026-08-20).** [variant] alone was never enough: the UI rendered a
 * cache hit as "probed", and a user on a DJI Air 3 reasonably read that as "the app
 * asked the aircraft and it said Lito". It had not asked. Every field that says what
 * the profile is now travels with [origin] and [decidedAtMs] saying where it came
 * from, and the same words go into the log at startup, not only when the value
 * changes.
 */
object StartupProbe {

    private val running = AtomicBoolean(false)
    @Volatile var readsFailed = false; private set
    @Volatile var serial = ""; private set
    @Volatile var variant: Boolean? = null; private set     // true = Lito names
    /** How [variant] was arrived at THIS session. Null while [variant] is null. */
    @Volatile var origin: DeviceStore.VariantOrigin? = null; private set
    /** When the stored decision was originally made (0 = this session / unknown). */
    @Volatile var decidedAtMs = 0L; private set
    /** True when [variant] was restored from [DeviceStore] rather than measured now. */
    @Volatile var fromCache = false; private set
    /** True when the aircraft denied EVERY spelling we know for the probe parameter —
     *  the profile switch cannot be right for this aircraft and the user must be told. */
    @Volatile var noKnownSpelling = false; private set
    /** True once a detection round has actually been run this session. */
    @Volatile var probeRan = false; private set
    /** True when the board ANSWERED that round at all — a confirmation or an explicit
     *  denial. Distinct from a verdict: a board can answer "this aircraft indexes the
     *  joined name", which is a positive that leaves [variant] null. */
    @Volatile var probeAnswered = false; private set

    /** Clear per-drone probe results so the next [run] re-detects them for a newly
     *  connected aircraft (called by [AircraftSession] on a serial change). */
    fun resetForNewDrone() {
        readsFailed = false; variant = null; serial = ""
        origin = null; decidedAtMs = 0L; fromCache = false; noKnownSpelling = false
        probeRan = false; probeAnswered = false
    }

    /** Record that the user picked the profile BY HAND, so [provenance] stops attributing
     *  it to whatever a probe last concluded. Without this a hand-flipped profile was
     *  still announced as "confirmed by the aircraft just now". */
    fun noteManual() {
        origin = DeviceStore.VariantOrigin.MANUAL
        decidedAtMs = System.currentTimeMillis()
        fromCache = false
        variant = AppState.litoMode
    }

    /** One sentence describing where the ACTIVE parameter-name profile came from.
     *  Used by the UI, the log and the diag server so all three tell the same story. */
    fun provenance(ru: Boolean = false): String {
        val names = if (AppState.litoMode) "Lito" else "g_config.*"
        val age = if (decidedAtMs <= 0L) "" else {
            val days = (System.currentTimeMillis() - decidedAtMs) / 86_400_000L
            if (ru) (if (days <= 0) ", сегодня" else ", $days дн. назад")
            else (if (days <= 0) ", today" else ", $days day(s) ago")
        }
        return names + " — " + when {
            noKnownSpelling -> if (ru) "борт не знает НИ ОДНОГО из известных нам написаний этого параметра"
                               else "the aircraft has no parameter under any spelling we know"
            origin == DeviceStore.VariantOrigin.MANUAL ->
                if (ru) "выбрано вручную$age" else "chosen by hand$age"
            origin == DeviceStore.VariantOrigin.PROBE && !fromCache ->
                if (ru) "подтверждено бортом сейчас (03:F7)" else "confirmed by the aircraft just now (03:F7)"
            origin == DeviceStore.VariantOrigin.PROBE ->
                if (ru) "подтверждено бортом ранее, взято из памяти$age"
                else "confirmed by the aircraft earlier, restored from memory$age"
            origin == DeviceStore.VariantOrigin.NEGATIVE ->
                if (ru) "выведено из отказа, НЕ подтверждено$age"
                else "inferred from a denial, NOT confirmed$age"
            origin == DeviceStore.VariantOrigin.LEGACY ->
                if (ru) "из памяти прежней сборки, происхождение не записано$age"
                else "remembered by an older build, origin not recorded$age"
            // The board answered, and what it said was "I index the joined name" — a
            // positive that leaves the Lito/g_config switch simply not applicable here.
            ParamAlias.known(ParameterAddress.FOREARM_LED.key) != null ->
                if (ru) "борт адресует параметр составным именем — переключатель профиля к нему не относится"
                else "this aircraft indexes the joined name — the profile switch does not apply to it"
            probeRan -> if (ru) "опрошено, но борт ничего определённого не ответил"
                        else "asked, but nothing conclusive came back"
            else -> if (ru) "значение по умолчанию, борт не спрашивали"
                    else "the app default — the aircraft was never asked"
        }
    }

    suspend fun run(ctx: Context) {
        if (!running.compareAndSet(false, true)) return   // atomic guard against concurrent probes
        try {
            serial = AircraftSerial.read()
            AircraftSession.onSerial(serial)              // reset cached state if this is a different drone
            val stored = if (serial.isNotEmpty()) DeviceStore.record(ctx, serial) else null

            // Restore a cached model so it shows immediately, even before Fly is opened.
            if (serial.isNotEmpty()) DeviceStore.model(ctx, serial)?.let { (code, name) ->
                AircraftIdentity.publish(code, name, AircraftIdentity.Source.CACHE)
            }

            // Precedence: a manual choice the user made for THIS aircraft outranks anything
            // a probe can conclude. Before this check the cached value was restored on every
            // run and pushed into AppState, so a manual flip was undone by the next probe
            // without a word.
            //
            // A REMEMBERED value that was never actually confirmed (LEGACY, or NEGATIVE) no
            // longer short-circuits the probe. That short circuit is what made a single bad
            // verdict permanent: `known ?: detectVariant()` meant the aircraft was asked
            // exactly once, ever, and a wrong answer could never be revisited.
            val reprobe = stored == null || (!stored.isManual && !stored.isMeasured)
            if (stored != null) {
                variant = stored.lito; origin = stored.origin
                decidedAtMs = stored.atMs; fromCache = true
            }
            if (reprobe) {
                val d = detectVariant()
                if (d != null) {
                    variant = d.lito; origin = d.origin; decidedAtMs = 0L; fromCache = false
                } else if (stored == null) {
                    // Nothing stored and nothing learned: the profile stays at whatever it
                    // was, and we say so rather than implying it was checked.
                    origin = null; decidedAtMs = 0L; fromCache = false
                }
            }
            variant?.let { AppState.setLito(ctx, it) }
            DiagLog.info("name-variant in use: ${provenance()}" +
                (if (serial.isNotEmpty()) " · aircraft $serial" else " · aircraft unknown"))

            // If the drone is here, we ASKED, and nothing came back, mark reads as
            // unavailable. Only a round that actually ran may conclude this, and an answer
            // counts even when it produced no verdict — a board that says "I index the
            // joined name" is answering perfectly well (2026-08-20).
            readsFailed = serial.isNotEmpty() && probeRan && !probeAnswered && !FlightState.readsWork

            if (variant != null || FlightState.readsWork) FlightState.refresh()

            if (serial.isNotEmpty()) DeviceStore.save(
                ctx, serial,
                AircraftIdentity.drone.code.ifEmpty { null },
                AircraftIdentity.drone.name.ifEmpty { null },
                variant,
                // Only write provenance for a verdict THIS run produced; re-stamping a
                // restored one would keep refreshing the date of a decision nobody made.
                if (fromCache) null else origin
            )
        } catch (e: Exception) {
            DiagLog.err("startup probe: ${e.message}")
        } finally { running.set(false) }
    }

    /** A verdict with the strength of the evidence behind it. */
    private class Verdict(val lito: Boolean, val origin: DeviceStore.VariantOrigin)

    /**
     * Which name-variant does the drone answer to? Null = undecided, and then the
     * current profile is left alone.
     *
     * Uses `03:F7` rather than a plain read. Both answer at about the same rate, but F7's
     * reply carries the parameter's canonical NAME, so a positive is self-verifying, and
     * its status 3 is an explicit "no such parameter" — a real answer, where a read can
     * only ever fail to arrive.
     *
     * **All three spellings go into ONE window** (2026-08-20). The probe used to ask the
     * two names one after another, up to six windows each, and could only ever answer with
     * one of them. That was wrong twice over:
     *
     *  - a firmware may index neither, but the JOINED form `a|b` that `03:E1` reports
     *    (see [ParamName]) — a spelling the old probe never tried;
     *  - and when both names came back *denied*, the code kept only the LAST denial and
     *    returned its opposite, so "the aircraft says neither of these exists" was
     *    reported as a confident verdict for whichever profile happened to be selected —
     *    and then persisted, which made it permanent.
     *
     * Losses on this bus are per-window, not per-request, so asking three spellings costs
     * exactly the window that asking one cost. Detection is skipped entirely when no
     * aircraft is around — it holds a socket on DJI Fly's video port and there is nothing
     * to learn.
     */
    private suspend fun detectVariant(): Verdict? {
        if (serial.isEmpty() && !FlightState.readsWork) return null
        val addr = ParameterAddress.FOREARM_LED
        val names = addr.names
        if (names.size < 2) return null
        val cands = ParamName.candidates(addr.key)          // [joined, lito, g_config.*]
        probeRan = true
        val r = ParamMeta.resolve(cands, attempts = DETECT_ATTEMPTS)
        probeAnswered = r.name != null || r.absent.isNotEmpty()

        r.name?.let { hit ->
            ParamAlias.note(addr.key, hit)
            noKnownSpelling = false
            return when (hit) {
                names[0] -> {
                    DiagLog.info("name-variant: 03:F7 confirmed '${ParamName.tag(hit)}' → Lito short names")
                    Verdict(true, DeviceStore.VariantOrigin.PROBE)
                }
                names[1] -> {
                    DiagLog.info("name-variant: 03:F7 confirmed '${ParamName.tag(hit)}' → g_config.* names")
                    Verdict(false, DeviceStore.VariantOrigin.PROBE)
                }
                else -> {
                    // The board indexes the JOINED form. Neither profile name is its
                    // address, so the profile switch is simply not the thing that decides
                    // this aircraft — ParamAlias now holds the real answer and every read
                    // and write goes through it.
                    DiagLog.info("name-variant: this aircraft indexes the JOINED name " +
                        "'${ParamName.tag(hit)}' — the Lito/g_config switch does not apply to it; " +
                        "reads and writes use the measured address")
                    null
                }
            }
        }

        // No positive. A CONFIRMED absence rules a spelling out, but only a set of
        // denials that leaves exactly ONE candidate standing says anything.
        val standing = cands.filter { it !in r.absent }
        return when {
            r.allAbsent -> {
                noKnownSpelling = true
                DiagLog.warn("name-variant: this aircraft denied EVERY spelling of " +
                    "'${addr.key}' (03:F7 status ${ConfigTable.ST_NO_SUCH_PARAM}). Neither profile can be " +
                    "right for it — the parameter is named something we do not know. Load its parameter " +
                    "export or dump the table to find the real name.")
                null
            }
            r.absent.isNotEmpty() && standing.size == 1 && standing[0] != cands[0] -> {
                val keep = standing[0] == names[0]
                DiagLog.warn("name-variant: '${r.absent.joinToString(", ")}' denied by this aircraft; " +
                    "'${standing[0]}' is the only spelling left standing — taking it on that negative " +
                    "alone (unconfirmed, it will be re-probed)")
                Verdict(keep, DeviceStore.VariantOrigin.NEGATIVE)
            }
            else -> {
                DiagLog.info("name-variant: undecided — ${r.silent.size} spelling(s) unanswered, " +
                    "${r.absent.size} denied. Leaving the profile as it is (${if (AppState.litoMode) "Lito" else "g_config.*"}) " +
                    "and NOT recording a verdict.")
                null
            }
        }
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
            DeviceStore.save(ctx, s, AircraftIdentity.drone.code, AircraftIdentity.drone.name,
                variant, if (fromCache) null else origin)
    }
}
