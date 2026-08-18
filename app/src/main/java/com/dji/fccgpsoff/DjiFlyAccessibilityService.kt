package com.dji.fccgpsoff

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads two things off the running DJI app, the SkylabFCCfree way:
 *
 *   1. The FOREGROUND package (which app owns the active window) — fed to
 *      [ForegroundGate] so the read-family features and the diag web page can
 *      block reads while DJI Fly is actually on screen.
 *
 *   2. The AIRCRAFT MODEL NAME painted on DJI Fly's / Pilot's UI — matched by
 *      [AircraftModelCatalog.findOnScreen] and published to [AircraftIdentity].
 *      This is the preferred identity source; [AircraftModelProbe] is the DUML
 *      fallback.
 *
 * No packageNames filter in the config, so we still learn the foreground even
 * when the user switches to a third app (otherwise the gate would think Fly is
 * still up). Model scanning only runs while a DJI app is foreground.
 */
class DjiFlyAccessibilityService : AccessibilityService() {

    @Volatile private var lastScanMs = 0L
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    // PNG encode runs here, off the main thread. Single-thread + a guard so two
    // screenshot requests can't encode full-screen bitmaps concurrently.
    private val encoder = Executors.newSingleThreadExecutor()
    private val encoding = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ForegroundGate.ownPackage = packageName
        ForegroundGate.accessibilityConnected = true
        DiagLog.info("accessibility service connected — foreground gating active")
        // Read DJI Fly's own localized flight-mode / disconnect strings once, off the
        // main thread: it opens a Resources per locale and we are on the UI callback.
        encoder.execute { runCatching { FlyUiPhrases.ensureLoaded(applicationContext) } }
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    /**
     * Capture the screen (Android 11+) and store the PNG in [ScreenshotStore].
     * The callback (and the full-screen PNG compress in it) runs on a background
     * executor, NOT the main thread, so it never janks the UI. A guard drops a
     * request while one is still encoding.
     */
    fun requestScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) { ScreenshotStore.fail("needs Android 11+"); return }
        if (!encoding.compareAndSet(false, true)) { ScreenshotStore.fail("busy — a capture is already encoding"); return }
        captureApi30()
    }

    /**
     * The actual API-30 capture, split out and annotated: the anonymous
     * [TakeScreenshotCallback] is itself an API-30 *class*, and lint does not treat
     * the caller's early return as a guard for a class declaration — only for
     * calls. @TargetApi puts the gate where lint can see it; [requestScreenshot]
     * is the only caller and checks the version first.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.R)
    private fun captureApi30() {
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, encoder, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    try {
                        val hb = res.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, res.colorSpace)
                        hb.close()
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hw?.recycle()
                        if (sw == null) { ScreenshotStore.fail("bitmap decode failed"); return }
                        val baos = ByteArrayOutputStream(256 * 1024)
                        sw.compress(Bitmap.CompressFormat.PNG, 100, baos)
                        sw.recycle()
                        ScreenshotStore.put(baos.toByteArray())
                    } catch (e: Exception) { ScreenshotStore.fail(e.message ?: "encode error") }
                    finally { encoding.set(false) }
                }
                override fun onFailure(errorCode: Int) { ScreenshotStore.fail("screenshot error $errorCode"); encoding.set(false) }
            })
        } catch (e: Exception) { ScreenshotStore.fail(e.message ?: "takeScreenshot threw"); encoding.set(false) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString().orEmpty()

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty()) {
            // Our OWN overlay (TYPE_APPLICATION_OVERLAY from OverlayService) also fires a
            // WINDOW_STATE_CHANGED with our package — but it is NOT the app coming to the
            // foreground: the user still sees whatever is behind it (DJI Fly). Measured on
            // hardware right after a controller reboot: the overlay's event flipped the
            // foreground to us for ~24 s, which dropped the read gate and let the drone-link
            // probe open a socket on Fly's 40007 video port exactly while Fly was connecting
            // to the aircraft. Only a REAL Activity of ours is a true foreground; the overlay
            // window carries a view class name, not "…Activity", so filter it out.
            val cls = event.className?.toString().orEmpty()
            val ownOverlayWindow = pkg == ForegroundGate.ownPackage && !cls.endsWith("Activity")
            if (ownOverlayWindow) {
                if (pkg != ForegroundGate.foregroundPackage)
                    DiagLog.info("foreground: ignoring own overlay window ($cls) — keeping '${ForegroundGate.foregroundPackage}'")
            } else {
                ForegroundGate.onWindow(pkg)
            }
        }
        if (pkg.isEmpty() || pkg !in ForegroundGate.DJI_PACKAGES) return

        val windowChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val fromEvent = ArrayList<String>(4)
        event.text.forEach { it?.toString()?.takeIf { s -> s.isNotBlank() }?.let(fromEvent::add) }
        event.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(fromEvent::add)
        scan(force = windowChanged, extra = fromEvent)
    }

    /**
     * Read the DJI app's screen: link state for [FlyLink], model name for
     * [AircraftIdentity].
     *
     * Throttled to [LINK_SCAN_MS]; [force] bypasses it for a window change.
     *
     * NOTE this used to stop scanning for good once a UI-sourced model was known.
     * It can't any more — the walk now also carries the aircraft LINK state, which
     * changes for the whole session, not once. The model match is what gets skipped
     * when we already have it, not the scan.
     */
    private fun scan(force: Boolean, extra: List<String> = emptyList()) {
        val now = System.currentTimeMillis()
        if (!force && now - lastScanMs < LINK_SCAN_MS) return

        // Ask the window system what is actually on screen instead of trusting the
        // package from the event (or from ForegroundGate, which only learns on a
        // window-state CHANGE). Two reasons: provenance — the labels we are about to
        // read must really be DJI Fly's, or we would classify someone else's screen
        // as the aircraft link; and level-vs-edge — see [ticker].
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return   // screen off / no window
        if (root.packageName?.toString() !in ForegroundGate.DJI_PACKAGES) return
        lastScanMs = now

        val texts = ArrayList<String>(64)
        texts.addAll(extra)
        val modeSlot = ArrayList<String>(4)
        collectVisibleLabels(root, texts, modeSlot, MAX_NODES)

        // What DJI Fly's own screen says about the aircraft link. Feeding UNKNOWN is
        // a no-op inside FlyLink, so a settings/album screen never erases the state.
        runCatching { FlyLink.observeScreen(modeSlot, texts) }

        val haveUi = AircraftIdentity.drone.source == AircraftIdentity.Source.UI && AircraftIdentity.drone.code.isNotEmpty()
        if (haveUi) return
        val match = runCatching { AircraftModelCatalog.findOnScreen(texts) }.getOrNull() ?: return
        AircraftIdentity.publish(match.code, match.name, AircraftIdentity.Source.UI)
    }

    /**
     * Re-read the screen on a timer while a DJI app is foreground, instead of
     * waiting for it to tell us it changed.
     *
     * Measured on the RC 2 the moment this went in: with the aircraft OFF, Fly's FPV
     * screen is **static** — the OSD numbers never move, so `typeWindowContentChanged`
     * stops arriving and the last reading was 47 s old while Fly sat in front. That
     * let a perfectly good "no aircraft" reading age out into UNKNOWN, which is
     * exactly the state that re-enables the blind apply we are trying to suppress.
     * The state we need is a *level*, not an edge, so it has to be sampled.
     */
    private val ticker = object : Runnable {
        override fun run() {
            // No foreground gate here on purpose. [ForegroundGate.foregroundPackage] is
            // EDGE-derived — it only changes on a window-state event — so it is empty
            // when the service binds while DJI Fly is already in front (observed live
            // right after an install: foreground="" and no scans at all), and it stays
            // on `systemui` after the notification shade closes over a static Fly. In
            // both cases the ticker would sit dormant on exactly the screen it exists
            // to sample. [scan] asks the window system directly instead, which is a
            // level, and skips anything that is not a DJI app.
            runCatching { scan(force = false) }
            handler.postDelayed(this, LINK_SCAN_MS)
        }
    }

    /**
     * Breadth-first collect text + contentDescription from the node tree.
     *
     * Labels painted in the window's top-left corner are ALSO collected into
     * [modeSlot]: that is where DJI Fly puts the flight mode, and the same slot
     * shows `N/A` when no aircraft is linked. The corner test is what keeps the
     * camera row's `N/A` (ISO/WB/F/S/MM, bottom-right of the identical screen)
     * from being read as "no drone" — see [FlyLinkUi]. Bounds are compared
     * against the ROOT node's own rect, not the display, so a scaled or
     * split-screen window still measures its own corner.
     */
    private fun collectVisibleLabels(
        root: AccessibilityNodeInfo?,
        out: MutableList<String>,
        modeSlot: MutableList<String>,
        max: Int
    ) {
        root ?: return
        val window = Rect().also { root.getBoundsInScreen(it) }
        val cornerRight = window.left + (window.width() * MODE_SLOT_W).toInt()
        val cornerBottom = window.top + (window.height() * MODE_SLOT_H).toInt()
        val bounds = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < max) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()?.takeIf { it.isNotBlank() }
            val desc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null || desc != null) {
                text?.let(out::add)
                desc?.let(out::add)
                node.getBoundsInScreen(bounds)
                // Ignore the root/full-screen container: its centre is nowhere near
                // the corner anyway, but a zero-size node would land there by default.
                if (!bounds.isEmpty && bounds.centerX() <= cornerRight && bounds.centerY() <= cornerBottom) {
                    text?.let(modeSlot::add)
                    desc?.let(modeSlot::add)
                }
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::add)
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        handler.removeCallbacks(ticker)
        ForegroundGate.accessibilityConnected = false
        DiagLog.info("accessibility service unbound — foreground gating inactive")
        return super.onUnbind(intent)
    }

    companion object {
        private const val MAX_NODES = 300
        /** Tree-walk cadence while a DJI app is foreground. One second is what the
         *  link state is worth: it gates a radio write, and Fly's own N/A flicker
         *  is filtered by [FlyLink]'s stability window, not by scanning slowly. */
        private const val LINK_SCAN_MS = 1_000L
        /** Fraction of the window that counts as the top-left "mode slot". On the
         *  RC 2's 1920x1080 FPV screen the label sits at ~9% x / ~5% y, well inside. */
        private const val MODE_SLOT_W = 0.30f
        private const val MODE_SLOT_H = 0.18f
        /** The running service instance, for on-demand screenshots. */
        @Volatile var instance: DjiFlyAccessibilityService? = null
    }
}
