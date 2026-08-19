package com.dji.fccgpsoff

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One apply, at a measured offset after the aircraft appears — the harness for
 * answering "when should the sequence be sent?".
 *
 * The question cannot be answered while the keepalive is running: it fires five to
 * seven bursts per link-up, so when 5.8 GHz finally becomes available in DJI Fly
 * there is no way to attribute it to one of them. And it cannot be answered from
 * the bus either — the 07:19 country read never answers on this hardware, so the
 * only detector of "it worked" is a human looking at DJI Fly.
 *
 * So: stop the keepalive, arm this with an offset, power-cycle the drone. Exactly
 * one profile goes out, at a known number of seconds after the aircraft showed up
 * on Fly's screen, and the log records both instants. Repeat with different offsets
 * and the threshold falls out of a handful of runs.
 */
object ApplyAt {

    private var job: Job? = null
    @Volatile private var armedSec = -1L
    @Volatile private var armedThen = 0L
    @Volatile private var lastResult = ""

    /**
     * @param sec  delay from the aircraft appearing to the FIRST apply
     * @param then if > 0, fire a SECOND apply this many seconds after the first —
     *             the test for whether bursts interfere with each other. A series is
     *             what the app actually does; if two closely spaced applies work no
     *             worse than one, the retry strategy is sound, and if the second one
     *             spoils a first that would have taken, it is not.
     */
    @Synchronized
    fun arm(ctx: Context, scope: CoroutineScope, sec: Long, then: Long = 0L): String {
        cancel()
        armedSec = sec.coerceIn(0, 600)
        armedThen = then.coerceIn(0, 600)
        lastResult = ""
        DiagLog.info("applyat: armed — apply ${armedSec}s after the next aircraft session" +
            if (armedThen > 0) ", then a second one ${armedThen}s later" else "")
        job = scope.launch {
            val startGen = FlyLink.generation
            // Wait for a session edge: a genuinely new aircraft, not the one already up.
            while (isActive && FlyLink.generation == startGen) delay(250)
            if (!isActive) return@launch
            val linkedAt = System.currentTimeMillis()
            DiagLog.info("applyat: aircraft session #${FlyLink.generation} — waiting ${armedSec}s")
            delay(armedSec * 1000)
            if (!isActive) return@launch
            val waited = (System.currentTimeMillis() - linkedAt) / 1000
            DiagLog.info("applyat: firing the single apply now (+${waited}s after the aircraft appeared)")
            val ok = runCatching { Features(ctx).applyFcc() }.getOrDefault(false)
            lastResult = "fired at +${waited}s, frames ${if (ok) "all sent" else "INCOMPLETE"}"
            DiagLog.info("applyat: $lastResult")
            if (armedThen > 0) {
                delay(armedThen * 1000)
                if (!isActive) return@launch
                val waited2 = (System.currentTimeMillis() - linkedAt) / 1000
                DiagLog.info("applyat: firing the SECOND apply now (+${waited2}s after the aircraft appeared)")
                val ok2 = runCatching { Features(ctx).applyFcc() }.getOrDefault(false)
                lastResult += "; second at +${waited2}s, frames ${if (ok2) "all sent" else "INCOMPLETE"}"
                DiagLog.info("applyat: second apply done")
            }
            DiagLog.info("applyat: series complete — watch DJI Fly and report when 5.8 appears")
            armedSec = -1
        }
        return "armed: apply at +${armedSec}s" + (if (armedThen > 0) " and +${armedSec + armedThen}s" else "") +
            " after the next aircraft session (keepalive should be OFF)"
    }

    @Synchronized
    fun cancel() { job?.cancel(); job = null; if (armedSec >= 0) DiagLog.info("applyat: disarmed"); armedSec = -1; armedThen = 0 }

    fun status(): String = when {
        armedSec >= 0 -> "armed for +${armedSec}s" + (if (armedThen > 0) " and +${armedSec + armedThen}s" else "") + " after the next aircraft session"
        lastResult.isNotEmpty() -> "done — $lastResult"
        else -> "not armed"
    }
}
