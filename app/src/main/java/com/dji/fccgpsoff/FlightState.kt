package com.dji.fccgpsoff

import java.util.EnumMap

/**
 * Live LED / GPS / flight-mode state, read back by hash over the 40007 window
 * ([ParamRead]) whenever OUR app owns the foreground (so we never touch 40007
 * while DJI Fly is on screen). Reads are racy, so each value is only replaced
 * when a read actually answers; a value stays null until first seen.
 *
 * [readsWork] latches true once any param answers, and is what the UI uses to
 * decide whether to show live state or fall back to "write-only" (blind) — if no
 * read ever answers, the state panel is disabled with a note, per the app's
 * honesty rule.
 *
 * **Pending writes (2026-08-20).** A write on this bus is blind and a read-back
 * takes hundreds of milliseconds to catch up, so between the two there is a window
 * in which the cached value still holds the OLD reading. This object now remembers
 * what the user asked for ([markWritten]) and hands the UI [shown] instead of the
 * raw cache, so a control cannot be dragged back to a stale value by a render tick
 * that happens to fire in that window — which is exactly the bug a user reported as
 * "the GPS switch does not stay flipped". The intent is dropped the moment a read
 * either confirms it or, past [SETTLE_MS], contradicts it; a contradiction is
 * logged, because a write that did not take is the interesting event.
 */
object FlightState {

    @Volatile var ledOn: Boolean? = null; private set
    @Volatile var gpsOn: Boolean? = null; private set
    @Volatile var cine: Boolean? = null; private set        // true = Cine(12), false = ATTI(3)
    @Volatile var readsWork = false; private set
    @Volatile var probed = false; private set               // a refresh has completed at least once
    @Volatile var lastMs = 0L; private set
    /** The flight controller answers a hash read ONLY when a drone is linked, so
     *  this is our "drone connected" signal: true = answered this cycle, false =
     *  probed but silent (no drone / link down), null = not probed (gate closed). */
    @Volatile var connected: Boolean? = null; private set

    /**
     * One of the three values this object holds, so a caller can read them ONE AT
     * A TIME and keep going until each has actually answered.
     *
     * Added 2026-08-19 with the "Read state" fix: [refresh] reads all three in one
     * pass and gives up on whichever did not answer, which is what left the panel
     * sitting on "reading…" with nothing behind it. The UI now retries per item,
     * and per item it needs to know what is still missing.
     */
    enum class Item(val label: String) {
        LED("LED"), GPS("GPS"), MODE("mode");

        fun address(): ParameterAddress = when (this) {
            LED -> ParameterAddress.FOREARM_LED
            GPS -> ParameterAddress.GPS_ENABLE
            MODE -> ParameterAddress.FLIGHT_MODE
        }

        /**
         * What to READ by: the logical parameter's key, not the profile's guess at its
         * spelling. [ParamRead] expands the key into every address the parameter can
         * have and asks them in one window, so the aircraft — not a stored profile —
         * decides which one is right, and the answer is then reused for writes.
         */
        fun paramName(): String = address().key
    }

    /** What the user asked for, when the frames went out, and which intent it is. The
     *  stamp is what lets a slow write recognise that a newer tap has superseded it. */
    private class Wanted(val on: Boolean, val atMs: Long, val stamp: Long)

    private val stamps = java.util.concurrent.atomic.AtomicLong()

    /** Guarded by the object monitor — written from click handlers, read from render. */
    private val want = EnumMap<Item, Wanted>(Item::class.java)

    /**
     * How long after a write a contradicting read-back is treated as "not settled yet"
     * rather than "the write did not take".
     *
     * Measured on a Lito X1 over RC2 (`forearm_led_ctrl`, see [ParamRead.confirmWrite]):
     * the board keeps answering with the OLD value for ~300-500 ms after a write that
     * genuinely landed. 1200 ms is that, with room for a retried read window.
     */
    internal const val SETTLE_MS = 1200L

    /**
     * How long a pending write may still be judged by a read-back.
     *
     * Past this, a difference between what was asked and what the board reads is not
     * evidence about our write: the floating panel writes while DJI Fly is in front, so no
     * read is possible at all until the user comes back to this app — by which time the
     * aircraft may have been power-cycled, or someone may have changed the value in Fly.
     * Calling that "the write did not take" would be a confident claim about a minute (or
     * an hour) nobody observed, which is exactly what this project's honesty rule forbids.
     */
    private const val VERDICT_MS = 60_000L

    /** The last read value of [item], with no pending intent mixed in. */
    fun value(item: Item): Boolean? = when (item) {
        Item.LED -> ledOn; Item.GPS -> gpsOn; Item.MODE -> cine
    }

    /** What the user asked for and no read has settled yet, or null. */
    @Synchronized fun wanted(item: Item): Boolean? = want[item]?.on

