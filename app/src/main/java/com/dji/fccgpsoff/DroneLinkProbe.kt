package com.dji.fccgpsoff

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runs the aux/hijack reader on 40007 — the only source of live FLYC OSD that
 * feeds [DroneLink] — but ONLY in windows where reading 40007 is free of cost.
 *
 * The constraint, proven on hardware (`doc/drone-link-detection.md` §3): opening
 * any socket on 40007 while DJI Fly is the foreground app freezes Fly's video for
 * ~1 s. So this controller holds an [AuxReader] lease **only when DJI Fly is not
 * foreground** (our app up, Fly backgrounded, or Fly stopped) and drops it the
 * instant Fly comes to the front. While the lease is dropped [DroneLink] coasts
 * on its last fresh OSD until [DroneLink.STALE_MS] expires.
 *
 * Foreground truth comes from the accessibility service via [ForegroundGate]. If
 * that service is not connected we cannot tell who is foreground, so we do NOT
 * probe (blipping Fly's video mid-flight would be the failure mode) — [DroneLink]
 * simply stays unfed, and the keepalive falls back to its honest "wait for a
 * confirmable link" behavior. This mirrors the app's read-gate policy.
 */
object DroneLinkProbe {

    private const val TICK_MS = 500L

    @Volatile var running = false; private set
    private var job: Job? = null
    // Atomic: the loop coroutine owns it, but releaseNow() flips it from the UI thread
    // when the user switches to Fly, and both must not release the lease twice.
    private val heldFlag = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * True when we may safely hold 40007 open: the foreground is known, it isn't Fly, and
     * Fly isn't about to become it.
     *
     * That last clause matters as much as the others: this holds a *persistent* competing
     * socket, and per `doc/drone-link-detection.md` Q-B2 the blip comes from the socket
     * merely existing, not from what it reads. Going through [ForegroundGate.readsAllowed]
     * rather than [ForegroundGate.isFlyForeground] means the pre-emptive block set when the
     * user taps "Open DJI Fly" drops this socket **before** the switch instead of after the
     * window event has already reported it.
     */
    private fun probingAllowed(): Boolean =
        ForegroundGate.accessibilityConnected && ForegroundGate.readsAllowed()

    /** Start the foreground-gated aux lifecycle on [scope]. Idempotent. */
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        running = true
        job = scope.launch { loop() }
    }

    private suspend fun CoroutineScope.loop() {
        DiagLog.info("drone-link probe: watching foreground (aux only while Fly not foreground)")
        try {
            while (isActive) {
                val allowed = probingAllowed()
                if (allowed && !heldFlag.get()) {
                    AuxReader.acquire(); heldFlag.set(true)
                    DiagLog.info("drone-link probe: aux ON — reading 40007 for live OSD")
                } else if (!allowed && heldFlag.get()) {
                    AuxReader.release(); heldFlag.set(false)
                    DiagLog.info("drone-link probe: aux OFF — DJI Fly foreground (protecting video)")
                }
                delay(TICK_MS)
            }
        } finally {
            if (heldFlag.getAndSet(false)) AuxReader.release()
            running = false
        }
    }

    /**
     * Drop the 40007 socket right now, without waiting for the next [TICK_MS] tick.
     *
     * Called when the user asks us to bring DJI Fly forward: the switch takes a couple of
     * hundred milliseconds, the tick is 500, and a competing socket still open when Fly
     * arrives is enough on its own to cost it a link — the aux reader is a second socket on
     * 40007 exactly like a read is. The loop re-acquires by itself once the gate reopens.
     */
    fun releaseNow() {
        if (heldFlag.getAndSet(false)) {
            AuxReader.release()
            DiagLog.info("drone-link probe: aux OFF now — handing 40007 over to DJI Fly")
        }
    }

    /** Stop the lifecycle; the aux lease (if any) is released in the loop's finally. */
    fun stop() { job?.cancel(); job = null }
}
