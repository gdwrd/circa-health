# Ring Health v4: day history + ring notifications

Status: plan (user direction, 2026-11-08). Ledger items live in `NEXTSTEPS.md` under "Active Task: v4";
this file holds the detail each item points to. `docs/PLAN.md` (scores) and `docs/PLAN3.md` (journal, signals,
design) stay the references for what already exists; nothing in them is re-derived here.

## Context and decisions (user, 2026-11-08)

- **Day history.** Every per-day section (Heart rate, Stress, Sleep) must show any past day, not only today.
  The Today screen stays today-only; the 30-day trend cards stay as they are.
- **Battery.** At ring battery ≤ 20 % and not charging: background sync runs every 15 min instead of the user's
  interval, and the user gets one notification that the ring needs charging. Both live in the **app** (the ring is
  read every sync; works with no backend). The backend `notify._battery` rule (≤ 15 %) is deleted so there is one source.
- **Morning sleep notification.** 30 min after waking: last night's sleep in one notification, tap opens Sleep.
- **Extras decided by the orchestrator** (the user asked for related ideas, did not pick): include
  (1) "Ring charged" notification, (2) 30-min sync while charging, (3) per-day resting HR / HRV on Heart — all a few lines
  on the same code paths. Skip (4) folding the "How was yesterday?" check-in into the morning notification: it changes
  behaviour the user already has.
- Standing rules hold: tokens-only UI, `Theme.kt` components, no new packages, never delete ring data, copy never
  says "Oura".

## What exists (do not rebuild)

- `/sleep?date=` already returns any night (session, stages, hr, naps, sleep_extra). The Sleep screen only shows today.
- `/hr?hours=` is now-relative (1..48 h). `/today` reads the stored `daily` row for today; past rows already hold
  `c.stress` (zone counts), `c.wear`, `inputs` (rhr, hrv, steps, sleep_min, stress_load, bed_start, …).
- Raw samples in VictoriaMetrics: `biometric_hr_bpm` (5 min, 0 = off finger), `biometric_stress` (30 min, 0 = no reading).
- `SyncWorker.collectBattery` → `BatteryStatus(level, charging)` every sync; `scheduleNext(ctx)` reads
  `Prefs.syncIntervalMin` (0 = off, default 5); `reschedule`/`runNow` exist. Local notifications: channel `health`
  (`postPending`), small icon `R.drawable.ic_stat_circa`, ids 1000+backend id; sync notification id `NOTIF_ID`.
- Backend rules in `notify.py` follow one shape: `_rule(now, dates)`, `_fired(kind, now)` for once-per-day,
  `_add(kind, title, body, now, extra)`; `evaluate()` runs them after rescore. App deep links: `extra.open`
  = `journal | trends` handled in `MainActivity.readLink` → `App.kt LaunchedEffect(link)`.
- Shared chart pieces in `ui/Charts.kt`: `rememberScrubMarker`, `startAxis`, `bottomAxis`, `chartLine`,
  `dashedLine`, `ScrubChartHost`. Heart's private `Chart(...)` draws the 24 h line with 5-min slots (`SLOT_S`).

## Phase H — day history

### H1 Backend (`backend/app/main.py`, `backend/tests/test_api.py`)
- `GET /hr?hours=&date=`: with `date` (ISO, 422 on a bad one) the window is `vm.midnight(d)` → `vm.midnight(d+1)`,
  capped at now; `hours` ignored. Same body `{points: [{t, bpm}]}`, `bpm > 0` only.
- `GET /stress?date=` (default today): `vm.series("biometric_stress", midnight, min(next midnight, now), "30m")`,
  `v > 0` → `{date, points: [{t, v}]}`. Zone of a sample is the app's business (same thresholds as
  `scoring.stress_zones`: ≤25 restored, ≤50 relaxed, ≤75 engaged, else stressed).
- `GET /today?date=`: optional `date`; the row for that day replaces today's. `vitals` and `signals.explain` stay
  today's (cheap, harmless); `resilience` and `baseline_days` keep using the 14 rows ending on that date. Add
  `"inputs": row["inputs"]` to the body (rhr, hrv, steps, sleep_min, stress_load, bed_start, worn_hours, nap_min).
- Tests: `/hr?date=` window and bad date 422; `/stress?date=` filters zeros; `/today?date=` returns that row's
  scores + `inputs`. Backend suite stays green.

### H2 App day picker: Heart + Stress (`ui/Theme.kt`, `ui/Api.kt`, `ui/HeartScreen.kt`, `ui/StressScreen.kt`)
- `Theme.kt` `DayPicker(date: LocalDate, onChange: (LocalDate) -> Unit)`: one row — `‹` chevron, centred label
  ("Today", "Yesterday", else "EEE d MMM"), `›` chevron disabled (secondaryLabel, no click) when `date == today`.
  Chevrons 44 dp touch targets, `tick()` haptic on change, tint colour. Lower bound 90 days back.
- `Api.hr(ctx, date: LocalDate)`, `Api.stress(ctx, date)`, `Api.today(ctx, date)` overloads (query `date=`).
- **Heart:** `DayPicker` above the "Heart rate" section; the picker label joins the range line
  ("Today · 52–118 bpm", "Tue 5 Nov · 48–121 bpm"); `load(date)` refetches only `/hr` and `/today?date=` on change
  (trends, journal, insights unchanged). The 24 h chart's x axis becomes the day's midnight → midnight (288 slots) so
  every day lines up; "Latest" reads "Last" on past days. Dose windows keep working from the journal entries of that
  date (already fetched, 30 days). Extra (3): a two-tile row under the chart — "Resting HR N bpm" and "HRV N ms" from
  `inputs.rhr` / `inputs.hrv` (— when null).
