package com.dji.fccgpsoff

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.widget.Toast
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Floating control overlay that sits ON TOP of DJI Fly. The window mechanism
 * (a TYPE_APPLICATION_OVERLAY window with FLAG_NOT_FOCUSABLE, a draggable ≡
 * handle that expands a button panel, DragTap tap-vs-drag) is adapted from
 * lmdegreeds/dji_gpsoff's OverlayService — the WINDOW code only. All button
 * actions go through THIS app's [Features] (by-name DUML writes we validated);
 * none of that project's DUML / param-by-index code is used.
 */
class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private var handle: View? = null
    private var handleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val f by lazy { Features(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AppState.load(applicationContext)   // ensure the saved overlay position is loaded (START_STICKY / boot)
        ForegroundServices.enter(this, NOTIF_ID, buildNotif())
        running = true
        if (handle == null) {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            // A sticky restart (or a revoked "draw over other apps" grant) can make
            // addView throw; don't crash — just stand down.
            if (runCatching { addHandle() }.isFailure) {
                DiagLog.err("overlay: cannot add window (permission revoked?) — stopping")
                stopSelf()
                return START_NOT_STICKY
            }
            DiagLog.info("overlay started")
        }
        return START_STICKY
    }

    private fun buildNotif(): Notification = ForegroundServices.notification(
        this, "duml_overlay", "Overlay",
        t("${AppCopy.NAME} — меню", "${AppCopy.NAME} — overlay"),
        t("Переключатели поверх DJI Fly", "Toggles over DJI Fly"),
        android.R.drawable.ic_menu_manage
    )

    private fun olp(w: Int, h: Int, gravity: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                   else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        return WindowManager.LayoutParams(w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { this.gravity = gravity }
    }

    private fun addHandle() {
        val t = TextView(this).apply {
            text = "≡"; setTextColor(0xFFFFFFFF.toInt()); textSize = 26f
            gravity = Gravity.CENTER; background = pill(0xCC3A3A40.toInt())
        }
        val sz = dp(54)
        // Restore the last dragged position (persisted); fall back to bottom-centre on
        // first run. Absolute TOP|LEFT coords so drag + panel math stay simple. Coerce
        // into the current screen in case resolution/orientation changed since we saved.
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - sz).coerceAtLeast(0)
        val maxY = (dm.heightPixels - sz).coerceAtLeast(0)
        val p = olp(sz, sz, Gravity.TOP or Gravity.LEFT).apply {
            if (AppState.overlayX in 0..maxX + 1 && AppState.overlayY >= 0) {
                x = AppState.overlayX.coerceIn(0, maxX); y = AppState.overlayY.coerceIn(0, maxY)
            } else {
                x = (dm.widthPixels - sz) / 2
                y = dm.heightPixels - sz - dp(24)
            }
        }
        handleParams = p
        t.setOnTouchListener(DragTap(p) { togglePanel() })
        handle = t
        wm.addView(t, p)
    }

    private fun togglePanel() {
        panel?.let { runCatching { wm.removeView(it) }; panel = null; return }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(0xB00A0A0B.toInt()); setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        // Apply FCC by hand, without leaving DJI Fly. The auto-apply covers the
        // normal case, but it fires on evidence (a link-up it can see) — when the
        // user knows the aircraft is up and just wants it now, this is the button.
        // Full width and accented: it is the primary action, not a paired toggle.
        // applyFcc() returns false on a partial send, which pillBtn already renders
        // as a warning rather than a tick.
        col.addOne(t("📡 Включить FCC", "📡 Enable FCC"), 0x9922C993.toInt()) { f.applyFcc() }
        // Quick toggles only (LED / GPS) — wired to THIS app's Features.
        col.addTwo(t("💡 LED ВКЛ", "💡 LED ON"), { f.setLed(true) }, t("LED ВЫКЛ", "LED OFF"), { f.setLed(false) })
        col.addTwo(t("📍 GPS ВКЛ", "📍 GPS ON"), { f.setGps(true) }, t("GPS ВЫКЛ", "GPS OFF"), { f.setGps(false) })

        val foot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        foot.addView(footBtn(t("Скрыть", "Hide"), 0xFF2E9BFF.toInt()) { togglePanel() }, equal())
        foot.addView(footBtn(t("Открыть приложение", "Open app"), 0xFF2E9BFF.toInt()) {
            togglePanel()
            runCatching {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
        }, equal())
        foot.addView(footBtn(t("Выход", "Exit"), 0xFFFF453A.toInt()) { togglePanel(); stopSelf() }, equal())
        col.addView(foot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Appear right at the handle; open upward if the handle sits in the lower
        // half of the screen (default is bottom-center), else downward.
        val hp = handleParams
        val dm = resources.displayMetrics
        val panelW = dp(300)
        val panelH = dp(238)                 // FCC row + 2 toggle rows + footer, approx
        val hx = hp?.x ?: 0
        val hy = hp?.y ?: dp(110)
        val px = hx.coerceIn(0, (dm.widthPixels - panelW).coerceAtLeast(0))
        val py = if (hy > dm.heightPixels / 2) (hy - panelH).coerceAtLeast(dp(8)) else hy + dp(58)
        val p = olp(panelW, WindowManager.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT).apply {
            x = px; y = py
        }
        panel = col
        wm.addView(col, p)
    }

    /** One full-width action button on its own row. */
    private fun LinearLayout.addOne(label: String, fill: Int, action: suspend () -> Any?) {
        addView(
            pillBtn(label, fill, action),
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(4); bottomMargin = dp(4) }
        )
    }

    private fun LinearLayout.addTwo(l1: String, a1: suspend () -> Any?, l2: String, a2: suspend () -> Any?) {
        val row = LinearLayout(this@OverlayService).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, dp(4)) }
        row.addView(pillBtn(l1, 0x99808080.toInt(), a1), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(4) })
        row.addView(pillBtn(l2, 0x99484848.toInt(), a2), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4) })
        addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun equal() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    private fun pillBtn(text: String, fill: Int, action: suspend () -> Any?): Button {
        val b = Button(this).apply {
            this.text = text; isAllCaps = false; setSingleLine(true); textSize = 15f
            setTextColor(0xF0FFFFFF.toInt()); background = pill(fill)
            minHeight = 0; minimumHeight = 0; setPadding(dp(4), dp(10), dp(4), dp(10))
        }
        autoSize(b, 11, 15)
        b.setOnClickListener {
            DiagLog.info("overlay tap: $text")
            main.post { Toast.makeText(this@OverlayService, "$text…", Toast.LENGTH_SHORT).show() }
            scope.launch {
                val r = runCatching { action() }.onFailure { DiagLog.err("overlay $text: ${it.message}") }
                // Honest result: a write that returns `false` (frame never left the
                // socket) is a failure, not just a thrown exception.
                val ok = r.isSuccess && r.getOrNull() != false
                main.post { Toast.makeText(this@OverlayService, (if (ok) "✅ " else "⚠ ") + text, Toast.LENGTH_SHORT).show() }
            }
        }
        return b
    }

    private fun footBtn(text: String, color: Int, l: View.OnClickListener): Button =
        Button(this).apply {
            this.text = text; isAllCaps = false; setSingleLine(true); textSize = 12f
            minWidth = 0; minimumWidth = 0; setPadding(dp(2), dp(6), dp(2), dp(6))
            setTextColor(color); setBackgroundColor(0)
            setOnClickListener(l); autoSize(this, 9, 12)
        }

    private fun autoSize(b: Button, minSp: Int, maxSp: Int) {
        if (Build.VERSION.SDK_INT < 26) return
        runCatching { b.setAutoSizeTextTypeUniformWithConfiguration(minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP) }
    }

    private fun pill(fill: Int): Drawable {
        val content = GradientDrawable().apply { setColor(fill); cornerRadius = dp(8).toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(8).toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), content, mask)
    }
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    override fun onDestroy() {
        super.onDestroy()
        running = false
        scope.cancel()                                  // drop in-flight LED/GPS writes
        main.removeCallbacksAndMessages(null)           // drop pending Toast runnables
        runCatching { panel?.let { wm.removeView(it) } }; panel = null
        runCatching { handle?.let { wm.removeView(it) } }; handle = null
        DiagLog.info("overlay stopped")
    }

    /** Draggable handle: move on drag, fire onTap on a clean tap. */
    private inner class DragTap(val p: WindowManager.LayoutParams, val onTap: () -> Unit) : View.OnTouchListener {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; moved = false; return true }
                MotionEvent.ACTION_MOVE -> {
                    if (Math.abs(e.rawX - downX) > dp(6) || Math.abs(e.rawY - downY) > dp(6)) moved = true
                    p.x = (startX + e.rawX - downX).toInt(); p.y = (startY + e.rawY - downY).toInt()
                    runCatching { wm.updateViewLayout(v, p) }; return true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap()
                    else AppState.setOverlayPos(applicationContext, p.x, p.y)   // persist the dragged position
                    return true
                }
            }
            return false
        }
    }

    companion object {
        @Volatile var running = false
        private const val NOTIF_ID = 1003
        fun start(ctx: android.content.Context) = ForegroundServices.launch(ctx, OverlayService::class.java)
        fun stop(ctx: android.content.Context) = ForegroundServices.stop(ctx, OverlayService::class.java)
    }
}
