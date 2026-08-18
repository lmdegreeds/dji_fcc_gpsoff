package com.dji.fccgpsoff

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DUML fallback for identity when DJI Fly's on-screen name is unavailable.
 *
 * Two independent kicks, routed to the right [AircraftIdentity] slot by catalog
 * kind so the RC can never mask the drone:
 *   - RC:    VersionInquiry (00:01) to receiver 0 — the RC answers for itself
 *            (this is what resolves rc331 = "DJI RC 2").
 *   - DRONE: VersionInquiry (00:01) to the flight controller (receiver 3),
 *            wrapped on 40007 exactly like [AircraftSerial]'s serial query —
 *            the one route proven to route a reply back on RC2.
 *
 * Honesty note confirmed on live RC2 (DJI RC 2 + drone): the 00:01 reply is a
 * short version-number block and often carries NO ASCII model string, so the
 * drone code frequently does NOT come from here. The reliable drone-model source
 * is the DJI Fly screen ([DjiFlyAccessibilityService]); this probe is a bonus.
 * [parseVersionReply] is pure and unit-tested.
 */
object AircraftModelProbe {

    private const val HOST = "127.0.0.1"
    private const val READ_WINDOW_MS = 500

    /**
     * Extract a catalog model code from a VersionInquiry (00:01) reply payload:
     * NLDFCC's offset-16 device-type field first, then any printable-ASCII run
     * that normalizes to a known code (catches an "fcNNNN" flight-controller id).
     * Returns the normalized code or null.
     */
    fun parseVersionReply(pl: ByteArray): String? {
        if (pl.isEmpty()) return null
        if (pl.size >= 18) {
            val field = String(pl, 16, pl.size - 16, Charsets.US_ASCII)
            AircraftModelCatalog.fromVersionField(field)?.let { code ->
                if (AircraftModelCatalog.byCode(code) != null) return AircraftModelCatalog.normalize(code)
            }
        }
        for (run in asciiRuns(pl)) {
            for (tok in run.split(" ")) {
                val code = AircraftModelCatalog.normalize(tok)
                if (code.isNotEmpty() && AircraftModelCatalog.byCode(code) != null) return code
            }
        }
        return null
    }

    /** Runs of printable ASCII (>= 3 chars) inside a byte array. */
    private fun asciiRuns(b: ByteArray, min: Int = 3): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (byte in b) {
            val c = byte.toInt() and 0xFF
            if (c in 0x20..0x7E) sb.append(c.toChar())
            else { if (sb.length >= min) out.add(sb.toString()); sb.setLength(0) }
        }
        if (sb.length >= min) out.add(sb.toString())
        return out
    }

    /**
     * Kick both the RC and the drone for their version, publish whatever
     * resolves (by kind). Respects the read gate. Returns a short summary.
     */
    suspend fun capture(f: Features): String {
        if (!ForegroundGate.readsAllowed()) {
            DiagLog.warn("model probe skipped: ${ForegroundGate.blockReason()}")
            return "blocked: DJI Fly is the active window"
        }
        // RC — the local VersionInquiry (receiver 0) via the shipped profile.
        val rc = f.deviceInfo()?.let { parseVersionReply(it) }?.let {
            if (AircraftIdentity.publish(it, null, AircraftIdentity.Source.DUML)) it else null
        }
        // Drone — VersionInquiry to the flight controller (receiver 3) on 40007.
        val drone = probeDrone()?.let {
            if (AircraftIdentity.publish(it, null, AircraftIdentity.Source.DUML)) it else null
        }
        return buildString {
            append("rc=").append(rc?.let { "${AircraftIdentity.rc.name} [$it]" } ?: "—")
            append(" | drone=").append(drone?.let { "${AircraftIdentity.drone.name} [$it]" }
                ?: "— (open DJI Fly to read the drone name from its screen)")
        }
    }

    /** Build the WRAPPED 00:01 request to the flight controller (40007 route). */
    private fun droneRequest(): ByteArray {
        val inner = DumlNative.nativeBuildFrame(
            DumlWire.SENDER_APP0, DumlWire.DST_FLIGHT, DumlWire.CT_ACK, 0x00, 0x01, ByteArray(0)
        )
        return DumlWire.wrap(inner)
    }

    /**
     * Send the drone version query on 40007 and read the window, mirroring
     * [AircraftSerial]'s serial probe. Returns a normalized AIRCRAFT code or null.
     */
    private suspend fun probeDrone(): String? = withContext(Dispatchers.IO) {
        val wire = droneRequest()
        DiagLog.tx(DumlWire.PORT_LED, "version 00:01 (drone)", wire)
        try {
            Socket().use { s ->
                s.connect(InetSocketAddress(HOST, DumlWire.PORT_LED), 400)
                s.soTimeout = 300
                s.getOutputStream().apply { write(wire); flush() }
                val end = System.currentTimeMillis() + READ_WINDOW_MS
                val out = ArrayList<Byte>(8192)
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    val r = try { s.getInputStream().read(buf) } catch (e: Exception) { -1 }
                    if (r < 0) break
                    for (k in 0 until r) out.add(buf[k])
                    extractDroneCode(out.toByteArray())?.let { return@withContext it }
                }
                extractDroneCode(out.toByteArray())
            }
        } catch (e: Exception) {
            DiagLog.err("drone version probe: ${e.message}"); null
        }
    }

    /**
     * Walk a 40007 window ([55 CC 30 75][len32][inner DUML]) for a 00:01
     * response frame and resolve an AIRCRAFT code from its payload. Mirrors
     * [AircraftSerial]'s extractor but for cmdId 0x01.
     */
    private fun extractDroneCode(stream: ByteArray, len: Int = stream.size): String? =
        WrappedFrames.walk(stream, len) { fr ->
            if (fr.cmdSet == 0x00 && fr.cmdId == 0x01 && fr.isResponse) {
                val code = parseVersionReply(fr.payload)
                if (code != null && AircraftModelCatalog.byCode(code)?.kind == AircraftModelCatalog.Kind.AIRCRAFT) code
                else null
            } else null
        }
}
