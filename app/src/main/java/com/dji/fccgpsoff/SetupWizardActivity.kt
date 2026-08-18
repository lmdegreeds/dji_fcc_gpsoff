package com.dji.fccgpsoff

import android.app.Activity
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
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/**
 * First-run setup wizard, bilingual (RU/EN, seeded from the device locale and
 * switchable in the header).
 *
 * It exists because every capability of this app that a user actually notices is
 * behind a permission Android will not grant from a manifest entry: accessibility
 * (the read gate + model scraping), "install unknown apps" (self-update), a SAF
 * folder grant (DJI Fly's flight logs), media access (screen recordings), and
 * "draw over other apps" (the floating menu). Each step therefore says WHY the
 * permission is wanted and WHAT TO TAP on the system screen that opens — the
 * settings screens are several levels deep and easy to get lost in.
 *
 * Everything except the welcome page is skippable; the app is designed to work
 * (with honest degradations) when every optional grant is declined.
 *
 * Re-runnable from the ⋮ menu, so the explanations stay reachable later.
 */
class SetupWizardActivity : Activity() {

    private val BG = 0xFF12141C.toInt(); private val CARD = 0xFF1B2030.toInt()
    private val INK = 0xFFECEEF3.toInt(); private val MUTED = 0xFF8A93A6.toInt()
    private val GREEN = 0xFF22C993.toInt(); private val BLUE = 0xFF4C6FFF.toInt()
    private val SLATE = 0xFF2A3042.toInt(); private val AMBER = 0xFFF5A623.toInt()

    private enum class Step { WELCOME, A11Y, INSTALL, FILES, SERVICES }

    private var step = Step.WELCOME

    // Service choices — "start now" and "add to autostart" are independent, so a
    // user can arm autostart without starting anything in this session, or try a
    // service once without arming it.
    private var nowKeep = true;    private var autoKeep = true
    private var nowOverlay = true; private var autoOverlay = true
    private var nowDiag = false;   private var autoDiag = false

