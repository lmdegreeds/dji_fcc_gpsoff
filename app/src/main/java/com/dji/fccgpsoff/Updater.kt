package com.dji.fccgpsoff

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Update check against this project's GitHub releases.
 *
 * Queries the release LIST rather than `/releases/latest`, because `latest`
 * silently excludes pre-releases — and offering pre-releases is a setting here,
 * so both modes have to come from one endpoint.
 *
 * Installing goes through [android.content.pm.PackageInstaller] rather than an
 * ACTION_VIEW intent on a FileProvider URI: it needs no androidx dependency (this
 * project has none) and, unlike the intent route, it reports back what actually
 * happened — refused, failed, or replaced. The user still confirms every install
 * in the system dialog; `REQUEST_INSTALL_PACKAGES` only makes that dialog reachable.
 *
 * Everything network-facing returns a [Result] instead of throwing, so a failed
 * check is shown as a message and never as a crash.
 */
object Updater {

    const val REPO = "lmdegreeds/dji_fcc_gpsoff"
    private const val API = "https://api.github.com/repos/$REPO/releases?per_page=30"
    private const val UA = "DJI_FCC_GPSOFF-updater"
    private const val CONNECT_MS = 8_000
    private const val READ_MS = 15_000
    /** Don't re-check on every launch; the release feed does not move that fast. */
    const val CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000

    /** One published release carrying an installable APK. */
    data class Release(
        val tag: String,            // "v1.1" as published
        val version: String,        // "1.1" — the tag without its leading v
        val title: String,
        val notes: String,          // release body (markdown, shown as plain text)
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
        val prerelease: Boolean,
        /**
         * Whether the chosen asset is signed with OUR key, as far as its file name
         * says: true = the name carries our tag, false = it carries someone else's,
         * null = the name carries no tag at all (a release published before the tag
         * existed). Only `false` is a reason to refuse — `null` is unknown, and
         * unknown is settled after the download by [AppSignature.mismatchReason].
         */
        val signerMatch: Boolean?,
    )

    sealed interface Result {
        /** A strictly newer release than [current] is available. */
        data class Available(val release: Release, val current: String) : Result
        data class UpToDate(val current: String) : Result
        /**
         * A newer release exists but its APK is signed with a different key, so
         * Android cannot install it over this build. Reported separately rather than
         * swallowed: the release IS there, and the user is entitled to know why the
         * app will not take it.
         */
        data class Incompatible(val release: Release, val current: String) : Result
        /** Reached GitHub but nothing usable (no releases, or none with an APK). */
        data class None(val reason: String) : Result
        data class Failed(val reason: String) : Result
    }

    // ------------------------------------------------------------------ check

    /**
     * Fetch releases and pick the newest one that beats [currentVersion].
     * [includePrerelease] also considers releases GitHub marks as pre-release.
     * Drafts are never considered — they have no public asset.
     */
    suspend fun check(currentVersion: String, includePrerelease: Boolean, signerTag: String = ""): Result = withContext(Dispatchers.IO) {
        val body = fetch(API) ?: return@withContext Result.Failed(
            t("не удалось связаться с GitHub", "could not reach GitHub"))
        val all = runCatching { parse(JSONArray(body), signerTag) }.getOrNull()
            ?: return@withContext Result.Failed(t("не удалось разобрать ответ GitHub", "could not parse the GitHub response"))

        val usable = all.filter { includePrerelease || !it.prerelease }
        if (usable.isEmpty()) return@withContext Result.None(
            if (all.isEmpty()) t("релизов пока нет", "no releases published yet")
            else t("есть только предварительные релизы — включите их в настройках",
                   "only pre-releases are published — enable them in settings"))

        // Newest by version, not by publish order: a patch to an older branch can
        // be published after a newer release.
        val newest = usable.maxWithOrNull { a, b -> compareVersions(a.version, b.version) }!!
        if (compareVersions(newest.version, currentVersion) <= 0) return@withContext Result.UpToDate(currentVersion)
        // A newer build that cannot replace this one is not an update offer. Say what it
        // is instead of downloading megabytes the installer will refuse.
        if (newest.signerMatch == false) Result.Incompatible(newest, currentVersion)
        else Result.Available(newest, currentVersion)
    }

