package com.dji.fccgpsoff

import android.content.Context
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Loads a FreeFCC/Skylab-format JSON profile and plays it on the port the
 * profile names (40009 for all shipped profiles), applying the 55CC3075
 * envelope only if the profile sets "wrapper": true.
 *
 * Frame fields: s=cmdSet, i=cmdId, d=dst(receiver TTII), p=payload hex.
 * Top-level: sender, cmd_type, rounds, inter_frame_delay_ms,
 *            inter_round_delay_ms, read_window_ms, port, wrapper, needs_response.
 */
class ProfileRunner(private val ctx: Context) {

    data class Frame(val cmdSet: Int, val cmdId: Int, val dst: Int, val payload: ByteArray, val sender: Int, val cmdType: Int, val note: String)
    data class Profile(
        val name: String, val port: Int, val wrapper: Boolean, val needsResponse: Boolean,
        val rounds: Int, val interFrameMs: Long, val interRoundMs: Long, val readWindowMs: Int,
        val frames: List<Frame>
    )

    fun load(asset: String): Profile =
        parse(ctx.assets.open("profiles/$asset").bufferedReader().use { it.readText() }, asset)

    companion object {
        /**
         * Pure JSON → [Profile] parse, split out of [load] so it can be unit-tested
         * off-device (no assets/Context). [asset] is only used for error messages.
         */
        fun parse(json: String, asset: String): Profile {
        val o = JSONObject(json)
        val sender = o.optInt("sender", 130)
        val cmdType = o.optInt("cmd_type", 0x20)
        val arr = o.getJSONArray("frames")
        val frames = (0 until arr.length()).map { k ->
            val f = arr.getJSONObject(k)
            // Every wire byte must fit a u8; a bad payload or field is a broken
            // profile — fail loudly with the frame index rather than send a
            // truncated FCC sequence or throw an opaque JSON/hex exception.
            fun u8(key: String, dflt: Int? = null): Int {
                val v = if (dflt == null) f.getInt(key) else f.optInt(key, dflt)
                require(v in 0..255) { "$asset frame $k: $key=$v out of range 0..255" }
                return v
            }
            val payload = try { DumlWire.hex(f.optString("p", "")) }
                catch (e: Exception) { throw IllegalArgumentException("$asset frame $k: bad payload — ${e.message}") }
            Frame(u8("s"), u8("i"), u8("d"), payload,
                  u8("sender", sender), u8("cmd_type", cmdType), f.optString("note", ""))
        }
        return Profile(
            name = o.optString("name", asset),
            port = o.optInt("port", DumlWire.PORT_FCC),
            wrapper = o.optBoolean("wrapper", false),
            needsResponse = o.optBoolean("needs_response", false),
            rounds = o.optInt("rounds", 1),
            interFrameMs = o.optLong("inter_frame_delay_ms", 30),
            interRoundMs = o.optLong("inter_round_delay_ms", 100),
            readWindowMs = o.optInt("read_window_ms", 50),
            frames = frames
        )
        }
    }

    /**
     * Outcome of a run:
     *  - [ok] is false only when the per-port lock could not be taken (another of
     *    our sessions is on that port) — so callers can tell "port busy" from
     *    "played", which is the normal case.
     *  - [sent] is true only when EVERY frame write went out (connect + full
     *    write). A partial send — e.g. the link dropped after the first of N
     *    frames — is a FAILED apply, not a success, and must not be reported as
     *    one. Mirrors FreeFCC 099081c (allFramesSucceeded). Frames that need a
     *    reply take the read path and don't feed [sent].
     */
    data class Result(val ok: Boolean, val sent: Boolean, val reply: ByteArray?)

    /**
     * Play a profile once (all rounds), serialized per-port against other sessions.
     * [alreadyLeased] = the caller already holds the port lease (e.g. Apply FCC runs
     * the profile and its follow-up regulatory write under one lease so the whole
     * sequence is atomic); then this must NOT re-acquire the same non-reentrant lock.
     */
    suspend fun run(p: Profile, alreadyLeased: Boolean = false): Result {
        val lease = if (alreadyLeased) null else PortSessionLock.acquire(p.port)
        if (!alreadyLeased && lease == null) {
            DiagLog.warn("${p.name}: port ${p.port} busy — skipped")
            return Result(false, false, null)
        }
        var lastReply: ByteArray? = null
        var allSent = true
        var wroteAny = false          // a response-only profile writes nothing → sent must be false
        try {
            repeat(p.rounds) { round ->
                for (fr in p.frames) {
                    val inner = DumlNative.nativeBuildFrame(fr.sender, fr.dst, fr.cmdType, fr.cmdSet, fr.cmdId, fr.payload)
                    val wire = if (p.wrapper) DumlWire.wrap(inner) else inner
                    val tag = "%02X:%02X".format(fr.cmdSet, fr.cmdId) + if (fr.note.isNotEmpty()) " — ${fr.note}" else ""
                    if (p.needsResponse) {
                        // reply path (device info / diag): a null reply is normal on
                        // RC2, so it can't gate [sent] — leave allSent to the writes.
                        val reply = DumlBus.sendOnce(p.port, wire, p.readWindowMs, "${p.name} $tag")
                        if (reply != null) lastReply = reply
                    } else {
                        wroteAny = true
                        if (!DumlBus.sendFrame(p.port, wire, "${p.name} $tag")) allSent = false
                    }
                    delay(p.interFrameMs)
                }
                if (round < p.rounds - 1) delay(p.interRoundMs)
            }
        } finally { lease?.close() }
        // sent is meaningful only for write profiles; a response-only profile wrote
        // nothing, so it must not report a successful send.
        return Result(true, allSent && wroteAny, lastReply)
    }
}
