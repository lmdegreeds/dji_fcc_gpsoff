package com.dji.fccgpsoff

import java.util.concurrent.atomic.AtomicLong

/** What DJI Fly's own screen says about the aircraft link right now. */
enum class FlyLinkUiState { CONNECTED, DISCONNECTED, UNKNOWN }

/**
 * Aircraft-link detection by reading DJI Fly's screen — the signal that is
 * available exactly when every other one is not.
 *
 * The hole this fills (measured, see the 22:54–22:57 session in
 * `doc/fcc-autoapply-tests.md`): while DJI Fly is foreground we have **no** way
 * to tell that an aircraft appeared. 40009 streams the controller's own
 * housekeeping byte-identically with the drone off ([LinkState] is therefore
 * always true, [DroneLink] needs 40007 which we must not open under Fly), and
 * the 07:19 country read never answers on RC2. So auto-FCC ran on timers: five
 * bootstrap applies anchored to *controller* boot, then a blind replay every
 * 90 s. Reboot the controller and then the drone and every bootstrap lands
 * before the aircraft exists, leaving FCC unapplied for ~2.5 minutes.
 *
 * Fly itself knows. Its FPV screen paints the flight mode in the top-left slot
 * ("N Mode" / "Режим C" / "普通挡" …) while an aircraft is linked, and puts
 * `N/A` in that same slot with a red "Aircraft not connected to RC" banner when
 * it is not. Both strings come from [FlyUiPhrases], i.e. out of Fly's own
 * resources in every locale it ships — no English/Russian assumption.
 *
 * Position matters: `N/A` also fills the camera fields (ISO/WB/F/S/MM) in the
 * bottom-right of the very same screen, so a naive "any N/A ⇒ no drone" is
 * wrong. [classify] only trusts `N/A` from the mode slot, and a mode label
 * always beats it.
 *
 * Pure Kotlin with an injectable [clock]; the Android side only supplies label
 * strings (see [DjiFlyAccessibilityService]).
 */
object FlyLinkUi {

    /**
     * Classify one screen observation.
     *
     * @param modeSlot labels painted in Fly's top-left mode slot
     * @param all every label on screen (used only for the explicit banner)
     */
    fun classify(modeSlot: Collection<String>, all: Collection<String>): FlyLinkUiState {
        val modes = FlyUiPhrases.modes()
        val slot = modeSlot.map(FlyUiPhrases::normalize).filter(String::isNotEmpty)
        // A live mode label is the strongest evidence and wins over everything —
        // including a stale disconnect banner still fading out.
        if (slot.any(modes::contains)) return FlyLinkUiState.CONNECTED

        val screen = all.map(FlyUiPhrases::normalize).filter(String::isNotEmpty)
        if (screen.any(modes::contains)) return FlyLinkUiState.CONNECTED

        // Fly's explicit "no aircraft on the link" banner, in Fly's own words.
        val disconnects = FlyUiPhrases.disconnects()
        if (disconnects.isNotEmpty() && screen.any { s -> disconnects.any { s.contains(it) } })
            return FlyLinkUiState.DISCONNECTED

        // The placeholder — but only from the mode slot, never from the camera row.
        val nas = FlyUiPhrases.nas()
        if (slot.any(nas::contains)) return FlyLinkUiState.DISCONNECTED

        // Any other screen (Fly's home, settings, album) says nothing about the link.
        return FlyLinkUiState.UNKNOWN
    }
}

/**
 * The live link state distilled from [FlyLinkUi] observations, plus the
 * "a NEW aircraft session started" edge the keepalive arms its apply on.
 *
 * Session rule, borrowed from SkylabFCCfree's link gate: a CONNECTED sighting
 * only opens a new session when it follows a *stable* disconnect
 * ([STABLE_DISCONNECT_MS]). DJI Fly flickers `N/A` for a beat during its own
 * reconnects and when something touches 40007, and each flicker must not look
 * like a fresh aircraft — that would put a full FCC burst on a link Fly is
 * still bringing up, which is exactly what costs Fly the connection.
 */