    private lateinit var body: LinearLayout
    private lateinit var title: TextView
    private lateinit var crumb: TextView
    private lateinit var langBtn: Button
    /** Pinned row of grant buttons, between the scrolling body and the nav footer. */
    private lateinit var actions: LinearLayout
    private lateinit var footer: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        AppState.load(applicationContext)
        nowKeep = AppState.autoKeepalive; autoKeep = AppState.autoKeepalive
        nowOverlay = AppState.autoOverlay; autoOverlay = AppState.autoOverlay
        nowDiag = AppState.autoDiag; autoDiag = AppState.autoDiag
        // Resume where the user was. Granting a special app access (notably "install
        // unknown apps") restarts our process, so coming back from that settings
        // screen re-enters onCreate — without this the wizard fell back to page 1.
        step = Step.values().getOrElse(AppState.wizardStep) { Step.WELCOME }

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD); setPadding(dp(14), dp(8), dp(8), dp(8))
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title = TextView(this).apply { setTextColor(INK); textSize = 15f; typeface = Typeface.DEFAULT_BOLD }
        crumb = TextView(this).apply { setTextColor(MUTED); textSize = 11f }
        titleCol.addView(title); titleCol.addView(crumb)
        bar.addView(titleCol, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Sets the language for the WHOLE app, not just the wizard.
        langBtn = smallBtn(langLabel(), SLATE) {
            AppState.setUiRu(this, !AppState.uiRu); langBtn.text = langLabel(); render()
        }
        bar.addView(langBtn)
        root.addView(bar)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))

        // Grant buttons live OUTSIDE the ScrollView. On the RC's short landscape
        // screen the explanation on the accessibility and files steps is taller
        // than the viewport, and an inline button ended up below the fold — the one
        // control the step exists for. Pinned here it is visible whatever the text
        // does, in any language.
        actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD); setPadding(dp(12), dp(8), dp(12), dp(2))
            visibility = View.GONE
        }
        root.addView(actions)

        footer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(CARD); setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        root.addView(footer)

        setContentView(root)
        render()
    }

    /** Grants change while we are in a system settings screen, so re-render on return. */
    override fun onResume() { super.onResume(); render() }

    // ------------------------------------------------------------------ render

    /** Label of the toggle: it offers the OTHER language. */
    private fun langLabel() = if (AppState.uiRu) "EN" else "РУ"

    private fun render() {
        body.removeAllViews(); footer.removeAllViews()
        actions.removeAllViews(); actions.visibility = View.GONE   // steps that add none keep no empty strip
        title.text = t("Настройка", "Setup")
        crumb.text = t("Шаг ", "Step ") + "${step.ordinal + 1}/${Step.values().size}"
        when (step) {
            Step.WELCOME -> welcome()
            Step.A11Y -> a11y()
            Step.INSTALL -> install()
            Step.FILES -> files()
            Step.SERVICES -> services()
        }
    }

    private fun welcome() {
        head(t("${AppCopy.NAME} — что это умеет", "${AppCopy.NAME} — what it does"))
        // Shared with the About page via AppCopy, so the two never disagree.
        bullets(*(if (AppState.uiRu) AppCopy.FEATURES_RU else AppCopy.FEATURES_EN).toTypedArray())
        note(t("Мастер займёт минуту. Он объяснит, зачем нужно каждое разрешение и что нажать на системном экране. " +
               "Всё, кроме этой страницы, можно пропустить — приложение работает и без необязательных разрешений.",
               "The wizard takes a minute. It explains why each permission is wanted and what to tap on the system " +
               "screen that opens. Everything after this page is skippable — the app works without the optional grants."))
        footerBtns(next = t("Начать", "Start"))
    }

    private fun a11y() {
        val on = isAccessibilityEnabled()
        head(t("Специальные возможности", "Accessibility"), t("рекомендуется", "recommended"))
        status(on, t("сервис включён", "service enabled"), t("сервис выключен", "service not enabled"))
        para(t("Зачем: пульт отдаёт видео DJI Fly через порт 40007. Любое чтение оттуда подвешивает картинку Fly " +
               "на 1–2 секунды. Этот сервис сообщает приложению, какое окно сейчас на экране, и чтения выполняются " +
               "только пока Fly не на переднем плане — то есть никогда во время полёта. Он же считывает название " +
               "модели дрона прямо с экрана Fly и делает скриншоты для веб-дашборда.",
               "Why: the controller mirrors DJI Fly's video on port 40007. Any read there freezes Fly's picture for " +
               "1–2 seconds. This service tells the app which window is on screen, so reads run only while Fly is not " +
               "in front — that is, never during a flight. It also reads the aircraft model straight off Fly's screen " +
               "and takes screenshots for the web dashboard."))
        steps(t("Что нажать на открывшемся экране:", "What to tap on the screen that opens:"),
            t("Найдите раздел «Скачанные приложения» / «Установленные сервисы»",
              "Find the \"Downloaded apps\" / \"Installed services\" section"),
            t("Выберите «DJI_FCC_GPSOFF — model & foreground»", "Pick \"DJI_FCC_GPSOFF — model & foreground\""),
            t("Включите переключатель и подтвердите «Разрешить» / «OK»", "Turn the switch on and confirm \"Allow\" / \"OK\""),
            t("Вернитесь назад — сюда", "Come back here"))
        note(t("Без этого приложение работает, но: гейт чтения по умолчанию разрешает (чтение может дёрнуть видео Fly, " +
               "если запустить его вручную при Fly на переднем плане), а авто-FCC идёт слепым путём вместо ожидания " +
               "подтверждения линка. На плавающее меню и на любые записи параметров это не влияет.",
               "Without it the app still works, but: the read gate defaults to allow (a manual read can blip Fly's video " +
               "if Fly is in front), and auto-FCC uses its blind path instead of waiting for a confirmed link. The " +
               "floating menu and all parameter writes are unaffected."))
        act(t("Открыть настройки", "Open settings")) {
            open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        footerBtns(back = true, skip = !on)
    }

    private fun install() {
        val on = canInstallPackages()
        head(t("Установка обновлений", "Installing updates"), t("необязательно", "optional"))
        status(on, t("разрешено", "allowed"), t("не разрешено", "not allowed"))
        para(t("Зачем: приложение умеет само скачивать новую версию из релизов GitLab и запускать её установку. " +
               "Без этого разрешения Android откажется открыть скачанный APK, и обновляться придётся вручную через " +
               "файловый менеджер. Разрешение относится только к этому приложению; каждую установку вы всё равно " +
               "подтверждаете отдельно.",
               "Why: the app can download a new version from GitLab releases and hand it to the installer. Without this " +
               "permission Android refuses to open the downloaded APK and you have to update by hand through a file " +
               "manager. The permission covers this app only; you still confirm every install separately."))
        steps(t("Что нажать на открывшемся экране:", "What to tap on the screen that opens:"),
            t("Включите «Разрешить установку из этого источника»", "Turn on \"Allow from this source\""),
            t("Вернитесь назад — сюда", "Come back here"))
        act(t("Открыть настройки", "Open settings")) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } else {
                // Pre-Oreo the setting is global, not per-app.
                open(Intent(Settings.ACTION_SECURITY_SETTINGS))
            }
        }
        footerBtns(back = true, skip = true)
    }

    private fun files() {
        val recs = AppState.recordsTree != null
        val movies = hasMediaPermission()
        head(t("Логи полётов и записи экрана", "Flight logs and screen recordings"), t("необязательно", "optional"))

        sub(t("Логи полётов DJI Fly", "DJI Fly flight logs"))
        status(recs, t("папка выбрана", "folder granted"), t("папка не выбрана", "folder not granted"))
        para(t("Зачем: чтобы скачивать логи полётов через веб-дашборд. Они лежат в приватной папке DJI Fly, а Android 11 " +
               "отдаёт такую папку только через системный выбор папки — разово, с вашим подтверждением.",
               "Why: to download flight logs through the web dashboard. They live in DJI Fly's private folder, and " +
               "Android 11 hands that folder over only through the system folder picker — once, with your confirmation."))
        steps(t("Что нажать в открывшемся выборе папки:", "What to tap in the folder picker that opens:"),
            t("Он откроется сразу на Android/data/dji.go.v5/files/FlightRecord",
              "It opens pre-aimed at Android/data/dji.go.v5/files/FlightRecord"),
            t("Нажмите «Использовать эту папку» и подтвердите «Разрешить»",
              "Tap \"Use this folder\" and confirm \"Allow\""))
        act(t("Выбрать папку логов", "Pick the log folder")) {
            if (Build.VERSION.SDK_INT <= 32 &&
                checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), REQ_STORAGE)
            } else openRecordsPicker()
        }

        sub(t("Записи экрана пульта", "Controller screen recordings"))
        status(movies, t("доступ есть", "access granted"), t("доступа нет", "no access"))
        para(t("Зачем: чтобы смотреть и скачивать записи экрана пульта через веб-дашборд. Они лежат в общей папке " +
               "Movies — во внутренней памяти и на SD-карте.",
               "Why: to watch and download the controller's screen recordings through the web dashboard. They live in " +
               "the public Movies folder — on internal storage and on the SD card."))
        steps(t("Что нажать:", "What to tap:"),
            t("В системном запросе выберите «Разрешить»", "Choose \"Allow\" in the system prompt"))
        act(t("Разрешить доступ к видео", "Allow video access")) {
            // Below API 33 both halves of this step ask for READ_EXTERNAL_STORAGE,
            // so granting it for the flight logs already covers the recordings —
            // the system then has nothing to ask and shows no dialog. Say so
            // instead of looking broken (the button used to be a silent no-op).
            if (hasMediaPermission())
                toast(t("Доступ к видео уже выдан — записи экрана видны.",
                        "Video access is already granted — screen recordings are visible."))
            else requestPermissions(arrayOf(mediaPermission()), REQ_MEDIA)
        }
        footerBtns(back = true, skip = true)
    }

    private fun services() {
        head(t("Что запустить", "What to run"))
        para(t("«Сейчас» запускает сервис прямо после мастера. «Автозапуск» поднимает его при старте приложения и " +
               "после перезагрузки пульта. Эти переключатели независимы, и всё это можно изменить позже на странице " +
               "Services.",
               "\"Now\" starts the service right after the wizard. \"Autostart\" brings it up on app launch and after a " +
               "controller reboot. The two are independent, and all of it can be changed later on the Services page."))

        serviceRow("⚡ " + t("Авто-FCC", "Auto FCC"),
            t("Применяет FCC, как только дрон подключился, и удерживает его после релинка. Главная причина ставить " +
              "это приложение.",
              "Applies FCC as soon as the aircraft links up and keeps it across relinks. The main reason to install this."),
            nowKeep, autoKeep, { nowKeep = it }, { autoKeep = it })

        val overlayOk = Settings.canDrawOverlays(this)
        serviceRow("🎈 " + t("Плавающее меню", "Floating menu"),
            t("Кнопка ≡ поверх DJI Fly с тумблерами GPS / LED / режим полёта.",
              "A ≡ handle over DJI Fly with GPS / LED / flight-mode toggles."),
            nowOverlay, autoOverlay, { nowOverlay = it }, { autoOverlay = it })
        if (!overlayOk) {
            note(t("Нужно разрешение «Поверх других приложений» — без него меню не появится.",
                   "Needs the \"Display over other apps\" permission — without it the menu will not appear."))
            act(t("Выдать разрешение", "Grant permission")) {
                open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        }

        serviceRow("🌐 " + t("Веб-дашборд", "Web dashboard"),
            t("HTTP-сервер на :8899 для браузера в той же сети. Без пароля — включайте только в доверенной сети.",
              "An HTTP server on :8899 for a browser on the same network. No password — enable it on a trusted LAN only."),
            nowDiag, autoDiag, { nowDiag = it }, { autoDiag = it })

        footerBtns(back = true, next = t("Готово", "Done"))
    }

    // ------------------------------------------------------------- UI helpers

    private fun head(text: String, badge: String? = null) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(TextView(this).apply {
            this.text = text; setTextColor(INK); textSize = 17f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (badge != null) row.addView(TextView(this).apply {
            this.text = badge; setTextColor(MUTED); textSize = 11f
            background = pillBg(SLATE, dp(9)); setPadding(dp(8), dp(3), dp(8), dp(3))
        })
        body.addView(row, lp(bottom = 8))
    }

    private fun sub(text: String) = body.addView(TextView(this).apply {
        this.text = text; setTextColor(INK); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
    }, lp(top = 10, bottom = 4))

    private fun para(text: String) = body.addView(TextView(this).apply {
        this.text = text; setTextColor(INK); textSize = 12.5f; setLineSpacing(dp(3).toFloat(), 1f)
    }, lp(bottom = 6))

    private fun note(text: String) = body.addView(TextView(this).apply {
        this.text = text; setTextColor(MUTED); textSize = 11.5f; setLineSpacing(dp(2).toFloat(), 1f)
    }, lp(top = 4, bottom = 4))

    private fun bullets(vararg items: String) {
        for (s in items) body.addView(TextView(this).apply {
            text = s; setTextColor(INK); textSize = 12.5f; setLineSpacing(dp(3).toFloat(), 1f)
        }, lp(bottom = 6))
    }

    /** A numbered "do this, then this" list — the part that keeps a user from
     *  getting lost several levels deep in Android settings. */
    private fun steps(lead: String, vararg items: String) {
        body.addView(TextView(this).apply {
            text = lead; setTextColor(MUTED); textSize = 11.5f
        }, lp(top = 6, bottom = 3))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = pillBg(CARD, dp(12))
            setPadding(dp(11), dp(8), dp(11), dp(9))
        }
        for ((i, s) in items.withIndex()) box.addView(TextView(this).apply {
            text = "${i + 1}.  $s"; setTextColor(INK); textSize = 12.5f; setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, if (i == 0) 0 else dp(4), 0, 0)
        })
        body.addView(box, lp(bottom = 8))
    }

    private fun status(ok: Boolean, yes: String, no: String) = body.addView(TextView(this).apply {
        text = if (ok) "✓ $yes" else "○ $no"
        setTextColor(if (ok) GREEN else AMBER); textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
    }, lp(bottom = 6))

    /** Add a grant button to the pinned row. Several share the row side by side —
     *  the files step has two, and both must be reachable without scrolling. */
    private fun act(label: String, onClick: () -> Unit) {
        actions.visibility = View.VISIBLE
        actions.addView(smallBtn(label, BLUE, onClick), lpH(right = 8))
    }

    /** One service with independent "now" and "autostart" switches. */
    private fun serviceRow(
        name: String, desc: String,
        now: Boolean, auto: Boolean,
        onNow: (Boolean) -> Unit, onAuto: (Boolean) -> Unit,
    ) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = pillBg(CARD, dp(14))
            setPadding(dp(12), dp(9), dp(12), dp(10))
        }
        box.addView(TextView(this).apply {
            text = name; setTextColor(INK); textSize = 14f; typeface = Typeface.DEFAULT_BOLD
        })
        box.addView(TextView(this).apply {
            text = desc; setTextColor(MUTED); textSize = 11.5f; setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(2), 0, dp(4))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(toggle(t("Сейчас", "Now"), now, onNow), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(toggle(t("Автозапуск", "Autostart"), auto, onAuto), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(row)
        body.addView(box, lp(bottom = 8))
    }

    private fun toggle(label: String, checked: Boolean, onSet: (Boolean) -> Unit): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        r.addView(TextView(this).apply { text = label; setTextColor(INK); textSize = 12.5f })
        r.addView(Switch(this).apply {
            isChecked = checked
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(GREEN, SLATE))
            setOnCheckedChangeListener { _, v -> onSet(v) }
        })
        return r
    }

    /**
     * Footer: Back on the left, then Skip / Next on the right. [skip] and [next]
     * are separate because on a grant page "skip" and "continue" mean different
     * things to the reader even though both just advance.
     */
    private fun footerBtns(back: Boolean = false, skip: Boolean = false, next: String? = null) {
        if (back) footer.addView(smallBtn(t("Назад", "Back"), SLATE) { go(-1) })
        footer.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        if (skip) footer.addView(smallBtn(t("Пропустить", "Skip"), SLATE) { go(1) }, lpH(right = 8))
        val isLast = step == Step.SERVICES
        footer.addView(smallBtn(next ?: t("Далее", "Next"), if (isLast) GREEN else BLUE) {
            if (isLast) finishWizard() else go(1)
        })
    }

    private fun go(dir: Int) {
        val all = Step.values()
        val i = (step.ordinal + dir).coerceIn(0, all.size - 1)
        step = all[i]
        AppState.setWizardStep(this, i)
        render()
    }

    // ------------------------------------------------------------------- done

    private fun finishWizard() {
        AppState.setAutoKeepalive(this, autoKeep)
        AppState.setAutoOverlay(this, autoOverlay)
        AppState.setAutoDiag(this, autoDiag)
        // The wizard asked about accessibility properly; don't let MainActivity's
        // one-line fallback dialog ask again on the very next screen.
        AppState.setA11yPrompted(this, true)
        AppState.setWizardDone(this, true)
        AppState.setWizardStep(this, 0)   // a later re-run from the ⋮ menu starts at the top

        if (nowKeep) FccKeepaliveService.start(this) else FccKeepaliveService.stop(this)
        if (nowDiag) DiagService.start(this) else DiagService.stop(this)
        if (nowOverlay && Settings.canDrawOverlays(this)) OverlayService.start(this)
        else OverlayService.stop(this)
        DiagLog.info("setup wizard: keepalive now=$nowKeep auto=$autoKeep · overlay now=$nowOverlay auto=$autoOverlay · diag now=$nowDiag auto=$autoDiag")

        // SKIP_AUTOSTART: we just applied exactly what the user chose, including
        // "autostart on but not now" — letting MainActivity re-run applyAutoStart
        // would override that and start it anyway.
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SKIP_AUTOSTART, true))
        finish()
    }

    // ------------------------------------------------------------ grant state

    private fun isAccessibilityEnabled(): Boolean {
        val want = android.content.ComponentName(this, DjiFlyAccessibilityService::class.java).flattenToString()
        val flat = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return flat.split(':').any { it.equals(want, ignoreCase = true) }
    }

    private fun canInstallPackages(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.canRequestPackageInstalls() else true

    /** Reading the Movies folders needs the granular media permission from API 33,
     *  and plain storage read below it. */
    private fun mediaPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasMediaPermission(): Boolean {
        return checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED
    }

    private fun open(i: Intent) {
        runCatching { startActivity(i) }.onFailure {
            toast(t("Не удалось открыть этот экран настроек: ", "Could not open that settings screen: ") + it.message)
        }
    }

    private fun openRecordsPicker() {
        runCatching { startActivityForResult(FlightRecords.grantIntent(), REQ_RECORDS) }
            .onFailure { toast(t("Выбор папки недоступен: ", "No folder picker: ") + it.message) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQ_STORAGE -> openRecordsPicker()
            // After a second refusal Android stops showing the dialog at all, so the
            // button would silently do nothing from then on. Send the user to the one
            // screen that can still grant it, rather than leaving them tapping.
            REQ_MEDIA -> if (!granted) {
                toast(t("Доступ не выдан. Откройте «Разрешения» и включите доступ к файлам / видео.",
                        "Not granted. Open Permissions and allow files / video access."))
                open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        render()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RECORDS) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) toast(FlightRecords.persist(applicationContext, uri))
        }
        render()
    }

    // ---------------------------------------------------------------- plumbing

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show()

    private fun smallBtn(text: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 12.5f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        minHeight = dp(36); minimumHeight = dp(36); minWidth = 0; minimumWidth = 0
        setPadding(dp(14), dp(6), dp(14), dp(6)); background = pillBg(color, dp(12)); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun pillBg(color: Int, radius: Int): Drawable {
        val content = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = radius.toFloat() }
        return RippleDrawable(ColorStateList.valueOf(0x66FFFFFF), content, mask)
    }

    private fun lp(top: Int = 0, bottom: Int = 0) =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top); bottomMargin = dp(bottom) }
    private fun lpH(right: Int = 0) =
        LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { rightMargin = dp(right) }
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    private companion object {
        const val REQ_RECORDS = 5001
        const val REQ_STORAGE = 5002
        const val REQ_MEDIA = 5003
    }
}
