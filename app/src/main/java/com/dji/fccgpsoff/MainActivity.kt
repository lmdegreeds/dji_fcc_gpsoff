package com.dji.fccgpsoff

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Compact single-screen shell for the RC2 landscape display. No system title bar
 * and no page-nav strip — a single thin top row carries the device summary and a
 * ⋮ menu that hides Log / Services / About. The main page shows the
 * FCC switch and the live LED / GPS / flight-mode state (read back by hash while
 * our window is in front).
 */
class MainActivity : Activity() {

    private val BG = 0xFF12141C.toInt(); private val CARD = 0xFF1B2030.toInt()
    private val INK = 0xFFECEEF3.toInt(); private val MUTED = 0xFF8A93A6.toInt()
    private val VIOLET = 0xFF6C5CE7.toInt(); private val BLUE = 0xFF4C6FFF.toInt()
    private val GREEN = 0xFF22C993.toInt(); private val CORAL = 0xFFFF6B6B.toInt()
    private val SLATE = 0xFF2A3042.toInt(); private val AMBER = 0xFFF5A623.toInt()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val f by lazy { Features(applicationContext) }

    private lateinit var status: TextView
    private lateinit var summary: TextView
    private lateinit var content: FrameLayout
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private val logRenderPending = java.util.concurrent.atomic.AtomicBoolean(false)

    // main-page live widgets
    private lateinit var ledSw: Switch
    private lateinit var gpsSw: Switch
    private lateinit var modeSw: Switch
    private lateinit var ledVal: TextView
    private lateinit var gpsVal: TextView
    private lateinit var modeVal: TextView
    private lateinit var stateNote: TextView
    private lateinit var deviceView: TextView
    private lateinit var linkDot: TextView

    private var pages = HashMap<String, View>()
    private var current = "main"
    private var foreground = false
    private var loopJob: Job? = null
    @Volatile private var flyStat = "?"
    /** Set on foreground entry / aircraft change to force one immediate live-state
     *  read; between those the loop throttles 40007 reads (see [READ_INTERVAL_MS]). */
    /** Set only by an explicit request — the read button, or a write that changed
     *  something. NOT by onResume: see the note there. */
    @Volatile private var refreshNow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        AppState.load(applicationContext)

        // First launch after install: hand over to the wizard before building
        // anything or starting a service — it is what decides which services run.
        if (!AppState.wizardDone) {
            startActivity(Intent(this, SetupWizardActivity::class.java))
            finish(); return
        }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        root.addView(topBar())
        content = FrameLayout(this)
        // Each page is built behind a guard. A single broken builder used to take
        // the whole Activity down with it — on a controller with no usable adb that
        // is an app that simply "crashes", with nothing to go on. Now the broken
        // page shows its own exception and every other page still works.
        pages["main"] = page("main") { buildMain() }
        pages["services"] = page("services") { buildServices() }
        pages["params"] = page("params") { buildParams() }
        pages["about"] = page("about") { buildAbout() }
        pages["log"] = page("log") { buildLog() }
        for ((k, v) in pages) content.addView(v.apply { visibility = if (k == "main") View.VISIBLE else View.GONE },
            FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        status = TextView(this).apply { setTextColor(MUTED); textSize = 11f; setPadding(dp(14), dp(2), dp(14), dp(6)) }
        root.addView(status)
        setContentView(root)

        // Coalesce log re-renders: renderLog rebuilds the whole buffer, and the log can
        // burst hundreds of lines/min (keepalive country + FCC replays). Rendering per
        // line saturated the UI thread and froze the Log page — so schedule at most one
        // render per LOG_RENDER_THROTTLE_MS instead of one per line.
        DiagLog.listener = { _ ->
            if (current == "log" && logRenderPending.compareAndSet(false, true))
                logView.postDelayed({ logRenderPending.set(false); renderLog() }, LOG_RENDER_THROTTLE_MS)
        }
        maybeRequestNotifications()
        // The wizard applies the user's exact "start now" choices itself, including
        // "autostart on, but not this session" — re-applying here would override that.
        if (!intent.getBooleanExtra(EXTRA_SKIP_AUTOSTART, false)) applyAutoStart()
        // One-shot: a config-change recreation must not inherit the flag and skip
        // auto-start on a launch the wizard had nothing to do with.
        intent?.removeExtra(EXTRA_SKIP_AUTOSTART)
        handleIntent(intent)
        maybePromptAccessibility()
        maybeCheckUpdates()
        showLastCrash()
        setStatus(t("Готово · FCC постоянный · применяется сразу · состояние читается, пока это окно впереди",
                    "Ready · FCC is persistent · applies live · state reads run while this window is in front"))
        startLoops()
    }

    /**
     * Build one page, or a visible error page instead of taking the process down.
     * The message and the top frames are shown on screen because that is the only
     * crash report available on a controller without adb.
     */
    private fun page(name: String, build: () -> View): View = try {
        build()
    } catch (e: Throwable) {
        DiagLog.err("page '$name' failed to build: $e")
        val text = "⚠ " + t("страница «$name» не построилась", "page '$name' failed to build") + "\n\n" +
            e.toString() + "\n\n" + e.stackTrace.take(12).joinToString("\n")
        ScrollView(this).apply {
            addView(TextView(this@MainActivity).apply {
                this.text = text
                setTextColor(CORAL); textSize = 10.5f; typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setTextIsSelectable(true)
            })
        }
    }

