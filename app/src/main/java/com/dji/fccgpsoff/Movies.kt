package com.dji.fccgpsoff

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Access to the RC2's own screen recordings — the two "Movies" folders you see
 * over MTP as:
 *
 *   Этот компьютер\DJI RC 2\Внутренний общий накопитель\Movies
 *       → primary external storage   /storage/emulated/0/Movies      (src "internal")
 *   Этот компьютер\DJI RC 2\SD_Card\Movies
 *       → the removable card volume   /storage/XXXX-XXXX/Movies       (src "sdcard")
 *
 * Unlike the flight records (buried in another app's Android/data, hence the SAF
 * dance in [FlightRecords]), Movies is a PUBLIC media directory. The reliable
 * route on the RC2 (Android 11) is MediaStore, which indexes every external
 * volume — the primary storage AND the SD card — so we query it per volume and
 * keep only items whose path is under a Movies/ folder. A direct File walk of
 * the same folders is kept as a fallback for when MediaStore hasn't indexed a
 * freshly captured clip yet.
 *
 * Reading is seekable (offset -> InputStream) so [DiagServer] can answer HTTP
 * Range requests, which an HTML5 <video> needs to stream and scrub.
 *
 * Needs READ_EXTERNAL_STORAGE (<=API 32) or READ_MEDIA_VIDEO (API 33+).
 */
object Movies {

    private const val MAX_DEPTH = 4
    private val VIDEO_EXT = setOf("mp4", "mov", "mkv", "m4v", "webm", "avi", "ts", "3gp", "3gpp")
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    /** One recording. [file] xor [uri] is set, depending on which route found it. */
    data class Mov(
        val id: String,          // stable handle for /movie?id= ("v:<vol>:<mediaId>" or "p:<path>")
        val name: String,        // display name (may carry a Movies/ subpath)
        val size: Long,
        val modified: Long,      // epoch ms
        val src: String,         // "internal" | "sdcard" | volume name | "dir"
        val mime: String,
        val durationMs: Long = 0,
        val file: File? = null,
        val uri: Uri? = null
    )

    /** A seekable byte source for one recording, used to answer Range requests. */
    class Source(
        val length: Long,
        val mime: String,
        val filename: String,
        private val opener: (Long) -> InputStream
    ) {
        fun open(offset: Long): InputStream = opener(offset)
    }

    // ---------------------------------------------------------------- listing

    /** id -> Mov from the most recent [list], so /movie Range requests skip a rescan. */
    @Volatile private var cache: Map<String, Mov> = emptyMap()

    /** Every visible recording across both Movies folders, newest first. */
    fun list(ctx: Context): List<Mov> {
        val out = LinkedHashMap<String, Mov>()
        val seen = HashSet<String>() // size|lowercased-basename — dedup File vs MediaStore hits
        runCatching { mediaStore(ctx, out, seen) }
            .onFailure { DiagLog.warn("movies (mediastore): ${it.message}") }
        for ((src, dir) in movieDirs()) {
            if (dir.isDirectory) runCatching { walk(dir, src, "", 0, out, seen) }
        }
        cache = out
        return out.values.sortedByDescending { it.modified }
    }

    /** Candidate Movies directories for the direct-File fallback. */
    private fun movieDirs(): List<Pair<String, File>> {
        val dirs = ArrayList<Pair<String, File>>()
        dirs += "internal" to File(Environment.getExternalStorageDirectory(), "Movies")
        // Removable volumes live under /storage/<VOLID> (VOLID like 1A2B-3C4D).
        runCatching {
            File("/storage").listFiles()?.forEach { v ->
                val n = v.name
                if (v.isDirectory && n != "emulated" && n != "self" && n.contains('-')) {
                    dirs += "sdcard" to File(v, "Movies")
                }
            }
        }
        return dirs
    }

    private fun walk(dir: File, src: String, prefix: String, depth: Int,
                     out: MutableMap<String, Mov>, seen: MutableSet<String>) {
        if (depth > MAX_DEPTH) return
        for (f in dir.listFiles() ?: return) {
            val rel = if (prefix.isEmpty()) f.name else "$prefix/${f.name}"
            if (f.isDirectory) { walk(f, src, rel, depth + 1, out, seen); continue }
            val ext = f.extension.lowercase()
            if (ext !in VIDEO_EXT) continue
            val key = "${f.length()}|${f.name.lowercase()}"
            if (!seen.add(key)) continue // already surfaced via MediaStore
            val id = "p:" + f.absolutePath
            out[id] = Mov(id, rel, f.length(), f.lastModified(), src, mimeFor(ext), file = f)
        }
    }

