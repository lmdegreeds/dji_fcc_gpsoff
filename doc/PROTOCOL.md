# Protocol reference

How DJI_FCC_GPSOFF actually talks to the aircraft. [Русский](PROTOCOL.ru.md) · [← README](../README.md)

## The three loopback ports

The controller runs local DUML proxies on `127.0.0.1`. They are **multi-client** — DJI Fly holds its own
session and the aircraft accepts frames from any client, so nothing is hijacked.

| Port | Bus | Identity | Framing | Used for |
| --- | --- | --- | --- | --- |
| **40009** | RADIO / SDR | sender **130**, `cmd_type` **0x20** | unwrapped | FCC, region, 4G, keepalive |
| **40008** | FLYC param inject | sender **2**, `cmd_type` **0x40** | unwrapped | GPS / LED / mode / param writes |
| 40007 | FLYC **and Fly's FPV video mirror** | sender 2, `cmd_type` 0x40 | `55 CC 30 75` + len32 | reads only: serial, model, link detection |

**40007 is the dangerous one** — any socket there while DJI Fly is foreground freezes its video for ~1–2 s
(proven on hardware). The app **never writes to 40007** and gates every read on it.

Frames are DUML V1: `55 | len_lo | ((len>>8)&3)|0x04 | crc8 | sender | receiver | seq | cmd_type | cmd_set |
cmd_id | payload… | crc16`. CRC-8 = poly `0x8C` init `0x77` over the first 3 bytes; CRC-16 = poly `0x8408`
init `0x3692`, LE. The **encryption bit is always 0** — the whole FCC path is unencrypted.

## FCC

Sent to **port 40009**, identity **130**, `cmd_type` **0x20**, **2 rounds**, 30 ms between frames. Sequence:
[`assets/profiles/fcc.json`](../app/src/main/assets/profiles/fcc.json) (from FreeFCC / SkylabFCCfree, AGPL),
played under one `PortSessionLock` lease so Apply FCC is atomic. `d` = receiver, `s:i` = cmd_set:cmd_id;
**PM** = parameter write by name-hash.

| # | `s:i` | → d | Payload | What it does |
| --- | --- | --- | --- | --- |
| 1 | `16:88` | 18 | `030100` | AUTOTEST **enter service mode** — unlocks the writes below |
| 2 | `06:114` | 6 | `00000000000100` | RADIO region → FCC (staged, not committed) |
| 3 | `03:249` | 3 | `8a237103` `f401` | **PM** `g_config.flying_limit.max_height` → **500 m** (`0x01F4`, U16 LE) |
| 4–5 | `00:00` / `00:50` | 31 / 111 | `000001` / `3131000000` | GENERAL activate change; country code `"11"` |
| 6 | `03:175` | 3 | `032400000000000000` | FLYC `0xAF` region blob (`0x24` = 36 = the FCC value of `ce_country_type`) |
| 7–8 | `07:48` | 9 | `41550000415500000100` | WIFI channel group, 2.4 and 5.8 (`41 55` = ASCII `"AU"`) |
| **9** | `09:39` | 9 | `00024800` `ffff` `02000000` `00` | **SDR register `0xffff0048` = 2 → `setForceFcc`.** The core write |
| **10** | `09:39` | 9 | `00026300` `ffff` `03000000` `00` | SDR register `0xffff0063` = 3 — the 5.8 GHz side |
| 11–12 | `07:24` / `07:25` | 7 / 9 | `ff415500` / `c0` | WIFI channel map / flag |
| 13–14 | `03:249` | 146 | `d04aeffb` `01` / `00` | **PM** `c1_regulatory_restriction` = 1, then 0 |
| 15 | `00:229` | 111 | `323201` | country code `"22"` |
| 16–17 | `03:249` | 3 | `236b8201` `01` / `8773e68a` `01` | **PM** `sdr_lost_prevent_{never,has}_takeoff_en` = 1 |
| 18–19 | `06:140` | 9 | `000300` / `000100` | RADIO params 03 / 01 |
| 20–21 | `06:114` / `16:88` | 6 / 18 | `000000000001ff` / `030100` | RADIO **commit** region (`ff` = commit); exit service mode |

