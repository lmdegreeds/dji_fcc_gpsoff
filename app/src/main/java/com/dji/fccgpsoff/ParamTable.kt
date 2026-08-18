package com.dji.fccgpsoff

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Whole-table facts from `03:E0 GetTblAttribute`: how many slots the firmware's parameter
 * table has and a CRC over its entries.
 *
 * Together those two numbers are a **fingerprint of the firmware's parameter table**. The
 * app addresses parameters by name precisely because indices move between builds — on this
 * very aircraft `ce_country_type` sat at index 239 under v300 and 49 under v400 — but the
 * fingerprint gives a cheap way to notice that the *loaded catalog* is for a different
 * build than the aircraft in front of us. Lito X1 v400 answers: table 0 only, 1594 entries,
 * crc 0x2ae1a5ad.
 *
 * 1594 counts empty slots as well; the Param Studio export of the same board has 953 named
 * parameters, so this number is not a parameter count and must not be shown as one.
 */
object ParamTable {

    /** Measured with DJI Fly backgrounded: one 1500 ms window answers `03:E0` about half
     *  the time, so four attempts put a read at ~94%. */
    private const val ATTEMPTS = 4
    private const val WINDOW_MS = 1500

    @Volatile var last: ConfigTable.TableAttr? = null; private set

    /**
     * Read table [tableNo]'s attributes. Null means no answer — never "no such table",
     * which the board reports explicitly as status 9.
     *
     * Correlated by sequence number: the reply echoes only the table number, and every
     * client asks about table 0.
     */
    suspend fun attr(tableNo: Int = 0): ConfigTable.TableAttr? {
        repeat(ATTEMPTS) {
            if (!ForegroundGate.readsAllowed()) return null
            val s = DumlSeq.next()
            val payload = byteArrayOf((tableNo and 0xFF).toByte(), ((tableNo shr 8) and 0xFF).toByte())
            val inner = DumlWire.withSeq(
                DumlNative.nativeBuildFrame(
                    DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
                    DumlWire.CMDSET_FLYC, ConfigTable.CMDID_TBL_ATTRIBUTE, payload
                ), s
            )
            val ask = DumlWindow.Ask("table $tableNo 03:E0", DumlWire.wrap(inner)) { fr ->
                if (fr.cmdSet != DumlWire.CMDSET_FLYC ||
                    fr.cmdId != ConfigTable.CMDID_TBL_ATTRIBUTE ||
                    !fr.isResponse || fr.seq != s
                ) null else fr.payload
            }
            val pl = DumlWindow.ask(DumlWire.PORT_VIDEO_MIRROR, ask, WINDOW_MS)
            ConfigTable.parseTableAttr(pl ?: ByteArray(0))?.let {
                if (tableNo == 0) last = it
                return it
            }
        }
        return null
    }

    /**
     * Read the fingerprint and compare it with what was last seen for this aircraft.
     * Returns a human line; also persists the new fingerprint.
     *
     * A missing answer is reported as "unverified", never as "changed" — silence on this
     * bus is a routing failure, and invalidating a good catalog on it would be wrong.
     */
    suspend fun checkFingerprint(ctx: Context, serial: String): String {
        val a = attr(0) ?: return "table fingerprint unverified (03:E0 unanswered)"
        if (a.status != ConfigTable.ST_OK) return "table 0 rejected by the aircraft (status ${a.status})"
        val crcHex = "0x%08x".format(a.entriesCrc)
        val line = "table 0: ${a.entriesNum} slots, crc $crcHex"
        if (serial.isEmpty()) return line
        val prev = DeviceStore.tableFingerprint(ctx, serial)
        DeviceStore.saveTableFingerprint(ctx, serial, a.entriesCrc, a.entriesNum)
        return when {
            prev == null -> "$line (first time seen for this aircraft)"
            prev.first == a.entriesCrc && prev.second == a.entriesNum -> "$line (unchanged)"
            else -> "$line — CHANGED from 0x%08x/%d: the firmware's parameter table is not the one seen before, so indices and defaults from an older export may no longer apply"
                .format(prev.first, prev.second)
        }
    }
}

