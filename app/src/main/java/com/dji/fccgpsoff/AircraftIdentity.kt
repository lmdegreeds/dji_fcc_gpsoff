package com.dji.fccgpsoff

/**
 * The detected identities on the link — the DRONE and the REMOTE, tracked in
 * SEPARATE slots so one never masks the other.
 *
 * Why separate: on RC2 a plain DUML VersionInquiry is answered by the RC itself
 * (e.g. rc331 = DJI RC 2), while the drone's model must come from the flight
 * controller's version (an "fcNNNN" string → a wm/wa code), from DJI Fly's
 * on-screen name, or from a version frame seen passively. Publishing both into
 * one slot let the RC overwrite the drone. [publish] now routes by catalog kind:
 * AIRCRAFT -> [drone], REMOTE -> [rc]; probe codes are ignored.
 *
 * The capability flags that drive the FCC decision are the DRONE's ([droneModel]).
 * In-memory only, like [SerialSniffer]; the served page polls [statusJson].
 */
object AircraftIdentity {

    /** Trust order for identity sources — a higher rank wins a conflict. */
    enum class Source(val rank: Int) {
        UI(4),        // read off DJI Fly's own screen — most reliable
        DUML(3),      // a validated version/probe reply
        PROP(2),      // system property (RC self-id)
        PASSIVE(1),   // seen drifting past on the stream
        CACHE(0)      // remembered from a previous session
    }

    class Slot(private val expect: AircraftModelCatalog.Kind) {
        @Volatile var code: String = ""; private set
        @Volatile var name: String = ""; private set
        @Volatile var source: Source? = null; private set
        @Volatile var atMs: Long = 0L; private set

        val model: AircraftModelCatalog.DjiModel? get() = if (code.isEmpty()) null else AircraftModelCatalog.byCode(code)

        /** Returns true if this slot accepted [code]. */
        fun accept(code: String, name: String?, source: Source): Boolean {
            val c = AircraftModelCatalog.normalize(code)
            val m = AircraftModelCatalog.byCode(c) ?: return false
            if (m.kind != expect) return false
            // Don't let a weaker source (CACHE/PASSIVE) overwrite a stronger one
            // (UI/DUML) with a DIFFERENT model. A same-or-higher-rank source, or a
            // refresh of the same code, always goes through.
            if (this.code.isNotEmpty() && c != this.code && this.source != null &&
                source.rank < this.source!!.rank) return false
            val changed = c != this.code || source != this.source
            this.code = c
            this.name = name?.takeIf { it.isNotBlank() } ?: (m.name ?: c.uppercase())
            this.source = source
            atMs = System.currentTimeMillis()
            if (changed) DiagLog.info("${expect.name.lowercase()} model: $name [$c] via $source")
            return true
        }

        fun clear() { code = ""; name = ""; source = null; atMs = 0L }

        fun appendJson(sb: StringBuilder) {
            if (code.isEmpty()) { sb.append("null"); return }
            val m = model
            sb.append("{\"code\":\"").append(code).append("\"")
                .append(",\"name\":\"").append(jsonEsc(name)).append("\"")
                .append(",\"source\":\"").append(source).append("\"")
                .append(",\"ageMs\":").append(System.currentTimeMillis() - atMs)
            if (m != null) {
                sb.append(",\"license\":\"").append(m.license).append("\"")
                    .append(",\"allowAOA\":").append(m.allowAOA)
                    .append(",\"canUseVPN\":").append(m.canUseVPN)
                    .append(",\"hasEndpoints\":").append(m.hasEndpoints)
            }
            sb.append("}")
        }
    }

    val drone = Slot(AircraftModelCatalog.Kind.AIRCRAFT)
    val rc = Slot(AircraftModelCatalog.Kind.REMOTE)

    /** Route a resolved code to the right slot by its catalog kind. */
    fun publish(code: String, name: String?, source: Source): Boolean =
        drone.accept(code, name, source) || rc.accept(code, name, source)

    /** DRONE capability row — what the FCC path should branch on. */
    val droneModel: AircraftModelCatalog.DjiModel? get() = drone.model

    fun statusJson(): String {
        val sb = StringBuilder(256).append("{\"drone\":")
        drone.appendJson(sb)
        sb.append(",\"rc\":")
        rc.appendJson(sb)
        return sb.append("}").toString()
    }

    private fun jsonEsc(s: String): String = Json.esc(s)
}
