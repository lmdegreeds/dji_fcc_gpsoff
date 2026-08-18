package com.dji.fccgpsoff

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic tally of every frame delivered on the main channel, so we can find a
 * PASSIVE "aircraft is linked" signal on 40009 (the SDR/radio proxy) — one that
 * distinguishes a powered drone from the controller's own radio housekeeping,
 * without touching DJI Fly's 40007.
 *
 * Fed from [DumlNative.onNativeFrame] (the app's full RX — reliable, unlike the
 * diag `/cap` one-shot which competes with the persistent reader). Per
 * cmdSet:cmdId it keeps a count, the last-seen time and the last payload; the
 * served `/radiolink` dumps the table so a drone-off vs drone-on comparison shows
 * which frame (or which byte inside a steady frame like 06:AE) flips on link.
 *
 * Purely observational — nothing is injected. Kept allocation-light for the RX
 * thread.
 */
object RadioLinkMonitor {

    private class Stat {
        val count = AtomicLong(0)
        @Volatile var lastMs = 0L
        @Volatile var lastPayload = ""
    }

    private val table = ConcurrentHashMap<Int, Stat>()

    /** Feed one delivered frame (RX thread). */
    fun offer(cmdSet: Int, cmdId: Int, payload: ByteArray) {
        val key = (cmdSet shl 8) or (cmdId and 0xFF)
        val s = table.getOrPut(key) { Stat() }
        s.count.incrementAndGet()
        s.lastMs = System.currentTimeMillis()
        // Cap the stored payload so a big frame can't bloat the table.
        val n = if (payload.size > 24) 24 else payload.size
        val sb = StringBuilder(n * 2)
        for (i in 0 until n) sb.append("%02x".format(payload[i].toInt() and 0xFF))
        s.lastPayload = sb.toString()
    }

    fun reset() { table.clear() }

    /** `{"ms":<uptimeRef>,"frames":[{"cmd":"06:AE","n":123,"ageMs":40,"p":"..."} …]}` sorted by cmd. */
    fun statusJson(): String {
        val now = System.currentTimeMillis()
        val rows = table.entries
            .sortedBy { it.key }
            .joinToString(",", "[", "]") { (key, s) ->
                val cmd = "%02X:%02X".format((key shr 8) and 0xFF, key and 0xFF)
                "{\"cmd\":\"$cmd\",\"n\":${s.count.get()},\"ageMs\":${now - s.lastMs},\"p\":\"${s.lastPayload}\"}"
            }
        return "{\"frames\":$rows}"
    }
}