object FlyLink {

    /**
     * How long DJI Fly must keep saying "no aircraft" before the next CONNECTED
     * counts as a new flight session rather than one of Fly's own blips.
     *
     * Measured, not guessed. Across a day of logs the two populations do not overlap
     * anywhere near each other:
     *
     *     genuine Fly flickers : 0.15 s, 0.9 s
     *     real power cycles    : 8.4, 8.5, 9.1, 9.4, 9.5 s and up
     *
     * The band between 1 s and 8 s is empty. The original 10 s sat ABOVE every real
     * power cycle, so it filtered out exactly what it was supposed to catch: two
     * measurement runs fired nothing at all because a 9 s power-cycle was written off
     * as a blip, and in normal use a quick battery swap would never re-apply FCC.
     * 3 s is 3x the longest flicker seen and well under the shortest real cycle.
     */
    const val STABLE_DISCONNECT_MS = 3_000L

    /** A verdict this recent is simply the truth, whatever else is going on. */
    const val STALE_MS = 30_000L

    /**
     * How long since the last successful *screen read* before we admit we are blind.
     *
     * Distinct from [STALE_MS] on purpose — the two used to be one value and that was
     * wrong. "We stopped being able to look" (Fly left the foreground, accessibility
     * died, the screen went off) is not the same as "we are looking, and this screen
     * says nothing about the aircraft". Measured live: opening Fly's settings panel
     * puts up a screen with neither a flight-mode label nor the disconnect banner, so
     * verdicts stop arriving while the 1 Hz scan keeps succeeding — and the old code
     * threw away a perfectly good CONNECTED after 30 s of that, handing the keepalive
     * back to the blind timer exactly while the user sits in the transmission settings.
     */
    const val SCAN_STALE_MS = 30_000L

    /**
     * How long a "no aircraft" verdict keeps gating the apply once verdicts stop.
     *
     * Asymmetric with CONNECTED, deliberately. A stale CONNECTED costs nothing: it
     * only lets the normal verify/replay path run, which is what would happen anyway.
     * A stale DISCONNECTED *holds the FCC burst*, so it must not outlive its evidence
     * — if we have been on uninformative screens this long, stop gating and let the
     * blind fallback take over.
     */
    const val DISCONNECT_TRUST_MS = 120_000L

    /**
     * A hole in SAMPLING this long means we cannot claim to know what the aircraft
     * did. Longer than a scan tick plus scheduling jitter, shorter than any real
     * power-cycle, so a momentary hiccup does not re-arm while a genuine
     * "user left DJI Fly for a while" always does.
     */
    const val BLIND_GAP_MS = 5_000L

    @Volatile var clock: () -> Long = System::currentTimeMillis

    /** True while the next CONNECTED reading owes us a session: nothing has been
     *  observed yet, or sampling stopped long enough for the aircraft to change
     *  under us. Cleared when a session is opened. */
    @Volatile private var armed = true

    @Volatile private var raw: FlyLinkUiState = FlyLinkUiState.UNKNOWN
    @Volatile private var lastSeenMs = 0L
    @Volatile private var sinceMs = 0L
    @Volatile private var disconnectedSinceMs = 0L
    /** Bumped once per new aircraft session; the keepalive watches this. */
    private val gen = AtomicLong(0)

    val generation: Long get() = gen.get()

