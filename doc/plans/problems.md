# DUML FCC Scaffold — единый план закрытия всех проблем

## Context

Две независимые ревизии сошлись на одном коде (~5 750 строк Kotlin + C++/JNI):
внешний аудит `CODE_REVIEW.md` (проведён по **исходной** версии — 16/16 тестов, «правок не вносилось»)
и мой аудит тремя агентами по слоям. Значительная часть P0/P1 внешнего аудита **уже
закрыта в этой сессии** (см. «Уже сделано»), сборка и 60 юнит-тестов зелёные.

Задача — закрыть **всё оставшееся**, объединив оба списка. Решения пользователя:
- **Глубина: полный hardening + архитектурный рефакторинг** (transport manager, AircraftSession,
  типизированные результаты, разбор God-объектов).
- **Диаг-сервер: оставить открытым (0.0.0.0, как в README), но усилить лимиты** и убрать
  произвольный `?dir`/path-traversal — рабочий curl/браузер-workflow не менять, токен не вводить.
- **Инфраструктура выпуска: да** — Gradle wrapper, CI (тесты+lint), debug/release split, signing/shrinker, license/NOTICE.

**Инвариант на весь план:** байты кадров, CRC, тайминги, порты и последовательности
`fcc.json`/`ce_restore.json` не меняются — они проверены на железе (RC 2 + Lito X1). Любая
переработка транспорта/парсера обязана проходить golden-тест «тот же кадр байт-в-байт».

## Уже сделано в этой сессии (baseline — не переделывать)

Нативное ядро: bound `build_frame`; waiter-gated `ready`/notify; закрытие fd RX-потоком +
`stop_main()` (устранён crash переназначения joinable-потока); `set_rx_sink`/`g_tp` под
синхронизацией; JNI null-checks + attach-once + `JNI_OnUnload`. Сервисы: `SupervisorJob` +
`scope.cancel()` во всех (`MainActivity`, `Overlay`, `Keepalive`); `ForegroundServices`
(единые уведомления, FGS-тип только API34+, crash-safe старт); правильный `Features` в
keepalive.onDestroy; `AppState.load` при sticky-restart keepalive. Парсеры: `DumlWire.hex`
валидация; `ProfileRunner.parse` (выделен, диапазоны, hex с контекстом). DiagServer: чтение
заголовков до `\r\n\r\n` с лимитом, ответ в try/catch, `SupervisorJob`, пул воркеров (16),
безопасный Range, лог ошибок, убран дубль JS-поллера. Консолидация: `WrappedFrames.walk`
(вместо 3 копий парсера 40007), `Json.esc/quote` (вместо 5 эскейперов, +control chars,
+ForegroundGate). Тесты: +44 (DumlWire/WrappedFrames/AircraftSerial/ParamRead/ParamCatalog/
ProfileRunner/AircraftModelProbe). Всё собирается (обе ABI) и проходит.

---

## Порядок этапов

Сначала F (безопасность, низкий риск), затем A→D **без параллельного изменения протокола**
(external рекомендует), затем E, G, H, I. Каждый этап заканчивается прогоном
`testDebugUnitTest` + `assembleDebug` через кэшированный gradle-8.9.

---

## Этап A. Корректная корреляция ответов и единый router

**Проблема (подтверждена):** `send_once` возвращает первый разобранный кадр, а не ответ на
запрос; `nativeBuildFrame` даёт seq `0`; `FccCountry.parse`/`ParamRead`/`AircraftModelProbe`
принимают payload без проверки cmdSet/cmdId/направления. Native `dispatch` уже waiter-gated,
но корреляция всё ещё по одному seq без command-tuple.

- Расширить `WrappedFrames.Inner` полями `sender/receiver/seq` и добавить **проверку CRC8/CRC16**
  внутреннего кадра (сейчас walker их не проверяет) — [WrappedFrames.kt](app/src/main/java/com/dji/fccgpsoff/WrappedFrames.kt).
- Ввести объект ожидаемого ответа `Expect(seq?, sender, receiver, cmdSet, cmdId, responseBit)` и
  единый `DumlRouter.await(port, wire, expect, timeoutMs)` поверх `WrappedFrames`/native waiter.
  Для one-shot запросов выделять реальный seq (не 0) — новый native `nativeBuildFrameSeq` или seq
  из Kotlin; сохранить прежние байты для frame-only путей.
- Перевести на router: [FccCountry.kt](app/src/main/java/com/dji/fccgpsoff/FccCountry.kt),
  [ParamRead.kt](app/src/main/java/com/dji/fccgpsoff/ParamRead.kt),
  [AircraftSerial.kt](app/src/main/java/com/dji/fccgpsoff/AircraftSerial.kt),
  [AircraftModelProbe.kt](app/src/main/java/com/dji/fccgpsoff/AircraftModelProbe.kt).
