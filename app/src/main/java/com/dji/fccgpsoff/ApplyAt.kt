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
    @Volatile private var armedCount = 1
    @Volatile private var lastResult = ""

    /**
     * @param sec  delay from the aircraft appearing to the FIRST apply
     * @param then spacing between applies when [count] > 1
     * @param count how many applies to fire in total. A single apply lands about half
     *              the time, so the app never relies on one — it fires a series. This
     *              is how the series itself gets measured, at a known offset and with
     *              nothing else on the bus.
     */
    @Synchronized
    fun arm(ctx: Context, scope: CoroutineScope, sec: Long, then: Long = 0L, count: Int = 1): String {
        cancel()
        armedSec = sec.coerceIn(0, 600)
        armedThen = then.coerceIn(0, 600)
        armedCount = count.coerceIn(1, 10)
        lastResult = ""
        DiagLog.info("applyat: armed — $armedCount apply(s), first ${armedSec}s after the next aircraft session" +
            if (armedCount > 1) ", then every ${armedThen}s" else "")
        job = scope.launch {
            val startGen = FlyLink.generation
            // Wait for a session edge: a genuinely new aircraft, not the one already up.
            while (isActive && FlyLink.generation == startGen) delay(250)
            if (!isActive) return@launch
            val linkedAt = System.currentTimeMillis()
            DiagLog.info("applyat: aircraft session #${FlyLink.generation} — waiting ${armedSec}s")
            delay(armedSec * 1000)
            if (!isActive) return@launch
            val parts = ArrayList<String>(armedCount)
            for (i in 1..armedCount) {
                if (i > 1) {
                    delay(armedThen * 1000)
                    if (!isActive) return@launch
                }
                val at = (System.currentTimeMillis() - linkedAt) / 1000
                // State AT THE MOMENT OF FIRING, not at arming time. A run measured with
                // the keepalive quietly running is not the run you think you ran, and
                // that mistake cost a whole afternoon of "manual works, automatic does
                // not" — the keepalive had restarted itself behind the measurement.
                val contaminated = FccKeepaliveService.running
                DiagLog.info("applyat: firing apply $i/$armedCount now (+${at}s after the aircraft appeared)" +
                    if (contaminated) "  ⚠ KEEPALIVE IS RUNNING — this run is contaminated" else "")
                val ok = runCatching { Features(ctx).applyFcc() }.getOrDefault(false)
                parts += "#$i at +${at}s ${if (ok) "sent" else "INCOMPLETE"}"
            }
            lastResult = parts.joinToString("; ")
            DiagLog.info("applyat: series complete ($lastResult) — watch DJI Fly and report when 5.8 appears")
            armedSec = -1
        }
        return "armed: $armedCount apply(s), first at +${armedSec}s" +
            (if (armedCount > 1) ", every ${armedThen}s after" else "") + " (keepalive should be OFF)"
    }

    @Synchronized
    fun cancel() { job?.cancel(); job = null; if (armedSec >= 0) DiagLog.info("applyat: disarmed"); armedSec = -1; armedThen = 0; armedCount = 1 }

    fun status(): String = when {
        armedSec >= 0 -> "armed: $armedCount apply(s) from +${armedSec}s" + (if (armedCount > 1) " every ${armedThen}s" else "")
        lastResult.isNotEmpty() -> "done — $lastResult"
        else -> "not armed"
    }
}
