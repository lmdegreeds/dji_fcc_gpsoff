package com.dji.fccgpsoff

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Access to DJI Fly's flight records:
 *
 *   /sdcard/Android/data/dji.go.v5/files/FlightRecord/FlightRecord_2026-08-09_[18-28-35].txt
 *
 * Since Android 11 that directory belongs to another app and is hidden from
 * plain file access (MANAGE_EXTERNAL_STORAGE does NOT lift it either), so three
 * routes are tried, best first:
 *
 *   1. SAF — a persisted ACTION_OPEN_DOCUMENT_TREE grant on the FlightRecord
 *      folder. The one route that is supposed to work on RC2 (Android 11);
 *      some ROMs block picking Android/data, hence the fallbacks.
 *   2. Plain File access — works on older/permissive ROMs and for any DJI
 *      package still readable (also covers the dji.go.v6 mod install).
 *   3. An explicit directory passed per request (?dir=/path), for records that
 *      were moved/copied somewhere reachable.
 *
 * Records are then served by [DiagServer] over Wi-Fi (single file or a zip) or
 * copied to the public Download/FlightRecord folder for MTP pickup.
 */
object FlightRecords {

    /** DJI apps that keep flight records in their external files dir. */
    val PACKAGES = listOf(
        "dji.go.v5",              // DJI Fly (stock)
        "dji.go.v6",              // the FCC/tweakbox mod (renamed package)
        "dji.go.v4", "dji.pilot", "dji.pilot2", "com.dji.industry.pilot"
    )

    private const val SUBDIR = "files/FlightRecord"
    private const val MAX_FILE = 32L * 1024 * 1024
    private const val MAX_ZIP = 64L * 1024 * 1024

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** One record. [file] xor [uri] is set, depending on which route found it. */
    data class Rec(
        val name: String,          // the .txt file name in the FlightRecord root
        val size: Long,
        val modified: Long,
        val src: String,           // "saf", a package name, or "dir"
        val file: File? = null,
        val uri: Uri? = null
    )

    // ---------------------------------------------------------------- listing

    fun dirs(): List<Pair<String, File>> {
        val root = Environment.getExternalStorageDirectory()
        return PACKAGES.map { it to File(root, "Android/data/$it/$SUBDIR") }
    }

    /**
     * Confine a caller-supplied `?dir=` to shared external storage. The diag
     * server is unauthenticated, so an arbitrary path would let a LAN client walk
     * and download anything the process can read — including app-private files and
     * SharedPreferences under /data. Canonicalize and require the result to sit
     * inside the external-storage root; reject everything else (traversal included).
     */
    private fun allowedExtraDir(path: String): File? {
        val d = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        val root = runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull() ?: return null
        val ok = d.path == root.path || d.path.startsWith(root.path + File.separator)
        if (!ok) DiagLog.warn("flight records: dir rejected (outside shared storage): $path")
        return if (ok) d else null
    }

    /** DJI flight logs are the `.txt` files in the FlightRecord ROOT — subfolders
     *  (module / MCDat logs, etc.) are not flight logs and are excluded. */
    private fun isFlightLog(name: String) = name.endsWith(".txt", ignoreCase = true)

    /** All flight logs visible through any route, newest first. */
    fun list(ctx: Context, extraDir: String? = null): List<Rec> {
        val out = LinkedHashMap<String, Rec>()
        if (extraDir != null) {
            val d = allowedExtraDir(extraDir)
            if (d != null && d.isDirectory) runCatching { walk(d, "dir", out) }
        }
        safTree(ctx)?.let { tree ->
            runCatching { walkSaf(ctx, tree, DocumentsContract.getTreeDocumentId(tree), out) }
                .onFailure { DiagLog.warn("flight records (saf): ${it.message}") }
        }
        for ((pkg, d) in dirs()) {
            if (!d.isDirectory) continue
            runCatching { walk(d, pkg, out) }
        }
        return out.values.sortedByDescending { it.modified }
    }

    fun find(ctx: Context, name: String, extraDir: String? = null): Rec? =
        list(ctx, extraDir).firstOrNull { it.name == name }

    /** Root-level `.txt` files only — no recursion into subdirectories. */
    private fun walk(dir: File, src: String, out: MutableMap<String, Rec>) {
        for (f in dir.listFiles() ?: return) {
            if (f.isDirectory || !isFlightLog(f.name)) continue
            if (!out.containsKey(f.name)) out[f.name] = Rec(f.name, f.length(), f.lastModified(), src, file = f)
        }
    }

