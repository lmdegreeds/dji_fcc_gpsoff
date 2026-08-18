package com.dji.fccgpsoff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Screen-derived aircraft-link detection. The phrase sets used here are the real
 * ones from `dji.go.v5` / DJI Fly 1.21.8 (aapt2 dump), including the Chinese
 * labels that carry no Latin letter — the reason the detector reads Fly's own
 * resources instead of matching a "Mode <letter>" pattern.
 */
class FlyLinkUiTest {

    @Before fun setUp() {
        FlyUiPhrases.setForTest(
            modes = setOf("n mode", "режим c", "режим n", "普通挡", "姿态挡", "modalità s"),
            disconnects = setOf("aircraft not connected to rc", "дрон не подключен к пульту"),
            nas = setOf("n/a")
        )
        FlyLink.resetForTest()
        FlyLink.clock = { now }
    }

    private var now = 1_000L

    // ---- classify -----------------------------------------------------------

    @Test fun `flight mode in the slot means linked`() {
        assertEquals(FlyLinkUiState.CONNECTED, FlyLinkUi.classify(listOf("Режим C"), listOf("Режим C", "0.0m")))
    }

    @Test fun `localized label without a latin letter still means linked`() {
        assertEquals(FlyLinkUiState.CONNECTED, FlyLinkUi.classify(listOf("普通挡"), listOf("普通挡")))
    }

    @Test fun `na in the mode slot means no aircraft`() {
        assertEquals(FlyLinkUiState.DISCONNECTED, FlyLinkUi.classify(listOf("N/A"), listOf("N/A", "0.0m")))
    }

    /** The exact failure a naive matcher hits: the FPV screen shows N/A for ISO,
     *  WB, F, S and MM in the bottom-right while an aircraft IS linked. */
    @Test fun `na in the camera row does not mean disconnected`() {
        val screen = listOf("Режим N", "N/A", "N/A", "N/A", "0.0m/s")
        assertEquals(FlyLinkUiState.CONNECTED, FlyLinkUi.classify(listOf("Режим N"), screen))
        // …and with no mode slot reading at all, camera N/A alone stays UNKNOWN.
        assertEquals(FlyLinkUiState.UNKNOWN, FlyLinkUi.classify(emptyList(), listOf("N/A", "0.0m/s")))
    }

    @Test fun `disconnect banner anywhere means no aircraft`() {
        assertEquals(
            FlyLinkUiState.DISCONNECTED,
            FlyLinkUi.classify(emptyList(), listOf("Дрон не подключен к пульту", "0.0m"))
        )
    }

    @Test fun `a mode label beats a banner still fading out`() {
        assertEquals(
            FlyLinkUiState.CONNECTED,
            FlyLinkUi.classify(listOf("Режим C"), listOf("Режим C", "Дрон не подключен к пульту"))
        )
    }

    @Test fun `an unrelated fly screen says nothing`() {
        assertEquals(FlyLinkUiState.UNKNOWN, FlyLinkUi.classify(listOf("Альбом"), listOf("Альбом", "Настройки")))
    }

    @Test fun `case width and trailing punctuation are normalized`() {
        assertEquals(FlyLinkUiState.CONNECTED, FlyLinkUi.classify(listOf("  N  MODE "), listOf("N MODE")))
        assertEquals(
            FlyLinkUiState.DISCONNECTED,
            FlyLinkUi.classify(emptyList(), listOf("Aircraft not connected to RC."))
        )
    }

    // ---- session state ------------------------------------------------------

    @Test fun `first connected sighting opens a session`() {
        assertEquals(0L, FlyLink.generation)
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        assertEquals(1L, FlyLink.generation)
        assertTrue(FlyLink.connected())
    }

    @Test fun `a short na flicker is not a new session`() {
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        assertEquals(1L, FlyLink.generation)
        now += 1_000; FlyLink.observe(FlyLinkUiState.DISCONNECTED)
        now += 2_000; FlyLink.observe(FlyLinkUiState.CONNECTED)     // 2 s gap < 10 s
        assertEquals("flicker must not re-arm the apply", 1L, FlyLink.generation)
    }

    @Test fun `a real power cycle opens the next session`() {
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        now += 1_000; FlyLink.observe(FlyLinkUiState.DISCONNECTED)
        now += FlyLink.STABLE_DISCONNECT_MS + 1
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        assertEquals(2L, FlyLink.generation)
    }

    /** Repeated DISCONNECTED polls must measure the whole gap, not the last tick. */
    @Test fun `repeated disconnected polls keep the original timestamp`() {
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        now += 500; FlyLink.observe(FlyLinkUiState.DISCONNECTED)
        repeat(12) { now += 1_000; FlyLink.observe(FlyLinkUiState.DISCONNECTED) }
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        assertEquals(2L, FlyLink.generation)
    }

    @Test fun `unknown never erases a known state`() {
        FlyLink.observe(FlyLinkUiState.CONNECTED)
        now += 1_000; FlyLink.observe(FlyLinkUiState.UNKNOWN)
        assertTrue(FlyLink.connected())
        assertEquals(1L, FlyLink.generation)
    }

