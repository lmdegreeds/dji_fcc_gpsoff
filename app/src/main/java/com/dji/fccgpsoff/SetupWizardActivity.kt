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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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

    // BATTERY sits next to A11Y because they fail the same way: both are settings the app
 //   cannot grant itself, both are silent when missing, and both stop the app doing the one
 //   thing it exists for. Added 2026-08-20 after a controller was found running the
 //   keepalive with optimisation on.
    private enum class Step { WELCOME, A11Y, BATTERY, INSTALL, FILES, PROFILE, SERVICES }

    private var step = Step.WELCOME

    // Service choices. ONE flag each since 2026-08-19: a service is either wanted
    // or not, and "wanted" means both "run it" and "bring it back next launch".
    // Splitting those produced states nobody asked for — running but not armed,
    // armed but not running — and the wizard is the worst place to offer them.
    private var useKeep = true
    private var useOverlay = true
    private var useDiag = false

    /** True once the user picked the name profile BY HAND on the profile step. A
     *  hand-picked profile outranks the background probe that runs at finish — see
     *  [finishWizard]. Cleared by a successful auto-detect: then the aircraft chose. */
    private var profileManual = false

    /** For the profile step's detection run. Kept off the finish-time probe, which is
     *  deliberately detached (see [finishWizard]). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
        useKeep = AppState.autoKeepalive
        useOverlay = AppState.autoOverlay
        useDiag = AppState.autoDiag
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
            Step.BATTERY -> battery()
            Step.INSTALL -> install()
            Step.FILES -> files()
            Step.PROFILE -> profile()
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

    /**
     * The battery-optimisation exemption.
     *
     * Marked "recommended", not "optional": the app's main job is re-applying FCC while the
     * controller sits idle, and an optimised app is frozen exactly then. The failure is
     * completely silent — a frozen process cannot log the fact that it was frozen — so the
     * only symptom is FCC quietly reverting and a gap in the log.
     */
    private fun battery() {
        val on = Grants.batteryUnrestricted(this)
        head(t("Работа в фоне (батарея)", "Running in the background (battery)"),
             t("рекомендуется", "recommended"))
        status(on, t("ограничений нет", "not restricted"), t("Android может замораживать приложение",
                                                            "Android may freeze the app"))
        para(t("Зачем: авто-FCC живёт фоновой службой и переприменяет режим, пока пульт лежит с потухшим экраном. " +
               "Уведомления о службе для Android мало: при включённой оптимизации батареи он всё равно " +
               "замораживает процесс, FCC перестаёт переприменяться, и сказать об этом некому — замороженный " +
               "процесс не может даже записать строчку в лог. Видно только по провалу во времени.",
               "Why: auto-FCC lives in a foreground service and re-applies the mode while the controller lies idle " +
               "with its screen off. A service notification is not enough for Android: with battery optimisation on " +
               "it freezes the process anyway, FCC stops being re-applied, and nothing reports it — a frozen process " +
               "cannot even write a log line. The only trace is a gap in the timestamps."))
        steps(t("Что нажать на открывшемся экране:", "What to tap on the screen that opens:"),
            t("Если появился вопрос «Разрешить работу в фоне?» — нажмите «Разрешить»",
              "If a dialog asks \"Allow app to always run in the background?\" — tap Allow"),
            t("Если открылся список «Оптимизация батареи» — выберите DJI_FCC_GPSOFF и «Не оптимизировать»",
              "If a \"Battery optimisation\" list opened — pick DJI_FCC_GPSOFF and \"Don't optimise\""),
            t("Если открылся экран приложения — «Батарея» → «Без ограничений»",
              "If the app's own page opened — Battery → Unrestricted"),
            t("Вернитесь назад — сюда", "Come back here"))
        note(t("Разрешение касается только этого приложения и ни на что, кроме фоновой работы, не влияет. " +
               "Отозвать его можно там же в любой момент.",
               "The exemption covers this app only and changes nothing except its ability to run in the background. " +
               "It can be revoked in the same place at any time."))
        act(t("Разрешить работу в фоне", "Allow background running")) {
            // Three screens, best first: the one-tap dialog, the optimisation list, this
            // app's settings page. Vendor ROMs hide different ones, so try until one opens.
            if (!Grants.openBatterySettings(this))
                toast(t("Это устройство не открывает ни один из экранов оптимизации батареи",
                        "This device offers none of the battery-optimisation screens"))
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

    /**
     * Which spelling of the flight-controller parameter names this aircraft uses.
     *
     * This step exists because the app stopped probing on its own (2026-08-19): a
     * detection opens sockets on DJI Fly's port and needs a POWERED, LINKED drone,
     * and every automatic probe was costing FCC when the user switched between this
     * app and Fly. What remains is the finish-time probe, and at setup time the
     * aircraft is usually still in its bag — so the probe finds nothing and the
     * profile silently stays on the default.
     *
     * A wrong profile is not loud: every name-addressed write (LED, GPS, ATTI/Cine,
     * the parameter editor's own defaults) goes out under a name the firmware does
     * not have and is a no-op that still reports "sent". So it is asked here, with a
     * default, an auto-detect for when the drone IS up, and the plain statement that
     * Settings can change it later.
     *
     * FCC itself does not depend on any of this: the 07:30 / 09:27 frames are
     * addressed by radio receiver and hardware register, never by parameter name.
     */
    private fun profile() {
        head(t("Имена параметров дрона", "The aircraft's parameter names"))
        para(t("Разные аппараты DJI называют одни и те же параметры по-разному. Lito X1 использует короткие имена " +
               "(forearm_led_ctrl, gps_enable), большинство остальных — длинные формы g_config.*. " +
               "Приложение пишет ровно одно имя, поэтому выбор должен быть верным.",
               "DJI aircraft spell the same parameters differently. Lito X1 uses the short names " +
               "(forearm_led_ctrl, gps_enable); most others use the long g_config.* forms. The app writes exactly " +
               "one name, so this has to be right."))
        choice(t("Короткие (Lito X1)", "Short (Lito X1)"), t("Длинные (g_config.*)", "Long (g_config.*)"),
               AppState.litoMode) { lito ->
            AppState.setLito(this, lito)
            profileManual = true
            render()
        }
        status(StartupProbe.variant != null,
            t("определено опросом борта: ", "detected by asking the aircraft: ") +
                (if (StartupProbe.variant == true) t("короткие", "short") else t("длинные", "long")),
            if (profileManual) t("выбрано вручную", "picked by hand")
            else t("борт ещё не опрашивался — стоит значение по умолчанию", "the aircraft has not been asked yet — this is the default"))
        // What the same probe found besides the names. Shown here because this is the
        // only step that talks to the aircraft, so it is where a user finds out whether
        // the app can see the drone at all.
        val serial = AircraftSession.serial.ifEmpty { StartupProbe.serial }
        val model = AircraftIdentity.drone
        note(t("Серийный номер: ", "Serial number: ") + serial.ifEmpty { "—" } +
             "\n" + t("Модель дрона: ", "Aircraft model: ") +
             (if (model.code.isEmpty()) t("— (определяется опросом или с экрана DJI Fly)",
                                          "— (from the probe, or read off DJI Fly's screen)")
              else "${model.name} [${model.code}]"))
        note(t("На FCC этот выбор не влияет: кадры мощности и 5.8 ГГц адресуются радиоприёмнику и регистру, " +
               "а не по имени параметра. От него зависят LED, GPS, ATTI/Cine и запись из редактора параметров.",
               "It does not affect FCC: the power and 5.8 GHz frames are addressed by radio receiver and hardware " +
               "register, not by parameter name. It does affect LED, GPS, ATTI/Cine and writes from the parameter editor."))
        note(t("Это всегда можно изменить позже: ⋮ → Настройки → Профиль устройства.",
               "You can change this at any time later: ⋮ → Settings → Device profile."))
        act(t("🔍 Определить автоматически", "🔍 Detect automatically")) { detectProfile() }
        footerBtns(back = true)
    }

    /**
     * Run the real detection ([StartupProbe]) from the wizard, with its one
     * precondition stated first: the drone must be powered and linked, because the
     * flight controller only answers a hash read when it is. Saying that up front is
     * cheaper than a failed probe the user has to interpret.
     */
    private fun detectProfile() {
        android.app.AlertDialog.Builder(this)
            .setTitle(t("Определить профиль", "Detect the profile"))
            .setMessage(t("Включите дрон и дождитесь связи с пультом — без живого борта определять нечего: " +
                          "имя проверяется запросом к полётному контроллеру.\n\n" +
                          "DJI Fly при этом должен быть свёрнут или закрыт: это окно должно оставаться впереди. " +
                          "Занимает несколько секунд.",
                          "Power the aircraft on and wait for the link — with no live board there is nothing to " +
                          "detect: the name is checked by asking the flight controller.\n\n" +
                          "DJI Fly must be minimised or closed — this window has to stay in front. " +
                          "Takes a few seconds."))
            .setNegativeButton(t("Отмена", "Cancel"), null)
            .setPositiveButton(t("Определить", "Detect")) { _, _ -> runDetectProfile() }
            .show()
    }

    /**
     * The one probe the wizard runs on demand, and it asks for everything the app
     * can learn from a live aircraft in one visit: the serial ([StartupProbe]), the
     * name variant, and the model over DUML ([AircraftModelProbe]).
     *
     * All three come off the same sockets on DJI Fly's port, so doing them together
     * costs one burst instead of three — and the three answers are worth exactly one
     * trip out to the field with the drone powered up.
     */
    private fun runDetectProfile() {
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle(t("Определяю…", "Detecting…"))
            .setMessage(t("Опрашиваю борт: серийный номер, модель, имена параметров…",
                          "Asking the aircraft: serial number, model, parameter names…"))
            .setCancelable(false)
            .show()
        scope.launch {
            runCatching { StartupProbe.run(applicationContext) }
            // Model over DUML. Separate from StartupProbe because it is the one thing
            // that has a second source (DJI Fly's own screen, via accessibility) and so
            // is not part of the startup path's must-have set.
            val modelNote = runCatching { AircraftModelProbe.capture(Features(applicationContext)) }
                .getOrDefault("model probe failed")
            DiagLog.info("wizard detect: $modelNote")
            // Whatever the model probe resolved is worth keeping against this serial.
            runCatching { StartupProbe.rememberModel(applicationContext) }
            runOnUiThread {
                runCatching { dlg.dismiss() }
                val v = StartupProbe.variant
                // A successful detection is the aircraft's own answer, so it is no
                // longer a hand-picked value that has to outrank the finish-time probe.
                if (v != null) profileManual = false
                val serial = AircraftSession.serial.ifEmpty { StartupProbe.serial }
                val found = buildString {
                    append(t("Имена: ", "Names: ")).append(when (v) {
                        true -> t("короткие (Lito X1)", "short (Lito X1)")
                        false -> t("длинные g_config.*", "long g_config.*")
                        else -> t("не подтвердились", "not confirmed")
                    })
                    append(t("\nСерийный номер: ", "\nSerial number: ")).append(serial.ifEmpty { "—" })
                    append(t("\nМодель: ", "\nModel: ")).append(
                        AircraftIdentity.drone.code.ifEmpty { "" }
                            .let { if (it.isEmpty()) "—" else "${AircraftIdentity.drone.name} [$it]" })
                }
                toast(when {
                    v != null -> found
                    serial.isEmpty() ->
                        t("Дрон не ответил. Включите его, дождитесь связи и повторите — или выберите профиль вручную.",
                          "No answer from the aircraft. Power it on, wait for the link and retry — or pick a profile by hand.")
                    else -> found + t("\n\nБорт на связи, но ни одно имя не подтвердилось — выберите профиль вручную.",
                                      "\n\nThe board is linked but neither name was confirmed — pick a profile by hand.")
                })
                render()
            }
        }
    }

    private fun services() {
        head(t("Что включить", "What to enable"))
        para(t("Один переключатель на сервис: он запускает его сразу после мастера И поднимает при каждом старте " +
               "приложения, в том числе после перезагрузки пульта. Всё это можно изменить позже — авто-FCC и " +
               "плавающее меню на главной странице, веб-дашборд на странице «Диагностика».",
               "One switch per service: it starts the service right after the wizard AND brings it up on every app " +
               "launch, including after a controller reboot. All of it can be changed later — auto FCC and the " +
               "floating menu on the Main page, the web dashboard on the Diagnostics page."))

        serviceRow("⚡ " + t("Авто-FCC", "Auto FCC"),
            t("Применяет FCC, как только дрон подключился, и удерживает его после релинка. Главная причина ставить " +
              "это приложение.",
              "Applies FCC as soon as the aircraft links up and keeps it across relinks. The main reason to install this."),
            useKeep) { useKeep = it }

        val overlayOk = Settings.canDrawOverlays(this)
        serviceRow("🎈 " + t("Плавающее меню", "Floating menu"),
            t("Кнопка ≡ поверх DJI Fly с тумблерами GPS / LED / режим полёта.",
              "A ≡ handle over DJI Fly with GPS / LED / flight-mode toggles."),
            useOverlay) { useOverlay = it }
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
            useDiag) { useDiag = it }

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

    /** Two mutually exclusive options as pills; the chosen one is filled green.
     *  Rendered rather than toggled because a switch cannot say what "off" means when
     *  both positions are a real answer. */
    private fun choice(a: String, b: String, first: Boolean, onPick: (Boolean) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(smallBtn(a, if (first) GREEN else SLATE) { onPick(true) }, lpH(right = 8))
        row.addView(smallBtn(b, if (first) SLATE else GREEN) { onPick(false) })
        body.addView(row, lp(top = 2, bottom = 6))
    }

    /** One service, one switch: it both runs the service and arms its auto-start. */
    private fun serviceRow(name: String, desc: String, on: Boolean, onSet: (Boolean) -> Unit) {
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
        box.addView(toggle(t("Включить · и запускать с приложением", "Enable · and start with the app"), on, onSet))
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
        // The ONE place that still probes the aircraft by itself. StartupProbe opens ~15
        // sockets on 40007 (serial, name-variant, live state) and was removed from every
        // automatic path on 2026-08-19 because that burst costs FCC whenever the user
        // switches between this app and DJI Fly. Here it is worth it and safe: setup runs
        // once, with the wizard in front and DJI Fly not in use, and the app needs the
        // name-variant before any parameter write can be correct.
        // Deliberately NOT tied to this Activity's life: the wizard finishes by launching
        // MainActivity and closing, and cancelling the probe half-way would leave the
        // name-variant undetected with nothing left to detect it.
        // A profile the user picked BY HAND on the profile step outranks whatever this
        // probe concludes — and it can only be pinned to an aircraft once the probe has
        // found a serial to pin it to, which is why it is re-applied here rather than
        // written on the step itself.
        val manualLito = if (profileManual) AppState.litoMode else null
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            runCatching { StartupProbe.run(applicationContext) }
            manualLito?.let { v ->
                AppState.setLito(applicationContext, v)
                StartupProbe.noteManual()
                AircraftSession.serial.ifEmpty { StartupProbe.serial }
                    .takeIf { it.isNotEmpty() }
                    ?.let { DeviceStore.setManualVariant(applicationContext, it, v) }
            }
        }
        AppState.setAutoKeepalive(this, useKeep)
        AppState.setAutoOverlay(this, useOverlay)
        AppState.setAutoDiag(this, useDiag)
        // The wizard asked about accessibility properly; don't let MainActivity's
        // one-line fallback dialog ask again on the very next screen.
        AppState.setA11yPrompted(this, true)
        // The wizard has a page for this now, so MainActivity must not ask a second time.
        AppState.setBatteryPrompted(this, true)
        AppState.setWizardDone(this, true)
        AppState.setWizardStep(this, 0)   // a later re-run from the ⋮ menu starts at the top

        if (useKeep) FccKeepaliveService.start(this) else FccKeepaliveService.stop(this)
        if (useDiag) DiagService.start(this) else DiagService.stop(this)
        if (useOverlay && Settings.canDrawOverlays(this)) OverlayService.start(this)
        else OverlayService.stop(this)
        DiagLog.info("setup wizard: keepalive=$useKeep overlay=$useOverlay diag=$useDiag · " +
            "names=" + (if (AppState.litoMode) "Lito" else "g_config.*") + (if (profileManual) " (manual)" else ""))

        // SKIP_AUTOSTART: we just applied exactly what the user chose, including
        // "autostart on but not now" — letting MainActivity re-run applyAutoStart
        // would override that and start it anyway.
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_SKIP_AUTOSTART, true))
        finish()
    }

    // ------------------------------------------------------------ grant state

    private fun isAccessibilityEnabled(): Boolean = Snapshot.isAccessibilityEnabled(this)

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

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()          // the finish-time probe runs on its own scope and survives
    }

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
