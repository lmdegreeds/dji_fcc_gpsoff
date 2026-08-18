package com.dji.fccgpsoff

/**
 * DJI aircraft/controller model catalog — modelId -> commercial name + the
 * capability flags that decide which FCC path applies.
 *
 * Two-source strategy (see 07-models.md):
 *   - The DETECTION *method* is Skylab-style: prefer the model name DJI Fly
 *     paints on screen (read by [DjiFlyAccessibilityService] and matched here
 *     via [findOnScreen]); a DUML version query is the fallback.
 *   - The capability FLAGS (allowAOA / canUseVPN / license / endpoints) come
 *     from the NLDFCC "DjiDeviceSpec" table (r3/i1.java), which SkylabFCCfree's
 *     own small code->name catalog does not carry. Each row is transcribed as
 *     the original j1 constructor was: (code, name, kind, license, flags), and
 *     the booleans are derived from `flags` exactly like j1 does:
 *         allowAOA    = flags & 0x40 != 0     (bit 6)
 *         canUseVPN   = flags & 0x80 == 0     (bit 7 CLEAR)
 *         hasEndpoints= flags & 0x10 == 0     (bit 4 CLEAR — Mavic3/P4/Inspire)
 *
 * Pure Kotlin, no Android imports, so it is unit-testable off-device.
 */
object AircraftModelCatalog {

    enum class Kind { AIRCRAFT, REMOTE, PROBE }
    enum class License { NONE, CONSUMER, ENTERPRISE, INDUSTRIAL, SMART_RC }

    data class DjiModel(
        val code: String,          // normalized modelId, e.g. "wm260"
        val name: String?,         // commercial name; null for unnamed probe codes
        val kind: Kind,
        val license: License,
        val flags: Int,
    ) {
        val allowAOA: Boolean get() = flags and 0x40 != 0
        val canUseVPN: Boolean get() = flags and 0x80 == 0
        val hasEndpoints: Boolean get() = flags and 0x10 == 0
    }

    /** A resolved on-screen / version-string identity. */
    data class Match(val code: String, val name: String?)

    // Explicit constructor keeps license unambiguous per row (transcribed from
    // the table rather than guessed from flags, which does not encode license).
    private fun m(code: String, name: String?, kind: Kind, license: License, flags: Int) =
        DjiModel(code, name, kind, license, flags)