    private fun mediaStore(ctx: Context, out: MutableMap<String, Mov>, seen: MutableSet<String>) {
        val resolver = ctx.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (vol in MediaStore.getExternalVolumeNames(ctx)) {
                val content = MediaStore.Video.Media.getContentUri(vol)
                val proj = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.MIME_TYPE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.RELATIVE_PATH
                )
                val sel = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
                val args = arrayOf("%Movies/%")
                val src = if (vol == MediaStore.VOLUME_EXTERNAL_PRIMARY) "internal" else "sdcard"
                resolver.query(content, proj, sel, args, null)?.use { c ->
                    val ci = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val cn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val cs = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val cm = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                    val ct = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                    val cd = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val cr = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val mediaId = c.getLong(ci)
                        val nm = c.getString(cn) ?: continue
                        val size = c.getLong(cs)
                        val modMs = c.getLong(cm) * 1000L
                        val mime = c.getString(ct) ?: mimeFor(nm.substringAfterLast('.').lowercase())
                        val dur = c.getLong(cd)
                        val relPath = c.getString(cr) ?: ""
                        val id = "v:$vol:$mediaId"
                        seen.add("$size|${nm.lowercase()}")
                        out[id] = Mov(
                            id, subPath(relPath, nm), size, modMs, src, mime, dur,
                            uri = ContentUris.withAppendedId(content, mediaId)
                        )
                    }
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val content = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            @Suppress("DEPRECATION")
            val proj = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.DATA
            )
            @Suppress("DEPRECATION")
            val sel = "${MediaStore.Video.Media.DATA} LIKE ?"
            val args = arrayOf("%/Movies/%")
            resolver.query(content, proj, sel, args, null)?.use { c ->
                val ci = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val cn = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val cs = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val cm = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val ct = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val cd = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                @Suppress("DEPRECATION")
                val cdata = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                while (c.moveToNext()) {
                    val mediaId = c.getLong(ci)
                    val nm = c.getString(cn) ?: continue
                    val size = c.getLong(cs)
                    val modMs = c.getLong(cm) * 1000L
                    val mime = c.getString(ct) ?: mimeFor(nm.substringAfterLast('.').lowercase())
                    val dur = c.getLong(cd)
                    val data = c.getString(cdata) ?: ""
                    val src = if (data.startsWith("/storage/emulated") || data.startsWith("/sdcard")) "internal" else "sdcard"
                    val id = "v:external:$mediaId"
                    seen.add("$size|${nm.lowercase()}")
                    out[id] = Mov(
                        id, nm, size, modMs, src, mime, dur,
                        uri = ContentUris.withAppendedId(content, mediaId)
                    )
                }
            }
        }
    }

    /** relPath is like "Movies/DJI/"; show the part below Movies/ as a subpath prefix. */
    private fun subPath(relPath: String, name: String): String {
        val i = relPath.indexOf("Movies/")
        val below = if (i >= 0) relPath.substring(i + "Movies/".length).trim('/') else ""
        return if (below.isEmpty()) name else "$below/$name"
    }

    // ------------------------------------------------------------------ source

    /** Resolve one recording by its id into a seekable byte source. */
    fun source(ctx: Context, id: String): Source? {
        val m = cache[id] ?: list(ctx).firstOrNull { it.id == id } ?: return null
        val fname = m.name.substringAfterLast('/')
        return when {
            m.file != null -> Source(m.file.length(), m.mime, fname) { off ->
                FileInputStream(m.file).also { if (off > 0) it.channel.position(off) }
            }
            m.uri != null -> {
                // MediaStore SIZE can be stale or wrong; the HTTP Content-Length MUST
                // equal the bytes we can actually deliver, or the browser waits forever
                // for the missing tail — the video shows a frame and scrubs (offsets we
                // already served) but never plays (spinner). Use the descriptor's real
                // size, falling back to the DB value only if it can't be read.
                val realLen = runCatching {
                    ctx.contentResolver.openFileDescriptor(m.uri, "r")?.use { it.statSize }
                }.getOrNull()?.takeIf { it >= 0 } ?: m.size
                Source(realLen, m.mime, fname) { off ->
                    val pfd = ctx.contentResolver.openFileDescriptor(m.uri, "r")
                        ?: throw IllegalStateException("cannot open ${m.uri}")
                    val fis = FileInputStream(pfd.fileDescriptor)
                    if (off > 0) fis.channel.position(off)
                    object : FilterInputStream(fis) {
                        override fun close() { try { super.close() } finally { runCatching { pfd.close() } } }
                    }
                }
            }
            else -> null
        }
    }

    private fun mimeFor(ext: String): String = when (ext) {
        "mp4", "m4v" -> "video/mp4"
        "mov" -> "video/quicktime"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "ts" -> "video/mp2t"
        "3gp", "3gpp" -> "video/3gpp"
        else -> "video/mp4"
    }

    // -------------------------------------------------------------- reporting

    fun report(ctx: Context): String {
        val recs = list(ctx)
        val sb = StringBuilder()
        sb.append("screen recordings: ").append(recs.size).append(" file(s)\n")
        for ((src, d) in movieDirs()) {
            val state = when {
                !d.exists() -> "absent"
                !d.isDirectory -> "not a dir"
                d.listFiles() == null -> "unreadable"
                else -> "readable"
            }
            sb.append("  [").append(src).append("] ").append(d.absolutePath).append("  → ").append(state).append('\n')
        }
        if (recs.isEmpty()) {
            sb.append("\nNothing visible. Grant media access on the RC (diag page → grant),\n")
            sb.append("then re-list. Freshly captured clips may take a moment to index.\n")
        } else {
            sb.append('\n')
            for (r in recs) sb.append(line(r)).append('\n')
        }
        return sb.toString()
    }

    private fun line(r: Mov) =
        "%-46s %11d B  %s  [%s]".format(r.name, r.size, ts(r.modified), r.src)

    private fun ts(t: Long) = synchronized(stamp) { stamp.format(Date(t)) }

    fun json(ctx: Context): String =
        list(ctx).joinToString(",", "[", "]") {
            """{"id":"${esc(it.id)}","n":"${esc(it.name)}","s":${it.size},"m":${it.modified},""" +
                """"src":"${esc(it.src)}","mime":"${esc(it.mime)}","dur":${it.durationMs}}"""
        }

    private fun esc(s: String) = Json.esc(s)
}