    /**
     * Pick the APK asset to offer from one release's assets.
     *
     * Names are `dji-fcc-gpsoff-<version>-<buildType>-<8 hex of the signing cert>.apk`
     * since 2026-08-19 (`app/build.gradle.kts`). When [signerTag] is known and any
     * asset carries it, that is the one — a release may legitimately carry both a
     * release-key and a debug-key APK, and only one of them can install here.
     * Otherwise fall back to the first APK and record what its name implies.
     */
    internal fun pickAsset(names: List<Pair<String, Pair<String, Long>>>, signerTag: String):
            Triple<String, Pair<String, Long>, Boolean?>? {
        if (names.isEmpty()) return null
        if (signerTag.isNotEmpty()) {
            names.firstOrNull { taggedWith(it.first, signerTag) }
                ?.let { return Triple(it.first, it.second, true) }
        }
        val first = names.first()
        // A name that carries SOME tag, just not ours, is a positive "different key".
        // A name with no tag at all says nothing — settle it after the download.
        val known = names.any { tagIn(it.first) != null }
        return Triple(first.first, first.second, if (known && signerTag.isNotEmpty()) false else null)
    }

    private fun taggedWith(name: String, tag: String): Boolean = tagIn(name).equals(tag, ignoreCase = true)

    /** The `-<8 hex>.apk` suffix of a build-stamped asset name, or null. */
    internal fun tagIn(name: String): String? {
        val m = Regex("-([0-9a-fA-F]{8})\\.apk$").find(name.trim()) ?: return null
        return m.groupValues[1].lowercase()
    }

