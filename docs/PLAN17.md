# PLAN17: the SR101 as a second ring in Circa

Plan only; no code here. Source for every ring fact: `docs/SR101.md` (sections 9 and 10 are the live reads) and
`emu/testdata/sr101/capture1.txt` … `capture3.txt`. Tags: **[ring]** seen on our ring, **[app]** read in the vendor
app JRing 2.0.8 only, **[unknown]** nobody has seen it. Two ring kinds exist, the Colmi R02 and this one ("keepfit");
nothing below is built for a third.

## 1. How the SR101 works, end to end

- **Link.** One Bluetooth service, `56ff`. The phone writes to characteristic `33f3` and the ring answers on `33f4`
  [ring]. No pairing, no bind, no app id: a cold connection gets answers [ring]. On subscribing the ring sends its
  capabilities `20`, info `0C`, activity `03` / `13` and battery `0B` without being asked [ring].
- **Frames.** Every message is exactly 20 bytes: byte 0 is the command, the rest is data padded with zeros. No length,
  no checksum, no encryption [app, and every captured frame fits]. A reply repeats the command byte [ring];
  command + `0x80` means "failed" [app]. Numbers are little-endian [ring].
- **Clock.** The ring counts seconds as if local wall-clock time were UTC. `01 <seconds> <tz hours>` sets it and the
  ring echoes it; before that our ring said 2024-10-01 [ring]. Every timestamp it stores is in that local time, so the
  phone converts each one back through the phone's time zone. What the tz byte does is [unknown]; the vendor app
  sends the raw offset without DST [app].
- **What it stores by itself.** History is asked per day (`day` 0 = today, up to 6 back [app]):

  | Data | Command | Step | Status |
  |---|---|---|---|
  | Steps | `10 day` → frames `10` | 1 min, 15 per frame | layout [app]; only an empty frame seen [ring] |
  | Sleep | `10 day` → frames `11` | 1 min: 0 wake, 1..79 light, 80+ deep; no REM | [app]; no night recorded yet |
  | Heart rate | `16 day` → `16 F0`, `16 AA`, `16 A0` | 12 values per frame = 2 min; the app keeps 2 one-minute means | one real frame [ring] |
  | Temperature | `39 day` | 5 min, pair (surface, body) in 0.01 °C | layout [app]; empty frame [ring] |
  | SpO2 | `40 day` | 1 min, 15 per frame | layout [app]; empty frame [ring] |
  | Stress, HRV, "blood pressure", "sugar" | `55 day` | 15 min [app] | empty frame [ring]; HRV capability bit is off [ring] |
  | Sport mode minutes | `25 day` | 1 min | empty frame [ring]; not used by Circa |

  An empty day answers one frame with a timestamp near the day's end and zeros [ring]. A day's reply has no end
  marker for `10`; the vendor app waits for 2 s of silence [app].
- **What must be switched on.** Heart-rate logging: `19 00 00 17 3B 01 05 01` = all day, on, every 5 min; echoed
  [ring] (our ring currently holds end 23:56, a typing slip). Whether it then logs is [unknown] until `capture4`.
  Temperature: `37 01` gave exactly one reading, 31.00 °C surface / 36.16 °C body, then stopped [ring]; whether any
  setting makes it log in the background is [unknown]. SpO2: `3E 01` "background SpO2" exists [app]; never sent by us.
- **Live readings.** `14 B4 00 00 00 00` streams heart rate about once a second after 11 s, `15` stops it [ring]. The
  session also lands in the `16` history [ring].
- **Keepalive.** `3A` every 10 s held an idle link for 130 s; the ring does not answer it [ring]. A link with a command
  every 4 s lasted 41 s with no `3A` [ring]. That an idle link drops after about 20 s is a third-party write-up's claim [unknown on ours].
- **Battery.** `0B` → percent and a state byte: 1 on the charger, 0 off it [ring, two reads]. Also plain GATT `2a19` [ring].
- **A day's sync.** Connect, subscribe, read `0B` and `03`, set the clock if `03` is off by more than a minute, then
  for today and yesterday: `10`, `16`, `39`, `40`, `55`. Once a day send `19` again. Disconnect.