    /**
     * What a CONTROL should show: the user's unsettled intent when there is one, else
     * the last read value, else null ("unknown"). The value LABEL beside the control
     * must keep using [value] — the control is what the user asked for, the label is
     * what the aircraft said, and conflating them is what hid the write failure.
     */
    fun shown(item: Item): Boolean? = wanted(item) ?: value(item)

    /** Record that a write for [item] has gone out, so a render tick cannot drag the
     *  control back to the pre-write reading before the read-back has caught up.
     *  [atMs] is when the frames went out — a caller that batches may know better than
     *  "now", and a test can use it to reach the far side of [SETTLE_MS] without sleeping. */
    @Synchronized fun markWritten(item: Item, on: Boolean, atMs: Long = System.currentTimeMillis()): Long {
        val s = stamps.incrementAndGet()
        want[item] = Wanted(on, atMs, s)
        return s
    }

    /**
     * Forget a pending intent without a verdict (the write never left the socket).
     *
     * [stamp] guards against a superseded write withdrawing a newer one: two taps on the
     * same switch race through independent coroutines, and the slower one finishing must
     * not touch the intent the user has since replaced. 0 means "whatever is there now",
     * for callers that never held a stamp.
     */
    @Synchronized fun clearWritten(item: Item, stamp: Long = 0L) {
        if (stamp == 0L || want[item]?.stamp == stamp) want.remove(item)
    }

    /**
     * Re-time the standing intent for [item] — the settle window has to be measured from
     * when the frames actually left the socket, not from the tap that started them.
     *
     * Only ever re-times; it can never create one, so a write finishing after the user has
     * changed their mind cannot resurrect a value nobody wants any more.
     */
    @Synchronized fun retimeCurrent(item: Item, atMs: Long = System.currentTimeMillis()) {
        val cur = want[item] ?: return
        want[item] = Wanted(cur.on, atMs, cur.stamp)
    }

    /** How long a pending write for [item] has been standing, or -1 when none is.
     *  Past [SETTLE_MS] a pending intent is no longer "the board has not caught up yet"
     *  but "nothing has come back to settle this either way", and the UI must say so
     *  rather than leaving "writing…" on screen indefinitely. */
    @Synchronized fun pendingAgeMs(item: Item): Long =
        want[item]?.let { System.currentTimeMillis() - it.atMs } ?: -1L

    /** Which of the three have never answered — what a "keep reading" loop still owes. */
    fun missing(): List<Item> = Item.values().filter { value(it) == null }

    /**
     * Fold one answered read into the state, resolving any pending write for [item].
     *
     * This is the ONE place a value changes, so it is also the one place that can say
     * what happened: a confirmation, a change seen on the aircraft, or — the line this
     * whole mechanism exists to produce — a write that demonstrably did not take.
     *
     * `internal` rather than private so the settle/contradict rules can be driven from a
     * unit test without a socket; nothing outside this file calls it.
     */
    internal fun observe(item: Item, on: Boolean) {
        val before = value(item)
        when (item) { Item.LED -> ledOn = on; Item.GPS -> gpsOn = on; Item.MODE -> cine = on }
        // Take the intent AND settle it in ONE critical section. Reading it, deciding, then
        // removing it left a window in which two readers both saw the same pending write
        // and both announced its verdict.
        var settled: Wanted? = null
        val pending = synchronized(this) {
            val cur = want[item]
            if (cur != null && (cur.on == on || System.currentTimeMillis() - cur.atMs > SETTLE_MS)) {
                want.remove(item); settled = cur
            }
            cur
        }
        val w = settled
        if (w != null) {
            val after = System.currentTimeMillis() - w.atMs
            if (w.on == on) {
                DiagLog.info("${item.label}: write CONFIRMED — aircraft reads ${sh(item, on)} ${after} ms after the write")
            } else if (after > VERDICT_MS) {
                DiagLog.info("${item.label}: reads ${sh(item, on)}, and the last write asked for " +
                    "${sh(item, w.on)} — but that was ${after / 1000}s ago with no reading in between, " +
                    "so this says nothing about whether the write landed. Showing what the aircraft says.")
            } else {
                DiagLog.warn("${item.label}: write did NOT take — asked ${sh(item, w.on)}, aircraft still reads " +
                    "${sh(item, on)} ${after} ms later (address ${ParamName.tag(item.address().name())}, " +
                    (if (item.address().nameIsMeasured()) "measured on this aircraft" else "from the stored profile") +
                    "). The control goes back to what the aircraft says.")
            }
        } else if (pending != null) {
            // Inside the settle window and contradicted: the board is known to answer with
            // the old value for a few hundred ms after a write that did land. Keep waiting.
        } else if (before != null && before != on) {
            DiagLog.info("${item.label}: now ${sh(item, on)} on the aircraft (was ${sh(item, before)}) — not our write")
        } else if (before == null) {
            DiagLog.info("${item.label}: first reading — ${sh(item, on)}")
        }
    }