    @Test fun `a stale observation stops being trusted`() {
        FlyLink.observe(FlyLinkUiState.DISCONNECTED)
        assertTrue(FlyLink.disconnected())
        now += FlyLink.STALE_MS + 1
        assertFalse("a stale screen reading must not keep gating the apply", FlyLink.disconnected())
        assertEquals(FlyLinkUiState.UNKNOWN, FlyLink.state)
    }

    // ---- looking, but at a screen that says nothing --------------------------
    // Real labels from the RC 2: opening Fly's settings panel replaces the FPV
    // screen, so the 1 Hz scan keeps succeeding while verdicts stop arriving.

    private val settingsScreen = listOf(
        "General setting", "Обычная", "Безопасность", "Передача", "2,4 ГГц", "5,8 ГГц",
        "Модель", "DJI Lito X1", "Прошивка дрона", "01.00.0400"
    )

    private val fpvConnected = listOf("Режим N", "0.0m", "0.0m/s", "N/A", "N/A")

    @Test fun `settings screen classifies as unknown, not as a disconnect`() {
        assertEquals(FlyLinkUiState.UNKNOWN, FlyLinkUi.classify(listOf("Обычная", "Безопасность"), settingsScreen))
    }

    @Test fun `a live scan on an uninformative screen keeps the last verdict`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertTrue(FlyLink.connected())
        // A minute in the settings panel, scanning the whole time.
        repeat(60) { now += 1_000; FlyLink.observeScreen(listOf("Обычная"), settingsScreen) }
        assertTrue(
            "sitting in Fly's settings must not hand the keepalive back to the blind timer",
            FlyLink.connected()
        )
    }

    @Test fun `losing sight of the screen does expire the verdict`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertTrue(FlyLink.connected())
        now += FlyLink.SCAN_STALE_MS + 1        // Fly backgrounded / a11y gone: no scans at all
        assertEquals(FlyLinkUiState.UNKNOWN, FlyLink.state)
    }

    @Test fun `a held disconnect gives up once its evidence is old`() {
        FlyLink.observeScreen(listOf("N/A"), listOf("N/A", "Дрон не подключен к пульту"))
        assertTrue(FlyLink.disconnected())
        // Still scanning, but on screens that say nothing. A "no aircraft" verdict
        // HOLDS the FCC burst, so unlike CONNECTED it must not stand forever.
        var elapsed = 0L
        while (elapsed < FlyLink.DISCONNECT_TRUST_MS) {
            now += 1_000; elapsed += 1_000
            FlyLink.observeScreen(listOf("Обычная"), settingsScreen)
        }
        assertTrue("still inside the trust window", FlyLink.disconnected())
        now += 2_000; FlyLink.observeScreen(listOf("Обычная"), settingsScreen)
        assertFalse("an old hold must release so the blind fallback can run", FlyLink.disconnected())
        assertEquals(FlyLinkUiState.UNKNOWN, FlyLink.state)
    }

    @Test fun `returning to the fpv screen re-arms nothing when the drone never left`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(1L, FlyLink.generation)
        repeat(120) { now += 1_000; FlyLink.observeScreen(listOf("Обычная"), settingsScreen) }
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals("a settings visit is not a new flight session", 1L, FlyLink.generation)
    }

    // ---- gaps in SAMPLING (we stopped watching) -----------------------------
    // Nothing samples Fly's screen while our own app is in front, the launcher is
    // up, or the display is off — and an aircraft can be swapped inside that hole.

    @Test fun `a battery swap while we were not watching opens a session`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(1L, FlyLink.generation)
        // User switches to our app for 40 s and swaps the battery. No observations.
        now += 40_000
        // Back in Fly, the drone has relinked: the screen reads exactly as before.
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(
            "an unobserved gap is not evidence that the same aircraft is still there",
            2L, FlyLink.generation
        )
    }

    @Test fun `a short banner after a sampling gap still opens the session`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(1L, FlyLink.generation)
        now += 60_000                                    // not watching: drone power-cycled
        // Back in Fly while the aircraft is still booting: banner for 5 s, then linked.
        FlyLink.observeScreen(listOf("N/A"), listOf("N/A", "Дрон не подключен к пульту"))
        now += 5_000
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(
            "5 s of banner after a blind gap is a link coming up, not a flicker",
            2L, FlyLink.generation
        )
    }

    @Test fun `a flicker with no sampling gap is still filtered`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(1L, FlyLink.generation)
        // Fly blips N/A for a beat while we keep sampling every second.
        now += 1_000; FlyLink.observeScreen(listOf("N/A"), listOf("N/A"))
        now += 1_000; FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals("a 1 s blip must not fire a burst", 1L, FlyLink.generation)
    }

    @Test fun `a tick-sized hiccup does not re-arm`() {
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(1L, FlyLink.generation)
        now += FlyLink.BLIND_GAP_MS - 500                 // one dropped tick, scheduler jitter
        FlyLink.observeScreen(listOf("Режим N"), fpvConnected)
        assertEquals(1L, FlyLink.generation)
    }
}
