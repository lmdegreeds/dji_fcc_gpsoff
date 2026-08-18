package com.dji.fccgpsoff

/**
 * Resolves the REMOTE (the controller we run on) from Android system properties,
 * the way SkylabFCCfree/NLDFCC do (r3.e.g reads ro.product.name / ro.build.product
 * and matches the RC table). This is local and reliable — no DUML round-trip — so
 * it fills the RC slot even when the racy version query does not.
 */
object ControllerProbe {

    // Order matters: the most model-specific keys first.
    private val KEYS = listOf(
        "ro.product.name", "ro.build.product", "ro.product.device",
        "ro.product.model", "ro.product.board"
    )

    /** Read a system property via the hidden android.os.SystemProperties.get. */
    fun prop(key: String): String = try {
        val c = Class.forName("android.os.SystemProperties")
        val m = c.getMethod("get", String::class.java)
        (m.invoke(null, key) as? String).orEmpty().trim()
    } catch (e: Exception) { "" }

    /** Resolve + publish the RC to [AircraftIdentity]. Returns the match or null. */
    fun read(): AircraftModelCatalog.Match? {
        for (k in KEYS) {
            val v = prop(k)
            if (v.isEmpty()) continue
            val m = AircraftModelCatalog.resolveRemote(v) ?: continue
            AircraftIdentity.publish(m.code, m.name, AircraftIdentity.Source.PROP)
            DiagLog.info("controller: ${m.name} [${m.code}] from $k=$v")
            return m
        }
        return null
    }

    /** Raw values of every probed key, for diagnostics (what the RC actually reports). */
    fun rawProps(): String = KEYS.joinToString(" | ") { "$it=" + prop(it).ifEmpty { "-" } }
}
