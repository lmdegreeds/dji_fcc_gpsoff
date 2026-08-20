package com.dji.fccgpsoff

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** How the keepalive decides that Apply FCC is worth replaying. */
enum class KeepaliveMode(val wire: String, val label: String) {
    /** Event-driven: replay when the aircraft records its home point. No idle traffic. */
    HOME_POINT("home_point", "on home point"),
    /** Timer-driven replay. Historically a 5 s country probe decided when to
     *  replay; that probe never answered on RC 2 (0 of 408 reads) and was removed,
     *  so this now differs from [HOME_POINT] in name only — both replay on the
     *  aircraft-session edge plus the blind cadence. Kept because it is persisted
     *  in prefs and offered by the diag page. */
    PERIODIC("periodic", "timed replay");

    companion object {
        fun of(s: String?): KeepaliveMode = values().firstOrNull { it.wire == s } ?: HOME_POINT
    }
}

/**
 * Foreground keepalive: re-applies FCC for as long as it is armed.
 *
 * Optional, not required for a one-off switch: the region/FCC write is
 * persistent and survives until the drone is rebooted. The loop is there only
 * because DJI Fly re-pushes CE/region on reconnect, so a session that relinks
 * can otherwise lose it.
 *
 * Which is why nothing here replays the profile on a bare timer. Both modes
 * (see [KeepaliveMode]) apply once at start, then wait for evidence that the
 * region actually moved: a new aircraft session on DJI Fly's screen, plus a
 * blind cadence for what cannot be observed. The old unconditional 2 s replay put
 * 21 frames x 2 rounds (~1.5 s of writes) on the shared bus 30 times a minute for
 * nothing.
 */
