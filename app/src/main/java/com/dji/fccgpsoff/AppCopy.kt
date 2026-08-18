package com.dji.fccgpsoff

/**
 * User-facing product copy that more than one screen shows, kept in one place so
 * the wizard's welcome page and the About page cannot drift apart.
 *
 * Bilingual by hand rather than through string resources + locale folders: the
 * whole UI is built in code, the About page deliberately shows BOTH languages at
 * once, and the wizard lets the user switch language independently of the device
 * locale — none of which a per-locale `values-ru/` split can express.
 */
object AppCopy {

    const val NAME = "DJI_FCC_GPSOFF"
    const val TELEGRAM = "https://t.me/degreeds"

    /** What the app does, shortest honest form. Shown on the wizard's first page
     *  and on About; keep the two lists parallel, item for item. */
    val FEATURES_RU = listOf(
        "⚡ Включает мощность FCC и частоты 5,8 ГГц. Применяется вживую, без перезагрузки дрона.",
        "🔁 Может запускаться вместе с пультом и активировать FCC автоматически — в том числе после релинка, когда DJI Fly возвращает CE.",
        "🎈 Плавающее меню поверх DJI Fly: GPS, передние LED, режим ATTI/Cine — не сворачивая Fly и не прерывая полёт.",
        "🧩 Редактор параметров дрона: диапазон, значение по умолчанию и тип спрашиваются у самого борта перед правкой, поэтому проверка идёт по его собственным пределам, а не по файлу. Сброс возвращает заводское значение борта.",
        "📚 Списки параметров — из наборов, зашитых в приложение (10 моделей), из экспортов dronehack (.dhp / .dhv2params) или прочитанные прямо с борта.",
        "📡 Чтение всего каталога параметров с дрона по радио, около двух минут. Нужно для аппарата, которого нет в наборах, и заодно показывает, где готовый список разошёлся с прошивкой.",
        "🌐 Веб-дашборд для ПК в той же сети: параметры, логи полётов, записи экрана, захват трафика в .pcap.",
    )

    val FEATURES_EN = listOf(
        "⚡ Unlocks FCC transmit power and the 5.8 GHz band. Applies live, no aircraft reboot.",
        "🔁 Can start with the controller and apply FCC automatically — including after a relink, when DJI Fly pushes CE back.",
        "🎈 Floating menu over DJI Fly: GPS, front LEDs, ATTI/Cine mode — without minimising Fly or interrupting the flight.",
        "🧩 Aircraft parameter editor: the range, default and type are asked of the aircraft itself before an edit, so a value is checked against the firmware's own limits rather than a file's. Reset restores the board's factory value.",
        "📚 Parameter lists come from sets bundled in the app (10 models), from dronehack exports (.dhp / .dhv2params), or read straight off the aircraft.",
        "📡 Reads the whole parameter catalog from the drone over the radio in about two minutes. For an aircraft no bundled set covers — and it also shows where a ready-made list has drifted from the firmware.",
        "🌐 Web dashboard for a PC on the same network: parameters, flight logs, screen recordings, .pcap capture.",
    )

    /** The one thing a user must know before leaving the diag server running. */
    const val DIAG_WARNING_RU =
        "Пока включён веб-дашборд, пульт держит открытый сервер на :8899 без пароля: лог, экран, логи полётов и " +
        "видео доступны любому устройству в той же сети. Выключайте, когда закончили."
    const val DIAG_WARNING_EN =
        "While the web dashboard is on, the controller hosts an unauthenticated server on :8899: the log, the " +
        "screen, flight records and videos are readable by any device on the same network. Turn it off when done."

    /** Shown above the parameter editor and again before every write: a bad value
     *  written to the flight controller can only be undone if the originals were
     *  saved first, and the reliable way to save them is the desktop app over USB. */
    const val PARAM_BACKUP_WARNING_RU =
        "⚠ Перед изменением параметров сделайте резервную копию в приложении на ПК (DJI Assistant 2) " +
        "по USB — так можно будет вернуть исходные значения, если что-то пойдёт не так."
    const val PARAM_BACKUP_WARNING_EN =
        "⚠ Before changing parameters, back them up with the desktop app (DJI Assistant 2) over USB — " +
        "so the originals can be restored if something goes wrong."

    const val CREDITS =
        "Payloads: FreeFCC / SkylabFCCfree (AGPL-3.0). Overlay window: lmdegreeds/dji_gpsoff. " +
        "License: AGPL-3.0-or-later. Ничего не отправляется в интернет / nothing is sent to the internet."
}
