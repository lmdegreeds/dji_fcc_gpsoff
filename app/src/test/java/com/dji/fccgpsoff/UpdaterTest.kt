package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version ordering decides whether the app offers an update at all, so it is the
 * one part of [Updater] worth pinning down: a wrong answer either nags forever or
 * silently hides a release.
 */
class UpdaterTest {

    private fun newer(a: String, b: String) = Updater.compareVersions(a, b) > 0
    private fun same(a: String, b: String) = Updater.compareVersions(a, b) == 0

    @Test fun `higher numbers win componentwise`() {
        assertTrue(newer("1.1", "1.0"))
        assertTrue(newer("2.0", "1.9"))
        assertTrue(newer("1.0.1", "1.0"))
        assertTrue(newer("1.10", "1.9"))     // not string ordering
    }

    @Test fun `equal versions are equal, however written`() {
        assertTrue(same("1.0", "1.0"))
        assertTrue(same("v1.0", "1.0"))      // the leading v is part of the tag, not the version
        assertTrue(same("1.0", "1.0.0"))     // a missing component counts as zero
    }

    @Test fun `a prerelease ranks below the finished version`() {
        assertTrue(newer("1.2", "1.2-beta1"))
        assertTrue(newer("1.2-beta2", "1.2-beta1"))
        assertTrue(newer("1.2-beta1", "1.1"))     // still newer than the older release
    }

    @Test fun `unparseable components do not crash and sort as zero`() {
        assertEquals(0, Updater.compareVersions("", ""))
        assertTrue(newer("1.0", ""))
        assertTrue(same("1.x", "1.0"))
    }

    /** The real question the launch check asks. */
    @Test fun `installed 1_0 is behind a published 1_1 and not behind 0_9`() {
        assertTrue(Updater.compareVersions("1.1", "1.0") > 0)
        assertTrue(Updater.compareVersions("0.9", "1.0") < 0)
        assertTrue(Updater.compareVersions("1.0", "1.0") == 0)
    }

    /** An unknown size must not render as "0.0 MB", which reads like a real number. */
    @Test fun `size label is safe for an unknown size`() {
        assertEquals("—", Updater.sizeLabel(0))
        assertEquals("—", Updater.sizeLabel(-1))
    }

    /** The separator follows the UI language, not the JVM's default locale — this
     *  test would otherwise pass or fail depending on the machine running it. */
    @Test fun `size label follows the ui language, not the device locale`() {
        val saved = AppState.uiRu
        try {
            AppState.uiRu = false
            assertEquals("2.0 MB", Updater.sizeLabel(2_100_000))
            AppState.uiRu = true
            assertEquals("2,0 МБ", Updater.sizeLabel(2_100_000))
        } finally { AppState.uiRu = saved }
    }
}
