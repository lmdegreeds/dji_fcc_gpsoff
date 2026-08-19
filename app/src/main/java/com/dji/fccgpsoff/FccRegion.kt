package com.dji.fccgpsoff

/**
 * Radio country the FCC switch writes (2026-08-19).
 *
 * `fcc.json`'s one 07:30 frame carries a two-letter ASCII country twice — the
 * 2.4 and 5.8 channel groups — and that code is the whole switch: "AU" turns
 * FCC power and the 5.8 band on, the aircraft's own country puts it back to CE
 * live, no reboot. See `doc/fcc-minimal-sequence.md`.
 *
 * The codes here are the ones the firmware was observed to take (the
 * `_reverting` note in `fcc.json`). AU stays the default: it is the code every
 * hardware-confirmed run used. Anything else is the user's choice, applied to
 * every command that carries a country — the apply path ([Features.applyFcc]),
 * the keepalive's re-apply (same path) and the "is it still FCC?" probe
 * ([FccCountry]).
 */
enum class FccRegion(val code: String, val label: String) {
    AUSTRALIA("AU", "Australia"),
    CHINA("CN", "China"),
    UNITED_STATES("US", "United States"),
    BOLIVIA("BO", "Bolivia"),
    RUSSIA("RU", "Russia"),
    NETHERLANDS("NL", "Netherlands"),
    MALAYSIA("MY", "Malaysia");

    /** `AU · Australia` — what the picker and the log show. */
    fun display(): String = "$code · $label"

    companion object {
        /** The code every hardware-confirmed FCC run on this project used. */
        val DEFAULT = AUSTRALIA

        /** Persisted code → region, falling back to [DEFAULT] for an unknown one. */
        fun of(code: String?): FccRegion =
            values().firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) } ?: DEFAULT

        // The 07:30 country frame: cmdSet 07, cmdId 30, payload
        // `CC 00 00 CC 00 00 01 00` — the code at byte 0 and again at byte 4.
        private const val CMDSET_WIFI = 0x07
        private const val CMDID_CHANNEL_GROUP = 0x30

        /**
         * Return [p] with every 07:30 country frame rewritten to [r].
         *
         * The profile stays the source of truth for order, cadence and rounds —
         * all of which were established on hardware and must not be re-derived in
         * code — and only the two ASCII code fields move. A frame whose payload
         * does not have the expected letters at 0..1 and 4..5 is left untouched
         * rather than patched on a guess.
         */
        fun patch(p: ProfileRunner.Profile, r: FccRegion): ProfileRunner.Profile {
            val a0 = r.code[0].code.toByte(); val a1 = r.code[1].code.toByte()
            val frames = p.frames.map { f ->
                if (f.cmdSet != CMDSET_WIFI || f.cmdId != CMDID_CHANNEL_GROUP) f
                else {
                    val pl = f.payload
                    if (pl.size < 6 || !isAlpha(pl[0]) || !isAlpha(pl[1]) || !isAlpha(pl[4]) || !isAlpha(pl[5])) f
                    else {
                        val out = pl.copyOf()
                        out[0] = a0; out[1] = a1; out[4] = a0; out[5] = a1
                        // The note ends up in the frame log, so it must name the code
                        // that actually went out, not the one the asset was written with.
                        f.copy(payload = out, note = "WIFI channel groups -> ${r.code}")
                    }
                }
            }
            return p.copy(name = "${p.name} ${r.code}", frames = frames)
        }

        private fun isAlpha(b: Byte): Boolean = (b.toInt() and 0xFF) in 65..90
    }
}