    /** Root-level `.txt` documents only — the direct children of the granted tree. */
    private fun walkSaf(ctx: Context, tree: Uri, docId: String, out: MutableMap<String, Rec>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val nm = c.getString(1) ?: continue
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) continue   // no subfolders
                if (!isFlightLog(nm)) continue
                if (!out.containsKey(nm)) {
                    out[nm] = Rec(nm, c.getLong(3), c.getLong(4), "saf",
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, id))
                }
            }
        }
    }

    // ------------------------------------------------------------------ reads

    fun open(ctx: Context, rec: Rec): InputStream? =
        rec.file?.inputStream() ?: rec.uri?.let { ctx.contentResolver.openInputStream(it) }

    fun read(ctx: Context, rec: Rec): ByteArray? {
        if (rec.size > MAX_FILE) throw IllegalStateException("file too large: ${rec.size} B")
        // rec.size is a hint: over SAF the provider may report 0 or -1 (unknown),
        // which would sail past the check above — so cap the actual read too.
        return open(ctx, rec)?.use { readLimited(it, MAX_FILE) }
    }

    /** Read a stream into memory, aborting past [max] bytes (guards unknown SAF sizes). */
    private fun readLimited(ins: InputStream, max: Long): ByteArray {
        val bos = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = ins.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IllegalStateException("file exceeds $max B")
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    /** All records as one zip, built in memory (records are small text files). */
    fun zip(ctx: Context, recs: List<Rec>): ByteArray {
        val total = recs.sumOf { it.size }
        if (total > MAX_ZIP) throw IllegalStateException("selection too large: $total B")
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for (r in recs) {
                val data = runCatching { read(ctx, r) }.getOrNull() ?: continue
                z.putNextEntry(ZipEntry(r.name).apply { time = r.modified })
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    // ------------------------------------------------------------------- SAF

    private fun safTree(ctx: Context): Uri? {
        val s = AppState.recordsTree ?: return null
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        // The grant is lost if the user revokes it or the picker's provider changes.
        val held = ctx.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        return if (held) uri else null
    }

    /**
     * Folder picker pre-opened at DJI Fly's FlightRecord directory. The user
     * still has to confirm the selection — that is the whole point of SAF.
     */
    fun grantIntent(pkg: String = "dji.go.v5"): Intent {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val docId = "primary:Android/data/$pkg/$SUBDIR"
            val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId)
            i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        return i
    }

    fun persist(ctx: Context, tree: Uri): String {
        return try {
            ctx.contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            AppState.setRecordsTree(ctx, tree.toString())
            val n = list(ctx).size
            DiagLog.info("flight records: granted $tree ($n files)")
            "granted: $n record(s) visible"
        } catch (e: Exception) {
            DiagLog.err("flight records: grant failed: ${e.message}")
            "grant failed: ${e.message}"
        }
    }

    fun forget(ctx: Context) {
        safTree(ctx)?.let { runCatching { ctx.contentResolver.releasePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        AppState.setRecordsTree(ctx, null)
        DiagLog.info("flight records: access forgotten")
    }

    // -------------------------------------------------------------- reporting

    /** Human-readable listing, also used as the /records endpoint body. */
    fun report(ctx: Context, extraDir: String? = null): String {
        val recs = list(ctx, extraDir)
        val sb = StringBuilder()
        sb.append("flight records: ").append(recs.size).append(" file(s)\n")
        sb.append("saf grant: ").append(safTree(ctx)?.toString() ?: "none").append('\n')
        for ((_, d) in dirs()) {
            val state = when {
                !d.exists() -> "absent/hidden"
                !d.isDirectory -> "not a dir"
                d.listFiles() == null -> "unreadable"
                else -> "readable"
            }
            sb.append("  ").append(d.absolutePath).append("  → ").append(state).append('\n')
        }
        if (recs.isEmpty()) {
            sb.append("\nNothing visible. On Android 11+ another app's Android/data is hidden:\n")
            sb.append("open the app on the RC → Flight records → Grant access, and pick\n")
            sb.append("Android/data/dji.go.v5/files/FlightRecord in the picker.\n")
        } else {
            sb.append('\n')
            for (r in recs) sb.append(line(r)).append('\n')
        }
        return sb.toString()
    }

    private fun line(r: Rec) =
        "%-52s %9d B  %s  [%s]".format(r.name, r.size, ts(r.modified), r.src)

    /** SimpleDateFormat is not thread-safe and the diag server serves concurrently. */
    private fun ts(t: Long) = synchronized(stamp) { stamp.format(Date(t)) }

    fun json(ctx: Context, extraDir: String? = null): String =
        list(ctx, extraDir).joinToString(",", "[", "]") {
            """{"n":"${esc(it.name)}","s":${it.size},"m":${it.modified},"src":"${esc(it.src)}"}"""
        }

    private fun esc(s: String) = Json.esc(s)

    // ----------------------------------------------------- copy to Downloads

    /**
     * Copy every visible record into the public Download/FlightRecord folder,
     * so they can be pulled over MTP without the diag server running.
     */
    fun copyToDownloads(ctx: Context, extraDir: String? = null): String {
        val recs = list(ctx, extraDir)
        if (recs.isEmpty()) return "nothing to copy — no records visible"
        var ok = 0
        for (r in recs) {
            val flat = r.name.replace('/', '_')
            val data = runCatching { read(ctx, r) }.getOrNull() ?: continue
            val wrote = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, flat)
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.Downloads.RELATIVE_PATH, "Download/FlightRecord")
                        put(MediaStore.Downloads.IS_PENDING, 1)
                    }
                    val res = ctx.contentResolver
                    val uri = res.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
                    res.openOutputStream(uri)?.use { it.write(data) }
                    values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
                    res.update(uri, values, null, null)
                    true
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "FlightRecord")
                    dir.mkdirs(); File(dir, flat).writeBytes(data); true
                }
            }.getOrDefault(false)
            if (wrote) ok++
        }
        val msg = "copied $ok/${recs.size} record(s) to Download/FlightRecord"
        DiagLog.info(msg)
        return msg
    }
}