/**
 * Bulk read of the whole parameter table by index (`03:E1`), for an aircraft no bundled
 * export covers.
 *
 * **This is not how parameters are normally obtained** — names come from the bundled sets
 * and from imported exports, and per-parameter metadata comes from [ParamMeta.info] on
 * demand. This exists for the case where neither is available.
 *
 * Measured on a Lito X1 v400 with DJI Fly backgrounded: 32 requests share one window and
 * resolve 128 of 128 indices once missing ones are re-asked, at ~0.09 s per index, so the
 * full 1594-slot table takes roughly two and a half minutes. (An earlier estimate of ~58
 * minutes came from a bench harness that opened a socket and an HTTP round trip per index;
 * it measured the harness, not the aircraft.)
 *
 * Correlation is by sequence number, and it has to be: an empty slot answers with a bare
 * two-byte status that echoes neither table nor index. Roughly 40% of the slots are empty,
 * so a walker that guessed would mislabel real parameters as absent — the worst outcome
 * here, because it produces a catalog that is quietly incomplete.
 */
object ParamDump {

    /** One request per index; 32 share a window. Deeper was faster per index but started
     *  dropping replies, and a partial window costs a whole retry pass. */
    private const val DEPTH = 32
    private const val WINDOW_MS = 2500
    /** Breathing room between windows. Each window is its own socket on DJI Fly's video
     *  port, so the dump is a long series of short touches rather than one held socket. */
    private const val CHUNK_GAP_MS = 120L
    /** Re-ask unresolved indices this many times before reporting them as unknown. */
    private const val MAX_PASSES = 4

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    @Volatile var running = false; private set
    @Volatile var total = 0; private set
    @Volatile var resolved = 0; private set
    @Volatile var pass = 0; private set
    @Volatile var note = ""; private set
    @Volatile var startedAtMs = 0L; private set
    @Volatile var fingerprint = ""; private set
    private val named = java.util.concurrent.ConcurrentHashMap<Int, ConfigTable.Item>()
    private val emptySlots = java.util.Collections.synchronizedSet(HashSet<Int>())

    val namedCount: Int get() = named.size
    val emptyCount: Int get() = emptySlots.size
    /** Indices that never answered. Reported separately and never folded into "empty" —
     *  silence is a routing failure, not evidence that a slot is unused. */
    val unknownCount: Int get() = (total - named.size - emptySlots.size).coerceAtLeast(0)

    fun stop() { job?.cancel(); running = false; note = "stopped" }

    /**
     * Start a dump. Returns a message; the work runs in the background because a full pass
     * far outlives the diag server's 15 s socket timeout. Poll [statusJson].
     */
    suspend fun start(ctx: Context, tableNo: Int = 0): String {
        if (running) return "already running — $resolved/$total resolved"
        if (!ForegroundGate.readsAllowed())
            return "blocked: " + (ForegroundGate.blockReason() ?: "DJI Fly is active")
        val attr = ParamTable.attr(tableNo)
            ?: return "cannot start: 03:E0 did not answer, so the table size is unknown"
        if (attr.status != ConfigTable.ST_OK) return "cannot start: table $tableNo rejected (status ${attr.status})"
        if (attr.entriesNum <= 0 || attr.entriesNum > 65535)
            return "cannot start: implausible entry count ${attr.entriesNum}"

        named.clear(); emptySlots.clear()
        total = attr.entriesNum; resolved = 0; pass = 0
        fingerprint = "table $tableNo · %d slots · crc 0x%08x".format(attr.entriesNum, attr.entriesCrc)
        startedAtMs = System.currentTimeMillis()
        note = "running"
        running = true
        job = scope.launch { runCatching { walk(ctx, tableNo) }.onFailure { note = "failed: ${it.message}" } }
        return "dump started — $fingerprint; poll /table/status\n" + FLY_ADVICE
    }

