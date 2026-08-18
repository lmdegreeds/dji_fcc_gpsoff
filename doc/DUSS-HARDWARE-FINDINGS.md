# DUSS «шина прошивки» — итоги проверки на реальном железе

> Проверка подхода из отчётов `NLDFCC/REPORT/` (`FIRMWARE-BUS-DUSS.md`, `DUSS-SYNC-REQRESP.md`,
> `DIFF_1.2.0.6_vs_2.0.0.5.md`) на **DJI RC 2** с запущенным дроном и DJI Fly. Дата: 2026-08-17.
> Тестировалось через диагностические эндпоинты `/duss/*` поверх диаг-сервера на `:8899` +
> разбор декомпиляции NLDFCC и strings из `libnld-core.so` (обе версии, 1.2.0.6 и 2.0.0.5).
>
> Документ переписан после полной сессии: ранние промежуточные выводы (в т.ч. «DUSS не достаёт дрон»)
> были ошибочны и здесь **не воспроизводятся** — ниже только финальная, подтверждённая картина.

---

## 0. TL;DR

- **Доступ к шине есть.** Обычное (неприв.) приложение открывает DUSS-сокеты, биндит источник
  `@/duss/mb/0x1e00` и шлёт на роутер `@/duss/mb/0x205` — **без запрета SELinux**.
- **Запись в дрон FLYC через DUSS РАБОТАЕТ.** Доказано: запись `forearm_led_ctrl` с **`flags=0x02`**
  тоглит лучи дрона (`false→true→false→true`). Ключ был в правильном флаг-байте.
- **Чтение через DUSS дрону НЕ помогает.** Ответ FLYC на RC2 приходит только через **40007-окно**;
  DUSS-инжект чтения ответа не порождает. Даже референс читает ответы по TCP (main/aux), не по DUSS.
- **RC-локальный запрос-ответ по DUSS ограничен:** на mailbox `0x1e00` отвечает только `0x205`, и только
  `00:01` Version, `00:0c` Get Device State, `00:ff` Query Device Info. SN/темп/GPS/статус линка — недоступны.
- **Сосуществование с Fly — полное:** 30 чтений + 48 записей при Fly на переднем плане, ноль блипов видео.
- **Для нашего приложения DUSS избыточен с 40008/40009:** запись и так работала, а проблему чтения
  (поймать ответ на 40007 с минимумом влияния на Fly) DUSS не решает. Ценность — исследовательская
  и узкая ниша (тихое RC-локальное чтение версии пульта). См. §11.

---

## 1. Топология шины (`/duss/scan`, `/duss/probe`)

Все mailbox-сокеты — **`SOCK_DGRAM` + abstract** (ведущий `@`); отчётная догадка про pathname неверна:

```text
@/duss/mb/0x205   @/duss/mb/0x1f06  @/duss/mb/0x1d03  @/duss/mb/0x607
@/duss/mb/0xe07   @/duss/mb/0xe06   @/duss/mb/0xe04   @/duss/mb/0xd00
@/duss/mb/0x0     @/duss/wlm_fmsg_forward
```

`/duss/probe 0x205`:

```text
DGRAM  abstract : OK                         ← реальный вариант
DGRAM  pathname : errno=2   (ENOENT)         ← вариант из отчёта §3 — такого сокета нет
STREAM abstract : errno=111 (ECONNREFUSED)
STREAM pathname : errno=2   (ENOENT)
```

Наш источник `0x1e00` в списке отсутствует (свободен для bind; оригинальный `libnld-core` занимал бы его).

## 2. Адресация, которая работает (ключевые факты)

1. **Флаг-байт запроса = `0x02`** (`cmdType=2`). Сборщик `r3.k1.a`:
   `flags = (ack<<7) | ((enc&3)<<5) | (cmdType&7)`. Все `NativeCore.z2m(...,type=2,...)` дают `0x02`.
   Наши прежние `0x20/0x40` попадали в поля **enc/ack**, поле cmdType оставалось 0 → форвардинг во FLYC
   молча дропался. RC-локальный `0x205` при этом отвечал и на `0x20/0x40` (он лоялен).
2. **DUML SRC = `0x1e`** — чтобы RC-локальный ответ вернулся на наш mailbox. Маршрут ответа:
   `reply-DST = request-SRC`, а `DUML-addr → DUSS-mailbox` берётся из старшего байта имени: `0x1e00 ⇔ 0x1e`.
   С `SRC=0x82` (MOBILE_APP) ответ забирает DJI Fly.
