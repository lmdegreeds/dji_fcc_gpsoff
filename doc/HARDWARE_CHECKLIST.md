# Hardware regression checklist (RC 2 + Lito X1)

The unit tests and the golden CRC test (`DumlCrcTest`) prove framing/CRC/parsing
off-device, but the wire behaviour must be re-verified on hardware after any change
to the transport, parser or profiles. Run this on a **DJI RC 2** with a linked
aircraft (v300 for the 5.8 GHz check).

## Wire invariant (must never change)
- [ ] `/send?port=40008&hex=…` for LED off emits exactly
      `551204c7020300004003f94e9115f300dc47` in the log (byte-for-byte).
- [ ] `fcc.json` frame 9 on the wire is
      `551804208209000020092700024800ffff02000000005e75`.

## FCC / region
- [ ] **Apply FCC** → log shows 21 `TX p40009` ×2 rounds + the regulatory write,
      status "FCC sent — reboot to apply".
- [ ] Apply FCC is **live** — DJI Fly's Transmission page shows FCC power **and** 5.8 /
      dual-band immediately, no reboot (v300 and v400).
- [ ] **Restore CE removed** — no CE button/endpoint in this FCC-only build.
- [ ] Apply FCC is atomic: no `port busy` interleave between the profile and the
      regulatory write (they now share one lease).

## Auto-apply on connect (telemetry-gated)
- [ ] Turn on **Auto-apply FCC on connect** + its auto-start; close the app; power
      **the controller only, no aircraft** → the log shows NO `applyFcc`; the
      keepalive notification says "waiting for aircraft telemetry"; `/link` reports
      `connected:false`.
- [ ] Now link the aircraft (DJI Fly shows a live link) → once telemetry starts
      (`/link` → `connected:true`) the log shows exactly one Apply FCC block (21
      `TX p40009` ×2 + the regulatory write), preceded by "aircraft telemetry seen",
      and **not before** the link.
- [ ] Drop and restore the link (power-cycle the aircraft) → one further Apply FCC
      on relink, respecting the 30 s floor — no write storm. Works indoors even when
      no home point is ever recorded (link re-established edge, not just 03:44).
- [ ] Reboot the controller with auto-start on → keepalive comes up, waits for the
      proxy, then waits for telemetry, and applies only after the aircraft is really
      linked.

## 4G (hybrid) — single 51:1A
- [ ] With no Cellular Dongle: **Enable 4G** (Device card) or `/4g` → the request goes
      out as one `TX p40009` `51:1A` frame; status is *refused* (`resp(3,3,3)`) or
      *no reply*, **never** a false "accepted".
- [ ] The frame carries the FULL `1581…` serial (check the log `sn=…`); with no known
      serial the button reads it first, and reports "no aircraft serial" if unavailable.
- [ ] With a paired/active Cellular Dongle: the reply is *accepted*; reboot and confirm
      4G in DJI Fly.

## Reads / correlation
- [ ] `/country` returns `AU` after FCC (and never a bogus 2-letter value from
      telemetry — command-matched now).
- [ ] `/serial` returns the real serial; a corrupt frame is not accepted (CRC).

## Config Table by hash (03:F7 / 03:FA / 03:E0)

Run these with **DJI Fly in the background**, not stopped — that is the working condition,
and the answer rate differs (one 1500 ms window answers ~70%, hence the retries). With Fly
in the **foreground** the only thing to check is that the gate refuses.

- [ ] `/params/info?name=forearm_led_ctrl` → `U8 / 1 B / 0 … 255 / default 239 (attribute 7)`.
- [ ] `/params/info?name=zzz_not_a_real_param` → **NO SUCH PARAMETER (status 3)**, never
      "no answer" — status 3 is an answer and the two must not be conflated.
- [ ] `/params/info?name=lida_x` (present in the bundled `litox1` set, absent on the board)
      → NO SUCH PARAMETER. This is the catalog-drift check.
