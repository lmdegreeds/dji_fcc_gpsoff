package com.dji.fccgpsoff

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-memory diagnostic log with logcat mirroring and file export.
 *
 * **Two rings, not one (2026-08-20).** Wire traffic and session events used to share a
 * single 3000-entry ring, and on a busy bus the traffic evicted the events — the
 * suppression in [rxFrame] exists because one live session left 881 aux frames in the
 * ring and no keepalive history at all. Suppression only slowed that down; it could not
 * stop it. Now TX/RX go to their own ring and everything else goes to the event ring, each with its own
 * capacity, so no amount of bus chatter can push out the lines a session is diagnosed
 * from. A dump merges the two by timestamp, so a reader still sees one story.
 *
 * **Nothing is silently lost.** Each ring counts what it dropped, and a dump says so at
 * the top. A truncated log used to be byte-for-byte indistinguishable from a complete one.
 *
 * **Lines carry a date.** The format was `HH:mm:ss.SSS`, which cannot be tied to a
 * calendar day, ordered against a second log, or lined up with a DJI flight record.
 *
 * Full-fidelity wire bytes still live in [DumlCapture] (16 MB byte ring, `/capframes`);
 * this is the readable narrative, and [LogStore] is what makes it survive a restart.
 */
object DiagLog {

    data class Entry(val ts: Long, val level: String, val msg: String)

    /** Session events: INFO / WARN / ERR. Sized so a long field session survives intact —
     *  a busy session produces a few hundred of these, so 4000 is many sessions' worth. */
    private const val MAX_EVENTS = 4000
    /** Wire traffic: TX / RX. Big enough to hold the burst around an interesting moment,
     *  small enough that a chatty bus cannot own the process's memory. */
    private const val MAX_WIRE = 4000
    /** Per-line ceiling. A full 1023-byte frame is 2046 hex characters; past this the tail
     *  is dropped with a marker, because [DumlCapture] is where whole payloads belong. */
    private const val MAX_MSG = 900

    private val events = ArrayDeque<Entry>(MAX_EVENTS)
    private val wireBuf = ArrayDeque<Entry>(MAX_WIRE)
    private var droppedEvents = 0L
    private var droppedWire = 0L

    /** Identifies THIS process lifetime. Two lifetimes concatenated in one file used to be
     *  indistinguishable; every session header and every on-disk file carries this. */
    val sessionId: String = java.lang.Long.toHexString(System.currentTimeMillis()).takeLast(6)
    val startedMs: Long = System.currentTimeMillis()

