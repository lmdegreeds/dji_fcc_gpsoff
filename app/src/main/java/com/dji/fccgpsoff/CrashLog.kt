package com.dji.fccgpsoff

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists an uncaught exception where it can be read on a controller that has no
 * usable adb — the normal situation for this app, since the RC is in the field and
 * its USB is often occupied or offline.
 *
 * The trace goes three places, cheapest first, because a crashing process may not
 * survive long enough to finish all of them:
 *   1. [DiagLog] — visible on the Log page and over the web dashboard;
 *   2. `Android/data/<pkg>/files/crash-<ts>.txt` — no permission needed, readable
 *      over MTP;
 *   3. public `Download/` — the one folder a user can find without knowing where
 *      app-private storage lives.
 *
 * The previous handler is always called afterwards, so the system still records
 * the crash and shows its dialog; nothing here swallows a failure.
 */
object CrashLog {

    private const val PREFIX = "crash-"
    private val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    private fun write(ctx: Context, thread: Thread, e: Throwable) {
        val sw = StringWriter()
        PrintWriter(sw).use { e.printStackTrace(it) }
        val version = runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull().orEmpty()
        val now = Date()
        val text = buildString {
            appendLine("${AppCopy.NAME} $version — crash ${stamp.format(now)}")
            appendLine("thread: ${thread.name}")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine()
            append(sw.toString())
        }

        val name = "$PREFIX${stamp.format(now)}.txt"
        runCatching { DiagLog.err("CRASH on ${thread.name}: $e — full trace in $name") }
        // And get it, and the lines that led to it, onto disk NOW. The store flushes on a
        // 15-second timer; this process has no 15 seconds left, and losing exactly the
        // run-up to a crash is the one loss that cannot be worked around afterwards.
        runCatching { LogStore.markEnd("uncaught ${e.javaClass.simpleName} on ${thread.name}", clean = false) }

        runCatching {
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, name).writeText(text)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) ctx.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            }
        }
    }

    /** Newest stored crash FILE, without reading it. Cheap enough for App.onCreate, where
     *  only its name, size and timestamp are wanted. */
    fun latestFile(ctx: Context): File? =
        (ctx.getExternalFilesDir(null) ?: ctx.filesDir)
            .listFiles { file -> file.name.startsWith(PREFIX) }?.maxByOrNull { it.lastModified() }

    /** Newest stored crash, or null. Used to surface it on the next launch. */
    fun latest(ctx: Context): Pair<File, String>? {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        val f = dir.listFiles { file -> file.name.startsWith(PREFIX) }
            ?.maxByOrNull { it.lastModified() } ?: return null
        val text = runCatching { f.readText() }.getOrNull() ?: return null
        return f to text
    }

    /** Drop every stored crash — the user has seen them. */
    fun clear(ctx: Context) {
        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        dir.listFiles { file -> file.name.startsWith(PREFIX) }?.forEach { runCatching { it.delete() } }
    }
}
