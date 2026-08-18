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
 * In-memory diagnostic log (ring buffer) with logcat mirroring and file export.
 * Every DUML TX/RX and every feature action lands here so a session can be
 * inspected on-device and shared for debugging a specific aircraft/firmware.
 */
object DiagLog {

    data class Entry(val ts: Long, val level: String, val msg: String)

    private const val MAX = 3000
    private val buf = ArrayDeque<Entry>(MAX)
    // SimpleDateFormat is not thread-safe; the UI and the (concurrent) diag server
    // both format log lines, so give each thread its own. Subclassed rather than
    // ThreadLocal.withInitial, which needs API 26 and this app runs from 24.
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /** UI hook — called on every new line (may be off the main thread). */
    @Volatile var listener: ((Entry) -> Unit)? = null

    @Synchronized private fun add(level: String, msg: String) {
        val e = Entry(System.currentTimeMillis(), level, msg)
        if (buf.size >= MAX) buf.removeFirst()
        buf.addLast(e)
        Log.println(if (level == "ERR") Log.ERROR else Log.INFO, "DJI_FCC_GPSOFF", "$level $msg")
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
     * The controller pushes `06:AE` at ~10 Hz and the aux/OSD stream is faster still,
     * so an unfiltered line per frame fills the [MAX]-entry ring in a couple of
     * minutes and evicts exactly the INFO lines a session is diagnosed from. Observed:
     * one live session left 881 aux frames in the ring and no keepalive history at all.
     *
     * So each distinct route+route-address+command is logged on first sighting and then
     * at most once per [RX_REPEAT_MS], carrying the count it stands for. New frame types
     * — the interesting ones — still appear immediately. Full fidelity, payload by
     * payload, lives in [DumlCapture] (16 MB byte ring, `/capframes`), not here.
     */
    fun rxFrame(sender: Int, receiver: Int, cs: Int, ci: Int, payload: ByteArray, route: Int = 0) {
        val key = (route shl 28) xor (sender shl 20) xor (receiver shl 12) xor (cs shl 4) xor ci
        val now = System.currentTimeMillis()
        var suppressed = 0L
        synchronized(this) {
            val seen = rxSeen[key]
            if (seen != null && now - seen[0] < RX_REPEAT_MS) { seen[1]++; return }
            suppressed = seen?.get(1) ?: 0L
            rxSeen[key] = longArrayOf(now, 0L)
            // Bounded: a misbehaving bus cannot grow this without limit.
            if (rxSeen.size > RX_KEYS_MAX) rxSeen.clear()
        }
        val repeat = if (suppressed > 0) " (x${suppressed + 1})" else ""
        add("RX", "%s %02X->%02X %02X:%02X %s%s".format(
            if (route == 1) "aux" else "main", sender, receiver, cs, ci, DumlWire.toHex(payload), repeat))
    }

    /** Per-command log cadence for recurring telemetry. */
    private const val RX_REPEAT_MS = 5_000L
    private const val RX_KEYS_MAX = 512
    private val rxSeen = HashMap<Int, LongArray>()

    // Snapshot under the lock, format OUTSIDE it: building a big string while
    // holding the monitor blocks add() on the RX thread (they share it), which
    // stalled large /log and /logjson responses into empty bodies under load.
    private fun snapshot(): List<Entry> = synchronized(this) { ArrayList(buf) }

    /** The newest [n] entries, copied under the lock — touching only those n, so the RX
     *  thread waiting on the same monitor isn't held up by the whole buffer. */
    private fun tailSnapshot(n: Int): List<Entry> = synchronized(this) {
        val from = maxOf(0, buf.size - n)
        val out = ArrayList<Entry>(buf.size - from)
        for (i in from until buf.size) out.add(buf[i])
        out
    }

    fun dump(): String =
        snapshot().joinToString("\n") { line(it) }

    /**
     * The newest [n] lines, formatted.
     *
     * The on-screen log only ever shows the tail, so formatting all [MAX] entries to throw
     * away all but the last screenful is pure waste — and on a busy bus that waste ran on
     * the main thread several times a second and made the whole UI stutter.
     */
    fun tail(n: Int): String =
        tailSnapshot(n).joinToString("\n") { line(it) }

    private fun line(e: Entry) = "${fmt.get().format(Date(e.ts))} ${e.level.padEnd(4)} ${e.msg}"

    @Synchronized fun clear() { buf.clear(); info("log cleared") }

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
     */
    fun export(ctx: Context): String {
        val text = dump()
        val name = "duml-${System.currentTimeMillis()}.log"
        val where: String = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return fallback(ctx, name, text)
                resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                "Download/$name"
            } else {
                @Suppress("DEPRECATION")
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val f = File(dir, name); f.writeText(text); f.absolutePath
            }
        } catch (e: Exception) {
            fallback(ctx, name, text)
        }
        info("exported to $where")
        return where
    }

    private fun fallback(ctx: Context, name: String, text: String): String = try {
        val f = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name)
        f.writeText(text); f.absolutePath
    } catch (e: Exception) {
        "export failed: ${e.message}"
    }

    /** Current buffer as text, for the Share action. */
    fun asText(): String = dump()
}
