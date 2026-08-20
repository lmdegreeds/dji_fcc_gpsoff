package com.dji.fccgpsoff

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One block that says what this app is, how it is set up, and what it currently believes
 * (2026-08-20).
 *
 * **Why.** Diagnosing a report from a controller nobody else can touch always started the
 * same way: is the accessibility service on? which build is this? is the keepalive
 * actually running or just armed? which aircraft, which parameter-name profile, and where
 * did that profile come from? None of it was in the log. Each answer lived behind a
 * different HTTP endpoint on a controller that, by the time the log was read, was packed
 * away. So the log now carries the answers.
 *
 * **Where it appears.** At the top of every export ([DiagLog.bundle]), once at startup,
 * on every entry into the foreground, and on demand from `/snapshot`. Startup and
 * foreground because those are the two moments a user's session actually begins.
 *
 * **Cost.** Property reads and package queries only — no socket, no DUML frame, nothing on
 * the bus. Every field degrades to a plain "?" or "no" rather than throwing, because a
 * snapshot that crashes is worse than a snapshot with a hole in it.
 */
object Snapshot {

    // Per-thread: this is formatted from App.onCreate's startup thread, the render tick,
    // the export coroutine and a DiagServer worker, and SimpleDateFormat is not thread-safe
    // — a shared instance corrupts its own output under concurrent use. Subclassed rather
    // than ThreadLocal.withInitial, which needs API 26 (minSdk here is 24).
    private val fmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US)
    }

    /** Emit the snapshot to the log, tagged with [why] so a reader knows what prompted it. */
    fun log(ctx: Context, why: String) {
        for (l in text(ctx, why).lines()) DiagLog.info(l)
        lastEnv = runCatching { fingerprint(ctx) }.getOrNull()
    }

    /**
     * The FACTS a setup consists of — deliberately NOT the rendered rows.
     *
     * The rendered rows carry ages and counters (`foreground=… for 41s`), which advance
     * every second, so comparing them would report a change on every single call: the whole
     * block would be re-logged several times a minute, the event ring would hold nothing
     * else within minutes, and the on-disk budget the store exists to protect would be
     * spent pruning the very sessions it was added to keep. So this is a list of
     * (label, value) pairs in which every value is an identity or a boolean, and nothing
     * ticks.
     */
    private fun fingerprint(ctx: Context): List<Pair<String, String>> {
        val tag = runCatching { AppSignature.ownTag(ctx) }.getOrDefault("")
        return listOf(
            "build" to "${AppVersion.of(ctx)}/$tag",
            "device" to "${Build.MODEL}/${Build.VERSION.SDK_INT}/${Locale.getDefault()}/${AppState.uiRu}",
            "accessibility" to (isAccessibilityEnabled(ctx).toString() + "/" +
                ForegroundGate.accessibilityConnected + "/" + ForegroundGate.foregroundPackage + "/" +
                ForegroundGate.readsAllowed()),
            "grants" to grantsFingerprint(ctx),
            "services" to (FccKeepaliveService.running.toString() + AppState.autoKeepalive +
                OverlayService.running + AppState.autoOverlay + DiagServer.isRunning + AppState.autoDiag +
                (FccKeepaliveService.activeMode?.wire ?: "")),
            "dji apps" to djiAppsLine(ctx),
            "aircraft" to (AircraftSession.serial + "/" + AircraftIdentity.drone.code),
            "names" to (AppState.litoMode.toString() + "/" + StartupProbe.origin + "/" +
                ParameterAddress.GPS_ENABLE.name() + "/" + ParameterAddress.GPS_ENABLE.nameIsMeasured()),
            "fcc region" to AppState.fccRegion.code,
        )
    }

    private fun grantsFingerprint(ctx: Context): String = listOf(
        runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false),
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.packageManager.canRequestPackageInstalls() else true
        }.getOrDefault(false),
        runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        }.getOrDefault(false),
    ).joinToString("/")

    @Volatile private var lastEnv: List<Pair<String, String>>? = null

    /**
     * Emit a snapshot only when the SETUP has moved since the last one, and say what moved.
     *
     * This is what replaces a heartbeat. A timer whose only product is proof of liveness
     * costs a lifecycle, a doze gap that reads like a dead process, and a standing
     * invitation to put a read on the bus; checking from ticks that already run costs
     * nothing and fires exactly when a reader would want to know — the accessibility
     * service dying, an overlay grant revoked, a service stopping, the aircraft changing.
     */
    fun logIfChanged(ctx: Context, why: String) {
        val now = runCatching { fingerprint(ctx) }.getOrNull() ?: return
        val before = lastEnv
        if (before == now) return
        if (before != null) {
            val moved = now.filter { (k, v) -> before.firstOrNull { it.first == k }?.second != v }
                .joinToString("; ") { it.first }
            DiagLog.warn("setup changed since the last snapshot ($moved) — the full block follows")
        }
        log(ctx, why)
    }

    /**
     * The block, as text. Field-per-line with a fixed-width key so it greps cleanly and
     * reads in a terminal.
     */
    fun text(ctx: Context, why: String = "export"): String {
        val sb = StringBuilder(1200)
        fun row(k: String, v: String) { sb.append(k.padEnd(13)).append(' ').append(v).append('\n') }

        sb.append("--- state snapshot (").append(why).append(") ").append(fmt.get().format(Date()))
            .append(" · app up ").append(upFor(System.currentTimeMillis() - DiagLog.startedMs))
            .append(" · device up ").append(upFor(SystemClock.elapsedRealtime())).append(" ---\n")

        row("build", buildLine(ctx))
        row("device", deviceLine())
        row("controller", controllerLine())
        row("accessibility", a11yLine(ctx))
        row("grants", grantsLine(ctx))
        row("services", servicesLine())
        row("dji apps", djiAppsLine(ctx))
        row("aircraft", aircraftLine())
        row("names", namesLine())
        row("state", FlightState.summary())
        row("reads", ReadStats.summary())
        row("fcc", fccLine())
        row("catalog", catalogLine())
        row("updates", updatesLine())
        row("log", logLine())
        return sb.toString().trimEnd('\n')
    }

    // ------------------------------------------------------------------ rows

    /** Build identity INCLUDING the signing key, because a debug-key and a release-key
     *  build of the same version are indistinguishable over `/version` yet only one of
     *  them can ever be updated in place. */
    private fun buildLine(ctx: Context): String {
        val tag = runCatching { AppSignature.ownTag(ctx) }.getOrDefault("")
        return AppVersion.of(ctx) + " · " + ctx.packageName +
            " · signing key " + (if (tag.isEmpty()) "UNKNOWN" else tag) +
            (if (tag.equals(RELEASE_KEY, true)) " (release key)" else if (tag.isEmpty()) "" else " (NOT the release key — this build cannot update a released install)")
    }

    /** The fingerprint every published release carries; see CLAUDE.md and `Updater.pickAsset`. */
    private const val RELEASE_KEY = "46435df3"

    private fun deviceLine(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})" +
            " · ${Build.PRODUCT}/${Build.DEVICE} · locale ${Locale.getDefault()}" +
            " · UI ${if (AppState.uiRu) "ru" else "en"}"

    private fun controllerLine(): String {
        val rc = AircraftIdentity.rc
        return (if (rc.code.isEmpty()) "not identified" else "${rc.name} [${rc.code}] via ${rc.source}") +
            " · props " + runCatching { ControllerProbe.rawProps() }.getOrDefault("?")
    }

    /**
     * Accessibility, which is the single most load-bearing setting in the app: it feeds
     * the foreground gate, and without it every read is refused.
     *
     * ENABLED (a Settings row) and BOUND (a live service connection) are different facts
     * with different failure modes — Android silently disables the service on every
     * reinstall, and a bound service can still have seen no window event yet — so all
     * three are printed, never collapsed into one "a11y: true".
     */
    private fun a11yLine(ctx: Context): String {
        val enabled = isAccessibilityEnabled(ctx)
        val bound = ForegroundGate.accessibilityConnected
        val fg = ForegroundGate.foregroundPackage
        val age = ForegroundGate.foregroundAgeMs
        return "settings=" + (if (enabled) "ENABLED" else "OFF") +
            " · service=" + (if (bound) "BOUND" else "not bound") +
            " · foreground=" + (if (fg.isEmpty()) "(never seen)" else fg) +
            (if (age == Long.MAX_VALUE) "" else " for ${age / 1000}s") +
            " · reads=" + (if (ForegroundGate.readsAllowed()) "ALLOWED"
                           else "BLOCKED (" + (ForegroundGate.blockReason()?.take(60) ?: "?") + ")") +
            (if (enabled && !bound) " ← enabled but not bound: the service was killed or is starting" else "") +
            (if (!enabled) " ← reads are refused until this is switched on" else "")
    }

    /**
     * The one copy of the "is our accessibility service enabled" query.
     *
     * It existed three times — in [MainActivity], [SetupWizardActivity] and
     * [FccKeepaliveService] — with two different comparison strategies. Takes a plain
     * Context, so a service or a snapshot can ask it too.
     */
    fun isAccessibilityEnabled(ctx: Context): Boolean = runCatching {
        val want = android.content.ComponentName(ctx, DjiFlyAccessibilityService::class.java).flattenToString()
        val flat = Settings.Secure.getString(ctx.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        flat.split(':').any { it.equals(want, ignoreCase = true) }
    }.getOrDefault(false)

    private fun grantsLine(ctx: Context): String {
        val overlay = runCatching { Settings.canDrawOverlays(ctx) }.getOrDefault(false)
        val install = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.packageManager.canRequestPackageInstalls() else true
        }.getOrDefault(false)
        val notif = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        }.getOrDefault(false)
        val media = runCatching {
            val p = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
                    else android.Manifest.permission.READ_EXTERNAL_STORAGE
            ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        // Not requested by this app, but decisive for "the keepalive died overnight":
        // without the exemption the OS may freeze the process regardless of the
        // foreground service.
        val battery = runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .isIgnoringBatteryOptimizations(ctx.packageName)
        }.getOrNull()
        return "overlay=${yn(overlay)} · install-apps=${yn(install)} · notifications=${yn(notif)} · " +
            "media=${yn(media)} · battery-unrestricted=" + (battery?.let { yn(it) } ?: "?") +
            (if (!notif) " ← a foreground service without a notification is easy for the OS to kill" else "")
    }

    /** Armed vs actually running, per service — the two diverge, and only the divergence
     *  explains "FCC stopped being applied and nothing said anything". */
    private fun servicesLine(): String =
        "keepalive=" + svc(FccKeepaliveService.running, AppState.autoKeepalive) +
            // The mode the service is ACTUALLY in when it is running; otherwise the mode it
            // would start in — and only called "armed" when it really is.
            (FccKeepaliveService.activeMode?.let { "(${it.wire})" }
                ?: "(would start in ${AppState.keepaliveMode.wire})") +
            " · overlay=" + svc(OverlayService.running, AppState.autoOverlay) +
            " · diag=" + svc(DiagServer.isRunning, AppState.autoDiag) +
            (if (DiagServer.isRunning) " on :8899" else "")

    /** Named `svc`, not `run`: a two-arg local `run` shadows the stdlib one inside this
     *  file, and the next person to reach for `run { … }` here would get a confusing error. */
    private fun svc(running: Boolean, armed: Boolean): String = when {
        running && armed -> "running+armed"
        running -> "running (not armed for next start)"
        armed -> "ARMED BUT NOT RUNNING"
        else -> "off"
    }

    /** Which DJI app is installed and at what version — the other half of every
     *  "worked on my aircraft" comparison, and free to read thanks to the manifest
     *  `<queries>` block. */
    private fun djiAppsLine(ctx: Context): String {
        val found = ForegroundGate.DJI_PACKAGES.mapNotNull { p ->
            runCatching {
                @Suppress("DEPRECATION")
                val pi = ctx.packageManager.getPackageInfo(p, 0)
                "$p ${pi.versionName} (${pi.versionCode})"
            }.getOrNull()
        }
        return if (found.isEmpty()) "none installed (or not visible)" else found.joinToString(" · ")
    }

    private fun aircraftLine(): String {
        val d = AircraftIdentity.drone
        val sn = AircraftSession.serial.ifEmpty { SerialSniffer.serial.ifEmpty { StartupProbe.serial } }
        return "SN " + sn.ifEmpty { "—" } +
            " · " + (if (d.code.isEmpty()) "model unknown (open DJI Fly)" else "${d.name} [${d.code}] via ${d.source}") +
            " · session #${AircraftSession.epoch}"
    }

    /** The profile AND where it came from — the field whose absence sent a user chasing
     *  the wrong cause. */
    private fun namesLine(): String =
        StartupProbe.provenance() +
            " · aliases measured: ${ParamAlias.resolvedCount}" +
            (if (ParamAlias.preferred >= 0) " · preferred spelling #${ParamAlias.preferred}" else "") +
            " · GPS writes go to " + ParamName.tag(ParameterAddress.GPS_ENABLE.name()) +
            (if (ParameterAddress.GPS_ENABLE.nameIsMeasured()) " (measured)" else " (assumed)")

    private fun fccLine(): String {
        val since = Features.sinceLastApplyMs()
        return "region ${AppState.fccRegion.display()} · trigger ${AppState.keepaliveMode.label} · " +
            "last apply " + (if (since == Long.MAX_VALUE) "never this run" else "${since / 1000}s ago")
    }

    private fun catalogLine(): String =
        if (ParamCatalog.params.isEmpty()) "none loaded"
        else "${ParamCatalog.sourceName} · ${ParamCatalog.params.size} params " +
            "(${ParamCatalog.params.count { !it.editable }} read-only)"

    private fun updatesLine(): String {
        val last = AppState.lastUpdateCheckMs
        return "check=${on(AppState.autoUpdateCheck)} · pre-releases=${on(AppState.updatePrerelease)} · " +
            "last checked " + (if (last == 0L) "never" else "${(System.currentTimeMillis() - last) / 60000} min ago")
    }

    private fun logLine(): String {
        val (ev, wire, drop) = DiagLog.counts()
        val (written, droppedToDisk) = LogStore.stats()
        return "in memory: $ev event + $wire wire line(s), $drop evicted · " +
            "on disk: ${LogStore.bytesOnDisk() / 1024} KB" +
            (if (droppedToDisk > 0) " ($droppedToDisk line(s) never reached it)" else "") +
            " at ${LogStore.location()} · written $written"
    }

    // ---------------------------------------------------------------- helpers

    private fun yn(b: Boolean) = if (b) "yes" else "NO"
    private fun on(b: Boolean) = if (b) "on" else "off"

    private fun upFor(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 90 -> "${s}s"
            s < 5400 -> "${s / 60}m"
            else -> "${s / 3600}h${(s % 3600) / 60}m"
        }
    }
}
