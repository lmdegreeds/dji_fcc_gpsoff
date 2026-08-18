package com.dji.fccgpsoff

/**
 * Single owner of the persistent native **main** channel (`nativeStart/Stop`),
 * ref-counted so it belongs to no one component in particular.
 *
 * The bug this fixes: the main channel is a process-global native singleton, yet
 * it was started by the keepalive / `/connect` and stopped unconditionally by
 * `MainActivity.onDestroy` — so closing the Activity tore down the channel the
 * keepalive (and capture) were still using. Now every user takes a ref via
 * [acquire]; the channel is stopped only when the **last** ref is released.
 *
 * The aux/hijack channel is independent and owned by [DumlCapture]; it is not
 * ref-counted here (only capture uses it).
 */
object DumlTransport {

    private val lock = Any()
    private var refs = 0

    /** Chosen loopback port, or -1 when the channel is not up. */
    @Volatile var port = -1; private set

    /** Take a reference and ensure the channel is up. Returns the port (-1 if the
     *  proxy isn't reachable yet — caller may retry via [ensureUp]). */
    fun acquire(): Int = synchronized(lock) { refs++; ensureLocked() }

    /** Re-check/(re)establish the channel without changing the ref count — for a
     *  holder that polls while the proxy comes up or after a link drop. */
    fun ensureUp(): Int = synchronized(lock) { ensureLocked() }

    /** Release one reference; stop the native channel when the last one goes. */
    fun release() = synchronized(lock) {
        if (refs > 0 && --refs == 0) {
            runCatching { DumlNative.nativeStop() }
            port = -1
        }
    }

    private fun ensureLocked(): Int {
        // nativeStart is idempotent (stop_main + start); (re)start only when the
        // channel isn't actually up, so a live channel is never disturbed.
        if (!isUp()) port = runCatching { DumlNative.nativeStart() }.getOrDefault(-1)
        return port
    }

    private fun isUp(): Boolean =
        runCatching { DumlNative.nativeStats().contains("connected=1") }.getOrDefault(false)
}
