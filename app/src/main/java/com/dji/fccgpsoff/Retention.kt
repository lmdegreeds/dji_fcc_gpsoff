package com.dji.fccgpsoff

import android.content.Context
import java.io.File

/**
 * How much diagnostic data this app is allowed to keep on the controller, and the one
 * place that enforces it (2026-08-20).
 *
 * [LogStore] bounds `logs/` at 1.2 MB, but it had four unbounded neighbours: `crash-*.txt`
 * accumulated forever in two places, `experiments.log` was append-only, and every board
 * dump wrote another `.dhp` under `param_tables`. The only deleter in the codebase was
 * `CrashLog.clear`, which is a delete-all behind a UI button. On a controller the user
 * cannot inspect without a cable, "the app quietly filled the disk" is a failure mode with
 * no symptom until it is severe.
 *
 * **Deleting evidence is the point of the caution here.** Every sweep is oldest-first,
 * best-effort, and always logged with what went — so a file that is missing from a later
 * report is accounted for rather than mysterious. Exported bundles in `Download/` are NOT
 * touched: that folder belongs to the user and it is the one place they can reach without
 * a cable.
 *
 * Runs once per session, from [LogStore.start], on the store thread.
 */
object Retention {

    /** Crash traces. Five covers "it has been doing this for a few days" — the sixth has
     *  never been the one anybody read. */
    private const val KEEP_CRASHES = 5
    /** Board dumps are the expensive ones: ~200 KB each and one per aircraft per firmware. */
    private const val KEEP_TABLES = 20
    private const val MAX_TABLE_BYTES = 4L * 1024 * 1024
    /** The experiment ledger is a single append-only file. */
    private const val KEEP_EXPERIMENT_ROWS = 2000

    fun sweep(ctx: Context) {
        val notes = ArrayList<String>(4)
        runCatching { crashes(ctx) }.getOrNull()?.let(notes::add)
        runCatching { tables(ctx) }.getOrNull()?.let(notes::add)
        runCatching { experiments(ctx) }.getOrNull()?.let(notes::add)
        if (notes.isNotEmpty()) DiagLog.info("retention: " + notes.joinToString(" · "))
        DiagLog.info("retention: this app holds ${footprint(ctx) / 1024} KB of diagnostics on the " +
            "controller — " + breakdown(ctx))
    }

    private fun crashes(ctx: Context): String? {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val all = dir.listFiles { f -> f.name.startsWith("crash-") }?.sortedBy { it.lastModified() }
            ?: return null
        if (all.size <= KEEP_CRASHES) return null
        val gone = all.take(all.size - KEEP_CRASHES).count { it.delete() }
        return if (gone == 0) null else "crash files ${all.size} → ${all.size - gone}"
    }

    private fun tables(ctx: Context): String? {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "param_tables")
        var files = dir.listFiles { f -> f.isFile }?.sortedBy { it.lastModified() } ?: return null
        if (files.size <= KEEP_TABLES && files.sumOf { it.length() } <= MAX_TABLE_BYTES) return null
        val before = files.size
        while (files.size > KEEP_TABLES || files.sumOf { it.length() } > MAX_TABLE_BYTES) {
            val victim = files.firstOrNull() ?: break
            if (!victim.delete()) break
            files = files.drop(1)
        }
        return if (files.size == before) null
        else "board dumps $before → ${files.size} (${files.sumOf { it.length() } / 1024} KB)"
    }

    /**
     * Trim the experiment ledger to its newest rows.
     *
     * Rewritten in place rather than rotated: it is one small text file, the reader always
     * wants the end of it, and a second file would be one more thing to remember to send.
     */
    private fun experiments(ctx: Context): String? {
        val f = File(ctx.filesDir, "experiments.log")
        if (!f.isFile) return null
        val before = f.length()
        val lines = runCatching { f.readLines() }.getOrNull() ?: return null
        if (lines.size <= KEEP_EXPERIMENT_ROWS) return null
        runCatching { f.writeText(lines.takeLast(KEEP_EXPERIMENT_ROWS).joinToString("\n", postfix = "\n")) }
            .getOrElse { return null }
        return "experiments.log ${before / 1024} KB → newest $KEEP_EXPERIMENT_ROWS rows (${f.length() / 1024} KB)"
    }

    /** Every byte this app holds under its own directories. Excludes `Download/`. */
    fun footprint(ctx: Context): Long {
        val ext = ctx.getExternalFilesDir(null)
        return (ext?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L) +
            (File(ctx.filesDir, "experiments.log").takeIf { it.isFile }?.length() ?: 0L)
    }

    private fun breakdown(ctx: Context): String {
        val ext = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        fun count(p: (File) -> Boolean): Pair<Int, Long> {
            val f = ext.walkBottomUp().filter { it.isFile && p(it) }.toList()
            return f.size to f.sumOf { it.length() }
        }
        val (logN, logB) = count { it.parentFile?.name == "logs" }
        val (crashN, crashB) = count { it.name.startsWith("crash-") }
        val (tblN, tblB) = count { it.parentFile?.name == "param_tables" }
        val expB = File(ctx.filesDir, "experiments.log").takeIf { it.isFile }?.length() ?: 0L
        return "$logN session log(s) ${logB / 1024} KB · $crashN crash file(s) ${crashB / 1024} KB · " +
            "$tblN board dump(s) ${tblB / 1024} KB · experiments.log ${expB / 1024} KB"
    }
}