    /**
     * The link state we are willing to act on.
     *
     * A fresh verdict is the truth. Past that, what matters is whether we can still
     * SEE Fly's screens: while the 1 Hz scan keeps succeeding, an uninformative screen
     * (settings, album, map full-screen) leaves the last verdict standing rather than
     * erasing it. Once even the scan stops, we are blind and say so. The one verdict
     * that does not get to stand indefinitely is DISCONNECTED — see [DISCONNECT_TRUST_MS].
     */
    val state: FlyLinkUiState
        get() {
            if (lastSeenMs == 0L) return FlyLinkUiState.UNKNOWN
            val now = clock()
            val verdictAge = now - lastSeenMs
            if (verdictAge <= STALE_MS) return raw
            val scanning = lastScanMs != 0L && now - lastScanMs <= SCAN_STALE_MS
            if (!scanning) return FlyLinkUiState.UNKNOWN
            if (raw == FlyLinkUiState.DISCONNECTED && verdictAge > DISCONNECT_TRUST_MS)
                return FlyLinkUiState.UNKNOWN
            return raw
        }

    /** Fly says an aircraft is on the link right now. */
    fun connected(): Boolean = state == FlyLinkUiState.CONNECTED

    /** Fly says there is NO aircraft — the case where applying FCC is pointless. */
    fun disconnected(): Boolean = state == FlyLinkUiState.DISCONNECTED

    /**
     * How long the aircraft has been continuously linked, in ms — or -1 when it is
     * not linked (or we cannot tell). Measured from the reading that flipped the
     * state to CONNECTED, so it is the aircraft's age on the link, not the app's.
     */
    fun connectedForMs(): Long =
        if (state == FlyLinkUiState.CONNECTED && sinceMs != 0L) clock() - sinceMs else -1

    /** ms since the last usable observation, or -1 if there has never been one. */
    fun ageMs(): Long = if (lastSeenMs == 0L) -1 else clock() - lastSeenMs

    /**
     * Classify + record one screen reading, keeping the labels behind the verdict.
     *
     * The labels are what `/flyui` reports. Worth the few hundred bytes: whether
     * DJI Fly exposes its flight-mode label to the accessibility tree at all —
     * rather than painting it on a canvas — is the one thing about this detector
     * that cannot be settled off-device, and this makes settling it one HTTP call.
     */
    fun observeScreen(modeSlot: Collection<String>, all: Collection<String>) {
        lastSlot = modeSlot.take(MAX_KEPT_SLOT).toList()
        lastScreen = all.take(MAX_KEPT_SCREEN).toList()
        val now = clock()
        // Did we stop WATCHING since the last reading? While our own app is in front,
        // the launcher is up, or the screen is off, nothing samples Fly's screen — and
        // an aircraft can be power-cycled inside that hole. Coming back to a screen
        // that says CONNECTED then looks identical to "nothing ever changed", which is
        // how a freshly booted (CE) aircraft used to keep its region: no session edge,
        // so nothing re-applied until the 90 s blind timer. A gap in SAMPLING is not
        // evidence of continuity — so it re-arms the session instead of being ignored.
        // NB deliberately measured on the scan, not the verdict: sitting in Fly's
        // settings keeps sampling alive and must NOT re-arm (see FlyLinkUiTest).
        if (lastScanMs != 0L && now - lastScanMs >= BLIND_GAP_MS) {
            armed = true
            DiagLog.info("fly UI: ${(now - lastScanMs) / 1000}s without a reading of DJI Fly's screen — re-arming the link check")
        }
        lastScanMs = now
        observe(FlyLinkUi.classify(modeSlot, all))
    }

