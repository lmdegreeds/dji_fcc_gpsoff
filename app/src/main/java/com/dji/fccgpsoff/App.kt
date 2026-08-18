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
        AppState.load(this)
        // FIRST diagnostic line of every session. Without it a live controller cannot
        // be asked which build it is running, and a log read remotely is ambiguous —
        // that ambiguity once cost half an hour of arguing over whether a fix was
        // even installed. See CLAUDE.md.
        DiagLog.info("build ${AppVersion.of(this)} starting")
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

/** The running build, read from the package (BuildConfig generation is off). */
object AppVersion {
    fun of(ctx: Context): String = runCatching {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        @Suppress("DEPRECATION")
        "v${pi.versionName} (code ${pi.versionCode})"
    }.getOrDefault("v?")
}
