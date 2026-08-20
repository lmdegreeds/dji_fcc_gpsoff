package com.dji.fccgpsoff

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Session events on disk, so a log survives the process that wrote it (2026-08-20).
 *
 * **Why this exists.** Everything the app knew about a session lived in a memory ring.
 * When a user on a DJI Air 3 exported a log to report a defect, the minute that mattered
 * had already been evicted by bus traffic, the startup probe's context was gone, and the
 * sessions before it had never existed at all. Diagnosis then depended on inference from
 * retry timings. A controller in the field has no adb; the exported file is the whole
 * evidence base, and it has to carry more than the last few minutes of one process.
 *
 * **What is kept, and what is not.** Only the EVENT channel (INFO/WARN/ERR) goes to disk.
 * Wire hex is high-volume, ages badly, and already has a full-fidelity home in
 * [DumlCapture]; the events are the narrative, and they are small — a busy session is a
 * few hundred lines, tens of kilobytes. So several past sessions cost less than one
 * session's traffic would.
 *
 * **Budgets.** At most [MAX_FILES] session files, [MAX_FILE_BYTES] each, [MAX_TOTAL_BYTES]
 * in total; the oldest go first. The directory is app-private external storage, which
 * needs no permission, is visible over MTP at
 * `Android/data/com.dji.fccgpsoff/files/logs/`, and is removed with the app.
 *
 * **Cost.** One append every [FLUSH_MS] on a background thread, only when something was
 * written. Never on the caller's thread, never while [DiagLog]'s monitor is held.
 */
object LogStore {

    /** How many past sessions to keep. Five plus the live one covers "it did this again
     *  yesterday too" without turning the controller into a log server. */
    private const val MAX_FILES = 6
    /** Per-file ceiling. ~192 KB is roughly 1500 event lines — more than a session
     *  produces, so a normal session is never truncated. */
    private const val MAX_FILE_BYTES = 192 * 1024L
    /** Everything under `logs/` together. A hard stop, independent of the file count. */
    private const val MAX_TOTAL_BYTES = 1_200_000L
    /** Flush cadence. Long enough that a chatty minute is one write, short enough that a
     *  crash or a kill loses at most this much. */
    private const val FLUSH_MS = 15_000L
    /** Ceiling on lines held while the disk is unavailable, so a broken path cannot grow
     *  the heap without bound. */
    private const val MAX_PENDING = 4000

    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val lineFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    @Volatile private var dir: File? = null
    @Volatile private var file: File? = null
    @Volatile private var failed = false          // report a write failure once, not per line
    @Volatile private var written = 0L
    @Volatile private var dropped = 0L

