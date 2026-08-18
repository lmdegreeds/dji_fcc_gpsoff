package com.dji.fccgpsoff

/**
 * Which app owns the active window on the RC, and whether it is safe for us to
 * READ from DJI Fly's ports right now.
 *
 * The reasoning (why this exists):
 *   - Our read-family features open a second socket on DJI Fly's video-mirror
 *     port (40007). That can blip Fly's video/link. But the blip only MATTERS
 *     when someone is actually watching Fly — i.e. Fly is the foreground app.
 *   - When OUR app (or the diag browser, or anything that is not Fly/Pilot) is
 *     in the foreground, Fly is backgrounded: it still holds the proxy ports
 *     and telemetry still flows, but nobody sees a momentary hiccup. So reading
 *     is safe.
 *   - Therefore: gate the read-family on "Fly is NOT the foreground app".
 *
 * The foreground package is fed by [DjiFlyAccessibilityService] from
 * TYPE_WINDOW_STATE_CHANGED events (no PACKAGE_USAGE_STATS permission needed).
 * If that service is not enabled we cannot tell — we DEFAULT TO ALLOW so the
 * app behaves as before, but [statusJson] reports the gate as inactive so the
 * user knows the protection is off.
 *
 * Pure Kotlin (only System.currentTimeMillis), unit-testable off-device.
 */
object ForegroundGate {

    /** DJI apps whose foreground presence means "a human is watching the feed".
     *  Which of these is "the" DJI Fly varies by controller and by how it was set
     *  up — v5 and v6 can both be installed, either can be the disabled one, or
     *  both can be live — so nothing here may assume an order means priority. */
    val DJI_PACKAGES = setOf("dji.go.v5", "dji.go.v6", "com.dji.industry.pilot")

    @Volatile var foregroundPackage: String = ""; private set
    @Volatile private var sinceMs: Long = 0L
    @Volatile var accessibilityConnected: Boolean = false
    /** Our own package, set once at startup so [statusJson] can label us. */
    @Volatile var ownPackage: String = ""

    /** Deadline until which reads stay blocked because we KNOW Fly is on its way to the
     *  front, even though no window event has said so yet. 0 = not expecting. */
    @Volatile private var expectFlyUntilMs: Long = 0L

    /**
     * Block reads immediately because DJI Fly is about to become the foreground app.
     *
     * The window event that normally closes this gate arrives from the accessibility
     * service only once the switch has already happened, and it lags — which is exactly
     * the S5 defect in `doc/fcc-autoapply-tests.md`, where reads landing across the
     * switch made Fly drop its link for a few seconds.
     *
     * When the switch is OUR doing (the "Open DJI Fly" button) we know a beat before the
     * system does, so close the gate first and hand over a quiet bus. The window is a
     * fallback only: a real window event supersedes it in either direction, so a launch
     * that never completes cannot leave reads blocked for longer than [EXPECT_FLY_MS].
     */
    /**
     * Called the instant reads must stop, so something can tear down what is already in
     * flight rather than wait for each reader to notice. Wired at startup to
     * [VideoPortGuard.closeAll]; kept as a hook so this object stays pure and testable.
     */
    @Volatile var onReadsMustStop: ((String) -> Unit)? = null

    fun expectFlyForeground(ms: Long = EXPECT_FLY_MS) {
        expectFlyUntilMs = System.currentTimeMillis() + ms
        DiagLog.info("reads held off for ${ms} ms — leaving the foreground, handing the bus over")
        runCatching { onReadsMustStop?.invoke("leaving the foreground") }
    }

    /** Drop the pre-emptive block (the switch was cancelled or never happened). */
    fun clearExpectedFly() { expectFlyUntilMs = 0L }

    private val expectingFly: Boolean get() = System.currentTimeMillis() < expectFlyUntilMs

    /** Feed a window-state change (called from the accessibility service). */
    fun onWindow(pkg: String?) {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty() || p == foregroundPackage) return
        foregroundPackage = p
        sinceMs = System.currentTimeMillis()
        // A confirmed window event is the truth, in both directions: it either makes the
        // pre-emptive block real, or says the switch didn't happen and lifts it.
        expectFlyUntilMs = 0L
        DiagLog.info("foreground: $p" + if (isFlyForeground) " (reads blocked)" else "")
        // Fly is in front NOW: tear down whatever is still reading rather than wait for
        // each reader's own poll to come round.
        if (isFlyForeground) runCatching { onReadsMustStop?.invoke("DJI Fly took the foreground") }
    }

    val isFlyForeground: Boolean get() = foregroundPackage in DJI_PACKAGES

    /** How long the current foreground app has held the front, or [Long.MAX_VALUE] if we
     *  have never seen a window event. */
    val foregroundAgeMs: Long
        get() = if (sinceMs == 0L) Long.MAX_VALUE else System.currentTimeMillis() - sinceMs

    /**
     * True while DJI Fly has been foreground for less than [ms] — i.e. its session with the
     * aircraft is still coming up.
     *
     * Verified on hardware: a full FCC burst sent while Fly sits on a settled link does
     * **nothing** visible, but the same burst landing in the seconds right after Fly takes
     * the front costs it the connection — Fly shows "not connected to the mobile device"
     * and can fall back to its connect-the-drone screen. So the burst is not the problem;
     * its timing is. Anything that reconfigures the radio should wait this out.
     */
    fun flySettling(ms: Long): Boolean = isFlyForeground && foregroundAgeMs < ms

    /**
     * True when it is safe to run read-family features. Reads are risky ONLY
     * while DJI Fly/Pilot is the active window; unknown foreground (service off)
     * defaults to allowed to preserve prior behavior.
     */
    fun readsAllowed(): Boolean = !isFlyForeground && !expectingFly

    /** Human-readable reason a read was blocked, or null when reads are allowed. */
    fun blockReason(): String? = when {
        isFlyForeground ->
            "DJI Fly/Pilot is the active window — reads are blocked (would risk a video/link blip). " +
                "Switch to this app (Fly stays running in the background), then retry."
        expectingFly -> "switching to DJI Fly — reads are held off so the handover is quiet"
        else -> null
    }

    /** How long a pre-emptive block lasts without confirmation. Long enough to cover a
     *  cold app switch, short enough that a cancelled one costs nothing noticeable. */
    private const val EXPECT_FLY_MS = 4000L

    fun statusJson(): String {
        val fg = foregroundPackage
        val label = when {
            fg.isEmpty() -> "unknown"
            fg in DJI_PACKAGES -> "dji"
            fg == ownPackage -> "self"
            else -> "other"
        }
        val ageMs = if (sinceMs == 0L) -1 else System.currentTimeMillis() - sinceMs
        // Package names are normally ASCII, but the value comes from an OS window
        // event — escape it rather than trust that, so a stray char can't break the
        // JSON the web dashboard parses.
        return "{\"foreground\":${Json.quote(fg)},\"label\":${Json.quote(label)}," +
            "\"flyForeground\":$isFlyForeground,\"readsAllowed\":${readsAllowed()}," +
            "\"serviceOn\":$accessibilityConnected,\"ageMs\":$ageMs}"
    }
}