    /** The full table — 101 rows, exactly matching 07-models.md. */
    val ALL: List<DjiModel> = listOf(
        // ---- Aircraft (70) ----
        m("ag405", "AGRAS MG-1S", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag406", "AGRAS MG-1A", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag407", "AGRAS MG-1P RTK", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag410", "AGRAS T20", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag411", "AGRAS T20", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag500", "AGRAS T10", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag501", "AGRAS T30", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag601", "AGRAS T40", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag700", "AGRAS T25", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag701", "AGRAS T50", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag802", "AGRAS T60", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag811", "AGRAS T70", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ag911", "AGRAS T100", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ea220e", "MATRICE 3D", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("ea220t", "MATRICE 3D THERMAL", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("m600", "MATRICE 600 PRO", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("m601", "MATRICE 600", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("pm320", "MATRICE 30 SERIES (M30/M30T)", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("pm410", "MATRICE 200", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("pm420", "MATRICE 200 V2", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("pm430", "MATRICE 300 RTK", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("pm431", "MATRICE 350 RTK", Kind.AIRCRAFT, License.INDUSTRIAL, 240),
        m("ta101", "FLYCART", Kind.AIRCRAFT, License.SMART_RC, 240),
        m("wa020", "NEO 2", Kind.AIRCRAFT, License.CONSUMER, 112),
        m("wa140", "MINI 4 PRO", Kind.AIRCRAFT, License.CONSUMER, 48),
        m("wa141", "FLIP", Kind.AIRCRAFT, License.CONSUMER, 112),
        m("wa150", "MINI 5 PRO", Kind.AIRCRAFT, License.CONSUMER, 48),
        m("wa151", "DJI Lito X1", Kind.AIRCRAFT, License.CONSUMER, 48),
        m("wa152", "DJI Lito 1", Kind.AIRCRAFT, License.CONSUMER, 48),
        m("wa1617", "MINI 4K", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wa233", "AIR 3", Kind.AIRCRAFT, License.CONSUMER, 176),
        m("wa234", "AIR 3S", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wa341", "MAVIC 4 PRO", Kind.AIRCRAFT, License.CONSUMER, 176),
        m("wa345e", "MATRICE 4E", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("wa345t", "MATRICE 4T", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("wa520", "AVATA 2", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wa521", "NEO", Kind.AIRCRAFT, License.CONSUMER, 112),
        m("wm100", "SPARK", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm100a", "SPARK", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm160", "MAVIC MINI", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm1605", "MINI SE", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm161", "MINI 2", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm1615", "MINI 2 SE", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm162", "MINI 3 PRO", Kind.AIRCRAFT, License.CONSUMER, 176),
        m("wm163", "MINI 3", Kind.AIRCRAFT, License.CONSUMER, 176),
        m("wm169", "AVATA", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm170", "FPV RACER", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm171", "FPV RACER 2", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm220", "MAVIC PRO", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm230", "MAVIC AIR", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm231", "MAVIC AIR 2", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm232", "AIR 2S", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm240", "MAVIC 2 PRO/ZOOM", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm245", "MAVIC 2 ENTERPRISE", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("wm246", "MAVIC 2 ENTERPRISE DUAL", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("wm247", "MAVIC 2 ENTERPRISE ADV", Kind.AIRCRAFT, License.ENTERPRISE, 240),
        m("wm260", "MAVIC 3", Kind.AIRCRAFT, License.CONSUMER, 240),
        m("wm2605", "MAVIC 3 CLASSIC", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm261", "MAVIC 3 PRO", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm265e", "MAVIC 3 ENTERPRISE", Kind.AIRCRAFT, License.ENTERPRISE, 224),
        m("wm265m", "MAVIC 3 MULTISPECTRAL", Kind.AIRCRAFT, License.ENTERPRISE, 224),
        m("wm265t", "MAVIC 3 THERMAL", Kind.AIRCRAFT, License.ENTERPRISE, 224),
        m("wm330", "PHANTOM 4 STANDARD", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm331", "PHANTOM 4 PROFESSIONAL", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm332", "PHANTOM 4 ADVANCED", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm334", "PHANTOM 4 RTK", Kind.AIRCRAFT, License.ENTERPRISE, 224),
        m("wm335", "PHANTOM 4 PROFESSIONAL 2.0", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm336", "PHANTOM 4 MULTISPECTRAL", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm620", "INSPIRE 2", Kind.AIRCRAFT, License.CONSUMER, 224),
        m("wm630", "INSPIRE 3", Kind.AIRCRAFT, License.ENTERPRISE, 224),
        // ---- Remote controllers (27) ----
        m("gl300", "Phantom 4 Std RC", Kind.REMOTE, License.NONE, 248),
        m("gl300c", "Phantom 4 Std RC", Kind.REMOTE, License.NONE, 248),
        m("gl300e", "Phantom 4 Pro/Adv RC", Kind.REMOTE, License.NONE, 248),
        m("gl300k", "Phantom 4 Pro+ V2.0 RC", Kind.REMOTE, License.NONE, 248),
        m("gl800a", "Cendence Remote Controller", Kind.REMOTE, License.NONE, 248),
        m("mr1sd25", "Mini SE RC", Kind.REMOTE, License.NONE, 248),
        m("mr1ss5", "Mavic Mini RC", Kind.REMOTE, License.NONE, 248),
        m("rc151", "DJI RC-N2", Kind.REMOTE, License.NONE, 248),
        m("rc151b", "DJI RC-N3", Kind.REMOTE, License.NONE, 248),
        m("rc160", "Mavic Mini RC", Kind.REMOTE, License.NONE, 248),
        m("rc221", "Avata 2 Motion RC", Kind.REMOTE, License.NONE, 248),
        m("rc331", "DJI RC 2", Kind.REMOTE, License.SMART_RC, 112),
        m("rc430", "Matrice 300 RC", Kind.REMOTE, License.SMART_RC, 240),
        m("rc520", "RC Pro 2", Kind.REMOTE, License.SMART_RC, 112),
        m("rc701", "RC Plus 2", Kind.REMOTE, License.SMART_RC, 240),
        m("rcc231", "DJI RC-N1C", Kind.REMOTE, License.NONE, 248),
        m("rcs231", "DJI RC-N1", Kind.REMOTE, License.NONE, 248),
        m("rm010", "Unknown (RC010)", Kind.REMOTE, License.NONE, 248),
        m("rm220", "RC Motion 2", Kind.REMOTE, License.NONE, 248),
        m("rm330", "DJI RC", Kind.REMOTE, License.SMART_RC, 240),
        m("rm500", "DJI Smart Controller", Kind.REMOTE, License.SMART_RC, 240),
        m("rm510", "RC Pro", Kind.REMOTE, License.SMART_RC, 240),
        m("rm510b", "RC Pro Enterprise", Kind.REMOTE, License.SMART_RC, 240),
        m("rm510bv", "RC Pro Enterprise", Kind.REMOTE, License.SMART_RC, 240),
        m("rm700", "RC Plus", Kind.REMOTE, License.SMART_RC, 240),
        m("rm700_enterprise", "RC Plus Enterprise", Kind.REMOTE, License.SMART_RC, 240),
        m("wm220_rc", "Mavic Pro RC", Kind.REMOTE, License.NONE, 248),
        // ---- Probe / service codes (4) ----
        m("3ae5", null, Kind.PROBE, License.CONSUMER, 242),
        m("d902", null, Kind.PROBE, License.CONSUMER, 242),
        m("wm220_gl", "DJI Gogggles V1", Kind.PROBE, License.NONE, 248),
        m("zv811", null, Kind.PROBE, License.CONSUMER, 242),
    )

    /** modelId -> row. Codes are unique. */
    val byCodeMap: Map<String, DjiModel> = ALL.associateBy { it.code }

    fun byCode(code: String): DjiModel? = byCodeMap[normalize(code)]

    // Aircraft with a unique commercial name, longest name first so a more
    // specific name ("MAVIC 3 PRO") wins over a prefix of it ("MAVIC 3").
    private val aircraftByName: List<Pair<String, DjiModel>> = ALL
        .filter { it.kind == Kind.AIRCRAFT && !it.name.isNullOrBlank() }
        .groupBy { it.name!!.uppercase() }
        .filterValues { it.size == 1 }               // drop ambiguous shared names (e.g. duplicate SPARK)
        .map { (name, list) -> name to list.first() }
        .sortedByDescending { it.first.length }

    // DJI Fly / Pilot on-screen spellings that differ from the catalog name.
    // Extend as observed on real screens. Key is uppercased alias text.
    private val aliases: List<Pair<String, String>> = listOf(
        "MAVIC 3E" to "wm265e",
        "MAVIC 3T" to "wm265t",
        "MAVIC 3M" to "wm265m",
        "MAVIC 3 CINE" to "wm261",
        "DJI FPV" to "wm170",
    ).sortedByDescending { it.first.length }

    // ---- normalization (ported from NLDFCC r3.i1.b / .a) ----

    private val FC_RE = Regex("^fc(\\d{3,5})$")
    private val CODE_ON_SCREEN_RE = Regex("(?i)\\bW[AM][0-9]{3}[0-9A-Z]?\\b")

    /** Strip embedded NULs, trim, and collapse internal whitespace runs to ONE
     *  space (single spaces are kept so multi-word names still match). */
    private fun flatten(s: String): String =
        s.filter { it.code != 0 }.trim().replace(Regex("\\s+"), " ")

    /**
     * Core code normalizer (= r3.i1.b): lowercase and strip spaces (codes have
     * none); a flight controller that reports "fcNNNN" maps to "wm"+last-3
     * digits; otherwise the string is already the modelId. "" for blank input.
     */
    fun normalize(raw: String?): String {
        val s = flatten(raw ?: "").replace(" ", "").lowercase()
        if (s.isEmpty()) return ""
        val fc = FC_RE.find(s) ?: return s
        val digits = fc.groupValues[1]
        val last3 = if (digits.length > 3) digits.substring(digits.length - 3) else digits
        return "wm$last3"
    }

    /**
     * Normalize a raw DUML version-inquiry string (= r3.i1.a): strip NULs, handle
     * the P4-standard special cases, take the first whitespace-separated token,
     * then [normalize]. Returns null if nothing usable.
     */
    fun fromVersionField(raw: String?): String? {
        val s = flatten(raw ?: "")
        if (s.isEmpty()) return null
        if (s.equals("ver.a", ignoreCase = true) || s.contains("dji p1 s", ignoreCase = true)) return "wm330"
        val first = s.split(' ').firstOrNull().orEmpty()
        if (first.isEmpty()) return null
        return normalize(first).ifEmpty { null }
    }

    /**
     * Resolve an aircraft identity from a bag of on-screen strings (DJI Fly UI).
     * Priority: explicit modelId codes (unambiguous) > commercial name match.
     * Only aircraft are returned — RC/probe rows are ignored here.
     *
     * Collect EVERY distinct aircraft the text names and return one only when the
     * screen names exactly one. A DJI-Fly device chooser / "select model" page
     * lists many models at once; picking the first (e.g. "NEO") off such a list
     * mislabels whatever is actually connected — so a multi-model screen resolves
     * to nothing, not to a guess. Per text string only the longest name match is
     * counted, so "Mavic 3 Pro" is one model (wm261), not also its "Mavic 3" prefix.
     */
    fun findOnScreen(texts: List<String>): Match? {
        val codes = LinkedHashSet<String>()
        // 1) explicit modelId codes anywhere in the text (e.g. an "about" screen).
        for (t in texts) {
            for (hit in CODE_ON_SCREEN_RE.findAll(t)) {
                val model = byCode(hit.value)
                if (model != null && model.kind == Kind.AIRCRAFT) codes.add(model.code)
            }
            // 1b) a bare code token of any length ("wm1605", "ea220e").
            for (tok in flatten(t).lowercase().split(' ')) {
                val model = byCodeMap[normalize(tok)]
                if (model != null && model.kind == Kind.AIRCRAFT) codes.add(model.code)
            }
        }
        // 2) commercial name — per string, take the single LONGEST match (aliases
        //    first, then catalog names, both longest-first) so overlapping name
        //    variants of ONE model don't count as several.
        for (t in texts) {
            val up = flatten(t).uppercase()
            if (up.isEmpty()) continue
            val code = aliases.firstOrNull { containsWord(up, it.first) }?.second
                ?: aircraftByName.firstOrNull { containsWord(up, it.first) }?.second?.code
            if (code != null) codes.add(code)
        }
        if (codes.size > 1) return null          // a list/picker names many models — don't guess
        val code = codes.firstOrNull() ?: return null
        return byCode(code)?.let { Match(it.code, it.name) }
    }

    /**
     * Resolve a REMOTE (controller) from a raw system-property value the way
     * NLDFCC's r3.i1.e/.f do: exact code match first, then a longest-first
     * prefix match against the known RC codes (a prop like "rc331_something"
     * still resolves), matching only on a word boundary.
     */
    fun resolveRemote(raw: String?): Match? {
        val low = flatten(raw ?: "").lowercase().replace(" ", "")
        if (low.isEmpty()) return null
        byCodeMap[normalize(low)]?.let { if (it.kind == Kind.REMOTE) return Match(it.code, it.name) }
        for ((name, code) in remoteByPrefix) {
            if (low == code) return Match(code, name)
            if (low.startsWith(code) && (low.length == code.length || !low[code.length].isLetterOrDigit()))
                return Match(code, name)
        }
        return null
    }

    // RC codes, longest-first, so "rc151b" wins over "rc151" on a prefix match.
    private val remoteByPrefix: List<Pair<String, String>> = ALL
        .filter { it.kind == Kind.REMOTE }
        .sortedByDescending { it.code.length }
        .map { (it.name ?: it.code) to it.code }

    /** True if [needle] appears in [hay] bounded by non-alphanumeric chars / edges. */
    private fun containsWord(hay: String, needle: String): Boolean {
        if (needle.isEmpty()) return false
        var from = 0
        while (true) {
            val i = hay.indexOf(needle, from)
            if (i < 0) return false
            val before = i == 0 || !hay[i - 1].isLetterOrDigit()
            val endIdx = i + needle.length
            val after = endIdx >= hay.length || !hay[endIdx].isLetterOrDigit()
            if (before && after) return true
            from = i + 1
        }
    }
}