- Native `request()` уже атомарно регистрирует waiter; добавить в match проверку
  cmdSet/cmdId/reverse-routing, очистку waiter/ready при reconnect (epoch, см. Этап B).
- **Golden-тесты** из существующих PCAP-примеров (`pcap_example/`) и известного
  `setForceFcc`-кадра: парсер и router узнают ответ, телеметрию — отвергают.

Критерий: фоновая телеметрия не может удовлетворить запрос; `FccCountry` не даёт ложный replay.

## Этап B. Один владелец DUML-транспорта

**Проблема (подтверждена):** `MainActivity.onDestroy` всегда `nativeStop()`, обрывая общий канал
keepalive/capture; `Transport.stop()` рвёт и aux; `send()` гонка fd; несколько `send_all` на
одном сокете могут перемешать кадры; нет process-level владельца.

- Новый `DumlTransport` (Kotlin, `SupervisorJob + Dispatchers.IO`) — единственная точка доступа к
  `DumlNative`. Ref-counted `MainLease`/`AuxLease`; `nativeStop`/`nativeStopAux` только при
  исчезновении последнего владельца. Убрать прямые `DumlNative.nativeStart/Stop*` из
  [MainActivity.kt](app/src/main/java/com/dji/fccgpsoff/MainActivity.kt),
  [Features.kt](app/src/main/java/com/dji/fccgpsoff/Features.kt),
  [FccKeepaliveService.kt](app/src/main/java/com/dji/fccgpsoff/FccKeepaliveService.kt),
  [DumlCapture.kt](app/src/main/java/com/dji/fccgpsoff/DumlCapture.kt).
- Перенести `PortSessionLock` внутрь менеджера и маршрутизировать через него **все** операции
  (см. Этап G: writes/probes/`/send` сейчас без lock).
- Native: mutex вокруг lifecycle/`send`/`set_rx_sink` (send() читает fd под локом или через atomic
  exchange-guard); ввести `epoch`, по которому чистится waiter/dedup при reconnect.

Критерий: закрытие Activity не влияет на keepalive/capture; параллельные UI+HTTP команды не
перемешивают кадры; reconnect не теряет и не путает ответы.

## Этап C. Модель aircraft session

**Проблема (подтверждена):** `AircraftIdentity.Slot.clear()` существует, но не вызывается;
identity/serial/variant/FlightState — независимые синглтоны без сброса; `StartupProbe.rememberModel`
может сохранить старую модель под новым serial; `Slot.accept` перезаписывает без приоритета
источника (CACHE/PASSIVE > UI).

- Единая `AircraftSession(serial, drone, rc, variant, linkState, epoch)` через `StateFlow`;
  переходы `disconnected → identifying → ready`, привязка к link epoch (из home-point/link RX).
- При смене serial или disconnect: `clear()` всех слотов, повторный probe модели и name-variant,
  и только затем разрешать записи. Приоритет источников: UI/validated-DUML > PASSIVE/PROP > CACHE
  в [AircraftIdentity.kt](app/src/main/java/com/dji/fccgpsoff/AircraftIdentity.kt).
- `DeviceStore` пишется только когда serial и identity из одного epoch —
  [StartupProbe.kt](app/src/main/java/com/dji/fccgpsoff/StartupProbe.kt),
  [DeviceStore.kt](app/src/main/java/com/dji/fccgpsoff/DeviceStore.kt). `StartupProbe.running`
  сделать атомарным (CAS вместо check-then-set).
- Accessibility: не прекращать скан content-changes после первого UI-match, если модель сменилась —
  [DjiFlyAccessibilityService.kt](app/src/main/java/com/dji/fccgpsoff/DjiFlyAccessibilityService.kt).

Критерий: подключение второго дрона не наследует ни одной записи/настройки первого.

## Этап D. Безопасная запись параметров

**Проблема (подтверждена):** при отсутствии read-back UI берёт ширину `1`
([MainActivity.kt](app/src/main/java/com/dji/fccgpsoff/MainActivity.kt) editor), а
`ParamCatalog.encode` игнорирует `type_id`/min/max, молча обрезает decimal, для `0x..` берёт
произвольную ширину, decode всегда unsigned.

- Typed codec по `type_id` (signed/unsigned int, float, точная width) с проверкой min/max и
  переполнения — [ParamCatalog.kt](app/src/main/java/com/dji/fccgpsoff/ParamCatalog.kt).
- Запретить запись при неизвестном типе/размере; raw-hex — только явный expert-режим.
- Диалог подтверждения показывает name, hash, old, new, **точные байты**.
- Типизированный результат записи `WriteResult { Sent, Confirmed, Busy, LinkDown, InvalidValue, NoReply }`
  в [ParameterAddress.kt](app/src/main/java/com/dji/fccgpsoff/ParameterAddress.kt) и
  [Features.kt](app/src/main/java/com/dji/fccgpsoff/Features.kt); double-confirm для
  flight-critical. Юнит-тесты кодека (signed/float/переполнение/clamp).