Frame 9 on the wire: `55 18 04 20 82 09 00 00 20 09 27 00024800ffff0200000000 5e 75`. Then `applyFcc` appends
**one** regulatory write in the *current profile's* spelling (`ce_regulatory_level` on Lito X1, else
`c1_regulatory_restriction`) = `01` — exactly what FreeFCC's fixed `c1_*` hash misses on Lito X1.

A before/after dump of all 952 FLYC parameters shows only `ce_regulatory_level` (255→1), `ce_country_type`
(643→36), `gnss_region_mode` (0→1) and the height limit (120→500) move. The **`EU_CE_*` block is
byte-identical** before/after and between firmware v300 and v400 — evidence it does *not* gate frequencies.
The SDR registers live in the SDR module, not the FLYC store, so they never appear in a parameter dump.

> **Frame 3 raises the flight-height limit to 500 m** — a side effect of the FreeFCC sequence, real and
> persistent. Drop frame 3 from `fcc.json` if you don't want it.

### Keepalive

The write survives reboots, but **DJI Fly re-pushes CE on every relink**. `FccKeepaliveService` retries
`connect` until a proxy answers (at boot there is none — proxies come up with the link), prefers a confirmed
link but **applies blind after 5 s** if none appears (40009 is video-safe and a no-op without an aircraft),
applies across a 45 s window so one write lands *after* Fly's own region push, then polls the `07:19` country
every 5 s and re-applies on drift — plus a **blind re-apply every 10 s**, since injected reads often don't
route back. That blind cadence is what restores FCC after a relink, in ~5–15 s, whichever app is in front.

## 4G activation (experimental)

**One** frame on **40009**, `cmd_type` `0x00`, `0x51:0x1A` (`wlm_service_mode_switch_req`) to the ground WLM
(`OFDM_GROUND`, receiver **238**): payload `00 00 01` (ver=0, service=LIVEVIEW, mode=LIVEVIEW_HYBIRD) ‖ the
full ASCII serial — the WLM matches by serial via `wlm_peer_dev_list_find`, so the full `1581…` is required.
`resp(3,3,3)` = refused, LTE not available yet (pair a Cellular Dongle first); `resp(9,9,9)` = invalid;
anything else = accepted; no reply = unknown (normal on RC 2). No keepalive — nothing pushes 4G back.

## Changing parameters

The flight controller addresses parameters by a hash of the name, computed at runtime with the `"_0"` suffix
the firmware appends:

```text
hash = 0
for byte b in (name + "_0"):  hash = ((hash << 8) | b) % 0xFFFFFFFB   → 4 bytes little-endian
```

Sent as `03:F9` **WriteParamValByHash** (`03:F8` = read), payload `hash(4) ‖ value(n)` LE, sender **2** →
receiver **3**, `cmd_type` **0x40**, **port 40008**, unwrapped, 2–3 repeats 100–120 ms apart.

| Name | Hash (LE) | | Name | Hash (LE) |
| --- | --- | --- | --- | --- |
| `ce_regulatory_level` | `d37acda3` | | `gps_enable` | `9d8a8881` |
| `c1_regulatory_restriction` | `d04aeffb` | | `g_config.gps_cfg.gps_enable` | `829542c5` |
| `ce_country_type` | `d084a0c6` | | `fswitch_selection` | `58fd9834` |
| `forearm_led_ctrl` | `4e9115f3` | | `sdr_lost_prevent_never_takeoff_en` | `236b8201` |
| `g_config.misc_cfg.forearm_lamp_ctrl` | `a259ceed` | | `sdr_lost_prevent_has_takeoff_en` | `8773e68a` |

LED off, end to end: `forearm_led_ctrl` → `4e9115f3` ‖ `00` → `55 12 04 c7 02 03 00 00 40 03 f9 4e9115f3 00
dc 47`, twice on 40008. Reproduce by hand with `/send?port=40008&hex=…`.

