package com.dji.fccgpsoff

import android.content.Context
import android.content.res.Configuration
import java.text.Normalizer
import java.util.Locale

/**
 * The DJI Fly screen phrases we recognize, read out of **DJI Fly's own string
 * resources** rather than hard-coded here.
 *
 * Why not a regex over "Mode N / Режим N": the flight-mode label is fully
 * localized and in some locales carries no Latin letter at all — Fly 1.21.8
 * ships `普通挡` (zh-rCN), `普通擋` (zh-rHK/TW), `姿態擋` for attitude, and Russian
 * even reorders the P-mode label to `P Режим`. Any letter-based pattern misses
 * those. DJI Fly is installed on the same device and its resources are readable
 * via [Context.createPackageContext], so we ask IT what the label says in every
 * locale it ships — the same trick SkylabFCCfree uses for its Home Point phrases.
 *
 * Resource names verified against `dji.go.v5` / **DJI Fly 1.21.8** (aapt2 dump of
 * `firmware_unpack/DJI Fly 1.21.8.apk`, the build that runs on the RC 2) and
 * cross-checked on the v6 APK. If a future Fly renames them, [loaded] goes false
 * and [FALLBACK_MODE] keeps the detector working on the Latin/Cyrillic forms.
 *
 * Loading is one-shot and lazy; [ensureLoaded] is safe to call from any thread.
 */
object FlyUiPhrases {

    /** Flight-mode labels — their presence in Fly's top-left slot means a live aircraft. */
    private val MODE_RES = listOf(
        "fpv_basic_flight_normal_mode",       // "N Mode" / "Режим N" / "普通挡"
        "fpv_basic_flight_sport_mode",        // "S Mode" / "Режим S" / "运动挡"
        "fpv_basic_flight_cinesmooth_mode",   // "C Mode" / "Режим C" / "平稳挡"
        "fpv_basic_flight_position_mode",     // "P Mode" / "P Режим"
        "fpv_basic_flight_attitude_mode"      // "Attitude Mode" / "姿态挡"
    )

    /** Explicit "the aircraft is not on the link" banner (the red one on the FPV screen). */
    private val DISCONNECT_RES = listOf(
        "fpv_capsule_drone_disconnect",       // "Aircraft not connected to RC" / "Дрон не подключен к пульту"
        "ABCfly_device_no_bind_rc_toast"      // "Aircraft not bound to RC"
    )

    /** The placeholder Fly paints in the mode slot when there is no aircraft. */
    private val NA_RES = listOf("fpv_flightosd_na", "fpv_base_default_NA", "default_value_na")

    /**
     * Used when the installed Fly does not expose the names above. Deliberately
     * covers only the forms we can state without guessing: the Latin/Cyrillic
     * "<letter> mode" family from the 1.21.8 catalog plus the CJK labels. A miss
     * here is not dangerous — an unrecognized label reads as UNKNOWN, which
     * leaves the old blind-apply behavior in place.
     */
    private val FALLBACK_MODE = setOf(
        "n mode", "s mode", "c mode", "p mode", "t mode", "attitude mode",
        "mode n", "mode s", "mode c", "mode p", "modo n", "modo s", "modo c", "modo p",
        "n modu", "s modu", "c modu", "p modu", "tryb n", "tryb s", "tryb c", "tryb p",
        "n-modus", "s-modus", "c-modus", "p modus", "modalità n", "modalità s", "modalità c",
        "режим n", "режим s", "режим c", "p режим", "режим p",
        "nモード", "sモード", "cモード", "pモード", "姿勢モード",
        "n 모드", "s 모드", "c 모드", "p 모드",
        "普通挡", "普通擋", "运动挡", "運動擋", "平稳挡", "平穩擋", "姿态挡", "姿態擋"
    )

    private val FALLBACK_NA = setOf("n/a", "--", "—")

    @Volatile private var modePhrases: Set<String> = FALLBACK_MODE
    @Volatile private var disconnectPhrases: Set<String> = emptySet()
    @Volatile private var naPhrases: Set<String> = FALLBACK_NA
    @Volatile private var localeCount = 0
    @Volatile private var loadedFrom = ""
    @Volatile private var attempted = false

    /** True once a DJI package's resources supplied at least the mode labels. */
    val loaded: Boolean get() = loadedFrom.isNotEmpty()