Критерий: значение никогда не обрезается молча и не отправляется с предполагаемой шириной 1.

## Этап E. Lifecycle и честное состояние UI

Скоупы уже с `SupervisorJob`+cancel (сделано). Остаётся:
- Блокирующий socket I/O — обернуть в `withContext(Dispatchers.IO)` в
  [AircraftSerial.kt](app/src/main/java/com/dji/fccgpsoff/AircraftSerial.kt),
  [ParamRead.kt](app/src/main/java/com/dji/fccgpsoff/ParamRead.kt),
  [AircraftModelProbe.kt](app/src/main/java/com/dji/fccgpsoff/AircraftModelProbe.kt),
  [FccCountry.kt](app/src/main/java/com/dji/fccgpsoff/FccCountry.kt) (сейчас работают только под `runBlocking` DiagServer).
- Честный результат: UI `stateSwitch` и `OverlayService.pillBtn` учитывают возвращённый
  `Boolean/WriteResult`, а не только отсутствие исключения ([MainActivity.kt](app/src/main/java/com/dji/fccgpsoff/MainActivity.kt),
  [OverlayService.kt](app/src/main/java/com/dji/fccgpsoff/OverlayService.kt)); `/fcc` не называть любой отказ `port busy`.
- Разделить состояние FCC-переключателя: intended FCC / send result / keepalive status (не привязывать
  положение к факту работы keepalive). Блокировать повторное нажатие на время операции.
- `AppState.load` в `Application.onCreate` (единая точка) — новый `App : Application`, зарегистрировать в манифесте.
- Уведомления: добавить `contentIntent` (тап → MainActivity) в `ForegroundServices.notification`;
  запросить `POST_NOTIFICATIONS` в рантайме на API 33+.

## Этап F. Диаг-сервер: усиление лимитов (сервер остаётся открытым)

Модель «открыто в доверенной LAN» сохраняется (решение пользователя). Закрыть конкретные риски:
- Убрать **произвольный `?dir`**: разрешать только whitelist корней (DJI-пакеты/Download), запретить
  path-traversal — [DiagServer.kt](app/src/main/java/com/dji/fccgpsoff/DiagServer.kt) route,
  [FlightRecords.kt](app/src/main/java/com/dji/fccgpsoff/FlightRecords.kt) `list(extraDir)`.
- `soTimeout` на принятых сокетах; лимиты `ms` для `/cap`/`/probe`; предел размера тела/ответа;
  жёсткий лимит на число одновременных соединений (semaphore — воркер-пул уже есть).
- `screen()` не блокировать IO-поток `Thread.sleep` — ждать через suspend/условную переменную.
- Гонка start/stop: при `stop()` до завершения async bind закрывать `ServerSocket` из bind-ветки
  (держать ссылку под локом, закрывать по флагу).
- Экранировать/чистить CR/LF в filename и MIME в HTTP-заголовках (сейчас только `"`→`_`).
- SAF: неизвестный/отрицательный размер обходит `MAX_FILE` — использовать counting-stream с жёстким
  пределом при чтении/зипе ([FlightRecords.kt](app/src/main/java/com/dji/fccgpsoff/FlightRecords.kt) `read`/`zip`).
- Обновить README/About: явно указать, что screenshot и файлы отдаются по LAN (снять противоречие
  «данные не покидают устройство»).

## Этап G. Производительность и файловый слой

- Убрать hex/logcat/sniffers из синхронного RX hot-path: `onNativeFrame` кладёт событие в bounded
  queue, обработка — на отдельном потоке ([DumlNative.kt](app/src/main/java/com/dji/fccgpsoff/DumlNative.kt)).
  Табличный hex-encoder вместо `"%02x".format` побайтно ([DumlWire.kt](app/src/main/java/com/dji/fccgpsoff/DumlWire.kt) `toHex`).
- `DumlCapture` ring — ограничить **по суммарным байтам**, не по числу кадров; `Rec` — не `data class`
  (ByteArray) ([DumlCapture.kt](app/src/main/java/com/dji/fccgpsoff/DumlCapture.kt)).
- `HomePointMonitor.seen++` — атомарный счётчик (main+aux конкурентно) ([HomePointMonitor.kt](app/src/main/java/com/dji/fccgpsoff/HomePointMonitor.kt)).
- PNG-энкод не на main executor: background executor + запрет параллельных screenshot-запросов
  ([DjiFlyAccessibilityService.kt](app/src/main/java/com/dji/fccgpsoff/DjiFlyAccessibilityService.kt)).