## 2. Side by side with the Colmi R02

The same byte means different things on the two rings (`37` is Colmi stress history and keepfit temperature mode; `39`
HRV history and temperature history; `16` HR log setting and HR history; `03` battery and activity; `15` HR history and
live-HR stop). No frame of one ring may reach the other.

| Data | R02 command, step | SR101 command, step | Range on SR101 | Circa emits today | Gap |
|---|---|---|---|---|---|
| Heart rate | `15`, 5 min | `16`, 1-min means every 5 min (expected) | bpm, 0 = none | `biometric_hr_bpm` | none after thinning |
| HRV | `39`, 30 min | `55` field, bit off | ms, 0 expected | `biometric_hrv_rmssd` | **lost** |
| Stress | `37`, 30 min | `55` field, 15 min | scale unknown | `biometric_stress` | unproven; off at first |
| Steps | `43`, hourly | `10`, 1 min | 0..255 a minute | `biometric_steps` | none after summing |
| Calories, distance | `43`, hourly | `03`, day total only | kcal, m | `biometric_calories`, `biometric_distance_meters` | **lost** per hour |
| SpO2 | `BC 2A`, hourly min + max | `40`, 1 min | % | `biometric_spo2_pct`, `biometric_spo2_min_pct` | none after thinning |
| Sleep | `BC 27`, stage runs with REM | `11`, 1 min, light / deep / wake | 0..255 | `biometric_sleep_{total,deep,rem,light,awake}_min`, `biometric_sleep_end_ts`, `biometric_sleep_stage` | **no REM**; "deep" is the vendor's cut at 80 |
| Temperature | none | `39`, 5 min | 0.01 °C | nothing (`biometric_skin_temp_c` reserved by T3) | **gained** |
| Battery | `03` | `0B` | %, charging | `ring_battery_pct`, `ring_charging` | none |
| Clock | `01` BCD, every sync | `01` seconds + tz | — | — | none |
| Wear state, motion log, pulse wave | `A1 0F`, `A1 0E` / `0D`, `A1 0C` (our RY02 patches) | none | — | `ring_worn`, `motion_*`, `biometric_pulse_*` | **lost** |

## 3. The smallest app change that syncs it

- **Protocol: new `KeepfitProtocol.kt`, an `object` beside `ColmiProtocol`.** Pure functions (frame builders, parsers,
  the thinning in section 4), no Android imports, so `app/src/test` covers it on the JVM. It returns the existing
  `ColmiProtocol` data classes (`HrSample`, `ActivitySample`, `Spo2Sample`, `SleepSession`, `SleepStage`,
  `BatteryStatus`), so `buildMetrics` needs no second set of types.
- **Builders are the allowlist.** The object can build only `01`, `03`, `0B`, `0C`, `20`, `10`, `16`, `39`, `40`, `55`,
  `19`, `37`, `3E`. There is no generic "frame from a byte" function; a test asserts the set.
- **`ColmiConnection`: no ring-kind parameter.** Three additions: `KeepfitProtocol.CHAR_NOTIFY` joins the list in
  `enableNotifications` (a missing characteristic is already skipped) and maps to `notifyV1` in `dispatch`; a
  `writeKeepfit(data)` beside `writePacket` that calls `writeToChar` with `33f3`; `hasKeepfit()` = service `56ff`
  is in the discovered list. Why this is enough: `writeToChar` fails with "Char not found" when the characteristic is
  absent, so a Colmi frame cannot physically reach the SR101 and a keepfit frame cannot reach the R02.
- **Ring kind: decided after connect, by service `56ff`.** That check cannot be wrong and needs no stored state for the
  sync itself. `Prefs` gains one value, `ringKind` (`"colmi"` default, `"keepfit"`), written by `SyncWorker` after each
  connect and by the scan dialog when the picked result carries manufacturer data for company `0x594A` (one line in
  `ui/SettingsScreen.kt`); only the screens read it, to hide what this ring cannot do. The BLE name is not used: these
  rings are sold under many names.
