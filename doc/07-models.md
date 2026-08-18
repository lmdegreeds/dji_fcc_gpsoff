# 07 — Таблица поддерживаемого оборудования

Источник: `decompiled/sources/r3/i1.java` (статический список `f6968a`), класс записи `r3/j1.java`.
Всего записей: **101** (дроны: 70, пульты: 27, probe: 4).

**Как читать:** `modelId` — код, который DJI-железо сообщает по DUML (на пультах — через system-property). `AOA`/`VPN` — доступные методы включения FCC (декод флагов: AOA=бит6=64; canUseVPN — когда бит7=128 сброшен). `EP` — есть спец-эндпоинты (класс `e1`: Mavic 3/Phantom 4/Inspire). `flags` — исходная битмаска конструктора `j1`.

## Дроны (aircraft) (70)

| modelId | Продукт | Лицензия | AOA | VPN | EP | flags |
|---|---|---|:--:|:--:|:--:|--:|
| `ag405` | AGRAS MG-1S | Industrial | AOA | — | — | 240 |
| `ag406` | AGRAS MG-1A | Industrial | AOA | — | — | 240 |
| `ag407` | AGRAS MG-1P RTK | Industrial | AOA | — | — | 240 |
| `ag410` | AGRAS T20 | Industrial | AOA | — | — | 240 |
| `ag411` | AGRAS T20 | Industrial | AOA | — | — | 240 |
| `ag500` | AGRAS T10 | Industrial | AOA | — | — | 240 |
| `ag501` | AGRAS T30 | Industrial | AOA | — | — | 240 |
| `ag601` | AGRAS T40 | Industrial | AOA | — | — | 240 |
| `ag700` | AGRAS T25 | Industrial | AOA | — | — | 240 |
| `ag701` | AGRAS T50 | Industrial | AOA | — | — | 240 |
| `ag802` | AGRAS T60 | Industrial | AOA | — | — | 240 |
| `ag811` | AGRAS T70 | Industrial | AOA | — | — | 240 |
| `ag911` | AGRAS T100 | Industrial | AOA | — | — | 240 |
| `ea220e` | MATRICE 3D | Enterprise | AOA | — | — | 240 |
| `ea220t` | MATRICE 3D THERMAL | Enterprise | AOA | — | — | 240 |
| `m600` | MATRICE 600 PRO | Enterprise | AOA | — | — | 240 |
| `m601` | MATRICE 600 | Enterprise | AOA | — | — | 240 |
| `pm320` | MATRICE 30 SERIES (M30/M30T) | Enterprise | AOA | — | — | 240 |
| `pm410` | MATRICE 200 | Enterprise | AOA | — | — | 240 |
| `pm420` | MATRICE 200 V2 | Enterprise | AOA | — | — | 240 |
| `pm430` | MATRICE 300 RTK | Enterprise | AOA | — | — | 240 |
| `pm431` | MATRICE 350 RTK | Industrial | AOA | — | — | 240 |
| `ta101` | FLYCART | Smart RC | AOA | — | — | 240 |
| `wa020` | NEO 2 | Consumer | AOA | VPN | — | 112 |
| `wa140` | MINI 4 PRO | Consumer | — | VPN | — | 48 |
| `wa141` | FLIP | Consumer | AOA | VPN | — | 112 |
| `wa150` | MINI 5 PRO | Consumer | — | VPN | — | 48 |
| `wa151` | DJI Lito X1 | Consumer | — | VPN | — | 48 |
| `wa152` | DJI Lito 1 | Consumer | — | VPN | — | 48 |
| `wa1617` | MINI 4K | Consumer | AOA | — | — | 240 |
| `wa233` | AIR 3 | Consumer | — | — | — | 176 |
| `wa234` | AIR 3S | Consumer | AOA | — | — | 240 |
| `wa341` | MAVIC 4 PRO | Consumer | — | — | — | 176 |
| `wa345e` | MATRICE 4E | Enterprise | AOA | — | — | 240 |
| `wa345t` | MATRICE 4T | Enterprise | AOA | — | — | 240 |
| `wa520` | AVATA 2 | Consumer | AOA | — | — | 240 |
| `wa521` | NEO | Consumer | AOA | VPN | — | 112 |
| `wm100` | SPARK | Consumer | AOA | — | — | 240 |
| `wm100a` | SPARK | Consumer | AOA | — | — | 240 |
| `wm160` | MAVIC MINI | Consumer | AOA | — | — | 240 |
| `wm1605` | MINI SE | Consumer | AOA | — | — | 240 |
| `wm161` | MINI 2 | Consumer | AOA | — | — | 240 |
| `wm1615` | MINI 2 SE | Consumer | AOA | — | — | 240 |
| `wm162` | MINI 3 PRO | Consumer | — | — | — | 176 |
| `wm163` | MINI 3 | Consumer | — | — | — | 176 |
| `wm169` | AVATA | Consumer | AOA | — | — | 240 |
| `wm170` | FPV RACER | Consumer | AOA | — | — | 240 |
| `wm171` | FPV RACER 2 | Consumer | AOA | — | — | 240 |
| `wm220` | MAVIC PRO | Consumer | AOA | — | — | 240 |
| `wm230` | MAVIC AIR | Consumer | AOA | — | — | 240 |
| `wm231` | MAVIC AIR 2 | Consumer | AOA | — | — | 240 |
| `wm232` | AIR 2S | Consumer | AOA | — | — | 240 |
| `wm240` | MAVIC 2 PRO/ZOOM | Consumer | AOA | — | — | 240 |
| `wm245` | MAVIC 2 ENTERPRISE | Enterprise | AOA | — | — | 240 |
| `wm246` | MAVIC 2 ENTERPRISE DUAL | Enterprise | AOA | — | — | 240 |
| `wm247` | MAVIC 2 ENTERPRISE ADV | Enterprise | AOA | — | — | 240 |
| `wm260` | MAVIC 3 | Consumer | AOA | — | — | 240 |
| `wm2605` | MAVIC 3 CLASSIC | Consumer | AOA | — | yes | 224 |
| `wm261` | MAVIC 3 PRO | Consumer | AOA | — | yes | 224 |
| `wm265e` | MAVIC 3 ENTERPRISE | Enterprise | AOA | — | yes | 224 |
| `wm265m` | MAVIC 3 MULTISPECTRAL | Enterprise | AOA | — | yes | 224 |
| `wm265t` | MAVIC 3 THERMAL | Enterprise | AOA | — | yes | 224 |
| `wm330` | PHANTOM 4 STANDARD | Consumer | AOA | — | yes | 224 |
| `wm331` | PHANTOM 4 PROFESSIONAL | Consumer | AOA | — | yes | 224 |
| `wm332` | PHANTOM 4 ADVANCED | Consumer | AOA | — | yes | 224 |
| `wm334` | PHANTOM 4 RTK | Enterprise | AOA | — | yes | 224 |
| `wm335` | PHANTOM 4 PROFESSIONAL 2.0 | Consumer | AOA | — | yes | 224 |
| `wm336` | PHANTOM 4 MULTISPECTRAL | Consumer | AOA | — | yes | 224 |
| `wm620` | INSPIRE 2 | Consumer | AOA | — | yes | 224 |
| `wm630` | INSPIRE 3 | Enterprise | AOA | — | yes | 224 |

