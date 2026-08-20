package com.dji.fccgpsoff

import java.util.concurrent.atomic.AtomicLong

/**
 * How well parameter reads are actually going on this aircraft (2026-08-20).
 *
 * Every retry budget in this app is tuned to one measured number — a single 40007 window
 * answers about 70% of the time with DJI Fly backgrounded ([ParamRead], [ParamMeta],
 * [StartupProbe]) — and that number was not observable at runtime. So a log could show
 * dozens of read attempts and still not say whether the bus was behaving normally, whether
 * DJI Fly was quietly refusing every window, or whether the app was addressing a hash the
 * firmware does not have. Those are three different faults with three different fixes.
 *
 * Four counters and a rolling window of the last [RECENT] outcomes. No sockets, no frames,
 * no timers: every number is incremented at a point where a read has already finished.
 * Pure Kotlin, so the summary wording is unit-testable off-device.
 */
object ReadStats {

    /** How many recent outcomes the "lately" figure is computed over. Small enough to
     *  react within one screenful of reads, large enough not to swing on a single miss. */
    private const val RECENT = 20

    private val asked = AtomicLong()
    private val answered = AtomicLong()
    private val blocked = AtomicLong()
    private val denied = AtomicLong()

    /** Ring of recent outcomes: true = answered. Guarded by the object monitor. */
    private val recent = ArrayDeque<Boolean>(RECENT)
    /** Set once the "nothing is answering" warning has been given, so it fires on the EDGE
     *  and not on every read round. Cleared by the next answer. */
    @Volatile private var warnedDry = false

    @Synchronized private fun push(ok: Boolean) {
        if (recent.size >= RECENT) recent.removeFirst()
        recent.addLast(ok)
    }

    /** A read window was opened and answered. */
    fun answered() { asked.incrementAndGet(); answered.incrementAndGet(); push(true); warnedDry = false }

    /** A read window was opened and nothing came back. */
    fun silent() { asked.incrementAndGet(); push(false) }

    /** A read was NOT attempted because the foreground gate was shut. Deliberately not
     *  counted as [silent]: the bus said nothing because nobody asked it anything. */
    fun gateBlocked() { blocked.incrementAndGet() }

    /** `03:F7` said "no such parameter" — the one negative on this bus that is real. */
    fun denied() { denied.incrementAndGet() }

    fun reset() {
        asked.set(0); answered.set(0); blocked.set(0); denied.set(0)
        synchronized(this) { recent.clear() }
        warnedDry = false
    }

    /** Percentage of windows that answered, or -1 when nothing has been asked. */
    fun rate(): Int {
        val a = asked.get()
        return if (a == 0L) -1 else ((answered.get() * 100) / a).toInt()
    }

    @Synchronized private fun recentAnswered(): Int = recent.count { it }
    @Synchronized private fun recentSize(): Int = recent.size

    /** One line for the state snapshot and for the end of a read round. */
    fun summary(): String {
        val a = asked.get()
        if (a == 0L && blocked.get() == 0L) return "no parameter read has been attempted this session"
        // rate() is -1 when nothing was asked, and that is not a percentage. It happens for
        // real: every read this session can have been refused by the foreground gate, in
        // which case there is a story to tell and no ratio to tell it with.
        return (if (a == 0L) "no window was ever opened"
                else "${answered.get()}/$a window(s) answered (${rate()}%)") +
            (if (recentSize() > 0) " · lately ${recentAnswered()}/${recentSize()}" else "") +
            (if (blocked.get() > 0) " · ${blocked.get()} not attempted (read gate shut)" else "") +
            (if (denied.get() > 0) " · ${denied.get()} address(es) denied by 03:F7" else "")
    }

    /**
     * Called at the end of a round that asked for everything it wanted.
     *
     * Emits the round's tally always, and — once, on the edge — the warning that matters:
     * a long run of open-gate windows with nothing in them. That is the state where the
     * two candidate explanations must be named, because the app cannot tell them apart and
     * the reader can.
     */
    fun endOfRound(answeredNow: Int, wanted: Int, ms: Long, detail: String) {
        DiagLog.info("reads this round: $answeredNow of $wanted answered in ${ms} ms" +
            (if (detail.isNotEmpty()) " ($detail)" else "") + " · session ${summary()}")
        if (answeredNow == 0 && wanted > 0 && ForegroundGate.readsAllowed() &&
            recentSize() >= RECENT && recentAnswered() == 0 && !warnedDry
        ) {
            warnedDry = true
            DiagLog.warn("reads: none of the last ${recentSize()} windows answered while the read gate " +
                "stayed OPEN. On this bus that is either no aircraft on the link, or an address this " +
                "firmware does not index — 03:F7 is what tells those apart, so re-probe the name " +
                "profile before concluding the drone is off.")
        }
    }
}