    /**
     * DJI Fly's traffic is what makes a window miss. Measured on the same aircraft: with Fly
     * stopped a read window answered essentially every time; with Fly merely backgrounded,
     * 40–70%. Over 1594 indices that is the difference between one clean pass and several
     * retry passes, so it is worth saying out loud rather than silently taking longer.
     */
    const val FLY_ADVICE =
        "For the fastest, most complete run, STOP DJI Fly first (not just background it) — " +
            "with Fly running, windows miss and the dump needs extra passes."

    private suspend fun walk(ctx: Context, tableNo: Int) {
        try {
            var todo = (0 until total).toList()
            while (todo.isNotEmpty() && pass < MAX_PASSES) {
                pass++
                val missed = ArrayList<Int>()
                for (chunk in todo.chunked(DEPTH)) {
                    if (!ForegroundGate.readsAllowed()) {
                        note = "paused — DJI Fly took the foreground"
                        running = false
                        return
                    }
                    val seqs = IntArray(chunk.size) { DumlSeq.next() }
                    val asks = chunk.mapIndexed { k, idx ->
                        val s = seqs[k]
                        val payload = byteArrayOf(
                            (tableNo and 0xFF).toByte(), ((tableNo shr 8) and 0xFF).toByte(),
                            (idx and 0xFF).toByte(), ((idx shr 8) and 0xFF).toByte()
                        )
                        val inner = DumlWire.withSeq(
                            DumlNative.nativeBuildFrame(
                                DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK,
                                DumlWire.CMDSET_FLYC, ConfigTable.CMDID_ITEM_ATTRIBUTE, payload
                            ), s
                        )
                        DumlWindow.Ask("item $idx 03:E1", DumlWire.wrap(inner)) { fr ->
                            if (fr.cmdSet != DumlWire.CMDSET_FLYC ||
                                fr.cmdId != ConfigTable.CMDID_ITEM_ATTRIBUTE ||
                                !fr.isResponse || fr.seq != s
                            ) null else fr.payload
                        }
                    }
                    val out = DumlWindow.collect(DumlWire.PORT_VIDEO_MIRROR, asks, WINDOW_MS)
                    for (k in chunk.indices) {
                        val pl = out[k]
                        val item = pl?.let { ConfigTable.parseItem(it) }
                        when {
                            item == null -> missed.add(chunk[k])
                            item.status != ConfigTable.ST_OK -> { emptySlots.add(chunk[k]); resolved++ }
                            // A status-0 reply carries its own index; if it disagrees with the
                            // one we asked for, the sequence number collided — don't file it.
                            item.index != chunk[k] -> missed.add(chunk[k])
                            else -> { named[chunk[k]] = item; resolved++ }
                        }
                    }
                    delay(CHUNK_GAP_MS)
                }
                todo = missed
                if (todo.isNotEmpty()) DiagLog.info("param dump: pass $pass left ${todo.size} unresolved")
            }
            val secs = (System.currentTimeMillis() - startedAtMs) / 1000
            note = if (unknownCount == 0) "complete in ${secs}s"
                   else "finished in ${secs}s with $unknownCount index(es) unanswered"
            DiagLog.info("param dump: ${named.size} named, ${emptySlots.size} empty, $unknownCount unknown ($note)")
        } finally {
            running = false
        }
    }

    fun statusJson(): String =
        "{\"running\":$running,\"total\":$total,\"resolved\":$resolved,\"named\":${named.size}," +
            "\"empty\":${emptySlots.size},\"unknown\":$unknownCount,\"pass\":$pass," +
            "\"elapsedMs\":${if (startedAtMs == 0L) 0 else System.currentTimeMillis() - startedAtMs}," +
            "\"fingerprint\":${Json.quote(fingerprint)},\"note\":${Json.quote(note)}}"