3. **Для записи** SRC не важен (fire-and-forget доходит и с `0x82`, и с `0x1e`).

## 3. Что DUSS МОЖЕТ (подтверждено на железе)

### 3.1 Запись в дрон FLYC ✅

`forearm_led_ctrl` (hash `4e9115f3`, подтверждён штатным `/ledon`), `03:F9`, `dst=3`, **`flags=0x02`**,
`sendto(@/duss/mb/0x205)`:

| действие | `/state.led` | лучи |
|---|---|---|
| DUSS write `=0xEF` | `false → true` | зажглись |
| DUSS write `=0x00` | `true → false` | погасли |
| DUSS write `=0xEF` | `false → true` | зажглись |

`/state.led` отражает реальное состояние (при `flags=0x40` не менялось). ⇒ DUSS-запись с `flags=0x02`
применяется на дроне. То есть `ce_regulatory_level`, GPS, высота и т.п. записи через DUSS достижимы.

### 3.2 RC-локальный запрос-ответ ✅ (узко)

Ответ приходит на mailbox `0x1e00` 24-байтным конвертом `21 12 ad de …` (НЕ сырой `0x55`-кадр):

```text
21 12 ad de | 00 00 00 1e | 05 02 00 00 | 00 00 c0 00 | 01 00 00 00 | 1f 00 00 00 | <payload 31B>
  magic        dst=0x1e       src=0x0205    resp-flag*     cmdId=0x01    len=31        payload
```

`resp-flag = 0x80 | request_cmd_type`; поля LE32; payload с offset 24 **байт-в-байт** совпадает с TCP-ответом
(`/deviceinfo` → "rc331").

**Что реально отвечает `0x205`** (перебор GENERAL-чтений, `src=0x1e`):

| cmd | что | ответ |
|---|---|---|
| `00:01` Version Inquiry | версия прошивки пульта | ✅ "rc331" + версия `04 00 00 0e 43 02 00 c0` |
| `00:0c` Get Device State | статус (не EncryptConfig — это была моя ошибка) | ✅ `000000000000` |
| `00:ff` Query Device Info | имя+сборка | ✅ "rc331 rc331 202010\|00:00" |
| `00:00` Ping, `00:51` SN, `00:54` Temp, `00:4b` Date, `00:53` GPS, `00:55` Alive, … | — | ✗ нет ответа |

Другие mailbox'ы (`0x1d03, 0x1f06, 0x607, 0xe04, 0xe06, 0xe07, 0xd00, 0x0`) на наши GENERAL-запросы
**не отвечают вообще**. `00:51` (SN) даже с payload `04` (как в референсе) — молчит: его ответ ушёл бы на
TCP-адрес `0x82`, который держит Fly.

## 4. Что DUSS НЕ может

- **Чтение параметров FLYC (`03:F8`)** через DUSS — ответа нет ни на одном канале: main/40009 несёт только
  RC-housekeeping (`06:AE`, `00:81` с "rc331", константы независимо от линка), в мирроре 40007 — вся
  телеметрия дрона, но `03:F8`-ответа нет. Ответ FLYC приходит **только через 40007-окно, куда инжектится
  сам запрос** (так работает `/readw`). DUSS-инжект чтения ответа не даёт.
- **Статус подключения дрона, температура дрона, GPS** — это данные дрона (OSD/40007) либо ответы, что
  забирает Fly; RC-локально через DUSS недоступны. Статус линка на RC2 — принципиально сигнал 40007 OSD.
- **Серийник пульта** — `0x205` его не отдаёт, ответ маршрутизируется на занятый Fly адрес `0x82`.

**Причина (корень):** с запущенным Fly мы достаём по DUSS только `0x205` на свой приватный mailbox
(`src=0x1e`), и он знает лишь про себя (RC-app: версия/сборка/state). Всё остальное отвечают другие
компоненты, чьи ответы уходят на адрес приложения `0x82`, которым владеет Fly.

## 5. Архитектура референса: TX по DUSS, RX по TCP

Strings из `libnld-core.so`:

```text
DUSS TX ...  /  DUSS connect(/duss/mb/0x205)          ← отправка по DUSS
main RX connected port=%d ...                          ← приём по TCP-порту
aux  RX connected port=%d ... hijack=%d                ← второй TCP-порт (40007)
request TX seq=... route=... mainConnected=.. auxConnected=..
response received seq=%u cmd=%02X/%02X len=%zu
```