class FccKeepaliveService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    // One Features per service instance, disconnected in onDestroy. Hoisted out of
    // onStartCommand so stop() releases the same persistent channel the loop opened.
    private val features by lazy { Features(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogStore.componentUp("FccKeepaliveService")
        // A START_STICKY restart hands us a null intent in what may be a fresh
        // process, where AppState still holds compile-time defaults — reload the
        // saved prefs so the mode/flags are the user's, not the defaults.
        AppState.load(applicationContext)
        val mode = KeepaliveMode.of(intent?.getStringExtra(EXTRA_MODE) ?: AppState.keepaliveMode.wire)
        ForegroundServices.enter(this, NOTIF_ID, buildNotification(mode))
        running = true
        if (loop != null && mode == activeMode) {
            // Used to return here in total silence, so a START_STICKY restart into a fresh
            // process left no marker at all and two process lifetimes ran together in one
            // log with nothing between them (2026-08-20).
            DiagLog.info("keepalive: re-start ignored — already running in ${mode.label}" +
                (if (intent == null) " (START_STICKY restart by the system)" else ""))
            return START_STICKY
        }
        loop?.cancel()
        activeMode = mode
        DiagLog.info("keepalive: mode = ${mode.label}")
        warnIfBlind()
        loop = scope.launch {
            if (awaitProxy() <= 0) return@launch              // cancelled while waiting
            // Feed DroneLink from 40007 while Fly isn't foreground. Started on the
            // SERVICE scope (a sibling), not this loop's child, so the loop can
            // finish/return without waiting on the probe's infinite watch.
            DroneLinkProbe.start(scope)
            if (!awaitLink(features)) { DroneLinkProbe.stop(); return@launch }  // wait for REAL aircraft OSD
            bootstrapApply(features)                          // apply so it lands AFTER Fly's region push
            DroneLinkProbe.stop()                             // link confirmed + FCC applied — stop touching 40007
            maintain(features)                                // verify region on 40009 + re-apply on drift/relink
        }
        return START_STICKY
    }

    /**
     * Say it loudly when auto-FCC is about to run without eyes.
     *
     * Android disables an accessibility service on every reinstall, and nothing used
     * to mention it. The keepalive then keeps applying on its bare timers: it cannot
     * see the aircraft appear, so it fires whenever, not when the aircraft is ready.
     * That cost nine consecutive test applies before anyone thought to check the one
     * status field that showed it — a field nobody reads while the app looks healthy.
     */
    private fun warnIfBlind() {
        // One implementation of this query, in [Snapshot] — it existed here and in two
        // Activities with two different comparison strategies (2026-08-20).
        val on = Snapshot.isAccessibilityEnabled(this)
        if (!on) DiagLog.warn(
            "keepalive: ACCESSIBILITY SERVICE IS OFF — the aircraft-link detector is dead, so FCC " +
            "is applied on bare timers instead of when the drone appears. Android switches this off " +
            "on every reinstall. Enable it in Android settings → Accessibility."
        )
    }

    /**
     * Wait for the loopback DUML proxy before touching anything.
     *
     * No port answers at BOOT_COMPLETED — the proxies come up with the aircraft
     * link, long after Android has finished booting — and a bootstrap replay
     * written before that goes into a closed socket. So the loop waits here,
     * with the same unbounded 5 s retry as Skylab's awaitControllerPort (the
     * service is the user's off switch).
     *
     * Reachability only: it no longer opens the persistent channel — see
     * [probeProxy]. Also keeps the probing off the main thread, where
     * onStartCommand runs.
     */
    private suspend fun CoroutineScope.awaitProxy(): Int {
        var attempt = 0
        while (isActive) {
            val port = probeProxy()
            if (port > 0) {
                DiagLog.info("keepalive: DUML broker reachable on $port" + if (attempt > 0) " after ${attempt + 1} attempts" else "")
                return port
            }
            if (attempt == 0) DiagLog.info("keepalive: no DUML proxy yet — retrying every ${CONNECT_RETRY_MS / 1000}s")
            attempt++
            delay(CONNECT_RETRY_MS)
        }
        return -1
    }

    /**
     * Which loopback port the broker is listening on, by connect+close — NOT by
     * opening the persistent channel.
     *
     * This gate only ever asked a reachability question ("is the DUML broker up
     * yet?"), but it used to answer it by taking a ref on the main channel and
     * leaving it held for the life of the service. Nothing read that channel: of
     * the eleven route-0 consumers exactly one (DumlCapture) uses those frames,
     * and it raises the channel itself now. Holding it here cost ~19 reconnects a
     * minute against a broker that shows slot contention, and buried the diag log
     * under housekeeping. 40007 is deliberately absent from the scan — probing it
     * blips DJI Fly's video.
     */
    private fun probeProxy(): Int =
        PROXY_PORTS.firstOrNull { runCatching { DumlNative.nativeProbePort(it) }.getOrDefault(false) } ?: -1

    /**
     * Decide when to fire the FIRST Apply FCC.
     *
     * PREFER a confirmed live link — real FLYC OSD on 40007 ([DroneLink.connected]),
     * fed by [DroneLinkProbe] in windows where DJI Fly isn't foreground — so the log
     * is honest and we don't spam a truly dead link. BUT the apply itself writes on
     * **40009**, which never blips DJI Fly's video and is a harmless no-op when no
     * aircraft is present. So a strict "never apply without OSD" gate is the wrong
     * trade: when the user only ever stays in DJI Fly (its window foreground), the
     * probe can't open 40007 (would blip video) and OSD never arrives — and gating on
     * it means **auto-FCC never fires during flight**, which is exactly when it's
     * wanted (verified on RC2: S2 in `doc/fcc-autoapply-tests.md`).
     *
     * So: wait up to [BLIND_FALLBACK_MS] for an OSD confirmation; if none appears
     * (Fly stayed foreground, or accessibility is off), **apply blind on 40009 anyway**
     * — harmless, video-safe, and the maintenance loop keeps it. Returns false only
     * if the coroutine is cancelled.
     */
    private suspend fun CoroutineScope.awaitLink(features: Features): Boolean {
        var blindDeadline = System.currentTimeMillis() + BLIND_FALLBACK_MS
        var announced = false
        var waitingLogged = false
        while (isActive) {
            if (DroneLink.connected()) { DiagLog.info("keepalive: aircraft OSD confirmed — applying FCC"); return true }
            // DJI Fly's own screen: a flight mode in the top-left slot means a live
            // aircraft. This is the only positive confirmation available while Fly is
            // foreground (40007 is off limits, 40009 looks identical with no drone).
            if (FlyLink.connected()) {
                DiagLog.info("keepalive: aircraft confirmed on DJI Fly's screen — applying FCC")
                return true
            }
            // …and the same screen saying "no aircraft" is the case the old code got
            // wrong: it blind-applied anyway, burning the whole bootstrap window into
            // a link with nothing on the other end (controller rebooted before the
            // drone). Wait instead — the session edge below will fire the apply.
            if (FlyLink.disconnected()) {
                if (!waitingLogged) {
                    waitingLogged = true
                    DiagLog.info("keepalive: DJI Fly reports no aircraft — holding the FCC burst until one links")
                }
                blindDeadline = System.currentTimeMillis() + BLIND_FALLBACK_MS   // grace restarts if the screen goes quiet
                delay(WATCH_TICK_MS)
                continue
            }
            waitingLogged = false
            if (System.currentTimeMillis() >= blindDeadline) {
                DiagLog.info("keepalive: no OSD window and DJI Fly's screen says nothing (a11y off / Fly not up) — applying FCC blind on 40009 (video-safe)")
                return true
            }
            if (!announced) {
                DiagLog.info("keepalive: proxy up — confirming aircraft, blind-apply fallback in ${BLIND_FALLBACK_MS / 1000}s"); announced = true
            }
            delay(WATCH_TICK_MS)
        }
        return false
    }

    /**
     * The first Apply on link-up. DJI Fly establishes its own session and re-pushes
     * its region (→ CE) *around* the moment telemetry starts, which can land AFTER a
     * single early apply and silently overwrite it — so the auto-apply seems to do
     * nothing until you press Enable FCC by hand once Fly has settled. To make it
     * stick without a human waiting for the right instant, we apply and then re-apply
     * a few times across [BOOTSTRAP_WINDOW_MS] (after a short settle), so at least one
     * write lands on top of Fly's push and while the aircraft is actually ready. The
     * ongoing home-point/drift maintenance then keeps it.
     */
    private suspend fun CoroutineScope.bootstrapApply(features: Features) {
        // Re-apply a few times across the window so at least one write lands ON TOP of DJI
        // Fly's region push (it re-pushes region around the moment telemetry starts) and sticks.
        //
        // BUT never fire the service-mode FCC burst on 40009 while DJI Fly is still bringing up
        // its link (fresh foreground). Measured on hardware after a controller reboot: a burst
        // landing on Fly's just-established aircraft link makes Fly drop the aircraft several
        // times in a row. So each apply waits out the settle window — DEFERRED, not skipped:
        // the deadline is pushed forward while deferring, so we still get the full run of
        // re-applies once Fly has settled (or once our app / a non-Fly window is foreground).
        // NOTE: the old code applied IMMEDIATELY, arguing the fresh-link window is when the
        // write sticks. If FCC stops sticking after a reboot, this gate is the thing to tune
        // (shorten FLY_SETTLE_MS or drop the gate) — it is the deliberate trade for not
        // disrupting Fly's reconnect.
        var deadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MS
        var n = 0
        // Applies made since the aircraft was last seen to (re)appear — NOT since this
        // call started. The fast knocks below are budgeted against THIS, because the
        // budget belongs to the aircraft's boot, not to the invocation: measured on
        // hardware, a power-cycle mid-window resumed the same bootstrapApply at its
        // 7th apply, found the fast budget already spent on startup, and gave the
        // freshly booted aircraft the SLOW cadence — exactly backwards.
        var sinceLink = 0
        var deferredLogged = false
        var goneLogged = false
        var linkYoungLogged = false
        var holdUntil = Long.MAX_VALUE
        while (isActive) {
            // The aircraft left mid-window (battery swap, power-cycle, range loss).
            // Frames sent now go into a proxy that silently drops them, and worse,
            // they burn the window that should cover the RELINK. Hold, and let the
            // window run from the moment Fly shows a flight mode again.
            if (FlyLink.disconnected()) {
                if (!goneLogged) {
                    goneLogged = true
                    holdUntil = System.currentTimeMillis() + BOOTSTRAP_HOLD_MAX_MS
                    DiagLog.info("keepalive: bootstrap FCC held — DJI Fly reports no aircraft on the link")
                }
                // Bounded: hand control back to the maintenance loop rather than sit
                // here while the drone is off — the next session edge re-enters this.
                if (System.currentTimeMillis() >= holdUntil) {
                    DiagLog.info("keepalive: bootstrap FCC abandoned — still no aircraft after ${BOOTSTRAP_HOLD_MAX_MS / 1000}s")
                    return
                }
                delay(WATCH_TICK_MS)
                deadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MS
                continue
            }
            if (goneLogged) {
                goneLogged = false
                sinceLink = 0          // the aircraft is back: it earns fresh fast knocks
            }
            // Let the AIRCRAFT settle before the first burst of a session.
            //
            // Distinct from the DJI Fly settle below, which measures how long Fly has
            // held the foreground — when Fly has been in front for minutes and a drone
            // is then powered on, that gate is already satisfied and we fired ~0.3 s
            // after the link appeared. Across every measurement those earliest applies
            // (+0.3, +5, +10, +15 s) never took, while a later one did; the aircraft is
            // still bringing itself up and refuses the region write. Waiting costs
            // nothing we were getting anyway.
            val linkedFor = FlyLink.connectedForMs()
            if (linkedFor in 0 until LINK_SETTLE_MS) {
                if (!linkYoungLogged) {
                    linkYoungLogged = true
                    DiagLog.info("keepalive: aircraft linked ${linkedFor / 1000}s ago — letting it settle ${LINK_SETTLE_MS / 1000}s before applying")
                }
                delay(WATCH_TICK_MS)
                deadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MS   // don't burn the window waiting
                continue
            }
            linkYoungLogged = false
            if (ForegroundGate.flySettling(FLY_SETTLE_MS)) {
                if (!deferredLogged) {
                    deferredLogged = true
                    DiagLog.info("keepalive: bootstrap FCC deferred — DJI Fly settling its link (${FLY_SETTLE_MS / 1000}s)")
                }
                delay(WATCH_TICK_MS)
                deadline = System.currentTimeMillis() + BOOTSTRAP_WINDOW_MS   // don't burn the window while waiting
                continue
            }
            deferredLogged = false
            DiagLog.info("keepalive: applying FCC on connect (bootstrap ${n + 1})")
            runCatching { features.applyFcc() }
            n++; sinceLink++
            if (System.currentTimeMillis() >= deadline) break
            // Knock more often at first. Measured on a clean power-cycle: the aircraft
            // relinked at 08:49:19, we applied at +3 s, +17 s, +31 s, +45 s, +58 s, and
            // FCC only became visible in DJI Fly after ~50-70 s — so the first three
            // writes were refused by an aircraft that was not ready yet. The readiness
            // moment is not observable (07:19 never answers on this hardware), so the
            // only lever is to knock more often while it is most likely to arrive.
            // Honest about the size of the win: with a 14 s step we hit ~5-10 s after
            // readiness; a 5 s step narrows that, it does not remove the aircraft's own
            // ~45 s. Total traffic is unchanged — the window is the same length.
            delay(if (sinceLink < BOOTSTRAP_FAST_TRIES) BOOTSTRAP_FAST_REAPPLY_MS else BOOTSTRAP_REAPPLY_MS)
        }
    }

    /**
     * Event-driven. Idles on a volatile flag — no bus traffic at all — and
     * replays once per link: arm on the home-point bit, then wait for it to
     * clear before arming again, so a session that relinks is covered but a
     * steady link is left alone. [HOME_POINT_DEBOUNCE_MS] keeps a flapping bit
     * from turning into a write storm.
     *
     * The whole mode rides on the main RX channel, so the idle wait also watches
     * for that socket dropping and re-acquires it — a dead channel would look
     * exactly like "no aircraft" and silence the trigger for good.
     */
    /**
     * Ongoing maintenance after the bootstrap — verify the region and re-apply when
     * DJI Fly has reset it (which it does on every drone relink / power-cycle).
     *
     * We do NOT use the 03:44 home-point bit: the persistent channel binds **40009**
     * (SDR/radio), where the flight-controller's OSD (03:44) never arrives — proven
     * live, `HomePointMonitor` sees 0 frames — so it silently never fires; and once
     * FCC is on it doesn't drop at the home-point moment anyway. Instead we VERIFY on
     * 40009 itself. There is nothing to read back — the 07:19 country probe that
     * used to run here never answered on this hardware and was removed — so replay
     * is driven by the aircraft-session edge plus a blind cadence. All on 40009,
     * never DJI Fly's 40007 — minimal impact on Fly, and independent of GPS.
     *
     * The blind timer is now the LAST resort, not the main path: [FlyLink] reports a
     * new aircraft session straight off DJI Fly's screen, and that edge re-runs the
     * whole bootstrap at the moment the aircraft appears. Which is what a relink
     * needs — 07:19 never answers on this hardware, so before that signal existed a
     * drone that powered up after the bootstrap window stayed CE for up to 90 s.
     */
    private suspend fun CoroutineScope.maintain(features: Features) {
        var lastApplyMs = System.currentTimeMillis()
        var deferredLogged = false
        var lastSession = FlyLink.generation
        var sessionStartedMs = System.currentTimeMillis()
        var idleLogged = false
        var nextVerifyMs = System.currentTimeMillis() + VERIFY_INTERVAL_MS
        var flyWasForeground = ForegroundGate.isFlyForeground
        while (isActive) {
            // DJI Fly coming back to the front is the moment FCC is most likely to have
            // just been lost: measured 2026-08-19, a Fly restart drops it, and so does a
            // trip through our own screen. Merely minimising Fly does NOT — but this edge
            // covers that case too, at the cost of one burst.
            //
            // It does not apply here; it makes the blind apply DUE. Everything below —
            // the settle deferral that keeps us off the radio while Fly's link comes up,
            // the port lease, the logging — then applies unchanged. Forcing the apply
            // from this point instead would have to duplicate all of it.
            val flyNow = ForegroundGate.isFlyForeground
            if (flyNow && !flyWasForeground) {
                DiagLog.info("keepalive: DJI Fly is back in front — FCC re-apply queued " +
                             "(waits for its link to settle)")
                lastApplyMs = 0L
            }
            flyWasForeground = flyNow
            // Tick fine, verify slow. The loop used to sleep the whole verify interval,
            // so a new aircraft session waited up to VERIFY_INTERVAL_MS to be noticed —
            // measured on hardware: the screen saw the aircraft at 08:49:19.1 and the
            // apply started at 08:49:22.3, 3.2 s of it spent asleep here. The 07:19
            // replay decision still runs on its own slower schedule below; only the
            // edge check got faster, and it costs nothing (a volatile read).
            delay(EDGE_TICK_MS)

            // A NEW aircraft session on DJI Fly's screen — the drone was power-cycled,
            // swapped, or came back from a link loss. Its region is CE again, so this
            // is the moment the full profile has to land. Before this existed, the
            // only thing that could correct a relink was the blind timer below, which
            // left FCC off for up to BLIND_APPLY_MS after the aircraft appeared.
            val session = FlyLink.generation
            if (session != lastSession) {
                lastSession = session
                // A session that opens right after OUR OWN burst is very likely our
                // doing, not a new aircraft. Measured on hardware: 4 of 6 link drops
                // came 0.7-5.1 s after an apply (the other two were the pilot pulling
                // power), and re-applying on that edge fed a loop — burst knocks the
                // link, Fly relinks and re-pushes its region, we see a "new session"
                // and burst again. Let the link settle instead; the blind timer still
                // covers a genuine relink we skipped here.
                if (Features.sinceLastApplyMs() < SELF_INFLICTED_MS) {
                    DiagLog.info("keepalive: session #$session opened ${Features.sinceLastApplyMs() / 1000}s after our own apply — " +
                        "treating it as our own disturbance, not re-applying")
                    continue
                }
                DiagLog.info("keepalive: DJI Fly shows a new aircraft session (#$session) — applying FCC for it")
                sessionStartedMs = System.currentTimeMillis()
                bootstrapApply(features)
                lastApplyMs = System.currentTimeMillis()
                continue
            }

            // No aircraft on the link: a replay would configure nothing. Stay off
            // the bus until one shows up.
            if (FlyLink.disconnected()) {
                if (!idleLogged) {
                    idleLogged = true
                    DiagLog.info("keepalive: idle — DJI Fly reports no aircraft; verification and replay paused")
                }
                continue
            }
            idleLogged = false

            if (System.currentTimeMillis() < nextVerifyMs) continue
            nextVerifyMs = System.currentTimeMillis() + VERIFY_INTERVAL_MS

            // Replay is time-driven, full stop. There is no read to gate it on: the
            // 07:19 country probe that used to run here answered 0 times out of 408
            // across every recorded session — this hardware does not route injected
            // reads back — so `drifted` never fired and `verified` never suppressed a
            // replay. It was not free either: each read was a fresh connection to a
            // broker that evicts its current client on every connect, ~11 evictions a
            // minute spent competing with DJI Fly for the single slot, feeding the very
            // contention that kept FCC from landing. Removed entirely; `/country` is
            // still there for a manual probe on hardware that does answer.
            //
            // Cadence: short while the aircraft is young, long once it has settled.
            // Measured on v1.0.1 with the burst no longer disturbing the link: five
            // applies in the first 45 s did NOT take, then nothing was sent for 97 s —
            // and FCC appeared 4 s after the very next apply. The aircraft becomes
            // willing at a moment of its own, and a fixed 90 s gap slept through it.
            val youngSession = System.currentTimeMillis() - sessionStartedMs < EARLY_WINDOW_MS
            val blindEvery = if (youngSession) EARLY_BLIND_APPLY_MS else BLIND_APPLY_MS
            val due = System.currentTimeMillis() - lastApplyMs >= blindEvery
            if (due) {
                val why = "unverifiable — blind"
                // Never reconfigure the radio while DJI Fly's session is still coming up.
                // Measured: the same burst on a settled link is invisible, but landing it in
                // the first seconds after Fly takes the front costs Fly the connection. Not
                // cancelled, just deferred — lastApplyMs is left alone, so this fires as
                // soon as the settle window passes.
                if (ForegroundGate.flySettling(FLY_SETTLE_MS)) {
                    if (!deferredLogged) {
                        deferredLogged = true
                        DiagLog.info("keepalive: FCC re-apply deferred ($why) — DJI Fly just came forward, " +
                            "letting its link settle for ${FLY_SETTLE_MS / 1000}s")
                    }
                    continue
                }
                deferredLogged = false
                DiagLog.info("keepalive: re-applying FCC ($why)")
                runCatching { features.applyFcc() }; lastApplyMs = System.currentTimeMillis()
            }
        }
    }

    override fun onDestroy() {
        LogStore.componentDown("FccKeepaliveService")
        DiagLog.info("keepalive: STOPPED (was ${activeMode?.label ?: "idle"}) — " +
            "FCC is no longer being re-applied")
        running = false
        activeMode = null
        DroneLinkProbe.stop()               // release any 40007 aux lease we hold
        loop?.cancel(); loop = null
        scope.cancel()                      // stop any in-flight applyFcc / awaitProxy
        features.disconnect()               // release the same persistent channel we opened
        super.onDestroy()
    }

    private fun buildNotification(mode: KeepaliveMode): Notification =
        ForegroundServices.notification(
            this, CHANNEL, "FCC keepalive",
            t("FCC активен", "FCC active"),
            t("Переприменяю режим FCC — ${mode.label}", "Re-applying radio FCC mode — ${mode.label}"),
            android.R.drawable.stat_sys_data_bluetooth
        )

    companion object {
        @Volatile var running = false
        /** Mode the live loop is running in, or null when stopped. */
        @Volatile var activeMode: KeepaliveMode? = null

        const val EXTRA_MODE = "mode"
        /** How long DJI Fly gets to settle after taking the foreground before we will
         *  reconfigure the radio. Measured on hardware: a full FCC burst on a settled
         *  link is invisible; the same burst just after the switch drops Fly's link.
         *  Tuned down from 20s → 10s: still skips the riskiest first seconds of Fly's
         *  reconnect, but applies FCC ~twice as fast after a controller/aircraft reboot. */
        private const val FLY_SETTLE_MS = 10_000L

        /**
         * How long the AIRCRAFT must have been on the link before the first apply of
         * a session. Separate from [FLY_SETTLE_MS], which is about DJI Fly's window,
         * not the drone — see the call site in bootstrapApply.
         *
         * Measured directly, one apply per drone power-cycle with the keepalive off
         * (the `/applyat` harness, so nothing else could be credited):
         *
         *     +0 s  -> never applied      +10 s -> applied
         *     +5 s  -> never applied      +30 s -> applied
         *
         * Seven single-shot runs put the threshold between 15 and 30 s: +0, +5, +10 and
         * +15 all failed, +30 worked twice. (One +10 run did succeed; it is a lone
         * outlier against six consistent runs and is not explained.)
         *
         * So this value is NOT "the moment that works" — the first burst at +15 will
         * usually be too early. It only skips the window that provably never works;
         * the retries after it are what actually land. Bursts are cheap and measurably
         * do not disturb the link (16 applies, zero correlated drops), so keeping the
         * floor low costs nothing and covers the case where the threshold is lower.
         */
        private const val LINK_SETTLE_MS = 15_000L

        private const val CHANNEL = "fcc_keepalive"
        private const val NOTIF_ID = 1001

        /** How often the maintenance loop verifies the region (07:19 on 40009). */
        private const val VERIFY_INTERVAL_MS = 5_000L
        /** Replay cadence when NOTHING could be verified — neither the regulatory
         *  parameter nor the country answered. A genuine fallback now, not the steady
         *  state: at the old 10 s this fired on every tick (07:19 never answers on this
         *  hardware) and re-configured the radio every ~14 s. The full burst enters
         *  service mode and commits a region change, and while 40009 does not blip Fly's
         *  video, landing that on a link Fly is still re-establishing DOES cost it the
         *  connection for a few seconds — observed right after every switch back to Fly.
         *  Verification by parameter read is what makes a long interval safe: whenever our
         *  app is in front, the read confirms FCC and pushes this deadline out. */
        private const val BLIND_APPLY_MS = 90_000L
        /** Replay cadence while the aircraft is still young. This is what actually
         *  covers a cold boot: the settle above cannot, because the moment the aircraft
         *  becomes willing depends on how long it was unpowered. Cheap to repeat now —
         *  three connections per apply, and no link disturbance. */
        private const val EARLY_BLIND_APPLY_MS = 30_000L
        /** How long an aircraft counts as young after its session opened. */
        private const val EARLY_WINDOW_MS = 5 * 60_000L
        /** Silent country reads before the probe is muted (see the call site). */
        private const val COUNTRY_GIVE_UP_AFTER = 3
        /** How often a muted probe is retried, in case this hardware does answer. */
        private const val COUNTRY_RETRY_MS = 5 * 60_000L
        /** Poll cadence while waiting for the first telemetry (awaitLink). */
        private const val WATCH_TICK_MS = 500L
        /** How long awaitLink waits for an OSD confirmation before applying FCC blind
         *  on 40009 (video-safe). Covers the "user only ever in DJI Fly" flight case. */
        private const val BLIND_FALLBACK_MS = 5_000L
        /** Retry cadence while the loopback proxy is not up yet (Skylab's APPLY_CONNECT_RETRY_DELAY_MS). */
        private const val CONNECT_RETRY_MS = 5_000L
        /** Bootstrap-apply timing on link-up (outlast DJI Fly's region push). */
        private const val BOOTSTRAP_SETTLE_MS = 5_000L        // let the link settle before the first apply
        private const val BOOTSTRAP_WINDOW_MS = 45_000L       // re-apply across this window…
        private const val BOOTSTRAP_REAPPLY_MS = 12_000L      // …every this long once the fast tries are spent
        /**
         * How many of the first applies use the tight cadence, and what that cadence is.
         *
         * Deliberately small. Three controlled power-cycles on RC 2 + Lito X1 (recorded
         * in `doc/fcc-autoapply-tests.md`) killed the theory that knocking harder helps:
         * the cycle with SEVEN applies in the first 57 s surfaced FCC at +133 s, with no
         * write at all in the 77 s before it — the aircraft had accepted an early write
         * and simply applied it on its own schedule, while the link stayed continuously
         * up (201 samples, no flicker). More bursts bought nothing and put four full
         * service-mode bursts on the bus during the most fragile seconds of link-up.
         *
         * One early retry is kept, for a different reason than speed: the apply now
         * fires ~0.3 s after the screen first shows a flight mode, which is earlier than
         * anything previously measured, so the first burst is the one most likely to
         * race a link that is still coming up. This is its cheap second chance.
         * Writes land at roughly 0, 5, 19, 33, 47… seconds after the link appears.
         */
        private const val BOOTSTRAP_FAST_TRIES = 1
        private const val BOOTSTRAP_FAST_REAPPLY_MS = 3_000L
        /** How often the maintenance loop re-checks fast-moving state (the session edge). */
        private const val EDGE_TICK_MS = 500L
        /** A link that drops within this long after our own apply is treated as
         *  collateral from that apply rather than a new aircraft. Covers the whole
         *  observed range of self-inflicted drops (0.7-5.1 s) with room for the
         *  relink and DJI Fly repainting its screen afterwards. */
        private const val SELF_INFLICTED_MS = 20_000L
        /** How long the bootstrap will hold while DJI Fly reports no aircraft before
         *  giving up and letting the maintenance loop drive. The session edge re-enters
         *  it the moment an aircraft appears, so nothing is lost by not waiting here. */
        private const val BOOTSTRAP_HOLD_MAX_MS = 60_000L
        /** Loopback ports the DUML broker may listen on, in scan order. 40007 is
         *  deliberately excluded: probing DJI Fly's video mirror blips its video. */
        private val PROXY_PORTS = intArrayOf(DumlWire.PORT_FCC, DumlWire.PORT_INJECT, 8901, 8902, 8903, 8904)

        fun start(ctx: Context, mode: KeepaliveMode = AppState.keepaliveMode) =
            ForegroundServices.launch(ctx, FccKeepaliveService::class.java) { putExtra(EXTRA_MODE, mode.wire) }
        fun stop(ctx: Context) = ForegroundServices.stop(ctx, FccKeepaliveService::class.java)
    }
}