- [ ] Editor dialog on any parameter shows a `board:` line under the `catalog:` line; where
      they disagree, both are visible.
- [ ] `/params/reset?name=forearm_led_ctrl` with the LED off → `CONFIRMED`, value 239, LEDs
      light. **Then check six neighbours are unchanged** (`ce_regulatory_level`,
      `ce_country_type`, `g_config.flying_limit.max_height`, `fswitch_selection`,
      `gps_enable`, `gnss_region_mode`) — 03:FA must reset exactly one parameter.
      Restore with `/ledoff`.
- [ ] `/params/reset?name=lida_x` → refuses and sends **nothing** (no `TX p40008` in the log).
- [ ] `/params/reset` with the aircraft off → refuses or reports no read-back, never "confirmed".
- [ ] `/table` → `table 0: 1594 slots, crc 0x2ae1a5ad`. Second call says `(unchanged)`.
- [ ] With Fly **foreground**: `/params/info` and `/params/reset` return `blocked: …`, and
      the video does not blip.

## Bulk table dump (03:E1)

Run with **DJI Fly stopped** — with Fly merely backgrounded a read window answers 40–70%
instead of ~100%, which turns one clean pass into several.

- [ ] `/table/dump` → starts, and the reply names the fingerprint and advises stopping Fly.
- [ ] `/table/status` shows `resolved` climbing and `named + empty + unknown == total`;
      on a Lito X1 v400 `total` is 1594 and a clean run ends with `unknown: 0` in
      roughly 2–4 minutes.
- [ ] Spot-check five names in `/table.json` against the bundled `litox1` set — type, size,
      **min, max AND default** must all match (this is the live detector for the F7-vs-E1
      limit-order trap).
- [ ] `/table/save` with `unknown > 0` **refuses**; `&partial=1` saves and records the gap
      in the file name.
- [ ] `/table/save` on a clean run writes `board-<serial>.dhp` and loads it as the active
      catalog; the Parameters page then lists those names.
- [ ] Switch to DJI Fly mid-dump → the dump **pauses** within a window (`note` says so) and
      the video does not blip worse than an ordinary read.
- [ ] `/table/dump` with the aircraft off → refuses (03:E0 unanswered), starts nothing.

## Name-variant detection / manual override
- [ ] Flip the Lito/other switch by hand → the status line says it is a manual choice.
      Restart the app → the choice **survives** the startup probe (it used to be silently
      reverted from the per-serial cache).
- [ ] `/profile/detect` clears the manual flag and re-probes; the log names the parameter
      that confirmed the variant.
- [ ] Set the switch deliberately wrong, then `/profile/detect` → it flips back, and the log
      shows either a 03:F7 confirmation or an explicit "does not exist on this aircraft".
- [ ] **Aircraft off** → `/profile/detect` does not flip the profile and does not spend
      ~18 s on 40007.

## Session / identity
- [ ] Swap to a **different** aircraft → the model/serial/variant update; the old
      drone's model does not linger and is not saved under the new serial.
- [ ] Swapping aircraft also clears the 03:F7 metadata cache (log: "param metadata cache
      cleared"), so the editor cannot show the previous drone's limits.

## Transport ownership
- [ ] Close the Activity while **Keepalive** (or capture) is on → the native
      channel stays up (keepalive keeps working); it stops only when the last user
      releases it.
- [ ] Reconnect after a link drop → no crash; stale replies are not returned.

## Services / UI honesty
- [ ] LED/GPS toggles report ⚠ when the frame did not leave the socket (pull the
      link and try).
- [ ] Parameter editor: writing with no read-back refuses width-1 guessing, shows
      the exact bytes to confirm, and reports Confirmed / Sent / NoReply / LinkDown.
- [ ] Notifications appear (grant POST_NOTIFICATIONS on Android 13+) and tapping
      one opens the app.

## Diag server limits
- [ ] `/records.json?dir=/data/data/…` is rejected (outside shared storage).
- [ ] A hung client is dropped after the socket timeout; the server keeps serving.