    /** If the previous run died, say so and offer the trace — it is written to
     *  Download/ and to the app's files dir, but nobody looks there unprompted. */
    private fun showLastCrash() {
        val (file, text) = CrashLog.latest(applicationContext) ?: return
        setStatus(t("⚠ прошлый запуск завершился ошибкой — нажмите, чтобы посмотреть",
                    "⚠ the previous run crashed — tap to view"))
        status.setOnClickListener {
            android.app.AlertDialog.Builder(this)
                .setTitle(t("Ошибка прошлого запуска", "Previous run crashed"))
                .setView(ScrollView(this).apply {
                    addView(TextView(this@MainActivity).apply {
                        this.text = text
                        setTextColor(INK); textSize = 10.5f; typeface = Typeface.MONOSPACE
                        setPadding(dp(12), dp(8), dp(12), dp(8)); setTextIsSelectable(true)
                    })
                })
                .setNeutralButton(t("Отправить", "Share")) { _, _ ->
                    runCatching {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                        }, t("Отправить отчёт", "Share the report")))
                    }
                }
                .setNegativeButton(t("Удалить", "Delete")) { _, _ ->
                    CrashLog.clear(applicationContext)
                    status.setOnClickListener(null)
                    setStatus(t("отчёт удалён (${file.name})", "report deleted (${file.name})"))
                }
                .setPositiveButton(t("Закрыть", "Close"), null)
                .show()
        }
    }

    // ---------- top bar ----------
    private fun topBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD); setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        summary = TextView(this).apply {
            setTextColor(INK); textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            text = "⚡ DJI_FCC_GPSOFF"
        }
        bar.addView(summary, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        linkDot = TextView(this).apply {
            textSize = 11.5f; typeface = Typeface.DEFAULT_BOLD; setPadding(dp(8), dp(4), dp(8), dp(4)); text = t("дрон ○", "drone ○"); setTextColor(MUTED)
        }
        bar.addView(linkDot)
        // Wider than the glyph needs: this is the only way to reach every other page, and
        // it lives in the corner of a screen people poke at while holding a controller.
        val menu = TextView(this).apply {
            text = "⋮"; setTextColor(INK); textSize = 22f
            gravity = Gravity.CENTER
            minWidth = dp(56); minimumWidth = dp(56)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = RippleDrawable(ColorStateList.valueOf(SLATE), null, pillBg(Color.WHITE, dp(12)))
            setOnClickListener { showMenu(it) }
        }
        bar.addView(menu)
        return bar
    }

    /**
     * The ⋮ menu, drawn in the app's own palette.
     *
     * The stock PopupMenu renders with the platform theme — a white sheet with black text on this
     * device — which looks like a different application dropped on top of a dark one. The
     * whole UI here is built in code from one palette, so the menu is too.
     */
    private fun showMenu(anchor: View) {
        // Keyed by item id, never by the visible title: a translated label must
        // not be able to miss its branch.
        val items = listOf(
            t("Главная", "Main") to "main",
            t("Параметры", "Params") to "params",
            t("Лог", "Log") to "log",
            t("Сервисы", "Services") to "services",
            t("Мастер настройки", "Setup wizard") to "wizard",
            t("Язык: English", "Language: Русский") to "lang",
            t("О программе", "About") to "about")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(CARD, dp(14))
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        val pop = android.widget.PopupWindow(col, dp(230), WRAP_CONTENT, true).apply {
            elevation = dp(12).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        for ((label, key) in items) {
            col.addView(TextView(this).apply {
                text = label; setTextColor(if (key == current) GREEN else INK)
                textSize = 15f; setPadding(dp(14), dp(12), dp(14), dp(12))
                background = RippleDrawable(ColorStateList.valueOf(SLATE), null, pillBg(Color.WHITE, dp(10)))
                setOnClickListener {
                    pop.dismiss()
                    when (key) {
                        "wizard" -> startActivity(Intent(this@MainActivity, SetupWizardActivity::class.java))
                        "lang" -> toggleLanguage()
                        else -> showPage(key)
                    }
                }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        pop.showAsDropDown(anchor, -dp(190), 0)
    }

    /** Flip the UI language and rebuild: pages are built in code from t(...)
     *  at construction time, so recreate() is what re-renders every screen. */
    private fun toggleLanguage() {
        AppState.setUiRu(this, !AppState.uiRu)
        recreate()
    }

    private fun showPage(key: String) {
        for ((k, v) in pages) v.visibility = if (k == key) View.VISIBLE else View.GONE
        current = key
        if (key == "log") renderLog()
    }

    // ---- swipe left / right to page, without blocking scroll or taps ----
    private val pageOrder = listOf("main", "params", "log", "services", "about")
    private val gesture by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x; val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) * 1.5f && Math.abs(dx) > dp(64) && Math.abs(vx) > 200f) {
                    step(if (dx < 0) 1 else -1); return true      // swipe left → next page
                }
                return false
            }
        })
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        runCatching { gesture.onTouchEvent(ev) }
        // Tapping away from a text field should put the keyboard away. Android does not do
        // this on its own, and on the RC's short screen the keyboard covers most of the
        // parameter list — so after typing a search there was no way back to the results
        // without the Back key.
        if (ev.action == MotionEvent.ACTION_DOWN) runCatching { dismissKeyboardIfTapOutside(ev) }
        return super.dispatchTouchEvent(ev)
    }

    private fun dismissKeyboardIfTapOutside(ev: MotionEvent) {
        val focused = currentFocus as? EditText ?: return
        val r = android.graphics.Rect()
        focused.getGlobalVisibleRect(r)
        if (r.contains(ev.rawX.toInt(), ev.rawY.toInt())) return   // tap landed in the field itself
        focused.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
            ?.hideSoftInputFromWindow(focused.windowToken, 0)
    }
    private fun step(dir: Int) {
        val i = pageOrder.indexOf(current).let { if (it < 0) 0 else it }
        val j = ((i + dir) % pageOrder.size + pageOrder.size) % pageOrder.size
        showPage(pageOrder[j])
    }

    // ---------- main page ----------
    private fun buildMain(): View {
        // FCC is a persistent, fire-once action with no read-back, so it is a
        // momentary BUTTON (not a stateful switch that would imply an on/off we
        // can't actually read). Restore CE was removed on purpose — reverting to CE
        // drops 5.8 and needs a reboot; the app is FCC-only now.
        val fccBtn = smallBtn(t("⚡ Включить FCC", "⚡ Enable FCC"), GREEN) {}
        val fccBtns = listOf(fccBtn)
        fun runFcc(status: String, action: suspend () -> Unit) {
            fccBtns.forEach { it.isEnabled = false }     // block a second press mid-sequence
            setStatus(status)
            scope.launch {
                runCatching { action() }                 // action sets its own final status
                runOnUiThread { fccBtns.forEach { it.isEnabled = true } }
            }
        }
        fccBtn.setOnClickListener { runFcc(t("⏳ применяю FCC…", "⏳ applying FCC…")) { applyFccAndConfirm() } }
        fccApplyBtn = fccBtn
        syncFccButtonLabel()
        val fccBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fccBody.addView(rowc(fccBtn))
        fccBody.addView(TextView(this).apply {
            text = t("Постоянно · применяется сразу (мощность + 5.8), без перезагрузки",
                     "Persistent · applies live (power + 5.8), no reboot")
            setTextColor(MUTED); textSize = 10.5f; setPadding(0, dp(6), 0, 0)
        })
        val fccCard = card(t("⚡ Радио / FCC", "⚡ Radio / FCC"), fccBody)

        // Device info
        deviceView = TextView(this).apply { setTextColor(INK); textSize = 12.5f; typeface = Typeface.MONOSPACE; text = "—" }
        val devBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devBody.addView(deviceView)
        devBody.addView(rowc(smallBtn(t("Копировать S/N", "Copy serial"), SLATE) {
            val s = SerialSniffer.serial.ifEmpty { StartupProbe.serial }
            if (s.isEmpty()) setStatus(t("серийника пока нет", "no serial yet"))
            else { getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("serial", s)); setStatus(t("скопировано: $s", "copied: $s")) }
        }, smallBtn(t("Опросить", "Re-probe"), BLUE) {
            setStatus(t("⏳ опрашиваю…", "⏳ probing…"))
            scope.launch {
                StartupProbe.run(applicationContext)
                runOnUiThread {
                    renderDevice()
                    setStatus(when {
                        // Reads open a socket on DJI Fly's port and are blocked while Fly is
                        // the active window, so a probe there can't read serial/variant —
                        // say so instead of leaving the status stuck on "probing…".
                        !ForegroundGate.readsAllowed() -> t(
                            "⚠ опрос пропущен — DJI Fly на переднем плане; переключитесь в это приложение (Fly продолжит работать) и повторите",
                            "⚠ probe skipped — DJI Fly is active; switch to this app (Fly keeps running), then re-probe")
                        StartupProbe.readsFailed -> t("⚠ опрос: дрон на связи, но ни одно чтение параметра не ответило",
                            "⚠ probe: drone present but no parameter read answered")
                        StartupProbe.serial.isNotEmpty() -> t(
                            "✅ опрос: серийник ${StartupProbe.serial} · профиль ${if (AppState.litoMode) "Lito" else "другой"}",
                            "✅ probe: serial ${StartupProbe.serial} · profile ${if (AppState.litoMode) "Lito" else "other"}")
                        else -> t("опрос завершён — серийника нет (подключите дрон / откройте DJI Fly на пульте)",
                            "probe done — no serial (link the drone / open DJI Fly on the RC)")
                    })
                }
            }
        }))
        // Getting back to Fly after the app has been in front — reads and the table dump
        // want Fly out of the way, and this is the way back without hunting for the launcher.
        devBody.addView(rowc(smallBtn(t("▶ Открыть DJI Fly", "▶ Open DJI Fly"), GREEN) { openFly() }), rowLp())
        val deviceCard = card(devBody)          // heading dropped: the contents name themselves

        // Live state: LED / GPS / mode
        ledVal = valLabel(); gpsVal = valLabel(); modeVal = valLabel()
        ledSw = stateSwitch({ on -> "LED " + if (on) t("вкл", "on") else t("выкл", "off") }) { on -> f.setLed(on) }
        gpsSw = stateSwitch({ on -> "GPS " + if (on) t("вкл", "on") else t("выкл", "off") }) { on -> f.setGps(on) }
        // Switch ON = ATTI (per user): on ⇒ ATTI, off ⇒ Cine. setFlightMode(cine) takes
        // the Cine flag, so an ON toggle writes ATTI via !on.
        modeSw = stateSwitch({ on -> t("режим ", "mode ") + if (on) "ATTI" else "Cine" }) { on -> f.setFlightMode(!on) }
        val stateBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stateBody.addView(stateRow("💡 LED", ledVal, ledSw))
        stateBody.addView(stateRow("📍 GPS", gpsVal, gpsSw), tightLp())
        stateBody.addView(stateRow("🎬 ATTI / Cine", modeVal, modeSw), tightLp())
        stateNote = TextView(this).apply { setTextColor(MUTED); textSize = 10.5f; setPadding(0, dp(6), 0, 0) }
        stateBody.addView(stateNote)
        // State is read on demand only (opening the app, an aircraft change, a write,
        // or this button) — never on a timer — so 40007 is not churned. Cf. flyStatus.
        stateBody.addView(rowc(smallBtn(t("↻ Прочитать состояние", "↻ Read state"), SLATE) {
            refreshNow = true
            setStatus(if (ForegroundGate.readsAllowed()) t("↻ перечитываю состояние…", "↻ re-reading state…")
                      else t("⚠ DJI Fly на переднем плане — переключитесь в это приложение, чтобы прочитать состояние",
                             "⚠ DJI Fly is active — switch to this app to read state"))
        }), tightLp())
        // Title-less, compact (no card header) to save vertical space.
        val stateCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(CARD, dp(16)); setPadding(dp(12), dp(7), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(9) }
            addView(stateBody)
        }

        // Auto-start switches, duplicated from the Services page onto the main screen
        // (free space under Read state). These set only the auto-start-on-launch flags;
        // the services themselves are toggled on the Services page.
        mainKaAuto = mkSwitch(AppState.autoKeepalive).apply { setOnClickListener { AppState.setAutoKeepalive(this@MainActivity, isChecked) } }
        mainDiagAuto = mkSwitch(AppState.autoDiag).apply { setOnClickListener { AppState.setAutoDiag(this@MainActivity, isChecked) } }
        mainOvAuto = mkSwitch(AppState.autoOverlay).apply { setOnClickListener { AppState.setAutoOverlay(this@MainActivity, isChecked) } }
        // Title-less, compact auto-start block (no card header, tight padding) so the
        // right column fits without a vertical scroll. Rows are spaced by the switches.
        val autoCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = pillBg(CARD, dp(16)); setPadding(dp(12), dp(2), dp(12), dp(2))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(9) }
            addView(cell(t("⚡ Авто-FCC", "⚡ Auto FCC"), mainKaAuto))
            addView(cell(t("🌐 Веб-дашборд", "🌐 Web dashboard"), mainDiagAuto))
            addView(cell(t("🎈 Плавающее меню", "🎈 Foreground menu"), mainOvAuto))
            mainDiagUrl = TextView(this@MainActivity).apply {
                setTextColor(GREEN); textSize = 11.5f; typeface = Typeface.MONOSPACE
                setPadding(0, dp(5), 0, 0); visibility = View.GONE
            }
            addView(mainDiagUrl)
        }

        fun column(vararg cards: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; for (c in cards) addView(c) }
        val cols = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(4)) }
        cols.addView(column(fccCard, deviceCard), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = dp(6) })
        cols.addView(column(stateCard, autoCard), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        return ScrollView(this).apply { addView(cols); isFillViewport = true }
    }

    private fun stateRow(label: String, value: TextView, sw: Switch): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(TextView(this).apply { text = label; setTextColor(INK); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.3f))
        r.addView(value, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(sw)
        return r
    }
    private fun valLabel() = TextView(this).apply { setTextColor(MUTED); textSize = 12f; typeface = Typeface.MONOSPACE; text = "?" }
    private fun rowLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(7) }
    private fun tightLp() = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(3) }

    /** A switch that performs a blind write; live state is confirmed by the read
     *  loop. [action] returns whether the frame actually left the socket, so the
     *  status reflects the real send result, not merely "no exception thrown". */
    private fun stateSwitch(label: (Boolean) -> String, action: suspend (Boolean) -> Boolean) = mkSwitch(false).apply {
        setOnClickListener {
            val on = isChecked
            scope.launch {
                val ok = runCatching { action(on) }.getOrDefault(false)
                runOnUiThread { setStatus((if (ok) "✅ " else "⚠ ") + label(on) +
                    if (ok) t(" (пишу…)", " (writing…)") else t(" — отправка не удалась", " — send failed")) }
                if (ok) { delay(400); FlightState.refresh(); runOnUiThread { renderState() } }
            }
        }
    }

    // ---------- services page ----------
    private lateinit var kaSw: Switch; private lateinit var diagSw: Switch; private lateinit var ovSw: Switch
    /** The big green Apply-FCC button, relabelled by [syncFccButtonLabel] when auto is on. */
    private lateinit var fccApplyBtn: Button
    private lateinit var litoSw: Switch
    // Main-screen copies of the service auto-start switches (also on the Services page).
    private lateinit var mainKaAuto: Switch; private lateinit var mainDiagAuto: Switch; private lateinit var mainOvAuto: Switch
    private lateinit var mainDiagUrl: TextView   // web-dashboard URL shown here while diag is running
    private lateinit var diagUrl: TextView
    private fun buildServices(): View {
        kaSw = serviceSwitch { on ->
            if (on) FccKeepaliveService.start(this) else FccKeepaliveService.stop(this)
            // The service flag lags start()/stop() by a moment, so label from the
            // intent, not from the flag — otherwise the button keeps the old text
            // until something else happens to refresh it.
            setFccButtonLabel(on)
            setStatus(t("keepalive ", "keepalive ") + if (on) t("вкл", "on") else t("выкл", "off")) }
        diagUrl = TextView(this).apply { setTextColor(GREEN); textSize = 12.5f; typeface = Typeface.MONOSPACE; setPadding(0, dp(7), 0, 0) }
        diagSw = serviceSwitch { on -> if (on) { DiagService.start(this); updateDiagUrl(true); setStatus(t("дашборд включён", "diag on")) } else { DiagService.stop(this); updateDiagUrl(false); setStatus(t("дашборд выключен", "diag off")) } }
        ovSw = serviceSwitch { on -> if (on) { if (!ensureOverlay()) { ovSw.isChecked = false; return@serviceSwitch }; OverlayService.start(this); setStatus(t("меню включено", "overlay on")) } else { OverlayService.stop(this); setStatus(t("меню выключено", "overlay off")) } }
        val kaAuto = mkSwitch(AppState.autoKeepalive).apply { setOnClickListener { AppState.setAutoKeepalive(this@MainActivity, isChecked) } }
        val diagAuto = mkSwitch(AppState.autoDiag).apply { setOnClickListener { AppState.setAutoDiag(this@MainActivity, isChecked) } }
        val ovAuto = mkSwitch(AppState.autoOverlay).apply { setOnClickListener { AppState.setAutoOverlay(this@MainActivity, isChecked) } }

        val svc = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        svc.addView(serviceItem(t("Авто-применение FCC при подключении", "Auto-apply FCC on connect"),
            t("Отправляет полный пакет FCC в момент, когда дрон реально появился на линке (пошла телеметрия), а не просто когда открыт порт — и переприменяет при каждом релинке. По событиям, без трафика вхолостую. Включите автозапуск ниже, чтобы сервис поднимался при старте приложения и после перезагрузки пульта.",
              "Sends the full FCC package the moment the aircraft comes on the link (telemetry starts) — not on a bare open port — then re-applies on each relink / new home point. Event-driven, no idle traffic. Turn on its auto-start below to arm it on launch and after a controller reboot."), kaSw))
        svc.addView(serviceItem(t("Веб-диагностика (:8899)", "Web diagnostics (:8899)"),
            t("Отдаёт дашборд и живой лог на http://<ip-пульта>:8899 для ПК в той же сети. Через него же скриншоты и захват трафика.",
              "Serves a dashboard + live log at http://<RC-ip>:8899 for a PC on the same Wi-Fi. Also does the screenshot / capture."), diagSw), rowLp())
        svc.addView(serviceItem(t("Плавающее меню поверх DJI Fly", "Floating controls over DJI Fly"),
            t("Маленькая панель поверх всех окон с LED / GPS / FCC прямо во время полёта в DJI Fly. Нужно разрешение «поверх других приложений».",
              "A small always-on-top panel with LED / GPS / FCC while you fly in DJI Fly. Needs the “display over other apps” permission."), ovSw), rowLp())
        svc.addView(diagUrl)
        svc.addView(TextView(this).apply { text = t("Запускать автоматически при старте приложения ↓", "Start automatically when the app launches ↓"); setTextColor(MUTED); textSize = 11f; setPadding(0, dp(12), 0, dp(2)) })
        svc.addView(cell(t("Авто-применение FCC", "Auto-apply FCC on connect"), kaAuto), rowLp())
        svc.addView(cell(t("Веб-диагностика", "Web diagnostics"), diagAuto), rowLp())
        svc.addView(cell(t("Плавающее меню", "Floating controls"), ovAuto), rowLp())
        val servicesCard = card(t("🛠 Фоновые сервисы", "🛠 Background services"), svc)

        litoSw = mkSwitch(AppState.litoMode).apply {
            setOnClickListener {
                // Flipping this by hand is an override that must survive the next startup
                // probe — otherwise the cached variant is restored and silently wins.
                AppState.setLito(this@MainActivity, isChecked)
                AircraftSession.serial.ifEmpty { StartupProbe.serial }
                    .takeIf { it.isNotEmpty() }
                    ?.let { DeviceStore.setManualVariant(this@MainActivity, it, isChecked) }
                setStatus(t("имена параметров: ", "param names: ") + (if (isChecked) "Lito" else t("другие DJI", "other DJI")) +
                    t(" — ручной выбор, опрос его не перезапишет", " — manual, the startup probe will not overwrite it"))
            }
        }
        val profileCard = card(t("📟 Профиль устройства (определяется при старте опросом имён параметров)",
                               "📟 Device profile (auto-detected at start by param-name probe)"),
            cell(t("Имена Lito (выкл = имена g_config.*)", "Lito names (off = g_config.* names)"), litoSw))

        val updBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        updBody.addView(cell(t("Проверять при запуске", "Check on launch"),
            mkSwitch(AppState.autoUpdateCheck).apply {
                setOnClickListener { AppState.setAutoUpdateCheck(this@MainActivity, isChecked) } }))
        updBody.addView(cell(t("Включая предварительные", "Include pre-releases"),
            mkSwitch(AppState.updatePrerelease).apply {
                setOnClickListener { AppState.setUpdatePrerelease(this@MainActivity, isChecked) } }), tightLp())
        updBody.addView(TextView(this).apply {
            text = t("Текущая версия: ", "Installed version: ") + currentVersion() + "  ·  " + Updater.REPO
            setTextColor(MUTED); textSize = 10.5f; setPadding(0, dp(6), 0, 0)
        })
        updBody.addView(rowc(smallBtn(t("🔄 Проверить сейчас", "🔄 Check now"), BLUE) { checkUpdates(manual = true) }), rowLp())
        val updateCard = card(t("⬆️ Обновления", "⬆️ Updates"), updBody)

        val langCard = card(t("🌍 Язык интерфейса", "🌍 Interface language"),
            cell(if (AppState.uiRu) "Русский" else "English",
                 smallBtn(t("Switch to English", "Переключить на русский"), BLUE) { toggleLanguage() }))

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(4)) }
        col.addView(servicesCard); col.addView(profileCard); col.addView(updateCard); col.addView(langCard)
        return ScrollView(this).apply { addView(col); isFillViewport = true }
    }

    // ---------- params page (load .dhp / .dhv2params, search, edit by hash) ----------
    private lateinit var paramSearch: EditText
    private lateinit var paramTable: android.widget.ListView
    private lateinit var paramEditOnly: android.widget.CheckBox
    private lateinit var paramGroupBtn: Button
    private val paramAdapter by lazy { ParamAdapter() }
    private var paramRows: List<ParamCatalog.Def> = emptyList()
    /** Active group filter (a [ParamGroups] id), or null for "all groups". */
    private var paramGroupFilter: String? = null

    // One shared set of column weights: the fixed header and every row lay out
    // with these, which is what actually makes the columns line up. Name first,
    // actions last, mirroring the web editor's table.
    private val PARAM_COLS = floatArrayOf(3.4f, 0.7f, 1.9f, 1.0f, 1.1f, 2.0f)
    // Built per Activity instance, after App.onCreate has loaded the language.
    private val PARAM_HEADS = arrayOf(
        t("Имя", "Name"), t("Тип", "Type"), t("Диапазон", "Range"),
        t("По умолч.", "Default"), t("Текущее", "Current"), t("Действия", "Actions"))
    /** Values actually read off the aircraft, by name. A row shows the catalog's
     *  number in grey until its name lands here, then the live one in green — the
     *  same distinction the web editor makes. */
    private val paramLive = HashMap<String, String>()
    /** Coalesces typing: each keystroke re-filters the whole catalog (~950 names)
     *  and resets the table's scroll, so it should not fire per character. */
    private val paramRenderTick = Runnable { renderParams() }

    private fun buildParams(): View {
        paramSearch = EditText(this).apply {
            hint = t("поиск по имени параметра", "search parameter name"); setTextColor(INK); setHintTextColor(MUTED); textSize = 13f
            background = pillBg(0xFF0C0E15.toInt(), dp(10)); setPadding(dp(10), dp(8), dp(10), dp(8))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) {
                    removeCallbacks(paramRenderTick); postDelayed(paramRenderTick, PARAM_SEARCH_DEBOUNCE_MS)
                }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        }
        paramEditOnly = android.widget.CheckBox(this).apply {
            text = t("только изменяемые — скрыть min = max", "editable only — hide min = max")
            setTextColor(MUTED); textSize = 11.5f
            setOnCheckedChangeListener { _, _ -> renderParams() }
        }
        // The header sits ABOVE the list, not inside it, so it stays put while the
        // rows scroll. Same weights as a row, so the columns align.
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF1A1E2B.toInt())
            for (i in PARAM_HEADS.indices) addView(paramCell(PARAM_HEADS[i], MUTED, PARAM_COLS[i], bold = true))
        }
        paramTable = android.widget.ListView(this).apply {
            adapter = paramAdapter
            divider = android.graphics.drawable.ColorDrawable(0xFF222839.toInt())
            dividerHeight = 1
            // Rows carry their own clickable cells; the list itself never handles
            // a tap, so don't paint a selector over them.
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
        }
        // Styled like [paramSearch], not like an action button: it sits beside the search
        // box on the filter row and does the same kind of job — narrowing the list — so it
        // should read as a field, not as something that fires an operation.
        paramGroupBtn = Button(this).apply {
            text = groupBtnLabel(); isAllCaps = false; textSize = 13f
            setTextColor(INK); typeface = Typeface.DEFAULT
            minHeight = dp(34); minimumHeight = dp(34); minWidth = 0; minimumWidth = 0
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            background = pillBg(0xFF0C0E15.toInt(), dp(10))
            setPadding(dp(10), dp(8), dp(10), dp(8)); stateListAnimator = null
            setOnClickListener { pickGroup() }
        }
        // Source row: only where a catalog comes from — a file, a set shipped in the APK,
        // or the aircraft itself — plus the Fly helper.
        val srcBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            fun next(v: View) = addView(v, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            addView(smallBtn(t("📂 Из файла", "📂 From file"), BLUE) { pickCatalog() })
            next(smallBtn(t("📦 Из набора", "📦 From set"), GREEN) { pickBundledSet() })
            next(smallBtn(t("📡 С борта", "📡 From aircraft"), VIOLET) { dumpFromAircraft() })
            next(smallBtn(t("⏹ Остановить Fly", "⏹ Stop Fly"), AMBER) { openFlyAppInfo() })
            next(smallBtn(t("▶ Открыть Fly", "▶ Open Fly"), GREEN) { openFly() })
        }
        // Filter row: the two things that narrow the list — text and group — sit together,
        // because they are used together. The search box no longer hogs the row: the group
        // button needs room for a real group name, not just "All groups".
        val findBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            fun next(v: View, weight: Float) =
                addView(v, LinearLayout.LayoutParams(0, WRAP_CONTENT, weight).apply { leftMargin = dp(8) })
            addView(paramSearch, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.5f))
            next(paramGroupBtn, 1.6f)
            next(smallBtn(t("Очистить", "Clear"), SLATE) {
                paramSearch.setText(""); paramGroupFilter = null
                paramGroupBtn.text = groupBtnLabel(); renderParams()
            }, 0.9f)
            next(paramEditOnly, 1.5f)
        }
        // Backup reminder: a wrong value here writes straight to the flight
        // controller, and the only clean undo is a copy made before the edit.
        val warn = TextView(this).apply {
            text = if (AppState.uiRu) AppCopy.PARAM_BACKUP_WARNING_RU else AppCopy.PARAM_BACKUP_WARNING_EN
            setTextColor(AMBER); textSize = 11.5f; setLineSpacing(dp(2).toFloat(), 1f)
            background = pillBg(0xFF241A05.toInt(), dp(10)); setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(6)) }
        col.addView(warn, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8) })
        col.addView(srcBar)
        col.addView(findBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(head, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        col.addView(paramTable, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return col
    }

    /** The group-filter button's caption: the active group's short label, or a
     *  neutral "all groups" when nothing is filtered. */
    private fun groupBtnLabel(): String {
        val g = paramGroupFilter?.let { ParamGroups.group(it) }
        return if (g == null) t("🗂 Все группы", "🗂 All groups") else "🗂 " + g.label()
    }

    /** Pick a semantic group to filter the catalog by. Only groups that actually
     *  occur in the loaded catalog are offered (each with its count), so the list
     *  never points at an empty bucket; "all groups" clears the filter. Counts honour
     *  the "editable only" filter, so the number beside a group matches the rows it
     *  yields with that filter on. */
    private fun pickGroup() {
        if (ParamCatalog.params.isEmpty()) {
            setStatus(t("сначала загрузите список параметров", "load a parameter catalog first")); return
        }
        val present = ParamGroups.present(ParamCatalog.matches("", paramEditOnly.isChecked))
        val labels = arrayListOf(t("🗂 Все группы", "🗂 All groups"))
        val ids = arrayListOf<String?>(null)
        for ((g, n) in present) { labels.add("${g.label()}  ·  $n"); ids.add(g.id) }
        val sel = ids.indexOf(paramGroupFilter).coerceAtLeast(0)
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Группа параметров", "Parameter group"))
            .setSingleChoiceItems(labels.toTypedArray(), sel) { dlg, which ->
                paramGroupFilter = ids[which]
                paramGroupBtn.text = groupBtnLabel()
                renderParams()
                dlg.dismiss()
            }
            .setNegativeButton(t("Закрыть", "Close"), null)
            .show()
    }

    /** Choose one of the parameter sets bundled in the APK — the cross-model
     *  "common" list, or a specific model — and load it like a picked file. The
     *  backup reminder rides along because loading a set is the step right before
     *  a user starts writing values. */
    private fun pickBundledSet() {
        val sets = BundledParamSets.list(this)
        if (sets.isEmpty()) { setStatus(t("встроенные наборы недоступны", "bundled sets unavailable")); return }
        val labels = sets.map {
            val tag = if (it.isCommon) t("общий", "common") else t("модель", "model")
            "${it.label()}\n$tag · ${it.count} " + t("параметров", "params") +
                (if (it.unique > 0) " · ${it.unique} " + t("уникальных", "unique") else "")
        }.toTypedArray()
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Набор параметров", "Parameter set"))
            .setItems(labels) { _, i ->
                val e = sets[i]
                setStatus(t("⏳ загружаю набор ${e.label()}…", "⏳ loading set ${e.label()}…"))
                scope.launch {
                    val res = runCatching { BundledParamSets.load(this@MainActivity, e) }
                    runOnUiThread {
                        res.onSuccess { paramGroupFilter = null; paramGroupBtn.text = groupBtnLabel(); renderParams() }
                            .onFailure { setStatus(t("⚠ не удалось загрузить набор: ${it.message}", "⚠ set load failed: ${it.message}")) }
                    }
                }
            }
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .show()
    }
    private fun renderParams() {
        if (!::paramTable.isInitialized) return
        // Group filter is applied on top of the name/editable filters. A group that
        // is no longer present (e.g. after loading a different catalog) matches
        // nothing rather than erroring; the button caption still reflects it.
        val gid = paramGroupFilter
        paramRows = ParamCatalog.matches(paramSearch.text.toString(), paramEditOnly.isChecked)
            .let { if (gid == null) it else it.filter { d -> ParamGroups.groupIdOf(d.name) == gid } }
        val locked = ParamCatalog.params.count { !it.editable }
        val groupNote = paramGroupFilter?.let { id ->
            " · " + t("группа", "group") + " " + (ParamGroups.group(id)?.label() ?: id)
        } ?: ""
        // The footer carries what the on-page source line used to. Only user actions
        // on this page call renderParams, so this never clobbers someone else's status.
        setStatus(if (ParamCatalog.params.isEmpty())
                t("список не загружен — «📂 Из файла», «📦 Из набора» или «📡 С борта»",
                  "no catalog loaded — tap 📂 From file, 📦 From set or 📡 From aircraft")
            else "📂 ${ParamCatalog.sourceName} · ${ParamCatalog.params.size} " + t("параметров", "params") +
                (if (locked > 0) " ($locked " + t("только чтение", "read-only") + ")" else "") +
                groupNote + " · " + t("показано", "showing") + " ${paramRows.size}")
        paramAdapter.notifyDataSetChanged()
    }

    /** One table cell, weighted so every row lines up with the header. */
    private fun paramCell(text: String, color: Int, weight: Float, bold: Boolean = false, lines: Int = 1) =
        TextView(this).apply {
            this.text = text; setTextColor(color); textSize = 11.5f
            typeface = if (bold) Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) else Typeface.MONOSPACE
            maxLines = lines; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(6), dp(6), dp(6), dp(6))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, weight)
        }

    /** A tappable action in the last column, styled like the web editor's links. */
    private fun paramAction(label: String) = TextView(this).apply {
        text = label; setTextColor(BLUE); textSize = 11.5f
        setPadding(dp(5), dp(6), dp(5), dp(6))
        isClickable = true
    }

    /**
     * The params table backed by view recycling. A full catalog is ~950 rows of
     * six cells each; inflating them all at once (what the old flat list did, and
     * why it was capped at 300) would be ~5700 views. A [android.widget.ListView]
     * keeps only the visible handful alive.
     */
    private inner class ParamAdapter : android.widget.BaseAdapter() {
        override fun getCount() = paramRows.size
        override fun getItem(position: Int) = paramRows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
            val row = convertView as? LinearLayout ?: newRow()
            bind(row, paramRows[position], position)
            return row
        }

        private fun newRow() = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(paramCell("", INK, PARAM_COLS[0], lines = 2))   // 0 name
            addView(paramCell("", MUTED, PARAM_COLS[1]))            // 1 type
            addView(paramCell("", MUTED, PARAM_COLS[2]))            // 2 range
            addView(paramCell("", MUTED, PARAM_COLS[3]))            // 3 default
            addView(paramCell("", MUTED, PARAM_COLS[4]))            // 4 current
            addView(LinearLayout(this@MainActivity).apply {          // 5 actions
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, PARAM_COLS[5])
                addView(paramAction(t("чтен.", "read")))
                addView(paramAction(t("запись", "write")))
                addView(paramAction(t("сброс", "reset")))
            })
        }

        /** Rebind a recycled row. Every listener is re-attached here — a recycled
         *  row must never keep the previous parameter's click target. */
        private fun bind(row: LinearLayout, d: ParamCatalog.Def, position: Int) {
            row.setBackgroundColor(if (position % 2 == 0) Color.TRANSPARENT else 0xFF12151F.toInt())
            val live = paramLive[d.name]
            (row.getChildAt(0) as TextView).apply {
                text = d.name; setTextColor(if (d.editable) INK else MUTED)
                setOnClickListener { openParamEditor(d) }          // tapping the name opens the editor
            }
            (row.getChildAt(1) as TextView).text = typeLabel(d)
            (row.getChildAt(2) as TextView).text =
                if (d.editable) d.range.ifEmpty { "—" } else t("заблок. (min = max)", "locked (min = max)")
            (row.getChildAt(3) as TextView).text = d.def.ifEmpty { "—" }
            // Current: the catalog's number in grey until a real read replaces it.
            (row.getChildAt(4) as TextView).apply {
                text = live ?: d.value.ifEmpty { "—" }
                setTextColor(if (live != null) GREEN else MUTED)
            }
            val actions = row.getChildAt(5) as LinearLayout
            val read = actions.getChildAt(0) as TextView
            val write = actions.getChildAt(1) as TextView
            val reset = actions.getChildAt(2) as TextView
            read.setOnClickListener { readParamInto(d) }
            // A locked entry can still be READ — those are the status counters, and
            // their value is the only interesting thing about them. It just cannot
            // be written, so those two actions go away rather than failing later.
            write.visibility = if (d.editable) View.VISIBLE else View.GONE
            reset.visibility = if (d.editable && d.def.isNotEmpty()) View.VISIBLE else View.GONE
            write.setOnClickListener { openParamEditor(d) }
            reset.setOnClickListener { stageParamWrite(d, d.def, reset = true) }
        }
    }

    /**
     * Open the OS "App info" page for the live DJI Fly, where the user can press
     * Force stop and get it off the bus — Fly holding 40007 is exactly what makes
     * parameter reads flaky, so this belongs beside the catalog controls.
     *
     * We do not try to stop it ourselves, and that is not a shortcut: DJI Fly is
     * registered as the HOME process on this controller (its launch activity is
     * HOME/LAUNCHER), so Android never reclaims it in a background sweep.
     * `killBackgroundProcesses` and `am kill` both report success and leave the
     * pid untouched. A real stop needs `force-stop`, which needs a system uid we
     * do not have — so the App info screen, where the user taps the button, is
     * the only thing that actually works from an ordinary APK.
     *
     * Getting Fly back afterwards needs no button of ours: it is the home app, so
     * the Home key relaunches it.
     */
    /**
     * Bring DJI Fly to the front.
     *
     * The launcher intent (ACTION_MAIN + CATEGORY_LAUNCHER) is what a home screen sends, so
     * an app that is merely backgrounded is **resumed**, not restarted — its link session
     * and flight state survive. If Fly is not running it simply starts.
     *
     * The counterpart to "⏹ Stop Fly": the parameter reads and the table dump want Fly out
     * of the way, and afterwards the user wants it back without hunting for the launcher.
     */
    private fun openFly() {
        val found = flyCandidates()
        fun launch(pkg: String) {
            val i = packageManager.getLaunchIntentForPackage(pkg)
            if (i == null) {
                setStatus(t("⚠ у $pkg нет экрана запуска", "⚠ $pkg has no launch screen")); return
            }
            // Close the read GATE before the switch, but depart passively — do NOT force-drop
            // the 40007 socket. expectFlyForeground() makes readsAllowed() false right now, so
            // no new read/retry starts and any in-flight short read ends by itself within its
            // window; the aux OSD probe releases on its own next tick. (An abrupt drop/close
            // synchronized to Fly's bring-up is what cost Fly its link for a few seconds; a
            // short self-terminating read overlapping the switch is harmless — proven on RC2.)
            ForegroundGate.expectFlyForeground()
            foreground = false                       // stop the live-status loop this instant
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            runCatching { startActivity(i) }
                .onSuccess { setStatus(t("→ открываю $pkg", "→ opening $pkg")) }
                .onFailure {
                    ForegroundGate.clearExpectedFly()   // the switch didn't happen; don't hold reads
                    setStatus("⚠ ${it.message}")
                }
        }
        when (found.size) {
            0 -> setStatus(t("⚠ приложение DJI не установлено", "⚠ no DJI app installed"))
            1 -> launch(found[0])
            else -> android.app.AlertDialog.Builder(this)
                .setTitle(t("Какое приложение DJI открыть?", "Which DJI app to open?"))
                .setItems(found.toTypedArray()) { _, i -> launch(found[i]) }
                .setNegativeButton(t("Отмена", "Cancel"), null)
                .show()
        }
    }

    private fun openFlyAppInfo() {
        // Whatever is in front is unambiguous — act on it.
        ForegroundGate.foregroundPackage.takeIf { it in ForegroundGate.DJI_PACKAGES }
            ?.let { return openAppInfo(it) }
        val found = flyCandidates()
        when (found.size) {
            0 -> setStatus(t("⚠ приложение DJI не установлено (искал ${ForegroundGate.DJI_PACKAGES.joinToString(", ")})",
                             "⚠ no DJI app installed (looked for ${ForegroundGate.DJI_PACKAGES.joinToString(", ")})"))
            1 -> openAppInfo(found[0])
            // Which package is the live DJI Fly differs between controllers — v5 and
            // v6 can both be installed and enabled. Guessing would send the user to
            // the App info page of an app that isn't the one holding the bus, so ask.
            else -> android.app.AlertDialog.Builder(this)
                .setTitle(t("Какое приложение DJI остановить?", "Which DJI app to stop?"))
                .setItems(found.toTypedArray()) { _, i -> openAppInfo(found[i]) }
                .setNegativeButton(t("Отмена", "Cancel"), null)
                .show()
        }
    }

    /** Installed DJI candidates, launchable ones first — a disabled package has no
     *  launch intent, so it is the less likely one to be holding the bus, but it is
     *  still offered because App info can Force stop it either way. */
    private fun flyCandidates(): List<String> {
        val installed = ForegroundGate.DJI_PACKAGES.filter { isInstalled(it) }
        val (launchable, disabled) = installed.partition { packageManager.getLaunchIntentForPackage(it) != null }
        return launchable + disabled
    }

    private fun openAppInfo(pkg: String) {
        runCatching {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }.onSuccess { setStatus(t("⏹ $pkg — нажмите там «Остановить», затем вернитесь", "⏹ $pkg — tap “Force stop” there, then come back")) }
            .onFailure { setStatus(t("⚠ не удалось открыть сведения о $pkg: ${it.message}", "⚠ cannot open app info for $pkg: ${it.message}")) }
    }

    /** Needs the `<queries>` block in the manifest on Android 11+, or every
     *  package but our own reads as absent. */
    private fun isInstalled(pkg: String) =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** Read one parameter and show it in its row (green) and the status line. */
    private fun readParamInto(d: ParamCatalog.Def) {
        setStatus(t("⏳ читаю ${d.name}…", "⏳ reading ${d.name}…"))
        scope.launch {
            val v = ParamRead.read(d.name)
            runOnUiThread {
                setStatus(if (v == null) t("⚠ ${d.name}: нет ответа", "⚠ ${d.name}: no answer")
                          else "${d.name} = ${ParamCatalog.decode(v, d.typeName)} (${DumlWire.toHex(v)}, ${v.size} B)")
                noteLive(d.name, v, d.typeName)
            }
        }
    }

    /** Read BEFORE encoding — the reply pins the byte width and gives the honest
     *  "old" value — then put the exact bytes in front of the user. */
    private fun stageParamWrite(d: ParamCatalog.Def, value: String, reset: Boolean, msg: TextView? = null) {
        scope.launch {
            val cur = ParamRead.read(d.name)
            when (val enc = ParamCatalog.encodeChecked(d, value, cur?.size)) {
                is ParamCatalog.Encoded.Invalid -> runOnUiThread { setStatus("⚠ ${enc.reason}") }
                is ParamCatalog.Encoded.Ok -> runOnUiThread { confirmParamWrite(d, enc, cur, msg, reset) }
            }
        }
    }

    /** Record a live read so the row shows it in green, and refresh the table. */
    private fun noteLive(name: String, v: ByteArray?, typeName: String) {
        if (v == null) return
        paramLive[name] = ParamCatalog.decode(v, typeName)
        if (::paramTable.isInitialized) paramAdapter.notifyDataSetChanged()
    }
    /** `U8`, `F32`… when the export declared one; else the raw id, or `?` if it had neither. */
    private fun typeLabel(d: ParamCatalog.Def): String =
        if (d.typeName.isNotEmpty()) d.typeName else if (d.typeId >= 0) d.typeId.toString() else "?"

    /**
     * Read the whole parameter table off the aircraft (`03:E1`) and use it as the catalog.
     *
     * For a drone no bundled set covers, and as a check on the ones that do — on the test
     * aircraft the shipped Lito X1 set turned out to carry 94 names the firmware does not
     * have and to miss 22 that it does.
     *
     * It walks every slot over DJI Fly's port, so the dialog says plainly to stop Fly first:
     * with Fly running the read windows miss and the walk needs extra passes.
     */
    private fun dumpFromAircraft() {
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Прочитать каталог с борта", "Read the catalog from the aircraft"))
            .setMessage(t(
                "Обойдёт все слоты таблицы параметров по индексу (03:E1) и соберёт имена, типы, " +
                    "диапазоны и значения по умолчанию с самого аппарата.\n\n" +
                    "Занимает около двух минут и идёт по порту DJI Fly. " +
                    "Для чистого прохода сначала остановите DJI Fly — при работающем Fly часть окон " +
                    "промахивается и нужны лишние проходы.",
                "Walks every slot of the parameter table by index (03:E1), collecting names, types, " +
                    "ranges and defaults from the aircraft itself.\n\n" +
                    "Takes about two minutes and uses DJI Fly's port. For a clean run, stop DJI Fly " +
                    "first — with Fly running some windows miss and extra passes are needed."))
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .setPositiveButton(t("Читать", "Read")) { _, _ -> runDump() }
            .show()
    }

    private fun runDump() {
        val progress = TextView(this).apply { setTextColor(MUTED); textSize = 13f; setPadding(dp(20), dp(16), dp(20), dp(8)) }
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(t("Чтение каталога с борта", "Reading the catalog"))
            .setView(progress)
            .setNegativeButton(t("Остановить", "Stop")) { _, _ -> ParamDump.stop() }
            .setCancelable(false).create()
        dlg.show()
        scope.launch {
            val started = ParamDump.start(applicationContext)
            if (!ParamDump.running) {
                runOnUiThread { dlg.dismiss(); setStatus("⚠ $started") }
                return@launch
            }
            while (ParamDump.running) {
                val pct = if (ParamDump.total > 0) 100 * ParamDump.resolved / ParamDump.total else 0
                runOnUiThread {
                    progress.text = t(
                        "${ParamDump.resolved} из ${ParamDump.total}  ($pct%)\nнайдено ${ParamDump.namedCount} · " +
                            "пусто ${ParamDump.emptyCount} · не ответили ${ParamDump.unknownCount}\nпроход ${ParamDump.pass}",
                        "${ParamDump.resolved} of ${ParamDump.total}  ($pct%)\nnamed ${ParamDump.namedCount} · " +
                            "empty ${ParamDump.emptyCount} · unread ${ParamDump.unknownCount}\npass ${ParamDump.pass}")
                }
                delay(700)
            }
            val serial = AircraftSession.serial.ifEmpty { StartupProbe.serial }
            // "unread" is not "empty": a slot that never answered is unknown, so a catalog
            // saved with a gap says so in its name rather than passing for the whole table.
            val report = ParamDump.save(applicationContext, serial, allowPartial = ParamDump.unknownCount > 0)
            runOnUiThread {
                dlg.dismiss()
                setStatus(report)
                // Same refresh the bundled-set loader does: the group filter belongs to the
                // old catalog and would hide most of the new one.
                paramGroupFilter = null
                paramGroupBtn.text = groupBtnLabel()
                renderParams()
            }
        }
    }

    private fun openParamEditor(d: ParamCatalog.Def) {
        val head = t("список: значение=${d.value} по умолч.=${d.def} диапазон=${d.range.ifEmpty { "нет" }} тип=${typeLabel(d)}",
                     "catalog: value=${d.value} default=${d.def} range=${d.range.ifEmpty { "none" }} type=${typeLabel(d)}")
        val input = EditText(this).apply { hint = t("новое значение (десятичное или 0x..)", "new value (decimal or 0x..)"); setText(d.value) }
        val msg = TextView(this).apply { setTextColor(MUTED); textSize = 12f; setPadding(dp(6), dp(4), dp(6), dp(4))
            text = "$head\n" + t("текущее: читаю…", "current: reading…") }
        // A 4th action doesn't fit AlertDialog's three buttons, so Reset lives in
        // the body. It resets to the AIRCRAFT's default via 03:FA, so it works even for a
        // parameter the loaded catalog knows nothing about; the catalog's own default is
        // only the fallback.
        val resetBtn = smallBtn(t("↺ Вернуть значение по умолчанию", "↺ Reset to default"), SLATE) {}
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), 0)
            addView(msg); addView(input); addView(resetBtn) }

        // What 03:F7 said about this parameter, once it has answered.
        var boardInfo: ConfigTable.Info.Ok? = null
        // The aircraft answered "no such parameter" — a real answer, not silence.
        var boardAbsent = false
        /** Label the Reset button with the default it will actually write, and say where
         *  that number came from — board or file. They are not always the same. */
        fun syncResetButton() {
            val bd = boardInfo?.def
            when {
                // The aircraft ANSWERED that it has no such parameter (03:F7 status 3). Any
                // write would be addressed to a hash the firmware doesn't know and would be
                // a guaranteed silent no-op, so don't offer one. This is different from
                // "no answer", which only means the reply didn't route back.
                boardAbsent -> {
                    resetBtn.isEnabled = false
                    resetBtn.text = t("↺ на этом борту такого параметра нет", "↺ not present on this aircraft")
                }
                bd != null -> {
                    resetBtn.isEnabled = true
                    resetBtn.text = t("↺ Значение по умолчанию борта ($bd)", "↺ Board's default ($bd)")
                }
                d.def.isNotEmpty() -> {
                    resetBtn.isEnabled = true
                    resetBtn.text = t("↺ Значение из списка (${d.def})", "↺ Catalog's default (${d.def})")
                }
                else -> {
                    resetBtn.isEnabled = false
                    resetBtn.text = t("↺ значение по умолчанию неизвестно", "↺ default unknown")
                }
            }
        }
        syncResetButton()
        val dlg = android.app.AlertDialog.Builder(this).setTitle(d.name).setView(box)
            .setPositiveButton(t("Записать", "Write"), null).setNeutralButton(t("Прочитать", "Read"), null)
            .setNegativeButton(t("Закрыть", "Close"), null).create()

        /**
         * Read the value, ask the board to describe the parameter, show both, and colour the
         * list row green.
         *
         * The `board:` line is the point of the 03:F7 lookup: the catalog line above it came
         * out of a file, and the two can disagree — a loaded export may be for another
         * firmware, or name a parameter this aircraft does not have at all (2 of 14 sampled
         * names in the bundled Lito X1 set are absent on the connected board). Showing both
         * is the disagreement warning; hiding it would be the bug.
         */
        fun refresh(label: String) {
            scope.launch {
                val v = ParamRead.read(d.name)
                val info = ParamMeta.info(d.name)
                runOnUiThread {
                    val board = when (info) {
                        null -> t("борт: нет ответа", "board: no answer")
                        is ConfigTable.Info.Absent ->
                            t("борт: ТАКОГО ПАРАМЕТРА НЕТ (03:F7 статус ${info.status})",
                              "board: NO SUCH PARAMETER (03:F7 status ${info.status})")
                        is ConfigTable.Info.Ok ->
                            t("борт: по умолч.=${info.def} диапазон=${info.min} … ${info.max} тип=${info.typeName}/${info.size}Б",
                              "board: default=${info.def} range=${info.min} … ${info.max} type=${info.typeName}/${info.size}B")
                    }
                    msg.text = "$label\n$board\n" + t("текущее: ", "current: ") + (v?.let {
                        "${ParamCatalog.decode(it, d.typeName)}  (${DumlWire.toHex(it)}, ${it.size} B)"
                    } ?: t("нет ответа", "no answer"))
                    noteLive(d.name, v, d.typeName)
                    boardInfo = info as? ConfigTable.Info.Ok
                    boardAbsent = info is ConfigTable.Info.Absent
                    syncResetButton()
                    // Writing to a parameter the aircraft has explicitly disowned cannot do
                    // anything, so take the offer away rather than let it report "sent".
                    dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.let { b ->
                        b.isEnabled = !boardAbsent
                        b.alpha = if (boardAbsent) 0.4f else 1f
                    }
                    if (boardAbsent) input.isEnabled = false
                }
            }
        }
        dlg.setOnShowListener {
            refresh(head)                                  // read current on open
            dlg.getButton(android.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener { refresh(head) }
            dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                stageParamWrite(d, input.text.toString(), reset = false, msg = msg)
            }
        }
        resetBtn.setOnClickListener {
            val bi = boardInfo
            if (bi == null) {
                // No 03:F7 answer: fall back to the file's default, down the ordinary checked
                // write path, and say plainly that the number came from the file.
                if (d.def.isEmpty()) return@setOnClickListener
                input.setText(d.def)
                stageParamWrite(d, d.def, reset = true, msg = msg)
                return@setOnClickListener
            }
            android.app.AlertDialog.Builder(this)
                .setTitle(t("Сброс к значению борта", "Reset to the board's default"))
                .setMessage(t(
                    "${d.name} → ${bi.def}\n\nЗначение взято у самого аппарата (03:F7), не из файла. " +
                        "Пишется командой 03:FA на 40008 — она сбрасывает ровно один параметр.",
                    "${d.name} → ${bi.def}\n\nThe value comes from the aircraft itself (03:F7), not from a " +
                        "file. It is written with 03:FA on 40008, which resets exactly one parameter."))
                .setNegativeButton(t("Отмена", "Cancel"), null)
                .setPositiveButton(t("Сбросить", "Reset")) { _, _ ->
                    scope.launch {
                        val (_, report) = ParamMeta.reset(d.name)
                        val v = ParamRead.read(d.name)
                        runOnUiThread {
                            setStatus(report)
                            msg.text = report
                            input.setText(bi.def)
                            noteLive(d.name, v, bi.typeName)
                        }
                    }
                }
                .show()
        }
        // A dialog has its own window and does not inherit the activity's soft-input mode,
        // so it needs the same treatment: pan the value field above the keyboard instead of
        // letting the keyboard cover it on this short landscape screen.
        dlg.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        dlg.show()
    }
    /** Show the exact bytes before writing a parameter, then write and report an
     *  honest [WriteResult] (never "success" for a frame that didn't leave or
     *  wasn't confirmed). */
    private fun confirmParamWrite(d: ParamCatalog.Def, enc: ParamCatalog.Encoded.Ok, cur: ByteArray?,
                                  msg: TextView?, reset: Boolean) {
        val hashHex = runCatching { DumlWire.toHex(DumlNative.nativeParamHash(d.name)) }.getOrDefault("?")
        val oldStr = cur?.let { "${ParamCatalog.decode(it, d.typeName)} (${DumlWire.toHex(it)})" }
            ?: t("неизвестно (чтение перед записью осталось без ответа)", "unknown (pre-write read went unanswered)")
        val body = t("Параметр: ", "Parameter: ") + "${d.name}\nhash: $hashHex\n\n" +
            t("было:  ", "old:   ") + "$oldStr\n" +
            t("стало: ", "new:   ") + "${ParamCatalog.decode(enc.bytes, d.typeName)} (${DumlWire.toHex(enc.bytes)})\n" +
            t("ширина: ", "width: ") + "${enc.widthNote}\n\n" +
            (if (reset) t("Это значение по умолчанию из списка ${ParamCatalog.sourceName}, а не с самого борта — борт не ответил на 03:F7. Пишется обычной записью 03:F9 по хешу.\n\n",
                         "This default comes from ${ParamCatalog.sourceName}, not from the aircraft — the board did not answer 03:F7. It is written as a normal 03:F9 write-by-hash.\n\n") else "") +
            (if (AppState.uiRu) AppCopy.PARAM_BACKUP_WARNING_RU else AppCopy.PARAM_BACKUP_WARNING_EN) + "\n\n" +
            t("Записать именно эти байты?", "Write these exact bytes?")
        android.app.AlertDialog.Builder(this)
            .setTitle(if (reset) t("Подтвердите сброс к значению по умолчанию", "Confirm reset to default")
                      else t("Подтвердите запись", "Confirm write"))
            .setMessage(body)
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .setPositiveButton(t("Записать", "Write")) { _, _ ->
                setStatus(t("⏳ пишу ${d.name}…", "⏳ writing ${d.name}…"))
                scope.launch {
                    val ok = ParameterAddress(d.name).write(enc.bytes, writes = 2, gapMs = 120)
                    // Poll for the read-back: the FC answers stale for the first
                    // ~300-500 ms, so a single read here used to call successful
                    // writes "read-back differs".
                    val back = ParamRead.confirmWrite(d.name, enc.bytes)
                    val result = when {
                        !ok -> WriteResult.LINK_DOWN
                        back == null -> WriteResult.NO_REPLY
                        back.value.contentEquals(enc.bytes) -> WriteResult.CONFIRMED
                        else -> WriteResult.SENT
                    }
                    runOnUiThread {
                        setStatus("${if (ok) "✅" else "⚠"} ${d.name} = ${DumlWire.toHex(enc.bytes)} · ${result.label}" +
                            (back?.let { t(" (через ${it.afterMs} мс)", " (after ${it.afterMs} ms)") } ?: ""))
                        // The dialog is only open when the write came from it; a row
                        // action writes with no dialog and reports on the status line.
                        msg?.text = t("текущее: ", "current: ") + (back?.let {
                            "${ParamCatalog.decode(it.value, d.typeName)} (${DumlWire.toHex(it.value)})"
                        } ?: t("нет ответа", "no answer"))
                        noteLive(d.name, back?.value, d.typeName)
                    }
                }
            }
            .show()
    }

    private fun pickCatalog() {
        runCatching {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_DHP)
        }.onFailure { setStatus(t("⚠ нет приложения для выбора файла: ${it.message}", "⚠ no file picker: ${it.message}")) }
    }

    // ---------- about page ----------
    /**
     * About — the same short capability list the setup wizard opens with (shared
     * via [AppCopy] so the two cannot drift), shown in both languages at once,
     * plus the contact link and the diag-server warning.
     */
    private fun buildAbout(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        col.addView(TextView(this).apply {
            text = "⚡ ${AppCopy.NAME}${versionSuffix()}"
            setTextColor(INK); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        })

        aboutSection(col, "Возможности", AppCopy.FEATURES_RU)
        aboutSection(col, "Features", AppCopy.FEATURES_EN)

        col.addView(TextView(this).apply {
            text = "✈  Telegram:  ${AppCopy.TELEGRAM}"
            setTextColor(BLUE); textSize = 13.5f; typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, dp(4))
            setOnClickListener { openUrl(AppCopy.TELEGRAM) }
        })

        aboutNote(col, "⚠  " + AppCopy.DIAG_WARNING_RU, AMBER, dp(10))
        aboutNote(col, "⚠  " + AppCopy.DIAG_WARNING_EN, AMBER, dp(4))
        aboutNote(col, AppCopy.CREDITS, MUTED, dp(12))
        return ScrollView(this).apply { addView(col); isFillViewport = true }
    }

    private fun aboutSection(col: LinearLayout, title: String, items: List<String>) {
        col.addView(TextView(this).apply {
            text = title; setTextColor(MUTED); textSize = 12f
            setPadding(0, dp(14), 0, dp(5))
        })
        for (s in items) col.addView(TextView(this).apply {
            text = s; setTextColor(INK); textSize = 12.5f
            setLineSpacing(dp(3).toFloat(), 1f); setPadding(0, 0, 0, dp(6))
        })
    }

    private fun aboutNote(col: LinearLayout, text: String, color: Int, topPad: Int) =
        col.addView(TextView(this).apply {
            this.text = text; setTextColor(color); textSize = 11.5f
            setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, topPad, 0, 0)
        })

    /** `  v0.1` — read from the package, since BuildConfig generation is off. */
    private fun versionSuffix(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName?.let { "  v$it" }.orEmpty()
    }.getOrDefault("")

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { setStatus(t("нечем открыть $url", "no app can open $url")) }
    }

    // ---------- log page ----------
    private fun buildLog(): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(8)) }
        col.addView(rowc(
            smallBtn(t("Очистить", "Clear"), SLATE) { DiagLog.clear(); renderLog() },
            smallBtn(t("Экспорт", "Export"), BLUE) { setStatus(t("сохранено: ", "saved: ") + DiagLog.export(applicationContext)); renderLog() },
            smallBtn(t("Поделиться", "Share"), VIOLET) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, DiagLog.asText()) }, t("Отправить лог", "Share log")))
            }))
        // A real ScrollView (not TextView + ScrollingMovementMethod): the latter
        // scrolls the text itself and paints a white text-selection highlight on the
        // line under the finger while dragging, and every new log line reset the
        // scroll. Here dragging is normal and highlight-free, and the tail is followed.
        logView = TextView(this).apply {
            setTextColor(INK); textSize = 10.5f; typeface = Typeface.MONOSPACE
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextIsSelectable(false); highlightColor = Color.TRANSPARENT
        }
        logScroll = ScrollView(this).apply {
            isFillViewport = true; background = pillBg(0xFF0C0E15.toInt(), dp(12)); addView(logView)
        }
        col.addView(logScroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(8) })
        return col
    }
    private fun renderLog() {
        // Follow the tail only when the user is already at (near) the bottom, so
        // scrolling up to read isn't yanked back down when new lines arrive.
        val atBottom = !::logScroll.isInitialized || run {
            val child = logScroll.getChildAt(0)
            child == null || logScroll.scrollY + logScroll.height >= child.height - dp(24)
        }
        // Only the tail is ever on screen, so format only the tail. Formatting the whole
        // 3000-entry buffer meant 3000 SimpleDateFormat calls per render, several times a
        // second on a busy bus, all on the main thread — and then discarding everything
        // past the last screenful. That was the Log page's stutter.
        val t = DiagLog.tail(LOG_TAIL_LINES)
        logView.text = if (t.length > 24000) t.substring(t.length - 24000) else t
        if (atBottom) logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
    }

    // ---------- FCC ----------
    /** Apply FCC, start the (gentle, event-driven) keepalive, and confirm by
     *  reading the regulatory param back. Returns true if the frames went out. */
    private suspend fun applyFccAndConfirm(): Boolean {
        val ok = f.applyFcc()
        if (!ok) { runOnUiThread { setStatus(t("⚠ FCC: порт занят", "⚠ FCC: port busy")) }; return false }
        FccKeepaliveService.start(this, KeepaliveMode.HOME_POINT)   // FCC on ⇒ keepalive on (event-driven, no idle spam)
        // NO read-back here, deliberately. Applying FCC writes on 40009, which never
        // disturbs DJI Fly; a confirmation read would open a socket on 40007 — Fly's own
        // port — moments after the radio was reconfigured, which is the worst possible
        // instant for it. The FCC path is write-only end to end, and the parameter editor
        // remains the place to read a value when someone actually wants one.
        runOnUiThread { setStatus(t("✅ кадры FCC отправлены · применено сразу · keepalive включён",
                                    "✅ FCC frames sent · applied live · keepalive on")) }
        return true
    }
    // ---------- live loops ----------
    private fun startLoops() {
        loopJob?.cancel()
        loopJob = scope.launch {
            // NO probe here. StartupProbe opens ~15 sockets on 40007 (serial 00:51,
            // up to 6 name-variant windows, then three param reads), and it used to run
            // as the first statement of this coroutine — before the `foreground` check,
            // before the read gate, on every Activity creation including a recreate from
            // the language toggle. Measured 2026-08-19: entering this app and going back
            // to DJI Fly loses FCC, while merely minimising Fly does not. That burst is
            // the difference. The probe now runs only where it is worth its cost: the
            // setup wizard, the "опрос" button, and /profile/detect.
            var lastEpoch = AircraftSession.epoch
            var lastReadMs = 0L
            runOnUiThread { runCatching { renderDevice(); renderState() } }
            while (isActive) {
                if (foreground) {
                    // A serial change (different aircraft) bumps the session epoch. The
                    // cached per-drone state is already cleared by AircraftSession; what
                    // is NOT done here any more is re-probing, because that is the same
                    // 40007 burst as above and an aircraft change is exactly when DJI Fly
                    // is busy establishing its link. The panel shows what is known and the
                    // "опрос" button re-probes when the user wants it.
                    if (AircraftSession.epoch != lastEpoch) {
                        lastEpoch = AircraftSession.epoch
                        DiagLog.info("aircraft changed — not re-probing (40007 stays quiet); " +
                                     "use the probe button to refresh")
                    }
                    // The ONLY reliable "drone connected" signal is a flight-controller
                    // read answering (40009 is identical drone on/off — proven live).
                    // That read opens DJI Fly's port (40007), so we do it ONLY while
                    // our app is in front — i.e. Fly is backgrounded and nobody is
                    // watching its video, the exact case ForegroundGate marks safe —
                    // at a gentle 15 s cadence (far below the churn that dropped links
                    // in SkylabFCCfree v1.5.78). During flight (Fly in front) reads are
                    // blocked and the link shows "unknown". Events (onResume / write /
                    // aircraft change) still force an immediate read via refreshNow.
                    // Live connect/LED/GPS/mode status. The read now goes through
                    // FlightState.refresh → ParamRead.readMany: ONE 40007 socket for all
                    // three params, with a strict mid-window abort the instant DJI Fly
                    // takes the foreground. That is cheap enough to keep on a timer AND on
                    // events (refreshNow: onResume / a write / an aircraft change) without
                    // the old multi-socket burst that dropped Fly's link on a switch.
                    // NO timed polling. Reading LED/GPS/mode puts frames on 40007, and
                    // measured on hardware that traffic competes with an FCC apply badly
                    // enough to cost it frames. The state is now read only when something
                    // actually asks: opening the screen, a write, or an aircraft change
                    // (refreshNow). Manual by request — see doc/fcc-autoapply-tests.md.
                    if (refreshNow && ForegroundGate.readsAllowed()) {
                        refreshNow = false
                        lastReadMs = System.currentTimeMillis()
                        runCatching { FlightState.refresh() }
                        StartupProbe.rememberModel(applicationContext)
                    } else if (refreshNow) {
                        refreshNow = false   // Fly active — can't read; don't spin
                    }
                    flyStat = flyStatus()   // socket-free (a11y-derived)
                    runOnUiThread { runCatching { renderDevice(); renderState() } }
                }
                delay(RENDER_INTERVAL_MS)
            }
        }
    }

    private fun renderDevice() {
        // Self-heal the FCC button's caption: the keepalive can also be switched from the
        // web dashboard or stop on its own, and this is the periodic tick that notices.
        syncFccButtonLabel()
        val serial = SerialSniffer.serial.ifEmpty { StartupProbe.serial }
        val d = AircraftIdentity.drone; val rc = AircraftIdentity.rc
        val sb = StringBuilder()
        sb.append(t("дрон:  ", "drone: ")).append(if (d.code.isEmpty()) t("— (откройте DJI Fly)", "— (open DJI Fly)") else "${d.name} [${d.code}]").append('\n')
        sb.append(t("пульт: ", "RC:    ")).append(if (rc.code.isEmpty()) "—" else "${rc.name} [${rc.code}]").append('\n')
        sb.append("SN:    ").append(serial.ifEmpty { "—" }).append('\n')
        sb.append(t("имена: ", "names: ")).append(
            when {
                StartupProbe.readsFailed -> t("не отвечает", "not answering")
                StartupProbe.variant == true -> t("Lito (опрошено)", "Lito (probed)")
                StartupProbe.variant == false -> t("g_config.* (опрошено)", "g_config.* (probed)")
                else -> if (AppState.litoMode) "Lito" else "g_config.*"
            })
        sb.append('\n').append("Fly:   ").append(flyStat)
        sb.append('\n').append(t("линк:  ", "link:  ")).append(
            // The ONLY reliable "drone is powered" signal on RC2 is a flight-
            // controller read answering (40009 shows only the controller's own
            // radio, identical drone on/off). That read runs only while our app is
            // in front (Fly backgrounded); during flight we honestly say unknown.
            when {
                FlightState.connected == true -> t("дрон подключён (чтение FLYC прошло)", "drone connected (FLYC read OK)")
                // Passive OSD confirms a live aircraft even when reads are blocked
                // (Fly active) — but only ever as a POSITIVE; absent OSD ≠ no drone
                // (the aux reader may simply be off), so it never turns the line red.
                DroneLink.connected() -> t("дрон подключён (живой OSD)", "drone connected (live OSD)")
                !ForegroundGate.readsAllowed() -> t("неизвестно — DJI Fly впереди", "unknown — DJI Fly active")
                FlightState.probed -> t("дрона нет (FLYC молчит)", "no drone (FLYC silent)")
                else -> t("читаю…", "reading…")
            })
        deviceView.text = sb.toString()
        if (::litoSw.isInitialized) litoSw.isChecked = AppState.litoMode
        // Keep the main-screen auto-start switches in sync (Services page / diag can change them).
        if (::mainKaAuto.isInitialized) {
            mainKaAuto.isChecked = AppState.autoKeepalive
            mainDiagAuto.isChecked = AppState.autoDiag
            mainOvAuto.isChecked = AppState.autoOverlay
        }
        // Show/refresh the web-dashboard URL in the auto-start block while diag runs.
        if (::mainDiagUrl.isInitialized) updateDiagUrl(DiagServer.isRunning)
        if (::linkDot.isInitialized) when {
            FlightState.connected == true || DroneLink.connected() -> { linkDot.text = t("дрон ●", "drone ●"); linkDot.setTextColor(GREEN) }
            FlightState.connected == false -> { linkDot.text = t("дрон ○", "drone ○"); linkDot.setTextColor(CORAL) }
            else -> { linkDot.text = t("дрон …", "drone …"); linkDot.setTextColor(AMBER) }   // unknown (Fly active / not read yet)
        }
        summary.text = "⚡ " + (if (d.code.isNotEmpty()) d.name else "DJI_FCC_GPSOFF") +
            (if (rc.code.isNotEmpty()) "  ·  ${rc.name}" else "")
    }

    private fun renderState() {
        fun set(v: TextView, sw: Switch, b: Boolean?, onTxt: String, offTxt: String) {
            when (b) {
                true -> { v.text = onTxt; v.setTextColor(GREEN); sw.isChecked = true }
                false -> { v.text = offTxt; v.setTextColor(MUTED); sw.isChecked = false }
                null -> { v.text = "?"; v.setTextColor(AMBER) }
            }
        }
        set(ledVal, ledSw, FlightState.ledOn, t("вкл", "on"), t("выкл", "off"))
        set(gpsVal, gpsSw, FlightState.gpsOn, t("вкл", "on"), t("выкл", "off"))
        // ATTI is the "on" position now: pass the inverted cine flag (ATTI ⇒ true).
        set(modeVal, modeSw, FlightState.cine?.let { !it }, "ATTI", "Cine")
        // Switches always stay enabled: a "no read-back" drone still takes blind
        // writes, per the honesty note above.
        stateNote.text = when {
            !ForegroundGate.readsAllowed() -> t("DJI Fly впереди — чтение приостановлено (переключитесь в это приложение)",
                                                "DJI Fly is active — reads paused (switch to this app to read state)")
            FlightState.connected == true -> t("вживую · обновлено ${(System.currentTimeMillis() - FlightState.lastMs) / 1000} с назад",
                                              "live · updated ${(System.currentTimeMillis() - FlightState.lastMs) / 1000}s ago")
            FlightState.readsWork -> t("последнее известное — сейчас чтения нет (дрон выключен? нажмите «Прочитать состояние»)",
                                       "last known — no live read now (drone off? tap Read state)")
            StartupProbe.readsFailed -> t("этот дрон не отвечал на чтения — переключатели пишут вслепую, состояние неизвестно",
                                          "this drone did not answer reads — switches write blind, state unknown")
            else -> t("читаю…", "reading…")
        }
    }

    /**
     * DJI Fly foreground vs not-in-front, from the accessibility gate — NO socket.
     * a11y only reports which package is foreground; it CANNOT tell a backgrounded
     * Fly from a stopped one. The old version probed 40007 to distinguish them, but
     * any 40007 touch churns the RC↔drone link, so a status label must not cost a
     * socket. So we honestly say "not in front" (could be background OR stopped)
     * rather than claim "background" for a Fly that may be dead.
     */
    private fun flyStatus(): String = when {
        ForegroundGate.isFlyForeground -> t("на переднем плане", "foreground")
        !ForegroundGate.accessibilityConnected -> t("неизвестно (включите спец. возможности)", "unknown (enable a11y)")
        // a11y can't tell a backgrounded Fly from a stopped one, and neither can usage
        // stats (proven on RC2: bg and killed both end with ACTIVITY_STOPPED, no distinct
        // event) — so we don't claim one. Any socket probe would churn the link.
        else -> t("не впереди (фон/остановлен)", "not in front (bg/stopped)")
    }

    // ---------- updates ----------
    /** Installed versionName, the baseline every release tag is compared against. */
    private fun currentVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    /** Launch check: opt-in, and at most once per [Updater.CHECK_INTERVAL_MS], so
     *  starting the app repeatedly does not hammer the GitHub API. */
    private fun maybeCheckUpdates() {
        if (!AppState.autoUpdateCheck) return
        if (System.currentTimeMillis() - AppState.lastUpdateCheckMs < Updater.CHECK_INTERVAL_MS) return
        checkUpdates(manual = false)
    }

    /**
     * Check GitHub. A [manual] check reports every outcome; the automatic one stays
     * silent unless there is actually something to install — an unreachable network
     * on a controller that is often offline is not worth a popup.
     */
    private fun checkUpdates(manual: Boolean) {
        if (manual) setStatus(t("⏳ проверяю обновления…", "⏳ checking for updates…"))
        scope.launch {
            val res = Updater.check(currentVersion(), AppState.updatePrerelease)
            AppState.setLastUpdateCheck(applicationContext, System.currentTimeMillis())
            runOnUiThread {
                when (res) {
                    is Updater.Result.Available -> showUpdateDialog(res.release, res.current)
                    is Updater.Result.UpToDate -> if (manual)
                        setStatus(t("✅ установлена последняя версия (${res.current})",
                                    "✅ you are on the latest version (${res.current})"))
                    is Updater.Result.None -> if (manual) setStatus("ℹ " + res.reason)
                    is Updater.Result.Failed -> if (manual) setStatus("⚠ " + res.reason)
                }
            }
        }
    }

    /** Show what the release actually says, then let the user take it or leave it. */
    private fun showUpdateDialog(r: Updater.Release, current: String) {
        val head = TextView(this).apply {
            text = t("Версия ${r.version}", "Version ${r.version}") +
                (if (r.prerelease) t("  · предварительная", "  · pre-release") else "") +
                "  ·  " + Updater.sizeLabel(r.apkSize) +
                "\n" + t("Установлена: ", "Installed: ") + current
            setTextColor(INK); textSize = 12.5f; setPadding(dp(4), 0, dp(4), dp(8))
        }
        // The release body is markdown; shown as plain text on purpose — rendering
        // it would mean a markdown parser for a changelog nobody styles anyway.
        val notes = TextView(this).apply {
            text = r.notes.ifBlank { t("(автор не оставил описания)", "(no release notes)") }
            setTextColor(MUTED); textSize = 12f; setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), 0)
            addView(head)
            addView(ScrollView(this@MainActivity).apply { addView(notes) },
                LinearLayout.LayoutParams(MATCH_PARENT, dp(150)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Доступно обновление", "Update available") + " — " + r.title)
            .setView(box)
            .setNegativeButton(t("Позже", "Later"), null)
            .setPositiveButton(t("Скачать и установить", "Download and install")) { _, _ -> downloadAndInstall(r) }
            .show()
    }

    private fun downloadAndInstall(r: Updater.Release) {
        setStatus(t("⏳ скачиваю ${r.tag}…", "⏳ downloading ${r.tag}…"))
        scope.launch {
            var shown = -1
            val file = Updater.download(applicationContext, r) { p ->
                // Only redraw on whole percent steps: the status line is a TextView
                // on the main thread, and a 64 KB buffer fires this hundreds of times.
                val pct = (p * 100).toInt()
                if (pct / 5 != shown) {
                    shown = pct / 5
                    setStatus(t("⏳ скачиваю ${r.tag}… $pct%", "⏳ downloading ${r.tag}… $pct%"))
                }
            }
            runOnUiThread {
                if (file == null) {
                    setStatus(t("⚠ не удалось скачать обновление — проверьте сеть",
                                "⚠ could not download the update — check the network"))
                    return@runOnUiThread
                }
                if (!canInstallPackages()) {
                    // Without this grant the installer refuses silently, so ask first.
                    setStatus(t("⚠ разрешите установку из этого источника, затем повторите",
                                "⚠ allow installs from this source, then retry"))
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")))
                    }
                    return@runOnUiThread
                }
                setStatus(t("⏳ запускаю установку…", "⏳ starting the install…"))
                Updater.install(applicationContext, file) { msg -> setStatus(msg) }
            }
        }
    }

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.canRequestPackageInstalls() else true

    // ---------- lifecycle / permissions ----------
    override fun onNewIntent(intent: Intent?) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent) }
    override fun onResume() {
        // No refreshNow here. Coming back to this screen used to force a live-state read
        // on 40007 — the port DJI Fly mirrors video on — which is half of why switching
        // between the two apps costs FCC (2026-08-19). State is read when asked for: the
        // "Прочитать состояние" button, or after a write.
        super.onResume(); foreground = true; syncSwitches()
    }
    /**
     * Leaving this screen stops 40007 reads **immediately**, whatever we are leaving for.
     *
     * `foreground = false` only stops the loop from starting the NEXT burst; a burst already
     * in flight keeps going until [ForegroundGate] closes, and that gate is driven by an
     * accessibility window event which arrives only once the next app is already up. If the
     * next app is DJI Fly, our read is competing on 40007 exactly while Fly re-establishes
     * its session — which is why leaving to Fly from here cost it a link while leaving to
     * Fly from the launcher never did: the launcher isn't running our 15-second read burst.
     *
     * onPause fires before the incoming activity resumes, so closing the gate here beats the
     * window event by the whole switch. [ParamRead] re-checks the gate every ~120 ms, so a
     * burst in flight aborts almost at once. Costs nothing when we are only going to the
     * launcher — the loop is stopped anyway — and any real window event supersedes it.
     */
    override fun onPause() {
        super.onPause()
        foreground = false
        ForegroundGate.expectFlyForeground(LEAVING_BLOCK_MS)
    }

    private fun syncSwitches() {
        if (::kaSw.isInitialized) {
            kaSw.isChecked = FccKeepaliveService.running
            diagSw.isChecked = DiagServer.isRunning
            ovSw.isChecked = OverlayService.running
            updateDiagUrl(DiagServer.isRunning)
        }
        syncFccButtonLabel()
    }

    /**
     * Say on the button itself when pressing it is unnecessary.
     *
     * With auto-FCC running, the keepalive applies FCC on every connect and re-applies it
     * after a relink, so the manual press is a no-op that only looks like the main action —
     * the biggest, greenest control on the page. Rather than hide or disable it (it is still
     * useful for forcing an immediate apply), the label admits the situation.
     */
    private fun syncFccButtonLabel() = setFccButtonLabel(FccKeepaliveService.running)

    private fun setFccButtonLabel(auto: Boolean) {
        if (!::fccApplyBtn.isInitialized) return
        fccApplyBtn.text = if (auto)
            t("⚡ Включить FCC (сейчас режим авто, нажимать не обязательно)",
              "⚡ Enable FCC (auto mode is on — pressing is optional)")
        else t("⚡ Включить FCC", "⚡ Enable FCC")
        fccApplyBtn.textSize = if (auto) 11f else 12f
    }
    private fun updateDiagUrl(on: Boolean) {
        val txt = if (on) t("▶ открыть на ПК:  ", "▶ open on PC:  ") + "http://${localIp()}:${DiagServer.PORT}/" else ""
        if (::mainDiagUrl.isInitialized) { mainDiagUrl.text = txt; mainDiagUrl.visibility = if (on) View.VISIBLE else View.GONE }
        if (::diagUrl.isInitialized) diagUrl.text = txt
    }

    private fun applyAutoStart() {
        if (AppState.autoKeepalive) FccKeepaliveService.start(this)
        if (AppState.autoDiag) DiagService.start(this)
        if (AppState.autoOverlay && (Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this))) OverlayService.start(this)
    }

    private fun handleIntent(i: Intent?) {
        when (i?.getStringExtra(EXTRA_ACTION)) {
            ACTION_GRANT_RECORDS -> requestRecordsAccess()
            ACTION_GRANT_MOVIES -> requestMoviesAccess()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val want = android.content.ComponentName(this, DjiFlyAccessibilityService::class.java).flattenToString()
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return flat.split(':').any { it.equals(want, ignoreCase = true) }
    }
    /** Ask for POST_NOTIFICATIONS on Android 13+, so the foreground-service
     *  notifications (and their tap-to-open) are actually shown. */
    private fun maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF) }
        }
    }

    private fun maybePromptAccessibility() {
        if (isAccessibilityEnabled() || AppState.a11yPrompted) return
        AppState.setA11yPrompted(this, true)
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Включить спец. возможности?", "Enable accessibility?"))
            .setMessage(t("Читает название модели дрона с экрана DJI Fly и отслеживает, какое приложение впереди, чтобы чтения " +
                          "никогда не мешали видео Fly. Включите «DJI_FCC_GPSOFF — model & foreground» в настройках спец. возможностей. Данные не покидают устройство.",
                          "Reads the drone's model name from DJI Fly's screen and tracks the foreground app so reads " +
                          "never disturb Fly's video. Enable \"DJI_FCC_GPSOFF — model & foreground\" in Accessibility settings. Stays on device."))
            .setPositiveButton(t("Открыть настройки", "Open settings")) { _, _ -> runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
            .setNegativeButton(t("Позже", "Later"), null)
            .show()
    }

    private fun requestMoviesAccess() {
        val perm = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(perm), REQ_READ_MEDIA); setStatus(t("Разрешите доступ к медиа, затем обновите список записей",
                                                                          "Allow media access, then reload recordings"))
        } else setStatus(t("доступ к медиа уже выдан", "media access already granted"))
    }
    private fun requestRecordsAccess() {
        if (Build.VERSION.SDK_INT in 23..32 &&
            checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQ_READ_STORAGE); return
        }
        openRecordsPicker()
    }
    private fun openRecordsPicker() {
        setStatus(t("Выберите Android/data/dji.go.v5/files/FlightRecord", "Pick Android/data/dji.go.v5/files/FlightRecord"))
        runCatching { startActivityForResult(FlightRecords.grantIntent(), REQ_RECORDS) }
            .onFailure { setStatus(t("⚠ нет приложения для выбора папки: ${it.message}", "⚠ no folder picker: ${it.message}")) }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQ_READ_STORAGE -> openRecordsPicker()
            REQ_READ_MEDIA -> setStatus(if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
                t("доступ к медиа выдан", "media granted") else t("доступ к медиа отклонён", "media denied"))
        }
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode == REQ_DHP) {
            if (resultCode != RESULT_OK || uri == null) { setStatus(t("файл не выбран", "no file picked")); return }
            scope.launch {
                val res = runCatching {
                    val json = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("cannot read")
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "catalog"
                    ParamCatalog.load(json, name)
                }
                runOnUiThread {
                    res.onSuccess { renderParams() }   // renderParams reports the load in the footer
                        .onFailure { setStatus(t("⚠ разбор файла не удался: ${it.message}", "⚠ parse failed: ${it.message}")) }
                }
            }
            return
        }
        if (requestCode != REQ_RECORDS) return
        if (resultCode != RESULT_OK || uri == null) { setStatus(t("доступ к логам полётов не выдан", "flight-record access not granted")); return }
        scope.launch { val r = FlightRecords.persist(applicationContext, uri); runOnUiThread { setStatus("🗂 $r") } }
    }
    private fun ensureOverlay(): Boolean {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            setStatus(t("Выдайте «Поверх других приложений» и снова включите меню",
                        "Grant 'Display over other apps', then toggle Overlay again"))
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            return false
        }
        return true
    }

    // ---------- building blocks ----------
    private fun card(title: String, body: View): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pillBg(CARD, dp(16)); setPadding(dp(12), dp(9), dp(12), dp(11)) }
        c.addView(TextView(this).apply { text = title; setTextColor(MUTED); textSize = 12f; setPadding(0, 0, 0, dp(7)) })
        c.addView(body)
        c.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(9) }
        return c
    }

    /** A card with no heading, and the vertical room a heading would have taken given back.
     *  Used where the contents already say what the card is — a title there costs about
     *  25dp and buys nothing, and on this screen that is the difference between the main
     *  page fitting and needing a scroll. */
    private fun card(body: View): View {
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = pillBg(CARD, dp(16)); setPadding(dp(12), dp(8), dp(12), dp(8)) }
        c.addView(body)
        c.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(7) }
        return c
    }
    private fun rowc(vararg views: View): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, v) in views.withIndex()) {
            val lp = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f); if (i > 0) lp.leftMargin = dp(8)
            r.addView(v, lp)
        }
        return r
    }
    private fun cell(label: String, control: View): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(TextView(this).apply { text = label; setTextColor(INK); textSize = 13f }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        r.addView(control)
        return r
    }
    /** A named service row: bold title + a one-line "what it does" + the control. */
    private fun serviceItem(name: String, desc: String, control: View): View {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = name; setTextColor(INK); textSize = 14f; typeface = Typeface.DEFAULT_BOLD },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        top.addView(control)
        col.addView(top)
        col.addView(TextView(this).apply { text = desc; setTextColor(MUTED); textSize = 11f; setLineSpacing(dp(2).toFloat(), 1f); setPadding(0, dp(2), dp(44), 0) })
        return col
    }
    private fun smallBtn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 12f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(34); minimumHeight = dp(34); minWidth = 0; minimumWidth = 0
        setPadding(dp(6), dp(5), dp(6), dp(5)); background = pillBg(color, dp(12)); stateListAnimator = null
        setOnClickListener { onClick() }
    }
    private fun serviceSwitch(onSet: (Boolean) -> Unit) = mkSwitch(false).apply { setOnClickListener { onSet(isChecked) } }
    private fun mkSwitch(checked: Boolean) = Switch(this).apply {
        isChecked = checked
        thumbTintList = ColorStateList.valueOf(Color.WHITE)
        trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GREEN, SLATE))
    }
    private fun pillBg(color: Int, radius: Int): Drawable {
        val content = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius.toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), content, mask)
    }
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)
    private fun setStatus(s: String) { scope.launch(Dispatchers.Main) { status.text = s } }
    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "0.0.0.0"
    } catch (e: Exception) { "0.0.0.0" }

    override fun onDestroy() {
        super.onDestroy()
        loopJob?.cancel()
        scope.cancel()                 // cancel every click-handler coroutine, not just the loop
        DiagLog.listener = null
        // Do NOT stop the native channel here: the Activity never started it (it
        // uses one-shot sockets), and the keepalive/capture may still own it.
        f.disconnect()                 // no-op unless this Activity actually connected
    }

    companion object {
        const val EXTRA_ACTION = "action"
        const val ACTION_GRANT_RECORDS = "grant_records"
        const val ACTION_GRANT_MOVIES = "grant_movies"
        /** Set by [SetupWizardActivity]: it already started exactly what the user
         *  picked, so this launch must not run [applyAutoStart] on top of it. */
        const val EXTRA_SKIP_AUTOSTART = "skip_autostart"
        private const val REQ_RECORDS = 4001
        private const val REQ_READ_STORAGE = 4002
        private const val REQ_READ_MEDIA = 4003
        private const val REQ_DHP = 4004
        private const val REQ_NOTIF = 4005
        /** UI re-render cadence — socket-free (renders cached state + passive
         *  serial/model updates). Live-state reads that open 40007 are event-driven
         *  ([refreshNow]), never on this tick. */
        private const val RENDER_INTERVAL_MS = 3_000L
        /** Max cadence of Log-page re-renders (coalesces bursty log output). */
        private const val LOG_RENDER_THROTTLE_MS = 400L
        /** Lines the Log page formats per render. Comfortably more than the ~24 000-character
         *  window the view shows, so nothing visible is ever missing, and far fewer than the
         *  3000-entry buffer that used to be formatted in full. */
        private const val LOG_TAIL_LINES = 400
        /** How long reads stay blocked after this screen loses the foreground. Covers a
         *  full app switch; a real window event lifts it sooner in either direction. */
        private const val LEAVING_BLOCK_MS = 3_000L
        /** Cadence of the live status read (connect + LED/GPS/mode), ONLY while our app
         *  is in front (Fly backgrounded). One batched 40007 socket, strict abort. */
        /** Coalesce typing in the params search box (see [paramRenderTick]). */
        private const val PARAM_SEARCH_DEBOUNCE_MS = 200L
    }
}