- `DiagLog` — потокобезопасный формат времени (`ThreadLocal<SimpleDateFormat>` или `DateTimeFormatter`)
  ([DiagLog.kt](app/src/main/java/com/dji/fccgpsoff/DiagLog.kt)); `export` fallback обернуть в try.
- Records/zip отдавать потоково (не целиком в память).
- `Movies` dedup по устойчивому ключу (не size+basename); Range edge-cases ([Movies.kt](app/src/main/java/com/dji/fccgpsoff/Movies.kt)).
- **PortSessionLock consistency:** обернуть `ParameterAddress.write/read`, serial/model probes,
  `/send`/`/probe`/`/cap`; сделать **Apply FCC атомарным** — regulatory-запись под тем же lease, что и
  профиль ([Features.kt](app/src/main/java/com/dji/fccgpsoff/Features.kt) `applyFcc`,
  [ParameterAddress.kt](app/src/main/java/com/dji/fccgpsoff/ParameterAddress.kt)).
- `catch (Exception) { -1 }` в read-циклах — различать таймаут и реальную ошибку сокета.
- Консолидировать 5 блокирующих probe-циклов + `ArrayList<Byte>`/`toByteArray()` (O(n²)) в один
  `SocketProbe.window()` (после Этапа A, чтобы не менять тайминги дважды).

## Этап H. Разбор крупных компонентов и чистка

- `MainActivity` (725 строк) → `MainController`/screens/permission-coordinator; убрать сетевые
  хелперы (`localIp`, `flyTraffic`) из Activity.
- `DiagServer` (712) → server-lifecycle / request-parser / router / handlers (records, movies, capture) /
  HTML-asset отдельным ресурсом.
- Протокол-константы в один модуль; переименовать `PORT_LED` → `PORT_VIDEO_MIRROR`; убрать дубль
  `MOBILE_APP=130` (native ×2 + `DumlWire`); дубли `dp()`/pill (`MainActivity` vs `OverlayService`);
  голые `>= 26` → `Build.VERSION_CODES.O`.
- Мёртвый код: `nativeSend`/`nativeRequest` (не зовутся из Kotlin), `activate4g` (нет UI) — либо
  интегрировать и покрыть тестами, либо удалить; `ProfileRunner.sent` для all-`needs_response`
  профиля не должен быть `true` без записей.
- `DumlWire.wrap`/40007-wrapped путь — оставить (используется read/diag), задокументировать как не-feature.

## Этап I. Тесты, CI, выпуск

- Добавить **Gradle wrapper** (`gradlew`+`gradle-wrapper.jar`, 8.9) и `.gitignore` (`.gradle/`,
  `.idea/`, `app/build/`, `.cxx/`, `local.properties`). `local.properties` из VCS убрать.
- Новые тесты: CRC/framing + resync, wrapped-parser с CRC, request-correlation (router отвергает
  телеметрию), param-кодек, смена дрона (session reset), HTTP parser/Range/limits + path-traversal
  запрещён, SAF/MediaStore edge-cases, service lifecycle.
- CI ([.github/workflows/build.yml](.github/workflows/build.yml)): добавить `testDebugUnitTest` и
  `lintDebug` перед `assembleDebug`.
- `debug`/`release` split: release с signing + shrinker (R8) + `useLegacyPackaging` под
  `extractNativeLibs`. Диаг-сервер и raw-инъекция **остаются** и в release (по решению
  пользователя), но с лимитами Этапа F.
- License/NOTICE (AGPL-attribution FreeFCC/Skylab/DjiDeviceSpec), privacy-disclosure и корректное
  описание screenshot/LAN.
- Hardware-regression checklist для RC2 + golden PCAP.

---

## Verification (после каждого этапа и в конце)

Кэшированный Gradle 8.9 (`~/.gradle/wrapper/dists/gradle-8.9-bin/.../bin/gradle`), `JAVA_HOME=jdk-21`,
NDK 26.1 + CMake 3.22.1 присутствуют:

- `gradle :app:testDebugUnitTest --console=plain` — все юнит-тесты зелёные (сейчас 60; растёт по этапам).
- `gradle :app:assembleDebug --console=plain` — сборка обеих ABI, нативный код под `-Wall` без ошибок.
- (Этап I) `gradle :app:lintDebug`.
- Golden: JS-mirror / сохранённый `setForceFcc`-кадр совпадает байт-в-байт после переработки
  парсера/транспорта (инвариант «на проводе ничего не изменилось»).
- Hardware (ручной чеклист, RC 2 + Lito X1 v300): Apply FCC → reboot → 5.8 ГГц/каналы; Restore CE;
  LED/GPS; serial read; смена дрона не наследует старую модель; keepalive переживает закрытие Activity.
