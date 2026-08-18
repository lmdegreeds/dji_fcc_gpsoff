package com.dji.fccgpsoff

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * Per-port serialization of DUML sessions on the shared loopback proxy — the
 * lightweight coexistence model (adapted from Skylab's DumlPortSessionLock).
 *
 * This is the *multi-client* strategy: DJI Fly keeps its own connection; we
 * only avoid running two of *our* sessions on the same port at once, so our
 * per-op send/read stays clean. Proven sufficient on RC2 / RC Pro 2.
 *
 * If a target proxy turns out to be single-client, replace this with a port
 * handover (see the SINGLE-CLIENT-UPGRADE note on native `Transport::start`):
 * seize the port and interpose so DJI Fly's traffic flows through you.
 */
object PortSessionLock {
    private val held = ConcurrentHashMap<Int, AtomicBoolean>()

    class Lease(private val flag: AtomicBoolean) : AutoCloseable {
        override fun close() { flag.set(false) }
    }

    suspend fun acquire(port: Int, timeoutMs: Long = 3_000): Lease? {
        val flag = held.computeIfAbsent(port) { AtomicBoolean(false) }
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (flag.compareAndSet(false, true)) return Lease(flag)
            delay(25)
        }
        return null
    }
}