**Why by name, not by id.** Parameter indices differ across models and firmware revisions; names are far more
stable. Nothing is hardcoded to an index — the app hashes whatever name it is given. That is also what makes
a foreign model's parameter list safe: an unknown name yields a hash the FC ignores.

**The Lito X1 naming problem.** Names are stable but not universal — Lito X1 spells `ce_regulatory_level`,
`forearm_led_ctrl` and `gps_enable` where other models use `c1_regulatory_restriction`,
`g_config.misc_cfg.forearm_lamp_ctrl` and `g_config.gps_cfg.gps_enable`. Writing every candidate and seeing
which answers **doesn't work here**, because reads don't reliably route back: the proxy routes *replies* to
the session owner (DJI Fly), so "no reply" means **no route back**, not "absent". So each logical parameter
carries both names ([`ParameterAddress.kt`](../app/src/main/java/com/dji/fccgpsoff/ParameterAddress.kt))
and the **Lito X1 / Other DJI** toggle picks exactly one — one name, one write, no candidate spray on a bus
Fly is sharing. For a new firmware, edit the name pair; nothing else changes.

## Why the overlay works while DJI Fly is in front

1. **The overlay never takes the foreground.** It is a `TYPE_APPLICATION_OVERLAY` window with
   `FLAG_NOT_FOCUSABLE`, owned by a service, not an Activity — it draws on top of Fly and gets its own
   touches but never takes input focus, so Android never pauses Fly, which keeps its Activity resumed and its
   DUML session open. A normal app screen would push Fly to the background, and *that* interrupts a flight.
2. **Writes go where Fly isn't looking** — 40008 / 40009, never 40007. Separate multi-client proxies, neither
   carrying Fly's video, so a write there cannot blip it.
3. **One-shot sockets, few of them.** `connect → write → close` per frame; no held session to collide with.
   `PortSessionLock` serialises only *our own* sequences per port, and writes are sparse and spaced.

The remaining risk is entirely on the **read** side — which is what accessibility gates.

## Why accessibility is needed

The service ("DJI_FCC_GPSOFF — model & foreground") does three things nothing else on a non-rooted Android 11 can:
it **reports which package owns the active window** (gating every touch of 40007 to run only while DJI Fly is
*not* in front, aborting mid-read the instant it takes the front — that is what bounds the video freeze to
"never while anyone is watching"); it **reads the aircraft model off DJI Fly's own UI**, the preferred
identity source; and it **takes screenshots** for the dashboard with no MediaProjection prompt.

**The app works without it**, with two honest degradations: the read gate defaults to *allow*, and auto-FCC
falls back to the 5 s blind path. The overlay and all writes never needed it. Known limit: nothing available
to a third-party app can distinguish a *backgrounded* Fly from a *stopped* one, so the UI says
`not in front (bg/stopped)` rather than guessing.

## Serial, model, logs and recordings

- **Serial** — a `GENERAL 00:51` request (field `0x04`), wrapped, on 40007; the whole read window is walked
  for a genuine `00:51` response, else a `1581[0-9A-Z]{12,18}` regex over the raw bytes (which also catches
  the passive `51:14` broadcast). ~2-in-3 hit rate, hence bounded retries. `readLive()` accepts **only** a
  real reply — the controller keeps re-broadcasting `51:14` for minutes after the aircraft powers off.
- **Model** — DJI Fly's on-screen name via accessibility (reliable), else DUML `VersionInquiry` `00:01`
  (which resolves `rc331` → "DJI RC 2"; the aircraft's reply often carries no ASCII model string at all).
- **Flight logs** sit in another app's private dir, hidden since Android 11 — `MANAGE_EXTERNAL_STORAGE` does
  *not* lift it. A persisted **SAF** folder grant is the route that works on RC 2, with plain `File` access
  and an explicit `?dir=` (confined to shared storage) as fallbacks.
- **Screen recordings** are public media on both volumes, read through **MediaStore** per volume with a
  `File` walk as fallback. Reading is seekable, so the dashboard answers HTTP Range and `<video>` can scrub.