- **`SyncWorker.doWork`: one early branch, not per-step conditionals.** The Colmi read (battery to motion log, today
  lines 149 to 314) moves unchanged into `readColmi(conn, days, deep)`; a new `readKeepfit(conn, days, deep)` sits
  beside it; both return one small holder of what was read, and the existing tail (`buildMetrics`, `SampleStore`,
  push, reports, widget) runs once on it. Ten `if (keepfit)` checks through the Colmi code would touch every step
  that works today. The automatic stress reading after the push runs only for the Colmi.
- **`readKeepfit`.** `0B`; `03`; `01` only when the ring's clock differs from the phone's local time by over 60 s;
  for each day `10`, `16`, `39`, `40`, `55` through one collector (write, keep frames whose first byte is the command
  or, for `10`, also `11`; stop on the fail byte, 2 s of silence or 20 s); on a deep sync `19` (all day, 5 min). No
  `3A`: the sync never idles longer than the 2 s wait.
- **`buildMetrics`.** Gains a temperature list → `biometric_skin_temp_c`; emits `biometric_calories` and
  `biometric_distance_meters` only when above 0 (the SR101 has neither per hour, and a 0 would score as a lazy day,
  see section 5).

**The SR101 path never sends:**
- any `ColmiProtocol` request: `batteryRequest`, `setTimeRequest`, `hrLogSettingsRequest`, `hrLogEvery5MinRequest`,
  `hrRequest`, `hrvRequest`, `activityRequest`, `stressRequest`, `spo2Request`, `sleepRequest`, `liveHrStartRequest`,
  `liveHrStopRequest`, and every `A1` frame (`wearRequest`, `motionInfoRequest`, `motionLogRequest`, raw stream,
  light copy), nor `updatePacket` from `RingLab.kt`;
- the never-send list of `docs/SR101.md` section 5: `0E` with any key (OTA mode, device off, restart, factory reset),
  `4B 05 00 01` (unbind), `50` (factory test), `1E` (device code), `30` (rename), `35` / `36` / `41` (dials), `A3`
  (legacy sync), anything to the `fef5` update characteristics or to `ff15`;