    /**
     * The collected parameters in the **v1 `.dhp` shape** — a bare array of
     * `{name, value, min, max, default, type_id}`.
     *
     * Deliberate: [ParamCatalog.load] already parses that, so a board-dumped catalog goes
     * through exactly the same path as a Param Studio export and behaves identically
     * downstream (same encoder, same limits, same grouping, same editor). No new parser.
     * `value` is left empty; the ordinary read path fills it in.
     */
    fun asDhpJson(): String = buildString {
        append('[')
        var first = true
        for (idx in named.keys.sorted()) {
            val it = named[idx] ?: continue
            if (!first) append(',')
            first = false
            append("{\"name\":").append(Json.quote(it.name))
                .append(",\"value\":\"\"")
                .append(",\"min\":").append(Json.quote(it.min))
                .append(",\"max\":").append(Json.quote(it.max))
                .append(",\"default\":").append(Json.quote(it.def))
                .append(",\"type_id\":").append(it.typeId)
                .append(",\"table_no\":").append(it.tableNo)
                .append(",\"param_index\":").append(it.index)
                .append('}')
        }
        append(']')
    }

    /**
     * Persist the dump next to the aircraft's serial and load it as the active catalog.
     *
     * Refuses a partial dump unless [allowPartial] — a catalog that silently claims to be
     * the whole table is worse than none, because nothing downstream can tell.
     */
    fun save(ctx: Context, serial: String, allowPartial: Boolean): String {
        if (named.isEmpty()) return "nothing to save — no parameters were read"
        if (unknownCount > 0 && !allowPartial)
            return "refusing to save: $unknownCount index(es) never answered. Re-run, or pass &partial=1 " +
                "to save ${named.size} parameters and have the gap recorded in the name."
        val tag = if (unknownCount > 0) "-partial$unknownCount" else ""
        val name = "board-${serial.ifEmpty { "unknown" }}$tag.dhp"
        return try {
            val dir = java.io.File(ctx.filesDir, "param_tables").apply { mkdirs() }
            val f = java.io.File(dir, name)
            val json = asDhpJson()
            f.writeText(json)
            val n = ParamCatalog.load(json, name)
            "saved ${f.absolutePath} and loaded $n parameters as the active catalog"
        } catch (e: Exception) {
            "save failed: ${e.message}"
        }
    }

    private fun dir(ctx: Context) = java.io.File(ctx.filesDir, "param_tables")

    /** Dumps saved on this controller, newest first. */
    fun saved(ctx: Context): List<java.io.File> =
        dir(ctx).listFiles()?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun savedReport(ctx: Context): String {
        val fs = saved(ctx)
        if (fs.isEmpty()) return "no saved dumps yet — run /table/dump then /table/save"
        return fs.joinToString("\n") {
            "%s  %d KB  %s".format(it.name, it.length() / 1024, java.util.Date(it.lastModified()))
        }
    }

    /**
     * Re-load a previously saved dump as the active catalog.
     *
     * Without this the dump was only half a feature: it survived on disk but died with the
     * process, because [save] can only write what is still in memory. Re-running a
     * two-minute radio sweep to recover a file already sitting in `filesDir` would be
     * absurd.
     */
    fun loadSaved(ctx: Context, name: String): String {
        val safe = name.substringAfterLast('/').substringAfterLast('\\')
        val f = java.io.File(dir(ctx), safe)
        if (!f.isFile) return "no such saved dump: $safe"
        return try {
            "loaded ${ParamCatalog.load(f.readText(), safe)} parameters from $safe"
        } catch (e: Exception) {
            "load failed: ${e.message}"
        }
    }
}

/** Shared sequence-number source for the correlated Config Table reads.
 *
 *  Frames built by the native builder all carry seq 0, so anything that needs to tell its
 *  own reply apart from another client's stamps one of these in via [DumlWire.withSeq]. The
 *  high range keeps us clear of counters other clients start from zero. */
internal object DumlSeq {
    private val n = java.util.concurrent.atomic.AtomicInteger(0x4000)
    fun next(): Int {
        val v = n.incrementAndGet() and 0x7FFF
        return if (v < 0x4000) 0x4000 else v
    }
}
