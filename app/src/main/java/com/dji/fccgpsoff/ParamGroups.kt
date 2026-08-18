package com.dji.fccgpsoff

/**
 * Semantic grouping of flight-controller parameters by NAME, so the editor can
 * offer a "filter by group" alongside the free-text search. A parameter with a
 * thousand-odd siblings is far easier to find inside "Battery & power" than by
 * guessing at its exact name.
 *
 * The rules are a straight port of the analysis classifier in
 * `djiparam/params/analyze_params.py` (`category_for` + `calibration_scope_for`)
 * — the same 15 buckets `category_summary.csv` was built from. It is an
 * analytical grouping by name/alias, NOT an official DJI taxonomy: two
 * anchored pre-rules (per-unit calibration state, model install geometry) win
 * over the ordered keyword rules, and anything unmatched falls to "Other".
 *
 * Matching mirrors the Python: the name (which already carries the `a|b` alias
 * form in the exports) is lower-cased and the ordered rules are tried with a
 * substring regex; the two anchored pre-rules test each alias part whole.
 */
object ParamGroups {

    /** One bucket: a stable [id] for the filter, bilingual label, and a one-line
     *  "what it includes" for the picker. [matcher] is null for the synthetic
     *  catch-all, which is assigned by elimination. */
    data class Group(
        val id: String,
        val labelRu: String, val labelEn: String,
        val descRu: String, val descEn: String,
        val matcher: ((name: String) -> Boolean)? = null,
    ) {
        fun label(): String = if (AppState.uiRu) labelRu else labelEn
        fun desc(): String = if (AppState.uiRu) descRu else descEn
    }

    // --- the two anchored pre-rules (win over the keyword rules below) ---
    private val PER_AIRCRAFT_CALIBRATION_STATE = Regex(
        "^(?:compass\\d+\\.(?:cali_step|status_data)|" +
        "g_config\\.fdi_sensor\\[\\d+\\]\\.(?:acc_bias|gyr_bias|mag_over|mag_stat)|" +
        "g_status\\.all_gyr_acc\\.(?:cali_cnt|cali_state|need_cali_type|msc_.*)|" +
        "imu\\d+_cali_status\\..*|" +
        "imu_app_temp_cali\\.(?:cali_cnt|state)|" +
        "imu_cali_[012]\\.acc_gyro.*|" +
        "imu_cali_ui\\..*|" +
        "device_gyr_acc\\.busy\\.cali_cnt|" +
        "g_cfg_debug\\.imu_cali_state.*|" +
        "mass_center_calibrated)$",
        RegexOption.IGNORE_CASE)

    private val MODEL_SENSOR_GEOMETRY = Regex(
        "^(?:(?:imu|gps)[0-2]_[xyz]|" +
        "imu[0-2]_mount_[xyz]|imu[0-2]_direction|" +
        "antenna_gps.*_[xyz]|imu_gps_.*_offset_[ab]_[xyz]|" +
        "uwb\\d+_[xyz]|lida_[xyz])$",
        RegexOption.IGNORE_CASE)

    /** Whole-string match against any `a|b` alias part — the anchored pre-rules
     *  are written against a single name, not the joined form. */
    private fun anyPart(name: String, re: Regex) = name.split('|').any { re.matches(it) }