Референс **отправляет по DUSS `0x205`, а ответы ждёт на TCP-портах** (main + aux/40007), сопоставляя по
`(seq, cmdSet, cmdId)`. Даже свежий 2.0 читает параметры через 40007 («RCLink parameter scan», DIFF §6).
То есть DUSS у референса — это тихий канал **отправки**, а не замена 40007 для чтения.

## 6. Сосуществование с DJI Fly

При Fly на переднем плане (`flyForeground:true`, `readsAllowed:false` — TCP-чтения гейтятся, `/duss/*` — нет):

| фаза | нагрузка | видео/линк |
|---|---|---|
| DUSS чтения | 30× `/duss/version` + `scan` + `probe` (30/30 ответов) | без блипа, без разрыва |
| DUSS записи | 48 fire-and-forget датаграмм на 8 mailbox'ов | без блипа, без разрыва |

DUSS не касается TCP `40007`, поэтому реально «тихий». Оговорка: нагрузка записей в этом тесте шла
неэффективным флаг-байтом (no-op); эффективные (`flags=0x02`) записи под Fly-foreground отдельно не гоняли
(транспорт тот же).

## 7. Эволюция транспорта 1.2.0.6 → 2.0.0.5

Сравнение обеих `libnld-core.so` (old 673 КБ / new 713 КБ, разные MD5). Строки, которых в OLD нет, а в NEW есть:
`/duss/mb/0x1e00`, `DUSS TX one-shot`, `DUSS bind(local abstract source)`, `bound /duss/mb/0x1e00 and
connected /duss/mb/0x205`, `RX len=… src… dst… seq… cmd…`, `RX invalid DUML datagram`, `failed to start RX
thread`, `nldfcc_duss_%d_%d`, теги `DUSS-1E00`/`NLD-DUSS-1E00`.

| | OLD 1.2.0.6 | NEW 2.0.0.5 |
|---|---|---|
| DUSS TX | `persistent` (один долгоживущий сокет) | `one-shot` (сокет на отправку, reopen при ошибке) |
| Источник | **не биндится** | `bind(@/duss/mb/0x1e00)` |
| RX на шине | **нет** (только отправка) | **RX-поток** `recvfrom(0x1e00)` + разбор DUML |
| Ответы | только по TCP (main/aux) | по DUSS (`0x1e00`) **и** по TCP |

**Преимущества нового подхода:** (1) двусторонность прямо на шине — OLD физически не мог получить ответ
по DUSS; (2) `one-shot` = самовосстановление, не держит fd постоянно; (3) независимость RC-локального
запрос-ответа от TCP-прокси; (4) эфемерное имя источника `nldfcc_duss_<pid>_<n>` (нет коллизий).

**Мотив (по DIFF §3):** headline 2.0 — **офлайн-лицензирование с привязкой к device-identity**
(secp256r1, SHA-3). Двусторонний DUSS даёт тихий, независимый от Fly способ прочитать
идентичность/состояние пульта. То есть DUSS переписан под лицензирование, а не ради нового FCC-качества.

> Примечание: каталог `NLDFCC/decompiled/` — это **1.2.0.6** (у `NativeCore` есть `z1a(String)`/`z1h`/`z2a`,
> убранные в 2.0). `.so` там тоже старая. Новую `.so` берём из `NLDFCC.apk`.

## 8. Где NLDFCC реально использует DUSS

DUSS в нативе = TX-часть `z2m`. В Java `z2m` вызывается **ровно из 3 мест** (`MainActivity`), все под гейтом
`n0()` = `f1891q0 != null` = `r3.e.g() != null` = **приложение запущено на распознанном DJI smart-контроллере**
(матч `ro.product.name`/`ro.build.product` по таблице пультов). На телефоне → `null` → работает TCP-путь
(`r3.k1.a` + `dVar`, route `"DUML"`).

| место | вызов | что |
|---|---|---|
| `MainActivity:822` (`b1`) | `z2m(src,dst, 3, cmdId, payload, 2, seq)` | **FLYC param read/write** (`03:F8/F9`) — редактор + макросы LED/GPS/ATTI/высота/регион |
| `MainActivity:3220` | `z2m(src,dst, 0, 1, [], 2, seq)` | **Version Inquiry** `00:01` |
| `MainActivity:3346` (`G0`) | `z2m(src,dst, 0, 0x51, {4}, 2, seq)` | **Get Serial Number** `00:51` |

Не через DUSS: «RCLink parameter scan» (40007), приём ответов (TCP main/aux), лицензирование, VPN, UI.

## 9. RX-парсер (движок демультиплексирования/корреляции)