    fun modes(): Set<String> = modePhrases
    fun disconnects(): Set<String> = disconnectPhrases
    fun nas(): Set<String> = naPhrases

    /** Case/width/whitespace-insensitive form; trailing sentence punctuation dropped
     *  ("Drone non connesso al radiocomando." ships with a full stop in it-IT). */
    fun normalize(value: CharSequence): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(WHITESPACE, " ")
            .trim()
            .trimEnd('.', '!', '?', '。', '！', '？')

    /**
     * Read the phrases from whichever DJI app is installed. One shot: the first
     * call does the work, later calls are free. Never throws — a failure leaves
     * the fallbacks in place and is reported in [statusJson].
     */
    @Synchronized
    fun ensureLoaded(ctx: Context) {
        if (attempted) return
        attempted = true
        for (pkg in ForegroundGate.DJI_PACKAGES) {
            val loadedNow = runCatching { loadFrom(ctx, pkg) }.getOrDefault(false)
            if (loadedNow) {
                DiagLog.info(
                    "fly UI phrases: loaded from $pkg — modes=${modePhrases.size} " +
                        "disconnect=${disconnectPhrases.size} na=${naPhrases.size} locales=$localeCount"
                )
                return
            }
        }
        DiagLog.warn(
            "fly UI phrases: no DJI app resources readable — using built-in list " +
                "(${FALLBACK_MODE.size} mode labels); link state may read UNKNOWN in exotic locales"
        )
    }

    /** Returns true when this package supplied the mode labels (the ones we cannot do without). */
    private fun loadFrom(ctx: Context, pkg: String): Boolean {
        val pkgCtx = ctx.createPackageContext(pkg, Context.CONTEXT_IGNORE_SECURITY)
        val base = pkgCtx.resources
        // Every locale the APK ships, plus whatever the device is set to. Capped:
        // a split/bundled APK can list hundreds and each one costs a Resources
        // instance — the DJI catalog only has ~24 real translations anyway.
        val tags = LinkedHashSet<String>()
        runCatching { base.assets.locales.filter { it.isNotBlank() }.forEach(tags::add) }
        base.configuration.locales.let { for (i in 0 until it.size()) tags.add(it[i].toLanguageTag()) }
        tags.add(Locale.ENGLISH.toLanguageTag())

        val modes = LinkedHashSet<String>()
        val disconnects = LinkedHashSet<String>()
        val nas = LinkedHashSet<String>()
        var seen = 0
        for (tag in tags) {
            if (seen++ >= MAX_LOCALES) break
            val cfg = Configuration(base.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
            val res = runCatching { pkgCtx.createConfigurationContext(cfg).resources }.getOrNull() ?: continue
            collect(res, pkg, MODE_RES, modes)
            collect(res, pkg, DISCONNECT_RES, disconnects)
            collect(res, pkg, NA_RES, nas)
        }
        if (modes.isEmpty()) return false

        modePhrases = modes + FALLBACK_MODE          // union: a renamed resource can't shrink coverage
        disconnectPhrases = disconnects
        naPhrases = nas + FALLBACK_NA
        localeCount = seen
        loadedFrom = pkg
        return true
    }

    private fun collect(res: android.content.res.Resources, pkg: String, names: List<String>, out: MutableSet<String>) {
        for (name in names) {
            val id = runCatching { res.getIdentifier(name, "string", pkg) }.getOrDefault(0)
            if (id == 0) continue
            runCatching { res.getText(id) }.getOrNull()
                ?.let(::normalize)
                ?.takeIf { it.isNotEmpty() }
                ?.let(out::add)
        }
    }

    fun statusJson(): String =
        "{\"loaded\":$loaded,\"source\":${Json.quote(loadedFrom)},\"locales\":$localeCount," +
            "\"modes\":${modePhrases.size},\"disconnect\":${disconnectPhrases.size},\"na\":${naPhrases.size}}"

    /** Test seam: install phrase sets without an Android Context. */
    internal fun setForTest(modes: Set<String>, disconnects: Set<String>, nas: Set<String>) {
        modePhrases = modes; disconnectPhrases = disconnects; naPhrases = nas
        attempted = true; loadedFrom = "test"; localeCount = 1
    }

    private const val MAX_LOCALES = 120
    private val WHITESPACE = Regex("\\s+")
}