    private fun parse(arr: JSONArray, signerTag: String): List<Release> {
        val out = ArrayList<Release>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("draft", false)) continue
            val tag = o.optString("tag_name").trim()
            if (tag.isEmpty()) continue
            val assets = o.optJSONArray("assets") ?: continue
            val apks = ArrayList<Pair<String, Pair<String, Long>>>()
            for (j in 0 until assets.length()) {
                val a = assets.optJSONObject(j) ?: continue
                val n = a.optString("name")
                if (n.endsWith(".apk", ignoreCase = true))
                    apks.add(n to (a.optString("browser_download_url") to a.optLong("size", 0L)))
            }
            val picked = pickAsset(apks, signerTag) ?: continue   // no APK ⇒ nothing to install
            if (picked.second.first.isEmpty()) continue
            out.add(Release(
                tag = tag,
                version = tag.removePrefix("v").removePrefix("V"),
                title = o.optString("name").ifBlank { tag },
                notes = o.optString("body").trim(),
                apkUrl = picked.second.first,
                apkName = picked.first,
                apkSize = picked.second.second,
                prerelease = o.optBoolean("prerelease", false),
                signerMatch = picked.third,
            ))
        }
        return out
    }

    private fun fetch(url: String): String? = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_MS; readTimeout = READ_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UA)
        }
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() }
            else { DiagLog.warn("update check: HTTP ${c.responseCode}"); null }
        } finally { c.disconnect() }
    } catch (e: Exception) {
        DiagLog.warn("update check: ${e.message}"); null
    }

    // -------------------------------------------------------------- versions

    /**
     * Compare two dotted versions, semver-style for the part that matters here:
     * numeric components left to right (a missing component counts as 0), and a
     * version carrying a pre-release suffix ranks BELOW the same numbers without
     * one — so `1.2-beta1` must not shadow the finished `1.2`.
     *
     * Returns >0 if [a] is newer, <0 if older, 0 if equal.
     */
    fun compareVersions(a: String, b: String): Int {
        val (na, sa) = split(a)
        val (nb, sb) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val d = na.getOrElse(i) { 0 } - nb.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        if (sa.isEmpty() && sb.isEmpty()) return 0
        if (sa.isEmpty()) return 1          // 1.2 is newer than 1.2-beta1
        if (sb.isEmpty()) return -1
        return sa.compareTo(sb)
    }

    /** `1.2.3-beta.1` → ([1,2,3], "beta.1"). Anything unparseable becomes 0. */
    private fun split(v: String): Pair<List<Int>, String> {
        val clean = v.trim().removePrefix("v").removePrefix("V")
        val cut = clean.indexOfFirst { it == '-' || it == '+' }
        val nums = (if (cut >= 0) clean.substring(0, cut) else clean)
            .split('.').map { p -> p.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return nums to (if (cut >= 0) clean.substring(cut + 1) else "")
    }

    // -------------------------------------------------------------- download

    /** Where a downloaded APK lands. Kept in cache so the OS can reclaim it. */
    private fun apkFile(ctx: Context) = File(ctx.cacheDir, "update.apk")

    /**
     * Download the release APK, reporting progress as 0..1. Returns the file, or
     * null with the reason logged. A partial download is deleted rather than left
     * for the installer to choke on.
     */
    suspend fun download(ctx: Context, r: Release, onProgress: (Float) -> Unit): File? = withContext(Dispatchers.IO) {
        val out = apkFile(ctx)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(r.apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_MS; readTimeout = READ_MS
                setRequestProperty("User-Agent", UA)
                instanceFollowRedirects = true      // release assets redirect to a CDN
            }
            if (conn.responseCode != 200) {
                DiagLog.err("update download: HTTP ${conn.responseCode}"); return@withContext null
            }
            val total = (if (r.apkSize > 0) r.apkSize else conn.contentLength.toLong()).coerceAtLeast(1L)
            out.outputStream().use { fos ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(64 * 1024)
                    var got = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        fos.write(buf, 0, n)
                        got += n
                        onProgress((got.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
            DiagLog.info("update: downloaded ${out.length()} B for ${r.tag}")
            out
        } catch (e: Exception) {
            DiagLog.err("update download: ${e.message}")
            runCatching { out.delete() }
            null
        } finally { conn?.disconnect() }
    }

    // --------------------------------------------------------------- install

    private const val ACTION_INSTALL_STATUS = "com.dji.fccgpsoff.INSTALL_STATUS"
    /** Signature-level, declared in the manifest — see the registration below. */
    private const val INSTALL_STATUS_PERMISSION = "com.dji.fccgpsoff.permission.INSTALL_STATUS"

    /**
     * Hand the APK to the system package installer.
     *
     * [onStatus] reports the outcome in words. The success case usually never
     * arrives: replacing our own APK kills this process, which IS the success —
     * so the caller must not treat silence as failure.
     */
    // The pre-33 registerReceiver overload takes a permission but no flags, and the
    // signature permission below is the stronger guarantee anyway (it names WHO may
    // broadcast, not merely whether the receiver is exported). Lint only knows about
    // the flag, so the check is silenced for that one call, not for the concept.
    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun install(ctx: Context, apk: File, onStatus: (String) -> Unit) {
        if (!apk.exists() || apk.length() == 0L) {
            onStatus(t("файл обновления не найден", "the update file is missing")); return
        }
        val app = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val status = i?.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) ?: -1
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // The system asks the user to confirm; we only have to show it.
                    @Suppress("DEPRECATION")
                    val confirm = i?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm == null) {
                        onStatus(t("система не вернула окно установки", "the system returned no install prompt")); return
                    }
                    runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { onStatus(t("не удалось открыть окно установки", "could not open the install prompt")) }
                    return
                }
                runCatching { app.unregisterReceiver(this) }
                val msg = i?.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                onStatus(when (status) {
                    PackageInstaller.STATUS_SUCCESS -> t("обновление установлено", "update installed")
                    PackageInstaller.STATUS_FAILURE_ABORTED -> t("установка отменена", "install cancelled")
                    PackageInstaller.STATUS_FAILURE_CONFLICT ->
                        t("конфликт подписи — этот APK подписан другим ключом, обновить поверх нельзя",
                          "signature conflict — this APK is signed with a different key and cannot replace the installed one")
                    else -> t("установка не удалась: $msg", "install failed: $msg")
                })
            }
        }
        // Context-registered, not in the manifest: the callback is only interesting
        // while this screen is alive, and a successful install ends the process anyway.
        //
        // Guarded twice, because this receiver launches an Intent it is handed:
        // NOT_EXPORTED where the platform supports it, and a signature-level
        // permission everywhere (a dynamic receiver is otherwise reachable by any
        // app that knows the action string).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, IntentFilter(ACTION_INSTALL_STATUS),
                INSTALL_STATUS_PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
        } else {
            app.registerReceiver(receiver, IntentFilter(ACTION_INSTALL_STATUS),
                INSTALL_STATUS_PERMISSION, null)
        }

        try {
            val installer = app.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite("apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val pi = PendingIntent.getBroadcast(
                    app, sessionId, Intent(ACTION_INSTALL_STATUS).setPackage(app.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                session.commit(pi.intentSender)
            }
            DiagLog.info("update: install session committed")
        } catch (e: Exception) {
            runCatching { app.unregisterReceiver(receiver) }
            DiagLog.err("update install: ${e.message}")
            onStatus(t("установка не удалась: ${e.message}", "install failed: ${e.message}"))
        }
    }

    /**
     * `2.1 MB` / `2,1 МБ`. The decimal separator follows the UI language, not the
     * device locale — otherwise a Russian-locale controller renders "2,0 MB" in
     * the English interface, mixing the two conventions in one string.
     */
    fun sizeLabel(bytes: Long): String {
        if (bytes <= 0) return "—"
        val locale = if (AppState.uiRu) java.util.Locale("ru") else java.util.Locale.US
        return String.format(locale, "%.1f %s", bytes / 1024.0 / 1024.0, t("МБ", "MB"))
    }
}