    /** Human wording per item, so a log line reads like the UI does. */
    private fun sh(item: Item, on: Boolean): String =
        if (item == Item.MODE) (if (on) "Cine" else "ATTI") else (if (on) "on" else "off")

    /**
     * Read exactly ONE value. Returns true when it answered.
     *
     * Same accounting as [refresh] for the value that did answer (it also proves a
     * drone is on the link), but a silent read here is NOT recorded as
     * `connected = false`: this is a per-item retry, and one unanswered item among
     * three says nothing about the link. Only [refresh], which asks for all three,
     * is entitled to that verdict.
     */
    suspend fun refreshOne(item: Item): Boolean {
        if (!ForegroundGate.readsAllowed()) return false
        val v = ParamRead.read(item.paramName()) ?: return false
        if (item == Item.MODE && v.isEmpty()) return false
        observe(item, decode(item, v))
        readsWork = true; setConnected(true); probed = true
        lastMs = System.currentTimeMillis()
        return true
    }

    /** Read LED/GPS/mode, each with ParamRead's own retries — the reliable path (the
     *  FC's replies on 40007 are racy, so per-param retries matter). Reads abort the
     *  instant DJI Fly takes the foreground (ParamRead's strict gate). `connected` =
     *  "a FLYC read answered" ⇒ a drone is on the link (the FC only replies when linked). */
    suspend fun refresh(): Boolean {
        if (!ForegroundGate.readsAllowed()) return false
        val t0 = System.currentTimeMillis()
        var got = 0
        val missed = ArrayList<String>(3)
        for (item in Item.values()) {
            val v = ParamRead.read(item.paramName())
            if (v == null || (item == Item.MODE && v.isEmpty())) { missed.add(item.label); continue }
            observe(item, decode(item, v))
            readsWork = true; got++
        }
        setConnected(got > 0)
        probed = true
        lastMs = System.currentTimeMillis()
        // One line per round, so a shared log carries the answer rate every retry budget in
        // this app is tuned to — it was not observable at runtime before 2026-08-20.
        ReadStats.endOfRound(got, Item.values().size, lastMs - t0,
            if (missed.isEmpty()) "" else "silent: " + missed.joinToString(", "))
        return got > 0
    }

    private fun decode(item: Item, v: ByteArray): Boolean =
        if (item == Item.MODE) (v[0].toInt() and 0xFF) == (ParameterAddress.MODE_CINE.toInt() and 0xFF)
        else valueOn(v)

    /**
     * Record that a full read round asked for every value and got nothing back while
     * the read gate stayed open — which is the same verdict [refresh] reaches, and
     * the only honest one: the flight controller answers a hash read whenever a drone
     * is linked, so total silence means no drone on the link.
     *
     * Separate from [refreshOne] on purpose: one silent item among three says nothing
     * about the link, so only a caller that asked for ALL of them may conclude this.
     */
    fun markSilent() {
        setConnected(false); probed = true
        lastMs = System.currentTimeMillis()
    }

    /** Log the link verdict only when it CHANGES — this is called on every read round
     *  and a line per round would be noise, while the transition is the fact. */
    private fun setConnected(v: Boolean) {
        if (connected == v) { connected = v; return }
        connected = v
        DiagLog.info(if (v) "flight controller is answering — a drone is on the link"
                     else "flight controller answered nothing this round — no drone on the link (or reads not routing)")
    }

    /** Drop all live state — called when the linked aircraft changes so readings
     *  from the previous drone don't linger on the new one. */
    fun reset() {
        ledOn = null; gpsOn = null; cine = null
        readsWork = false; probed = false; connected = null; lastMs = 0L
        synchronized(this) { want.clear() }
    }

    private fun valueOn(v: ByteArray): Boolean = v.isNotEmpty() && (v[0].toInt() and 0xFF) != 0

    fun statusJson(): String =
        "{\"led\":${jb(ledOn)},\"gps\":${jb(gpsOn)},\"cine\":${jb(cine)},\"connected\":${jb(connected)}," +
            "\"readsWork\":$readsWork,\"probed\":$probed," +
            "\"pending\":{" + Item.values().joinToString(",") { "\"${it.label}\":${jb(wanted(it))}" } + "}," +
            "\"ageMs\":${if (lastMs == 0L) -1 else System.currentTimeMillis() - lastMs}}"

    /** One line for the state snapshot: values, their age, and any unsettled write. */
    fun summary(): String = Item.values().joinToString(" · ") { i ->
        val v = value(i)?.let { sh(i, it) } ?: "?"
        val w = wanted(i)?.let { " (write pending: ${sh(i, it)})" } ?: ""
        "${i.label}=$v$w"
    } + " · reads " + (if (readsWork) "work" else "have never answered") +
        " · link " + (connected?.let { if (it) "up" else "silent" } ?: "not probed") +
        (if (lastMs == 0L) "" else " · last read ${(System.currentTimeMillis() - lastMs) / 1000}s ago")

    private fun jb(b: Boolean?): String = b?.toString() ?: "null"
}