## Пульты (remote controllers) (27)

| modelId | Продукт | Лицензия | AOA | VPN | EP | flags |
|---|---|---|:--:|:--:|:--:|--:|
| `gl300` | Phantom 4 Std RC | — | AOA | — | — | 248 |
| `gl300c` | Phantom 4 Std RC | — | AOA | — | — | 248 |
| `gl300e` | Phantom 4 Pro/Adv RC | — | AOA | — | — | 248 |
| `gl300k` | Phantom 4 Pro+ V2.0 RC | — | AOA | — | — | 248 |
| `gl800a` | Cendence Remote Controller | — | AOA | — | — | 248 |
| `mr1sd25` | Mini SE RC | — | AOA | — | — | 248 |
| `mr1ss5` | Mavic Mini RC | — | AOA | — | — | 248 |
| `rc151` | DJI RC-N2 | — | AOA | — | — | 248 |
| `rc151b` | DJI RC-N3 | — | AOA | — | — | 248 |
| `rc160` | Mavic Mini RC | — | AOA | — | — | 248 |
| `rc221` | Avata 2 Motion RC | — | AOA | — | — | 248 |
| `rc331` | DJI RC 2 | Smart RC | AOA | VPN | — | 112 |
| `rc430` | Matrice 300 RC | Smart RC | AOA | — | — | 240 |
| `rc520` | RC Pro 2 | Smart RC | AOA | VPN | — | 112 |
| `rc701` | RC Plus 2 | Smart RC | AOA | — | — | 240 |
| `rcc231` | DJI RC-N1C | — | AOA | — | — | 248 |
| `rcs231` | DJI RC-N1 | — | AOA | — | — | 248 |
| `rm010` | Unknown (RC010) | — | AOA | — | — | 248 |
| `rm220` | RC Motion 2 | — | AOA | — | — | 248 |
| `rm330` | DJI RC | Smart RC | AOA | — | — | 240 |
| `rm500` | DJI Smart Controller | Smart RC | AOA | — | — | 240 |
| `rm510` | RC Pro | Smart RC | AOA | — | — | 240 |
| `rm510b` | RC Pro Enterprise | Smart RC | AOA | — | — | 240 |
| `rm510bv` | RC Pro Enterprise | Smart RC | AOA | — | — | 240 |
| `rm700` | RC Plus | Smart RC | AOA | — | — | 240 |
| `rm700_enterprise` | RC Plus Enterprise | Smart RC | AOA | — | — | 240 |
| `wm220_rc` | Mavic Pro RC | — | AOA | — | — | 248 |

## Probe / служебные коды (4)

| modelId | Продукт | Лицензия | AOA | VPN | EP | flags |
|---|---|---|:--:|:--:|:--:|--:|
| `3ae5` | (probe/unnamed) | Consumer | AOA | — | — | 242 |
| `d902` | (probe/unnamed) | Consumer | AOA | — | — | 242 |
| `wm220_gl` | DJI Gogggles V1 | — | AOA | — | — | 248 |
| `zv811` | (probe/unnamed) | Consumer | AOA | — | — | 242 |

## Декодировка флагов конструктора j1

`j1(modelId, productName, kind, requiredLicense, endpoints, flags)` — `flags` управляет обнулением полей и возможностями:

| бит | значение | смысл |
|--:|--:|---|
| 1 | 2 | productName = null (probe-коды) |
| 3 | 8 | требуемая лицензия = null (пульты) |
| 4 | 16 | endpoints = null (нет спец-эндпоинтов) |
| 6 | 64 | **allowAOA = true** — FCC через AOA-режим |
| 7 | 128 | **canUseVPN** доступен, когда бит 128 НЕ установлен |

Типовые значения: `48`=VPN-only (Mini 4/5 Pro, Lito), `112`=AOA+VPN (RC 2, NEO/FLIP), `176`=ни AOA ни VPN (Air 3, Mini 3/3 Pro, Mavic 4 Pro), `224`=AOA+спецEP (Mavic 3/Phantom 4/Inspire), `240`=AOA (большинство), `248`=пульты (без лицензии), `242`=probe.