Читает из трёх источников (DUSS `0x1e00` + TCP main + TCP aux) и для каждого куска:
пересобирает байты в DUML-кадры (`0x55`+длина; `parser pending overflow … clearing` при мусоре) →
CRC8/CRC16-валидация (`RX invalid DUML datagram` при провале) → разбор заголовка
(`RX len=… src… dst… seq… cmd…`) → дедуп/эхо-фильтр (`matchedReqEcho`, `dupSame`, `dupCross`) →
сопоставление с ждущим запросом по `(seq,cmdSet,cmdId)` (`response received` / `request timeout` /
`Empty (Timeout)`) → отдача в Kotlin-колбэк (`RCLink Kotlin RX sink`) и типизация в `r3.k1.c`
(`DumlParsedPacket` = header+payload+тип). Метрики — в строке `NATIVE frames=… matchedRsp… dupCross…`.
Наш [`duml_core.cpp`](../native/duml_core.cpp) (`reader_loop → parse_frame → dispatch`, `classify()`,
эхо-фильтр по `sender==MOBILE_APP`, waiter-таблица) — функциональный аналог.

## 10. Наши диагностические эндпоинты (не в горячем пути)

- **Нативка:** [`native/duss_bus.cpp`](../native/duss_bus.cpp) — `duss_probe()`, `duss_xact()`
  (socket → bind → `SO_RCVTIMEO` → connect/`sendto` → recv/`recvfrom`, `rxFrom`/`rxRaw`).
- **JNI/Kotlin:** `nativeDussProbe/nativeDussXact`; [`DussBus.kt`](../app/src/main/java/com/dji/fccgpsoff/DussBus.kt)
  (скан `/proc/net/unix`, `SRC_ADDR=0x1e`).

| эндпоинт | что делает |
|---|---|
| `GET /duss/scan` | mailbox-сокеты из `/proc/net/unix` |
| `GET /duss/probe[?peer=]` | матрица connect `{DGRAM,STREAM}×{abstract,path}` |
| `GET /duss/version` | безопасный VersionInquiry round-trip |
| `GET /duss/req[?hex=… \| set=&id=&recv=&type=&payload=&sender=][&…&nc=0&peer=&ms=…]` | произвольная транзакция; `rxRaw`/`reply` |
| `GET /duss/send?hex=` | легаси fire-and-forget на `0x205` |

Рецепт RC-локального round-trip: `GET /duss/req?peer=/duss/mb/0x205&set=0&id=1&type=0x40` → `rxRaw=2112adde…`.
Рецепт записи в FLYC: кадр `SRC|dst=03|02(flags)|03|F9|hash4LE+value` → `sendto(0x205)`; ответ (если нужен) — на TCP.

## 11. Что это значит для нашего приложения

**DUSS не даёт нового полезного качества поверх 40008/40009:**

| задача | 40008/40009 | DUSS |
|---|---|---|
| Запись в FLYC (LED/GPS/param/регион) | ✅ работает | ✅ работает (`flags=0x02`) — то же самое |
| Сосуществование с Fly | ✅ | ✅ — не лучше |
| Чтение FLYC-параметра с ответом | ✅ через 40007-окно | ❌ ответа не даёт |
| RC-локальный GENERAL round-trip без гейта | частично (read-gated) | ✅ (только version/state/deviceinfo) |
| Независимость от TCP-прокси/`PortSessionLock` | ❌ | ✅ (для RC-локального) |

**Единственная реальная ниша для нас** — тихое ungated чтение **версии/сборки прошивки пульта** (`00:01`/`00:ff`),
которых у приложения сейчас нет; можно добавить в `ControllerProbe` как обогащение идентификации RC.

**Не решает** главную боль — чтение параметров + ответ на 40007 с минимумом влияния на Fly. Рычаг там —
пассивный тап 40007 + аккуратный teardown (см. память `fly-switch-40007-baseline`), не DUSS.

**Рекомендация:** `/duss/*` оставить как research/диагностику (изолировано, дёшево; удобно для bring-up новых
моделей пультов — раскладка mailbox'ов отличается между моделями/прошивками). В фичи не тащить.

## 12. Открытые вопросы

- SDR force-FCC (`09:27`, RC-сторона): по аналогии с FLYC-записью, вероятно достижим через DUSS — проверить
  (в этой сессии SDR не отвечал на `07:19` вообще ни по одному каналу, так что сравнить не удалось).
- Полный гибридный READ как в референсе: TX по DUSS + приём ответа на TCP по seq — воспроизвести end-to-end.
- Меняется ли доступ к дрон-форварду и к чужим mailbox'ам под системным контекстом/подписью DJI.