    private fun kw(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

    /** Ordered keyword rules — first hit wins, exactly as in the Python list. */
    private data class Rule(val id: String, val re: Regex)
    private val RULES = listOf(
        Rule("limits", kw("eu_ce|remote.?id|(^|[._|])rid|geo|airport|country|license|" +
            "flying_limit|height_limit|max_height|max_radius|radius_limit|" +
            "roof_limit|novice|reg_|c0_rid|limit_height|limit_radius")),
        Rule("rth", kw("go_home|gohome|homing|landing|takeoff|fail_safe|failsafe|" +
            "rc_lost|sdr_lost|homepoint|home_point|prevent_landing")),
        Rule("obstacle", kw("avoid|vision|vps|mvo|tof|lidar|radar|ultrasonic|obstacle|" +
            "ground_detect|terrain|safe_dis")),
        Rule("battery", kw("battery|(^|[._|])bat|voltage|power|soc_|remain_cap|cell_")),
        Rule("nav", kw("gps|gnss|rtk|waypoint|position|location|satellite|home_lat|" +
            "home_lon|beacon|uwb")),
        Rule("gimbal", kw("gimbal|camera|zenmuse|pano|pitch_to_center|rot_camera")),
        Rule("motors", kw("motor|esc|engine|propeller|prop_|actuator|mixer|idle_level|" +
            "thrust|ppm_|arm_stop")),
        Rule("imu", kw("imu|compass|magnet|baro|gyro|gyr_|acc_|sensor|calib|cali_|" +
            "bias|temperature|press_alti")),
        Rule("modes", kw("mode_|control_mode|rc_scale|exp_mid|vert_vel|vert_acc|" +
            "horiz_(cur|max|vel|acc)|tilt_atti|tors_gyro|brake|stick|" +
            "fswitch|atti_range|yaw_rate|lift_exp|manual_actual|fpv_")),
        Rule("stab", kw("notch|lpf|lowpass|filter|fltr|gain|pid|auto_tun|sweep|" +
            "sdft|ffwd|fdbk|comp_fc|boost_freq|boost_gain|adapt_")),
        Rule("rc", kw("(^|[._|])rc|sbus|sdr|radio|wifi|antenna|led|lamp|usb|uart|" +
            "cloudctrl|app_enable")),
        Rule("diag", kw("debug|sim_|simulator|(^|[._|])test|factory|status|statistical|" +
            "fault|history|busy|counter|packet_cnt|hms|monitor|reboot|" +
            "user_info|sweep_")),
    )

    /** Every group in the order they appear in the picker, catch-all last. The
     *  two calibration/geometry pre-rules are folded into their matchers here so
     *  a single [groupIdOf] pass reproduces the Python precedence. */
    val ALL: List<Group> = listOf(
        Group("calib_state", "Калибровка и bias экземпляра", "Per-aircraft calibration & bias state",
            "bias IMU, состояние компаса, температура и прогресс калибровки конкретного аппарата — не переносить между дронами",
            "IMU bias, compass state, calibration temperature/progress of THIS aircraft — do not copy between drones"),
        Group("geometry", "Геометрия установки датчиков", "Sensor installation geometry (model)",
            "координаты и ориентация IMU/GPS/антенн/RTK/UWB/LiDAR в корпусе модели",
            "IMU/GPS/antenna/RTK/UWB/LiDAR coordinates and orientation in the model's airframe"),
        Group("limits", "Ограничения полёта и регуляторные", "Flight limits & regulation",
            "высота, радиус, CE/C0/C1, RID, GEO и региональные флаги",
            "height, radius, CE/C0/C1, RID, GEO and regional flags"),
        Group("rth", "RTH, взлёт, посадка и failsafe", "RTH, takeoff, landing & failsafe",
            "высота/скорость RTH, homing, потеря связи, посадочные состояния",
            "RTH height/speed, homing, link loss, landing states"),
        Group("obstacle", "Обход препятствий, vision и рельеф", "Obstacle sensing, vision & terrain",
            "visual sensing, VPS/MVO, ToF, LiDAR, обход препятствий",
            "visual sensing, VPS/MVO, ToF, LiDAR, obstacle avoidance"),
        Group("battery", "Батарея и питание", "Battery & power",
            "пороги заряда/напряжения, smart battery, аутентификация батареи",
            "charge/voltage thresholds, smart battery, battery authentication"),
        Group("nav", "Навигация, GNSS и позиционирование", "Navigation, GNSS & positioning",
            "GPS/Galileo/BeiDou, RTK, waypoint и координатные источники",
            "GPS/Galileo/BeiDou, RTK, waypoint and coordinate sources"),
        Group("gimbal", "Подвес и камера", "Gimbal & camera",
            "gimbal, camera, panorama и центрирование подвеса",
            "gimbal, camera, panorama and gimbal centring"),
        Group("motors", "Моторы, ESC и силовая установка", "Motors, ESC & propulsion",
            "ESC, моторы, пропеллеры, mixer, idle, actuator",
            "ESC, motors, propellers, mixer, idle, actuator"),
        Group("imu", "IMU, компас и управление калибровкой", "IMU, compass & calibration controls",
            "здоровье IMU/gyro/accelerometer, команды и пороги калибровки",
            "IMU/gyro/accelerometer health, calibration commands and thresholds"),
        Group("modes", "Режимы и управляемость", "Flight modes & handling",
            "Normal/Sport/Tripod/Cine/Manual, скорости, наклон, expo, rc_scale, торможение",
            "Normal/Sport/Tripod/Cine/Manual, speeds, tilt, expo, rc_scale, braking"),
        Group("stab", "Стабилизация, коэффициенты и фильтры", "Stabilization, gains & filters",
            "notch/LPF, gain, feed-forward, auto-tuning, компенсация вибраций",
            "notch/LPF, gain, feed-forward, auto-tuning, vibration compensation"),
        Group("rc", "RC, радио, свет и интерфейсы", "RC, radio, lights & interfaces",
            "RC/SBUS/SDR, LED, антенны, интерфейсные флаги",
            "RC/SBUS/SDR, LED, antennas, interface flags"),
        Group("diag", "Диагностика, сервис и симулятор", "Diagnostics, service & simulator",
            "тестовые, factory/debug, fault/history, runtime status",
            "test, factory/debug, fault/history, runtime status"),
        Group("other", "Прочие непрозрачные внутренние", "Other / opaque internal",
            "поля, назначение которых нельзя надёжно вывести только из имени",
            "fields whose purpose cannot be inferred reliably from the name alone"),
    )

    private val BY_ID = ALL.associateBy { it.id }
    fun group(id: String): Group? = BY_ID[id]

    /** The bucket id for a parameter name — the single source of truth both the
     *  filter and any per-group counting go through. */
    fun groupIdOf(name: String): String {
        if (anyPart(name, PER_AIRCRAFT_CALIBRATION_STATE)) return "calib_state"
        if (anyPart(name, MODEL_SENSOR_GEOMETRY) && !name.lowercase().startsWith("sim_")) return "geometry"
        val text = name.lowercase()
        for (r in RULES) if (r.re.containsMatchIn(text)) return r.id
        return "other"
    }

    /** Groups that actually occur in the loaded catalog, each with its count, in
     *  [ALL] order — so the filter never lists an empty bucket. */
    fun present(defs: List<ParamCatalog.Def>): List<Pair<Group, Int>> {
        val counts = HashMap<String, Int>()
        for (d in defs) counts.merge(groupIdOf(d.name), 1, Int::plus)
        return ALL.mapNotNull { g -> counts[g.id]?.let { g to it } }
    }
}
