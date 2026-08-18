package com.dji.fccgpsoff

import android.app.Application

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