    /** Feed one classified observation. UNKNOWN is ignored: an unrelated Fly
     *  screen must not erase what the FPV screen last told us. */
    fun observe(observed: FlyLinkUiState) {
        if (observed == FlyLinkUiState.UNKNOWN) return
        val now = clock()
        lastSeenMs = now
        val previous = raw
        if (observed == previous) {
            // Still disconnected: keep the original timestamp so the stability
            // window measures the whole gap, not the time since the last poll.
            if (observed == FlyLinkUiState.DISCONNECTED && disconnectedSinceMs == 0L) disconnectedSinceMs = now
            // An armed CONNECTED still owes us a session even when the state did not
            // move — after a sampling gap this reading is the FIRST thing we know
            // about the current aircraft, whatever the stale `raw` happens to say.
            if (observed == FlyLinkUiState.CONNECTED && armed) openSession(now, "first reading after a gap in sampling")
            return
        }
        raw = observed
        sinceMs = now
        when (observed) {
            FlyLinkUiState.DISCONNECTED -> {
                disconnectedSinceMs = now
                DiagLog.info("fly UI: aircraft NOT linked (Fly shows no flight mode)")
            }
            FlyLinkUiState.CONNECTED -> {
                // Open a session when this is the first thing we know (app start, a11y
                // just connected, or we were not sampling), or after a disconnect long
                // enough to be a real power-cycle rather than one of Fly's own blips.
                //
                // `armed` is what makes a SHORT observed disconnect count: return to Fly
                // while the drone is still booting and you see the banner for ~5 s before
                // the mode label — under the stable-gap rule alone that reads as a
                // flicker, even though the aircraft genuinely just came up.
                // Capture the off-duration BEFORE the fields it is derived from are
                // cleared — `sinceMs` was just set to `now` above, so reading it here
                // printed "aircraft off for 0s" for a genuine 24 s power-cycle. The
                // number only appears in the log, but that log is what these sessions
                // are diagnosed from.
                val offForMs = if (disconnectedSinceMs != 0L) now - disconnectedSinceMs else 0L
                val stableGap = disconnectedSinceMs != 0L && offForMs >= STABLE_DISCONNECT_MS
                disconnectedSinceMs = 0L
                if (armed || previous == FlyLinkUiState.UNKNOWN || stableGap) {
                    openSession(now, if (stableGap) "aircraft off for ${offForMs / 1000}s" else "first reading")
                } else {
                    DiagLog.info("fly UI: aircraft linked again within ${STABLE_DISCONNECT_MS / 1000}s — treated as a flicker, not a new session")
                }
            }
            FlyLinkUiState.UNKNOWN -> {}
        }
    }

    private fun openSession(now: Long, why: String) {
        armed = false
        disconnectedSinceMs = 0L
        val n = gen.incrementAndGet()
        DiagLog.info("fly UI: aircraft linked — new flight session #$n ($why)")
    }

    fun statusJson(): String =
        "{\"state\":${Json.quote(state.name)},\"raw\":${Json.quote(raw.name)}," +
            "\"generation\":${gen.get()},\"ageMs\":${ageMs()}," +
            "\"stateAgeMs\":${if (sinceMs == 0L) -1 else clock() - sinceMs}," +
            "\"phrases\":${FlyUiPhrases.statusJson()}}"

    /** What the accessibility tree actually handed us on the last scan, and the
     *  verdict it produced — the on-hardware check for this whole detector. */
    fun snapshotJson(): String {
        val slot = lastSlot.joinToString(",") { Json.quote(it) }
        val screen = lastScreen.joinToString(",") { Json.quote(it) }
        return "{\"state\":${Json.quote(state.name)},\"generation\":${gen.get()}," +
            "\"scanAgeMs\":${if (lastScanMs == 0L) -1 else clock() - lastScanMs}," +
            "\"modeSlot\":[$slot],\"screen\":[$screen],\"phrases\":${FlyUiPhrases.statusJson()}}"
    }

    private const val MAX_KEPT_SLOT = 8
    private const val MAX_KEPT_SCREEN = 48
    @Volatile private var lastSlot: List<String> = emptyList()
    @Volatile private var lastScreen: List<String> = emptyList()
    @Volatile private var lastScanMs = 0L

    /** Test seam. */
    internal fun resetForTest() {
        raw = FlyLinkUiState.UNKNOWN; lastSeenMs = 0L; sinceMs = 0L; disconnectedSinceMs = 0L; gen.set(0)
        lastSlot = emptyList(); lastScreen = emptyList(); lastScanMs = 0L; armed = true
    }
}