- **Stress:** `DayPicker` above the zones card; title "Today" → picker label; zones from `/today?date=`; new card
  "Through the day": Vico column chart of `/stress?date=` samples (x = 30-min slot from midnight, 48 slots, labels
  12 AM / 6 AM / 12 PM / 6 PM via `bottomAxis`), each column in its zone colour (a `ColumnCartesianLayer` with a
  per-column colour, or 4 series filtered by zone), scrub label "Engaged 62 · 2:30 PM". Empty state reuses the zones'
  `EmptyState` copy. The "Daytime stress load" and Resilience cards stay (Resilience is 14-day, unaffected by the picker).
- Check: `compileDebugKotlin`; Pixel_9a light + dark: today, yesterday, a day with no data, chevron disabled at today.

### H3 App day picker: Sleep (`ui/SleepScreen.kt`)
- `DayPicker` above "Time asleep"; the night shown is `/sleep?date=<picked>` (the backend already keys a night
  by the day it ends on). The 7-night chart, Regularity and Blood oxygen cards follow the picked date: the week is
  `picked-6..picked`, so `load(date)` reruns the existing 7 parallel calls. No-session copy on a past day:
  "No sleep recorded for this night."
- Check: `compileDebugKotlin`; Pixel_9a light + dark: last night, two nights back, a night without data.

## Phase N — ring notifications

### N1 App battery (`SyncWorker.kt`, `Prefs.kt`, `ui/SettingsScreen.kt`; backend `notify.py`, `tests/test_notify.py`)
- `Prefs`: `lastBatteryPct: Int` (−1 unknown), `lastCharging: Boolean`, `batteryWarned: Boolean`, `chargedTold: Boolean`.
- `SyncWorker.doWork`, right after `collectBattery`: store level/charging; then
  - level ≤ 20 && !charging && !batteryWarned → local notification id 2 on channel `health`:
    title "Ring battery N %", body "Charge the ring soon." (tap opens MainActivity, no extras); set `batteryWarned`.
  - charging || level > 20 → `batteryWarned = false`.
  - Extra (1): charging && level == 100 && !chargedTold → notification id 3: "Ring charged",
    "Put it back on to keep tracking."; set `chargedTold`. `!charging` → `chargedTold = false`.
  - Both skipped (not queued) without POST_NOTIFICATIONS, like `postPending`.
- `scheduleNext(ctx)`: `user = syncIntervalMin`; `user <= 0` → off (unchanged); else
  `minutes = when { lastCharging -> max(user, 30); lastBatteryPct in 0..20 -> max(user, 15); else -> user }`
  (extra (2): nothing new to read while charging). Log "Next sync in 15 min (battery 18 %)" / "(charging)" when stretched.
- Settings "Every" value: `intervalLabel(interval)` + " · 15 min on low battery" when `lastBatteryPct in 0..20`
  and not charging, " · 30 min while charging" when charging. Check it at 360 dp (suffix may wrap; one line preferred).
- Backend: delete `notify._battery` and remove it from `evaluate`; delete
  `test_battery_refires_only_after_ack_and_recharge`; `test_pending_and_ack_routes` must seed a notification another way
  (call `notify._add("checkin", …)` directly) — same assertions.
- Check: `compileDebugKotlin`; backend suite green; the emulator has no ring, so set the prefs via a one-off debug
  path or `adb shell` to see the Settings suffix and the "Next sync in 15 min" log line; a real ring verifies the rest.

### N2 Morning sleep notification (`backend/app/notify.py`, `tests/test_notify.py`; app `ui/App.kt`)
- `notify._morning(now, dates)`: `row = db.days(today, today)`; `n = row.c.session`; fire when `n` exists,
  `now >= end + 30 min`, `now.hour < 14` (a late sync must not fire in the evening) and not `_fired("morning", now)`.
  Title: "Last night · Sleep {sleep_score}" ("Last night" when the score is null); body:
  "{duration} asleep · Readiness {readiness_score}" (drop the readiness part when null; duration as the app:
  "7 h 15 min", from `inputs.sleep_min`). `extra = {"open": "sleep"}`. Add to `evaluate`'s tuple.
- `vm.tz()` for local hours; `end` is the session's epoch seconds (`n["end"]`).
- Tests: fires once after end+30 min with the right title/body/extra; not before 30 min; not after 14:00; not twice;
  nothing without a session.
- App `App.kt LaunchedEffect(link)`: `"sleep" -> { go(TabItem.Sleep); onLinkHandled() }` (same shape as `trends`).
- Timing caveat (accepted): the notification lands at the first sync ≥ 30 min after wake — up to one sync
  interval late — and only once the ring has reported the finished night and the backend has rescored it. The app
  posts pending notifications right after its own push; if the rescore has not finished, it arrives next sync.
- Check: backend suite green; `compileDebugKotlin`; `adb shell am start -n dev.ring.health/.MainActivity --es open sleep`
  opens the Sleep tab cold + warm.

## Phase R

### R5 Release v3.2.0 "Day history + ring alerts"
- versionCode 12 / 3.2.0; same keystore (cert `3c236b22`); `install -r` over v3.1.0 on Pixel_9a; 20 fast flings on
  Heart with the picker, crash buffer empty. Homelab backend redeploy (backup `ring.db` first, never `down -v`), smoke
  `/health`, `/hr?date=`, `/stress?date=`, `/today?date=`, `/sleep?date=` 200. Push + `gh release create` are the user's.