    private val pending = ArrayList<DiagLog.Entry>(256)
    private val flusher = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "diaglog-store").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }

    /** True once a directory has been established and writing is possible. */
    val enabled: Boolean get() = dir != null && !failed

    /** Where the files are, for the UI and the state snapshot. */
    fun location(): String = dir?.absolutePath ?: "(not started)"

    /** Bytes currently held on disk under `logs/`. */
    fun bytesOnDisk(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L

    /** How many lines have reached disk, and how many were dropped for want of one. */
    fun stats(): Pair<Long, Long> = written to dropped

    /**
     * Start persisting. Called once from [App.onCreate], before anything else logs, so the
     * very first line of a session — the build banner — is on disk too.
     *
     * Failures here are not fatal: the app keeps its in-memory log and says the on-disk
     * one is unavailable, rather than refusing to run.
     */
    fun start(ctx: Context) {
        if (dir != null) return
        val base = runCatching { ctx.getExternalFilesDir(null) }.getOrNull() ?: ctx.filesDir
        val d = File(base, "logs")
        if (!d.exists() && !d.mkdirs()) { failed = true; return }
        dir = d
        val previous = newestOther(d)
        file = File(d, "session-${stamp.format(Date(DiagLog.startedMs))}-${DiagLog.sessionId}.log")
        // Everything below touches flash, and is queued onto the store thread. This runs
        // from Application.onCreate — before the first frame and inside BootReceiver's
        // delivery window — and reading a 192 KB session file there is exactly the kind of
        // main-thread I/O this project forbids elsewhere. The tasks run in submission order
        // on a single thread, so the header is still the first line in the file.
        val app = ctx.applicationContext
        flusher.submit {
            runCatching { file!!.appendText(DiagLog.header() + "\n") }
            // The epitaph of the session before this one, read BEFORE pruning could remove it.
            previous?.let { runCatching { readEpitaph(it) } }
            runCatching { prune() }
            // logs/ is bounded; its four neighbours were not.
            runCatching { Retention.sweep(app) }
        }
        flusher.scheduleWithFixedDelay({ runCatching { flush() } }, FLUSH_MS, FLUSH_MS, TimeUnit.MILLISECONDS)
    }

    private fun newestOther(d: File): File? =
        d.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }

    /**
     * Say how the PREVIOUS session ended, from the tail of its file.
     *
     * A log that simply stops is ambiguous three ways — the user closed the app, the OS
     * killed the process, or the controller lost power — and those are three different
     * diagnoses. An end marker present means a clean exit; absent means it died; and an
     * uptime that went backwards between the two headers means the controller rebooted,
     * which nothing else in the app can see after the fact.
     */
    private fun readEpitaph(prev: File) {
        // ONE read of the file, not one per thing we want from it: a session file can be
        // 192 KB, and this runs on the store thread where it is queued behind the header.
        val whole = prev.readText()
        val text = if (whole.length <= 8192) whole else whole.takeLast(8192)
        val head = whole.lineSequence().firstOrNull().orEmpty()
        val prevUptime = Regex("device up (\\d+)s").find(head)?.groupValues?.get(1)?.toLongOrNull()
        // The LAST marker, not the first: componentUp clears `ended`, so one file can carry
        // a marker followed by a resumed session that then died. Taking the first would
        // report exactly the clean exit this mechanism exists to disprove. And it only
        // counts when nothing of substance follows it.
        val end = END_RE.findAll(text).lastOrNull()
            ?.takeIf { m -> text.substring(m.range.last + 1).lineSequence().drop(1).none { it.isNotBlank() } }
        val lastLine = text.trimEnd().lines().lastOrNull().orEmpty().take(140)
        val gapS = ((System.currentTimeMillis() - prev.lastModified()) / 1000).coerceAtLeast(0)
        if (end != null) {
            val how = end.groupValues[2]
            if (how == "cleanly")
                DiagLog.info("previous session ${end.groupValues[1]} ended cleanly ${gapS}s ago " +
                    "(${prev.name}) — this is a fresh start, not a restart after a kill")
            else
                DiagLog.warn("previous session ${end.groupValues[1]} ENDED ON A CRASH ${gapS}s ago " +
                    "(${prev.name}) — the trace is in a crash-*.txt beside this log")
        } else {
            DiagLog.warn("previous session left NO end marker in ${prev.name}; its last line was " +
                "${gapS}s before this start — it was killed, it crashed, or the controller lost " +
                "power. Last line: $lastLine")
        }
        if (prevUptime != null && prevUptime > DiagLog.startedUptimeMs / 1000)
            DiagLog.info("the controller REBOOTED between the two sessions — device uptime was " +
                "${prevUptime}s when ${prev.name} started and is ${DiagLog.startedUptimeMs / 1000}s now")
    }

    private val END_RE = Regex("""===== session ([0-9a-f]+) ended \((cleanly|ON A CRASH)\)""")

    /**
     * Write the session's epitaph and get everything to disk.
     *
     * Called when the LAST of our components goes away, so a session that merely switched
     * screens is not declared over. Idempotent: a second call after the marker is written
     * only flushes.
     */
    fun markEnd(reason: String, clean: Boolean = true, sync: Boolean = false) {
        if (ended) { drain(sync); return }
        ended = true
        val ranS = (System.currentTimeMillis() - DiagLog.startedMs) / 1000
        val (writtenLines, _) = stats()
        val word = if (clean) "cleanly" else "ON A CRASH"
        DiagLog.info("===== session ${DiagLog.sessionId} ended ($word) after ${ranS}s · " +
            "$writtenLines event line(s) on disk · $reason =====")
        drain(sync)
    }

    /**
     * Get the queue to disk.
     *
     * [sync] means "on this thread, now" and has exactly one caller: the uncaught-exception
     * handler, where there is no later. Everywhere else this is reached from an Android
     * lifecycle callback on the MAIN thread, and an append plus a directory prune there is
     * precisely the main-thread I/O this project forbids — so it is queued instead
     * (2026-08-20).
     */
    private fun drain(sync: Boolean) {
        if (sync) runCatching { flush() }
        else runCatching { flusher.submit { runCatching { flush() } } }
    }

    @Volatile private var ended = false

    /**
     * Which of our components are alive right now.
     *
     * A session is over when the LAST one goes, not when any one of them does — the user
     * closing the Main screen while the keepalive keeps applying FCC is not the end of
     * anything. Kept as a set of names rather than a counter so a double stop cannot make
     * the tally negative and so the epitaph can say what went last.
     */
    private val live = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    fun componentUp(name: String) {
        val fresh = live.add(name)
        if (fresh && ended) {
            // Something started again after we had declared the session over — say so,
            // rather than leaving a marker in the middle of a file that keeps growing.
            ended = false
            DiagLog.info("session ${DiagLog.sessionId} RESUMED — $name started after the end marker")
        }
    }

    fun componentDown(name: String) {
        live.remove(name)
        if (live.isEmpty()) markEnd("last component gone: $name")
    }

    /**
     * Queue one event line. Called from inside [DiagLog]'s monitor, so it must not block,
     * must not do I/O, and must never call back into [DiagLog] — the flusher takes the two
     * locks in the opposite order and a synchronous call back would be a deadlock waiting
     * to happen.
     */
    fun offer(e: DiagLog.Entry) {
        synchronized(pending) {
            if (pending.size >= MAX_PENDING) { dropped++; return }
            pending.add(e)
        }
        // An ERR is the line most likely to be followed by the process ceasing to exist, so
        // it does not wait out [FLUSH_MS]. Submitted to the existing thread rather than
        // written here: the caller is inside [DiagLog]'s monitor.
        if (e.level == "ERR") runCatching { flusher.submit { runCatching { flush() } } }
    }

    /** Serialises the I/O half of [flush]. The queue keeps its own lock so [offer] never
     *  waits on a disk write; this one keeps two concurrent flushes from interleaving their
     *  appends, double-counting [written], or racing the rotation branch — flush is
     *  reachable from the store thread, from a dying process via [CrashLog], and from an
     *  export, all at once. */
    private val io = Any()

    /** Write everything queued. Safe to call from anywhere; does nothing when idle. */
    fun flush() {
        val f = file ?: return
        if (failed) return
        val batch: List<DiagLog.Entry>
        synchronized(pending) {
            if (pending.isEmpty()) return
            batch = ArrayList(pending)
            pending.clear()
        }
        synchronized(io) { writeBatch(f, batch) }
    }

    private fun writeBatch(f: File, batch: List<DiagLog.Entry>) {
        // I/O and any logging happen with NO lock held — see [offer].
        val text = buildString(batch.size * 80) {
            for (e in batch) append(lineFmt.get().format(Date(e.ts)))
                .append(' ').append(e.level.padEnd(4)).append(' ').append(e.msg).append('\n')
        }
        try {
            if (f.length() + text.length > MAX_FILE_BYTES) {
                // A session that will not stop talking must not eat the whole budget: cap
                // it, say so in the file itself, and let the newest lines keep flowing into
                // a fresh part rather than being dropped.
                f.appendText("--- this session exceeded ${MAX_FILE_BYTES / 1024} KB; continued in the next part ---\n")
                val next = File(f.parentFile, f.nameWithoutExtension + "+.log")
                file = next
                next.appendText(DiagLog.header() + " (continued)\n")
                next.appendText(text)
            } else f.appendText(text)
            written += batch.size
            prune()
        } catch (e: Exception) {
            if (!failed) {
                failed = true
                DiagLog.warn("log store: cannot write ${f.absolutePath} — ${e.message}; " +
                    "this session stays in memory only")
            }
        }
    }

    /** Keep the directory inside its budgets, oldest first. */
    private fun prune() {
        val d = dir ?: return
        val keep = file?.name
        var files = (d.listFiles() ?: return).filter { it.isFile }.sortedBy { it.lastModified() }
        var removed = 0
        while (files.size > MAX_FILES || files.sumOf { it.length() } > MAX_TOTAL_BYTES) {
            val victim = files.firstOrNull { it.name != keep } ?: break
            if (!victim.delete()) break
            removed++
            files = files.filter { it != victim }
        }
        if (removed > 0) DiagLog.info("log store: pruned $removed old session file(s) — " +
            "${files.size} kept, ${bytesOnDisk() / 1024} KB")
    }

    /**
     * The contents of every session file EXCEPT this one, oldest first — what a bundle
     * puts in front of the live session.
     *
     * Capped at [MAX_TOTAL_BYTES] of text so an export cannot become unmanageable even if
     * the directory somehow grew past its budget.
     */
    fun previousSessions(ctx: Context): List<String> {
        start(ctx)
        val d = dir ?: return emptyList()
        val keep = file?.name
        val files = (d.listFiles() ?: return emptyList())
            .filter { it.isFile && it.name != keep }
            .sortedBy { it.lastModified() }
        val out = ArrayList<String>(files.size)
        var budget = MAX_TOTAL_BYTES
        for (f in files) {
            if (budget <= 0) break
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            budget -= text.length
            out.add(if (text.length <= MAX_FILE_BYTES) text
                    else text.takeLast(MAX_FILE_BYTES.toInt()).let { "--- earlier part of ${f.name} omitted ---\n$it" })
        }
        return out
    }

    /** How many EARLIER sessions are on disk right now. */
    fun previousSessionCount(ctx: Context): Int {
        start(ctx)
        val keep = file?.name
        return dir?.listFiles()?.count { it.isFile && it.name != keep } ?: 0
    }

    /** Delete every stored session but the live one. Offered in the UI beside the export,
     *  because the user is the only one who can decide the old evidence is spent. */
    fun clearPast(ctx: Context): Int {
        start(ctx)
        val d = dir ?: return 0
        val keep = file?.name
        val gone = (d.listFiles() ?: return 0).count { it.isFile && it.name != keep && it.delete() }
        DiagLog.info("log store: deleted $gone stored session file(s)")
        return gone
    }
}
