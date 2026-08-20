package com.dji.fccgpsoff

import android.app.Application
import android.content.Context

/**
 * Loads persisted [AppState] once, in the single place guaranteed to run before
 * any Activity, Service or BroadcastReceiver in this process — so a START_STICKY
 * service restarted into a fresh process never reads compile-time defaults.
 * Components may still call [AppState.load] defensively; it is idempotent.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Before AppState, so a failure while loading state is still recorded.
        CrashLog.install(this)
        // Before the first log line, so the build banner reaches disk too. Everything a
        // session records is otherwise lost with the process, which is exactly what made
        // a field report from a DJI Air 3 so expensive to diagnose (2026-08-20).
        LogStore.start(this)
        AppState.load(this)
        // FIRST diagnostic line of every session. Without it a live controller cannot
        // be asked which build it is running, and a log read remotely is ambiguous —
        // that ambiguity once cost half an hour of arguing over whether a fix was
        // even installed. See CLAUDE.md.
        DiagLog.info("build ${AppVersion.of(this)} starting")
        // And, off this thread: what this app is, how it is set up, what it is allowed to
        // do. Written at startup rather than only at export time, because a log shared
        // after the fact must carry its own context — the controller is not there to be
        // asked. NOT on the main thread: the block costs a signing-certificate digest,
        // four package queries and half a dozen permission checks, and this runs before
        // the first frame and inside BootReceiver's delivery window.
        val app = this
        Thread({
            runCatching { Snapshot.log(app, "startup") }
            // Point at a recent crash file, because nothing in the app did: the trace goes
            // to a file no endpoint serves and no log line ever mentioned (2026-08-20).
            //
            // It says the file EXISTS and how old it is — not that it belongs to the session
            // before this one. Nothing here links the two, and [LogStore]'s end marker is the
            // thing that actually knows how the last session finished.
            runCatching {
                CrashLog.latestFile(app)?.let { f ->
                    val ageS = (System.currentTimeMillis() - f.lastModified()) / 1000
                    if (ageS in 0..CRASH_NOTICE_S)
                        DiagLog.warn("a crash report from ${ageS}s ago is on this controller: " +
                            "${f.name} (${f.length()} B). Whether it belongs to the session before " +
                            "this one is said by that session's end marker, not by this line. " +
                            "Open it from the ⋮ menu, or find it beside the session logs.")
                }
            }
        }, "startup-snapshot").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }.start()
        // Passive departure from DJI Fly's 40007 video-mirror port.
        //
        // We deliberately do NOT force-close in-flight 40007 reads when Fly takes the
        // foreground. The RC2 build (com.example.dumlfcc) proved a short, self-terminating
        // 40007 read that merely OVERLAPS the switch is harmless to Fly; what blipped Fly's
        // link for a few seconds right after switching was the abrupt EXTERNAL close
        // (VideoPortGuard.closeAll / DroneLinkProbe.releaseNow) fired synchronously from the
        // accessibility window event, exactly as Fly re-established its own 40007 session.
        //
        // So onReadsMustStop is left UNSET → ForegroundGate.onWindow() closes nothing. Reads
        // and their retries stop cooperatively via ForegroundGate.readsAllowed(): the current
        // short read ends by itself, and no new read/retry is started while Fly is foreground.
        // The aux OSD probe likewise releases on its own tick (DroneLinkProbe loop), not
        // slammed from the outside.
    }
}

/** How recent a crash file has to be to be worth mentioning at startup. A day covers
 *  "it crashed, I reopened it later"; older than that and it is history, not news. */
private const val CRASH_NOTICE_S = 24 * 3600L

/** The running build, read from the package (BuildConfig generation is off). */
object AppVersion {
    fun of(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        "v${pi.versionName} (code ${pi.versionCode})"
    }.getOrDefault("v?")
}