    // SimpleDateFormat is not thread-safe; the UI and the (concurrent) diag server
    // both format log lines, so give each thread its own. Subclassed rather than
    // ThreadLocal.withInitial, which needs API 26 and this app runs from 24.
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val fullFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US)
    }

    /** UI hook — called on every new line (may be off the main thread). */
    @Volatile var listener: ((Entry) -> Unit)? = null

    /** Where an entry belongs. Wire traffic must never evict a session event. */
    private fun isWire(level: String) = level == "TX" || level == "RX"

    @Synchronized private fun add(level: String, msg: String) {
        val text = if (msg.length <= MAX_MSG) msg else msg.take(MAX_MSG) + "…(+${msg.length - MAX_MSG} chars)"
        val e = Entry(System.currentTimeMillis(), level, text)
        if (isWire(level)) {
            if (wireBuf.size >= MAX_WIRE) { wireBuf.removeFirst(); droppedWire++ }
            wireBuf.addLast(e)
        } else {
            if (events.size >= MAX_EVENTS) { events.removeFirst(); droppedEvents++ }
            events.addLast(e)
            LogStore.offer(e)          // events, and only events, are what survives a restart
        }
        Log.println(if (level == "ERR") Log.ERROR else Log.INFO, "DJI_FCC_GPSOFF", "$level $text")
        listener?.invoke(e)
    }

    fun info(msg: String) = add("INFO", msg)
    fun warn(msg: String) = add("WARN", msg)
    fun err(msg: String)  = add("ERR", msg)
    fun tx(port: Int, tag: String, wire: ByteArray) = add("TX", "p$port $tag ${DumlWire.toHex(wire)}")
    fun rx(port: Int, tag: String, data: ByteArray) = add("RX", "p$port $tag ${DumlWire.toHex(data)}")

    /**
     * Log one received frame — but never let recurring telemetry bury the log.
     *
     * The controller pushes `06:AE` at ~10 Hz and the aux/OSD stream is faster still, so an
     * unfiltered line per frame fills the ring in a couple of minutes. Each distinct
     * route+routing+command is logged on first sighting and then at most once per
     * [RX_REPEAT_MS], carrying the count it stands for. New frame types — the interesting
     * ones — still appear immediately.
     *
     * The key is a Long. It used to be `(route shl 28) xor (sender shl 20) xor
     * (receiver shl 12) xor (cs shl 4) xor ci`, whose last two terms overlap: cmdSet's low
     * nibble sits on cmdId's high nibble, so `03:44` and `07:04` collided and one of them
     * was suppressed as if it were the other. A reader could not trust that a command
     * missing from the log was missing from the bus (fixed 2026-08-20).
     */
    fun rxFrame(sender: Int, receiver: Int, cs: Int, ci: Int, payload: ByteArray, route: Int = 0) {
        val key = (route.toLong() shl 32) or (sender.toLong() shl 24) or
            (receiver.toLong() shl 16) or (cs.toLong() shl 8) or ci.toLong()
        val now = System.currentTimeMillis()
        var suppressed = 0L
        synchronized(this) {
            val seen = rxSeen[key]
            if (seen != null && now - seen[0] < RX_REPEAT_MS) { seen[1]++; return }
            suppressed = seen?.get(1) ?: 0L
            rxSeen[key] = longArrayOf(now, 0L)
            // Bounded: a misbehaving bus cannot grow this without limit. Say so when it
            // happens — dropping the tallies silently hid how much had been suppressed.
            if (rxSeen.size > RX_KEYS_MAX) {
                val pending = rxSeen.values.sumOf { it[1] }
                rxSeen.clear()
                if (pending > 0) add("INFO", "rx suppression table reset — $pending suppressed frame(s) unaccounted")
            }
        }
        val repeat = if (suppressed > 0) " (x${suppressed + 1})" else ""
        add("RX", "%s %02X->%02X %02X:%02X %s%s".format(
            if (route == 1) "aux" else "main", sender, receiver, cs, ci, DumlWire.toHex(payload), repeat))
    }

    /** Per-command log cadence for recurring telemetry. */
    private const val RX_REPEAT_MS = 5_000L
    private const val RX_KEYS_MAX = 512
    private val rxSeen = HashMap<Long, LongArray>()

    // Snapshot under the lock, format OUTSIDE it: building a big string while
    // holding the monitor blocks add() on the RX thread (they share it), which
    // stalled large /log and /logjson responses into empty bodies under load.
    private fun snapshot(): List<Entry> = synchronized(this) { merge(events, wireBuf) }

    /** Both rings in timestamp order. Each is already ordered, so this is a linear merge. */
    private fun merge(a: Collection<Entry>, b: Collection<Entry>): List<Entry> {
        val out = ArrayList<Entry>(a.size + b.size)
        val ia = a.iterator(); val ib = b.iterator()
        var ea = if (ia.hasNext()) ia.next() else null
        var eb = if (ib.hasNext()) ib.next() else null
        while (ea != null || eb != null) {
            if (eb == null || (ea != null && ea.ts <= eb.ts)) { out.add(ea!!); ea = if (ia.hasNext()) ia.next() else null }
            else { out.add(eb); eb = if (ib.hasNext()) ib.next() else null }
        }
        return out
    }

    /**
     * The newest [n] entries across both rings, copied under the lock.
     *
     * Only [n] from EACH ring is touched, not the whole of both: this runs on the main
     * thread several times a second while the Log page is open, and merging 8000 entries to
     * throw away all but 400 of them is the same waste that made the UI stutter before (see
     * [tail]). Taking the newest n from each ring cannot miss any of the newest n overall,
     * so slicing the merge afterwards is exact.
     */
    private fun tailSnapshot(n: Int): List<Entry> = synchronized(this) {
        fun lastOf(d: ArrayDeque<Entry>): List<Entry> {
            val from = maxOf(0, d.size - n)
            val out = ArrayList<Entry>(d.size - from)
            for (i in from until d.size) out.add(d[i])
            return out
        }
        val all = merge(lastOf(events), lastOf(wireBuf))
        if (all.size <= n) all else ArrayList(all.subList(all.size - n, all.size))
    }

    /** How many lines each ring has evicted this session. */
    @Synchronized fun dropped(): Pair<Long, Long> = droppedEvents to droppedWire

    /** Live counts, for the state snapshot. */
    @Synchronized fun counts(): Triple<Int, Int, Long> =
        Triple(events.size, wireBuf.size, droppedEvents + droppedWire)

    /**
     * The session banner. Every dump and every on-disk file starts with one, so a file
     * holding several process lifetimes can still be read apart, and so a log shared
     * without its controller still says which build produced it.
     */
    fun header(): String =
        "===== DJI_FCC_GPSOFF session $sessionId · started ${fullFmt.get().format(Date(startedMs))}" +
            // Device uptime, so a later session can tell an app restart from a controller
            // power-cycle: uptime going BACKWARDS between two sessions is a reboot, and
            // there is no other way to see one after the fact.
            " · device up ${startedUptimeMs / 1000}s ====="

    /** Device uptime when this session began — the reboot detector; see [header]. */
    val startedUptimeMs: Long = android.os.SystemClock.elapsedRealtime()

    fun dump(): String = buildString {
        appendLine(header())
        val (de, dw) = dropped()
        if (de > 0 || dw > 0)
            appendLine("--- NOTE: $de event line(s) and $dw wire line(s) were evicted from the in-memory " +
                "rings before this dump" +
                (if (LogStore.enabled) "; the on-disk session files hold the older EVENTS (wire lines are never stored)"
                 else "; the on-disk store is not running, so the older events are gone with them") + " ---")
        for (e in snapshot()) appendLine(line(e))
    }

    /**
     * The newest [n] lines, formatted.
     *
     * The on-screen log only ever shows the tail, so formatting all entries to throw away
     * all but the last screenful is pure waste — and on a busy bus that waste ran on the
     * main thread several times a second and made the whole UI stutter.
     */
    fun tail(n: Int): String = tailSnapshot(n).joinToString("\n") { line(it) }

    private fun line(e: Entry) = "${fmt.get().format(Date(e.ts))} ${e.level.padEnd(4)} ${e.msg}"

    @Synchronized fun clear() {
        events.clear(); wireBuf.clear(); droppedEvents = 0; droppedWire = 0
        info("log cleared")
    }

    /** Structured rows for the web UI's filterable table: [{t,l,m}, ...]. */
    fun asJson(): String = buildString {
        append('[')
        var first = true
        for (e in snapshot()) {
            if (!first) append(','); first = false
            append("{\"t\":\"").append(fmt.get().format(Date(e.ts)))
            append("\",\"l\":\"").append(e.level)
            append("\",\"m\":\"").append(Json.esc(e.msg)).append("\"}")
        }
        append(']')
    }

    /**
     * Export to the PUBLIC Downloads folder so it's visible over MTP / in any
     * file manager. Android 10+ uses MediaStore (no permission needed); older
     * writes straight to /sdcard/Download. Returns a user-facing location.
     *
     * What goes in is the BUNDLE ([bundle]): this session AND the sessions before it, plus
     * a state snapshot. The single most expensive part of diagnosing the Air 3 report was
     * that the log had been exported after the interesting minute had already scrolled out
     * of a memory-only ring, and the sessions before it did not exist at all.
     */
    class Export(val path: String, val uri: android.net.Uri?)

    fun export(ctx: Context): String = exportFile(ctx).path

    /**
     * As [export], but also hands back the MediaStore `content://` URI when there is one,
     * so the Share action can attach the FILE instead of pasting its text into an Intent
     * extra — a bundle carrying several stored sessions is well past the ~1 MB a Binder
     * transaction will take, and it would throw on exactly the sessions worth sharing.
     */
    fun exportFile(ctx: Context): Export {
        LogStore.flush()
        val text = bundle(ctx)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        // The version goes in the file name for the same reason it goes in the APK's:
        // so a file sitting in Downloads can be tied to a build without opening it.
        val ver = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty().ifEmpty { "unknown" }
        val name = "dji-fcc-gpsoff-$ver-$stamp-$sessionId.log"
        var shareUri: android.net.Uri? = null
        val where: String = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return Export(fallback(ctx, name, text), null)
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                shareUri = uri
                "Download/$name"
            } else {
                // No WRITE_EXTERNAL_STORAGE is declared, so this branch throws on most
                // API 24-28 devices and lands in the app folder instead. Say which one it
                // was rather than printing a path the file is not at.
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = File(dir, name); f.writeText(text); f.absolutePath
            }
        } catch (e: Exception) {
            fallback(ctx, name, text)
        }
        info("exported ${text.length} chars to $where " +
            "(session $sessionId, ${LogStore.previousSessionCount(ctx)} earlier session(s) included)")
        return Export(where, shareUri)
    }

    private fun fallback(ctx: Context, name: String, text: String): String = try {
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name)
        f.writeText(text); f.absolutePath
    } catch (e: Exception) {
        "export failed: ${e.message}"
    }

    /**
     * Everything a maintainer should receive: what the app is and how it is set up, then
     * the sessions that came before this one, then this one.
     *
     * Ordered oldest-first so the file reads forwards in time end to end.
     */
    fun bundle(ctx: Context): String = buildString {
        appendLine(header())
        appendLine()
        appendLine(Snapshot.text(ctx))
        appendLine()
        val past = LogStore.previousSessions(ctx)
        if (past.isNotEmpty()) {
            appendLine("===== ${past.size} earlier session(s) from this controller =====")
            for (p in past) { appendLine(p); appendLine() }
        }
        appendLine("===== this session =====")
        append(dump())
    }

    /** Current buffer as text, for the Share action. */
    fun asText(): String = dump()
}