- state changes nobody asked for: `48` (app id), a `4B` bind answer, `02`, `1A`, `1B`, `21`, `23` (spot "blood
  pressure" / "sugar").

**Off for this ring at first** (the screen says "not available on this ring", read from `ringKind`):

| Feature | Where | Later |
|---|---|---|
| Ring lab: firmware update, motion and light recording, Check ring | `RingLab.kt` (`flashFirmware`, `recordMotion`, `recordLight`, `readRingInfo`) | update: never from Circa (SR5 owns firmware). Check ring: `0C` |
| Live heart rate | `ui/HeartScreen.kt` → `runLiveHr` | `14 B4 00 00 00 00` / `15`, seen working |
| Breathe heart-rate trace | `ui/BreatheScreen.kt` → `runLiveHr` | same; the breathing guide itself needs no ring |
| Automatic stress readings | `SyncWorker.autoReading` | never: needs the RY02 light copy |
| Motion log, wear state | `A1 0E` / `0D`, `A1 0F` | no command known; "ring off" falls back to hours without heart rate |

## 4. Metric mapping

Timestamps: ring seconds are local wall-clock, so `ms = LocalDateTime.ofEpochSecond(ts, 0, UTC).atZone(phone zone)`
(as `ColmiProtocol.ringClockMs` does), one function with the zone as a parameter. Our captures were made at UTC+0,
where a wrong conversion looks right: the tests use a fixed non-UTC zone.

| Command | Metric | Conversion | Step pushed |
|---|---|---|---|
| `16` | `biometric_hr_bpm` | two one-minute means per `A0` frame (values above 0 only), then one mean per 300 s slot, stamped at the slot start, finished slots only | 5 min |
| `10` frames `10` | `biometric_steps` | sum per local hour, stamped at the hour start | 1 h |
| `10` frames `11` | the seven `biometric_sleep_*` metrics | minutes → runs of light / deep / wake as `SleepStage` (`SLEEP_LIGHT`, `SLEEP_DEEP`, `SLEEP_AWAKE`), runs → `SleepSession`; `biometric_sleep_rem_min` is never sent | per session |
| `39` | `biometric_skin_temp_c` (new) | surface / 100; 0 skipped; the "body" value is the vendor's formula over the same sensor, not sent | 5 min, as read |
| `40` | `biometric_spo2_pct`, `biometric_spo2_min_pct` | per hour: rounded mean and minimum of the minutes above 0 → `Spo2Sample`; finished hours only (existing rule) | 1 h |
| `55` | `biometric_stress`; `biometric_hrv_rmssd` | off until a capture shows values (U5). Then: mean per 30-min slot, finished slots; HRV as read when above 0. "Blood pressure" and "sugar" never: no such sensor | 30 min |
| `25` | none | not read | — |
| `0B` | `ring_battery_pct`, `ring_charging` | percent; state 1 = charging; stamped now | per sync |
| `03` | none | the ring's clock for the `01` decision; day steps go to the Sync log beside the `10` sum | — |

- **Watermarks: none new in `Prefs`.** The code has none for slot data today (`buildMetrics`: "No watermarks for slot
  data or sleep"): each sync re-reads today and yesterday, a deep sync 7 days, and both stores keep the larger value
  per metric and timestamp (`SampleStore.add`, `samples.put`). That rule is why only finished slots are sent: a mean
  over a half-filled slot could be higher than the final one and would stick. Hourly step sums and session totals
  only grow, so they are safe unfinished.
- **Thin, do not push per-minute data.** Reasons: (1) `_hourly` in `backend/app/main.py` takes the *last* sample of
  each hour, so per-minute steps would count one minute per hour; (2) `scoring.stress_zones` counts each sample as
  half an hour and `_score_day`'s SpO2 block counts samples under 90; (3) night heart rate feeds `hr_p10`,
  `scoring.low_index` and `scoring.wind_down` ("one higher sample allowed"), all tuned on 5 to 15 min samples;
  (4) `cardio.workouts` already works on 300 s slots (`cardio._slots`), so nothing is lost; (5) 1440 rows a day per
  metric against 288 or 24. Temperature stays at the ring's 5 min: it is already one reading per slot.

## 5. Scores without HRV and REM

| Score | What changes on this ring | What the backend does today | Smallest honest handling |
|---|---|---|---|
| Sleep | No REM; "deep" is another vendor's definition | `_sessions`: `parts[k].get(start, 0.0)` → `rem_min` 0; `scoring.sleep_score`: `"rem": _lin(session["rem_min"], 0, 90)` = **0**, one of 7 contributors, so every night loses up to 14 points | No REM sample for the session → `rem_min` None → contributor None; `_result` already averages what is present. Deep: no change until real nights show its size (U3) |
| Readiness | No overnight HRV; inherits the Sleep score | `readiness_score.rel`: `None if v is None or not b` → `hrv_balance` None; score = mean of the other six | Nothing to change. The Sleep fix above stops the REM loss leaking in through the `sleep` contributor |
| Activity | No hourly calories | `activity_score`: `None if cal is None or not cal_7d_mean` | Do not send 0 calories (section 3): the contributor is None, score from steps, inactive hours, goal days |
| Stress | No trusted stress value | `_score_day`: `zones = scoring.stress_zones(st) if st else None` → `stress_load` None | Not shown. Mapped only if U5 shows a real, scaled value |
| Resilience | Needs stress load | `scoring.resilience`: "None until 5 days of each" | Not shown once the R02's days leave the 14-day window |
| Body signals | HRV input gone, temperature gained | `scoring.strain` skips an input that is None and needs 2; `DIRS` already has `temp` | Nothing now; T4 adds `inputs["temp"]` (its "None under 4 samples" becomes 24 at this ring's 5-min step) |

- **Labelled so.** `scoring.confidence` already counts present contributors. The app's contributor rows
  (`ui/TodayScreen.kt`, "REM sleep", "HRV balance") must read "not measured by this ring" when the value is null and
  `ringKind` is keepfit; SRA6 checks what they show today.
- **Baselines: the v8 decision holds.** The new ring replaces the old one under the same metric names, and the
  baselines (`scoring.baseline`, 5 of 14 days; `scoring.robust`, 7 of 14) relearn. For up to 14 days they blend two
  sensors, so resting heart rate and Body signals can read a few points off; that is the whole cost.
- **A `ring` label is not available cheaply:** the backend drops labels (`samples.parse`), circa.db is keyed by
  metric and timestamp with the larger value winning, and `SampleStore` stores no labels. Two rings under one name
  would overwrite each other. It would need a key change in both stores for a comparison nobody will run twice.
- **Comparison instead (SRA8):** before the switch day the app syncs the SR101 into the PC's local stack for a day and
  a night while the homelab keeps only R02 data, then the two nights are read side by side. No code, no mixed data.

## 6. Unknowns that change the plan

| # | Unknown | Changes | Capture that settles it |
|---|---|---|---|
| U1 | Does temperature log in the background, and what turns it on (`37 01` left on, or `19`)? | No log → one `37 01` spot reading per sync, stamped now; nights depend on the phone being near | `37 01` left on, worn 1 h, then `39 00` |
| U2 | Do history reads erase? | Yes → no re-read safety net; the app must store before anything else and never read from the PC again | `10 00` and `16 00` twice in one connection, then once more after a reconnect |
| U3 | Sleep after a real night: are `11` frames only sent for sleep blocks, how is wake inside the night marked, how much is "deep" | The session rule in `KeepfitProtocol` and whether the deep curve needs a second look | One night worn, `10 00` and `10 01` in the morning |
| U4 | How many days the ring keeps | `days` on a deep sync (6 assumed from the vendor app) | `16 01` … `16 06` a week after the clock set |
| U5 | Is HRV absent for sure; is the `55` stress value real and on what scale; is a `55` entry 5 or 15 min | Whether stress and resilience ever come back | `55 00` after a night; the empty reply's 23:45 stamp hints at 5-min entries |
| U6 | Battery life with heart rate every 5 min and a sync every 5 min | `19` interval, or a slower sync for this ring | `0B` each morning for 3 days |
| U7 | Does logging stop without the `48` app id or the `4B` bind | Would force one deliberate state change; decided with the user | The night of U3 with neither sent: if history is there, no |
| U8 | Does the ring log 0 or nothing when off the finger; does `3E 01` start SpO2 logging | "Ring off" detection; whether `3E` is sent on the deep sync | Ring on the table 30 min, then `16 00`; `3E 01`, a night, `40 00` |
| U9 | The `16 AA` header and a multi-frame `16` reply | The collector's end rule (2 s silence is the fallback) | Covered by `capture4` |

## 7. Ledger items

- [ ] **SRA1 Keepfit protocol object** (may run now; shares no file with SR3). Files: `app/src/main/java/com/nsheremet/circa/KeepfitProtocol.kt` (new), `app/src/test/java/com/nsheremet/circa/KeepfitProtocolTest.kt` (new). UUIDs, the 13 builders of PLAN17 section 3, the local-time conversion, parsers for `0B`, `03`, `20` (capability bits), `0C`, `16` (`F0` / `AA` / `A0`), `10` / `11`, `39`, `40`, and the thinning of section 4 (5-min heart rate, hourly steps and SpO2). Tests use the frames of `emu/testdata/sr101/capture1.txt` … `capture3.txt` copied as hex: battery 99 % charging and not, clock 2024-10-01 and 2026-11-18 13:10:16, capabilities (temperature, SpO2 history, stress history set; HRV not), `38` = 31.00 / 36.16 °C, the `16 A0` frame at 13:10:00 = 71 bpm, the five empty replies → no samples; one test in a non-UTC zone; one test that the builders' first bytes are exactly the allowlist. Layouts never seen on the ring (`10` / `11`, `39`, `40` with data) get a synthetic frame named as such. No Android import in the object. **Check:** `.\gradlew.bat :app:compileDebugKotlin`; `.\gradlew.bat :app:testDebugUnitTest --tests com.nsheremet.circa.KeepfitProtocolTest`. STOP.
- [ ] **SRA2 A night on the finger** (user-driven; extends SR2, from the PC with the scratch read script, the R02 stays the phone's ring). Evening: `19 00 00 17 3B 01 05 01`, `37 01` left on. Morning: `0B`, `03`, then `10`, `16`, `39`, `40`, `55` for days 0 and 1, the day-0 set twice, once more after a reconnect. Nothing from the never-send list, no `48`, no `4B` answer. **Check:** `emu/testdata/sr101/capture5.txt` (and on) hold the frames; `docs/SR101.md` gains a section answering U1, U2, U3, U5, U7, U9 of PLAN17 with decoded values a person can judge (sleep window, deep minutes, lowest night heart rate, temperature range). STOP.
- [ ] **SRA3 Backend: a night without REM** (may run beside SRA1). Files: `backend/app/main.py` (`_sessions`), `backend/app/scoring.py` (`sleep_score`), `backend/tests/test_scoring.py`, `backend/tests/test_api.py`. A session with no `biometric_sleep_rem_min` sample has `rem_min` None (the clip and override lines in `_sessions` keep None); `sleep_score` gives `rem` None for it; `report.py` and `insights.py` already read `rem_min` through `.get` or must skip None (read them, fix only what breaks). One test: the same night with and without the REM sample differs only in the `rem` contributor. No rescore of stored days as a check. **Check:** `python -m compileall -q backend/app && python -m pytest backend/tests -q`. STOP.
- [ ] **SRA4 Sleep, temperature and stress parsers from the real night** (after SRA2). Files: `KeepfitProtocol.kt`, `KeepfitProtocolTest.kt`. The `11` minutes → `SleepSession` rule written against the captured night (where a session starts and ends, wake inside it), `39` and `40` tests replaced by real frames, `55` parsed only if SRA2 showed non-zero values. **Check:** the night's test yields the sleep window SRA2 wrote down; `.\gradlew.bat :app:testDebugUnitTest --tests com.nsheremet.circa.KeepfitProtocolTest`. STOP.
- [ ] **SRA5 App: sync the SR101** (after SRA4). Files: `ColmiConnection.kt` (`writeKeepfit`, `hasKeepfit`, the notify characteristic), `Prefs.kt` (`ringKind`), `SyncWorker.kt` (`readColmi` moved out of `doWork` unchanged, `readKeepfit`, the holder, `buildMetrics`: temperature list, calories and distance only above 0, no automatic reading for keepfit), `ui/SettingsScreen.kt` (the scan pick sets `ringKind` from manufacturer id `0x594A`). The Sync log names the kind and the counts ("Ring: SR101, read N HR, N steps hours, N SpO2, N temp, N sleep sessions"). Nothing from PLAN17's never-send lists. **Check:** `.\gradlew.bat :app:compileDebugKotlin`; `.\gradlew.bat :app:testDebugUnitTest`; the diff of the Colmi read is a move (same lines, `git diff --color-moved`). STOP.
- [ ] **SRA6 App: what this ring cannot do** (after SRA5). Files: `ui/HeartScreen.kt`, `ui/BreatheScreen.kt`, `ui/RingLabScreen.kt` (with its Check ring row), `ui/SettingsScreen.kt` (automatic readings switch), `ui/TodayScreen.kt` (null REM / HRV contributor text). With `ringKind` keepfit: Ring lab, live heart rate, the Breathe trace and the automatic readings switch say "not available on this ring" and open no connection; a null contributor reads "not measured by this ring". Tokens from `ui/Theme.kt` only. **Check:** `.\gradlew.bat :app:compileDebugKotlin`; `.\gradlew.bat :app:testDebugUnitTest`. STOP.
- [ ] **SRA7 Release** (after SRA6; a release item). `versionCode` and name bumped in `app/build.gradle.kts`, `.\gradlew.bat assembleRelease`, `apksigner verify --print-certs` shows the cert starting `3c236b22`; the R02 must sync unchanged on it before anything else (user: one sync, Sync log reads as before). Backend from SRA3 deployed only when the user asks. **Check:** the cert line; the user's R02 sync line. STOP.
- [ ] **SRA8 Trial into the local stack** (user-driven; after SRA7). On the phone: wait for "0 waiting to send", set the URL to the PC's local stack (`:8429`, started per Facts), pick the SR101 in the scan, wear it a day and a night with the R02 on the other hand. Then URL and ring back to the homelab and the R02 after "0 waiting to send" again (a deep sync brings the R02's day back). **Check:** from the local `circa.db`, per metric the row count and step; the night's sleep window, lowest heart rate and SpO2 beside the R02's from the homelab; sync duration and battery drop from the Sync log; U4, U6 and U8 answered in `docs/SR101.md`. STOP.
- [ ] **SRA9 Switch day** (user decides; after SRA8). The R02's last sync, the SR101 selected against the homelab, a `circa.db` backup first (`docker exec circa-backend sh -c 'cp /data/circa.db /data/circa.db.bak-sr101-…'`). Baselines relearn over 14 days; T4 (temperature input) opens after 7 nights with its sample floor at 24. **Check:** the first homelab day shows a Sleep score with `rem` null, a Readiness score with `hrv_balance` null, and `biometric_skin_temp_c` rows (or the U1 fallback's). STOP.

## Found while reading

- `CLAUDE.md` says the `last*Ms` watermark in `Prefs` decides what is new. `Prefs` has none for ring data (`lastSyncMs`, `lastScreenMs`, `lastAutoReadingMs`, `lastMotionSeq` only), and `buildMetrics` says "No watermarks for slot data or sleep".
- `NEXTSTEPS.md` Facts point at `SyncWorker.kt:336` for the sleep emission and `ColmiProtocol.kt:36` for the stage bytes; they are at `SyncWorker.kt` 613 to 633 and `ColmiProtocol.kt` 41 to 44 now.
- Facts: "`biometric_hr_bpm` is 0 when the ring is off the finger". `parseHrPacket` skips 0 and `buildMetrics` filters `bpm > 0`, so the current app never sends a 0; `_off_gap`'s fallback ("a run of >= 2 HR 0 readings") can only match old rows.
- `docs/SR101.md` opens with "Static analysis only; nothing was sent to the ring", but sections 9 and 10 record writes (`01`, `37`, `14`, `15`, `19`).
- `docs/SR101.md` section 1 still guesses "most likely no temperature sensor" and calls the capability bit unknown; section 9 shows the bit set and section 10 a reading. Section 7's first two rows are answered by section 9.
- `docs/SR101.md` section 3 gives a CRC-32 for the `0C` reply; section 9 found the tail differing between replies of the same content, so that check cannot be used as written.
- `docs/SR101.md` section 7 lists the `0B` state byte as unknown; the captures show 1 on the charger and 0 off it.
- `docs/SR101.md` section 4 says a `16` reply ends with `16 FF` = none; `capture3` shows a reply with data and no end frame in 4 s, and a `16 F0` head whose bytes 2..5 are the day's local midnight (not in the table).
- The ring holds the auto heart-rate end as 23:56 (section 10's slip) until someone sends 23:59.
- SR2's check says the results go into "section 9" of `docs/SR101.md`; section 9 is taken by the first reads, and `capture4` does not exist yet.
- `SyncWorker`, `SampleStore`, `Marks.kt` and `runLiveHr` hard-code the label `device=colmi_r02`; harmless (the backend drops labels) but wrong for a second ring.
- The captures were taken at UTC+0 with tz byte 0, so the local-time rule of section 3 of `docs/SR101.md` is still [app], not [ring].
