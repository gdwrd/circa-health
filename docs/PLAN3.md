# Circa v3: insight features + a design with character

Status: plan (user-approved direction, 2026-11-08). Ledger items live in `NEXTSTEPS.md` under
"Active Task: v3"; this file holds the detail each item points to. `docs/PLAN.md` (v2) stays the
reference for the scoring that already exists; nothing in it is re-derived here.

## Context and decisions (user, 2026-11-08)

- Build, in this order: **illness signals** → **journal** (the tags redesign, with ADHD medication and blood
  pressure entries) → **journal insights** → **sleep and heart depth** (overnight HR on the Sleep screen, naps,
  sleep regularity, wear detection, SpO₂ night summary) → **design with character** → **home-screen widget**
  and **weekly report screen**.
- **Skipped:** CSV/PDF export (not needed), the Colmi **R09** / skin temperature (the user may buy one; the
  signals code leaves a slot for temperature but nothing is built for it now), app icon and name (still deferred).
- **Illness signals may be weak.** The R02 has no thermometer or respiratory rate, so a 1–2 day lead from
  resting HR, HRV, overnight recovery, awake minutes and SpO₂ is accepted. Copy must say "may be" and never
  diagnose. Upgrade path: add nightly temperature deviation as one more input when the R09 arrives.
- **Tags are confusing.** Rename the feature **Journal**, make it a yes/no morning check-in about yesterday
  plus today's medication, and show the payoff (insights) right under the entries. "Tag" disappears from the UI.
- **ADHD medication.** The user started stimulant medication ~3 weeks before 2026-11-08. Evidence (adult
  wearable cohorts): daytime pulse +2–3 bpm on average and rising over the first months, blood pressure up a
  few mmHg, sleep timing drifting later; HRV effects unclear. Features: dose time in the journal, dose marker and
  active window on the Heart screen, medication-aware baselines, on/off-day comparison, manual blood pressure
  entries, dose-time vs sleep-onset insight. Everything is phrased as observation, not advice.
- **Design.** Keep the iOS grouped-list grammar and the `Theme.kt`-tokens-only rule, but give the app a
  character: one hero per screen, rounded display numerals (bundled OFL font — this supersedes the v2
  "no bundled fonts" note), gradient + glow score rings, count-up numbers, finger-scrubbable charts, shared-element
  expand, haptics, blurred tab bar, mood tint behind the hero, dark-first polish. Reduce-motion is respected.
- **Data rule (standing):** never delete ring data unless good data for the same period already replaces it.
  Rescoring rewrites `daily` rows and `score_*` samples (same timestamps; VM dedup keeps the larger value for
  ties — fine for scores only when the new value is ≥ the old; for scores that may drop, the backend's SQLite row
  is the source of truth and the app reads SQLite, so Grafana may lag until the next rescore writes a new value).
- **Process:** one ledger item per agent run, `CLAUDE.md` rules, Sonnet agents, the orchestrator writes the ledger.
  Backend: tests for every pure function and route. App: `compileDebugKotlin` + emulator screenshots the agent looks at.
  Real-ring behaviour is verified by the user after each release.

## What the data allows

| Source | Series | Cadence | Used by |
|---|---|---|---|
| ring HR log | `biometric_hr_bpm` (0 = not worn) | 5 min | RHR, recovery, overnight HR curve, daytime HR, wear detection |
| ring HRV | `biometric_hrv_rmssd` | 30 min | HRV balance, strain |
| ring stress | `biometric_stress` | 30 min | zones, resilience |
| ring SpO₂ | `biometric_spo2_pct` | 30–60 min, when the ring's periodic SpO₂ is on | night summary, strain |
| ring activity | `biometric_steps`, `_calories`, `_distance_meters` | hourly | activity score, wear detection (secondary) |
| ring sleep | `biometric_sleep_{total,awake,rem,deep,light}_min`, `_end_ts`, `_stage` | per session | sleep score, naps, regularity, hypnogram |
| backend | `daily`, `baselines`, `tags`→`journal`, `notifications`, `settings` (SQLite) | per day | everything below |

Not available (stays omitted): temperature, respiratory rate, blood pressure (manual entry only), workouts.

## Architecture additions

```
ring ──BLE──▶ app ──POST import──▶ backend ──▶ VM
                ▲                    │ rescore(d): scores + strain signals + sleep depth + wear
                │                    │ notify: readiness, bedtime, inactivity, battery, weekly, strain, check-in
                └── GET /today /sleep /journal /journal/insights /week /hr /trends ◀──┘
app: Today hero + Body signals + Journal card · Sleep (HR overlay, naps, regularity, SpO₂) · Heart (dose marker,
     on/off comparison) · Trends (This week card, journal markers) · Settings (medication) · Glance widget
```

New SQLite: `journal(id PK, date TEXT, kind TEXT, value TEXT, note TEXT, ts INTEGER)` replaces `tags`
(single user; migrate rows: `date` = local date of `ts`, `kind` = lower-cased tag mapped onto the fixed kinds,
unknown tags → `kind='note'`, `note` = tag). `settings` gains `medication_started` (ISO date or empty),
`medication_time` (HH:MM, default `08:00`), `medication_window_h` (float, default 8). `daily.contributors`
JSON gains `signals`, `sleep_extra` (naps, regularity, spo2, hr_low_t), `inputs.worn_hours`, `inputs.nap_min`,
`inputs.day_hr` (mean awake HR), `inputs.bed_start`, `inputs.bed_end`.

---

## Phase A — Journal (replaces tags) + medication

### A1 Backend journal
Files: `backend/app/db.py`, `backend/app/main.py`, `backend/tests/test_journal.py` (replaces `test_tags.py`).

- Schema: create `journal` as above; on `init()`, if `tags` exists and `journal` is empty, copy rows then
  leave `tags` in place (never delete data). Migration helper in `db.py`, guarded by `PRAGMA table_info`.
- Kinds (fixed, validated server-side): `alcohol`, `caffeine_late`, `late_meal`, `workout`, `sick`, `travel`,
  `stress`, `medication` (value `HH:MM`), `bp` (value `SYS/DIA`, 70–250 / 40–150), `note` (free text ≤ 200 in `note`).
  Several `medication` rows per day are allowed (two doses); every other kind is unique per `(date, kind)`
  (`INSERT OR REPLACE` on a unique index).
- Routes (bearer, same router):
  - `GET /journal?days=N` (1..365) → `{"entries":[{id,date,kind,value,note,ts}], "kinds":[...]}` newest first.
  - `POST /journal` body `{date, kind, value?, note?}` → 422 on bad kind/value/date; `date` ≤ today.
  - `DELETE /journal/{id}` → `{"status":"ok"}` (user removes a wrong entry; this is user data, not ring data).
  - `GET /settings`, `PATCH /settings` body subset of `{step_goal, sleep_need_min, medication_started,
    medication_time, medication_window_h}` → validated, stored in `settings`. (The app's local `Prefs.stepGoal` /
    `sleepNeedHours` remain; Settings screen writes both. Backend values drive scoring, as today.)
  - `/tags` routes removed (app v2.1 ships in lockstep; a v2.0.x app only loses tag chips, nothing else).
- Tests: migration copies tags; kinds validation; medication duplicates allowed, others replaced; BP range;
  settings patch validation; `days` clamp.

### A2 App journal UI
Files: `ui/TodayScreen.kt`, `ui/Api.kt`, `ui/TrendsScreen.kt` (markers only), `ui/SettingsScreen.kt` (Medication section).

- Today gets a **Journal** card: header "Journal", one line of why on first use ("Log what you did and we'll
  show how it affects your sleep and recovery."), today's and yesterday's entries as chips (kind label, value for
  medication/BP: "Medication 08:10", "BP 128/82"), and a full-width "Log yesterday / today" button.
- **Check-in sheet** (bottom sheet, same style as today's AddTagSheet): segmented control **Yesterday | Today**
  (default Yesterday before 12:00 local, else Today); one toggle row per kind with a plain question:
  "Alcohol?", "Caffeine after 2 PM?", "Late meal?", "Workout?", "Felt sick?", "Travelled?", "Stressful day?";
  **Medication** row with a time field pre-filled from `medication_time` and "+ second dose"; **Blood pressure**
  row with two numeric fields; **Note** text field. Toggling writes immediately (`POST`), un-toggling `DELETE`s —
  no Save button, no lost input. Each kind's label has a one-line "why we ask" under an ⓘ tap.
- Trends: journal entries replace tag dots under the chart (same drawing, read from `/journal`).
- Settings: **Medication** section — "I take medication" toggle; started on (date picker); usual time; active
  window hours (dialog 4 / 6 / 8 / 12). Writes `PATCH /settings`. Copy: "Used to mark doses on the Heart screen and
  to compare your heart rate with the days before you started."
- `Api`: `journal(days)`, `addJournal(date, kind, value, note)`, `deleteJournal(id)`, `settings()`, `patchSettings(map)`.
  Remove `tags`/`addTag`.
- Empty state and learning copy as above; no "tag" word anywhere.
- Check: compile; emulator screenshots of the card (empty, with chips), the sheet (both segments, medication
  with two doses, BP), Settings Medication section, light + dark.

### A3 Morning check-in notification
Files: `backend/app/notify.py`, `backend/tests/test_notify.py`, `app/.../SyncWorker.kt` (intent extra only),
`app/.../MainActivity.kt`, `ui/App.kt`, `ui/TodayScreen.kt` (open sheet from extra).

- Rule `_checkin`: 08:00–11:00 local, once per day, only if `journal` has no row for yesterday → "How was yesterday?"
  body "Takes ten seconds: alcohol, caffeine, workout, medication." `kind="checkin"`, `extra={"open":"journal","date":<yesterday>}`
  (new nullable `notifications.extra` TEXT column; `pending()` returns it).
- App: `SyncWorker.postPending` puts `extra` into the notification's `PendingIntent` as string extras
  (`open`, `date`); `MainActivity` hands the intent extras to `App`, which starts on Today with the sheet open on
  that date. Also used by the weekly notification later (`open=trends`).
- Check: pytest (fires once, not when an entry exists, not outside the window); compile; emulator: launch with
  `adb shell am start -n com.nsheremet.circa/.MainActivity --es open journal --es date 2026-11-07` opens the sheet.

---

## Phase B — Insight engine

### B1 Backend strain signals (illness)
Files: `backend/app/scoring.py`, `backend/app/main.py`, `backend/app/notify.py`, `backend/tests/test_scoring.py`,
`test_api.py`, `test_notify.py`.

**Baselines with spread.** `scoring.robust(values_14d) -> (median, spread) | None`: median and
`1.4826 × MAD` over the trailing 14 days, needs ≥ 7 values (spread needs more than the 5 the mean uses).
Spread floors so one quiet fortnight does not make everything a signal: RHR 1.5 bpm, HRV 5 ms, awake 10 min,
recovery_frac 0.10, SpO₂ min 1.0 %.

**Medication-aware window.** If `settings.medication_started` is set, the 14-day window for `rhr`, `hrv` and
`day_hr` uses only days ≥ that date (the body's new normal). Until 7 such days exist the strain inputs that depend
on them are `null` and `/today.signals.status = "learning"` with the reason `"medication"`. Readiness keeps its
existing mean baseline but gets a note (see B2).

**Per-night directional z-scores** (positive = worse):
| input | source (already in `inputs`/night) | direction |
|---|---|---|
| `rhr` | `hr_p10` | up |
| `hrv` | `hrv_mean` | down |
| `awake` | session `awake_min` | up |
| `recovery` | `recovery_frac` | up (later = worse) |
| `spo2` | nightly SpO₂ min (B-side of C1; `null` until it exists) | down |
| `temp` | reserved, always `null` (R09 later) | up |

`z = dir × (x − median) / spread`; `flag = z ≥ 1.5`; `contribution = clamp(z / 3, 0, 1)`;
`index = round(100 × mean(contributions over available inputs))` (needs ≥ 2 available, else `null`).
Level: `calm` < 25, `elevated` 25–50, `high` > 50.

`scoring.strain(night_inputs, robust_baselines) -> {"index", "level", "flags": {input: z}, "available": n}` — pure.

**Persistence/API.** `_score_day` stores `contributors.signals = {index, level, flags, streak}` where
`streak` = consecutive nights (ending on d) with ≥ 2 flags. `/today` returns `signals` plus `explain`: journal
kinds from yesterday that commonly raise the same inputs (`alcohol`, `workout`, `sick`, `travel`, `stress`,
`late_meal`) so the app can say "you logged alcohol yesterday". `/trends?metric=strain` returns the index series.

**Notification** `_strain`: when `level != calm` and `streak ≥ 2`, once per 3 days: title "Your body may be under
strain", body "Resting HR +6 bpm and HRV −20 % vs your usual for 2 nights. Rest and hydrate; see a doctor if you feel
unwell." If `explain` is non-empty: "You logged alcohol yesterday, which can cause this." Never the word "sick/ill"
as a statement.

**Tests:** robust median/MAD with floors; strain on a calm night (index < 25, no flags), a 2-flag night, missing
inputs (`available` count, `null` under 2); medication window excludes earlier days; streak counting across days;
notification fires on streak 2, not on 1, once per 3 days; `/today.signals` shape.

### B2 App Body signals card
Files: `ui/TodayScreen.kt`.

- Card **Body signals** under the scores: a 3-step capsule indicator (calm / elevated / high) in the readiness
  colour → amber → activity pink, headline per level ("Your body looks like itself." / "Some signs of strain." /
  "Clear signs of strain."), then one line per flag in words with the deviation ("Resting heart rate +6 bpm above
  your usual", "HRV 20 % below your usual", "Woke 25 min more than usual", "Heart rate settled late in the night",
  "Blood oxygen dipped lower than usual"), the `explain` line when present, and the footer "Based on N nights.
  Not medical advice." Learning state: "Learning your usual values · 5/7 nights" (or "… since your medication
  started on 18 Oct").
- Tapping the card expands "How this works" (two sentences: compares last night with your own last 14 nights; the
  R02 has no temperature sensor so this is a hint, not a prediction).
- Readiness contributor **note** when `medication_started` is set and within the last 60 days: under the
  contributors, "Resting HR is compared with your 14-day baseline, which now includes days on medication; it
  settles after about two weeks."
- Check: compile; screenshots of calm, elevated with two flags + explain, learning (seed the local backend).

### B3 Backend journal insights
Files: `backend/app/insights.py` (new, pure functions + one DB-reading entry point), `backend/app/main.py`
(route), `backend/tests/test_insights.py`.

For each kind over the last 90 days, split days into **with** / **without** and compare the outcome that
follows. Needs ≥ 3 days on each side, otherwise `{"status":"learning","need":3-n}`.

| kind | outcome window | outcomes (mean with − mean without) |
|---|---|---|
| alcohol, caffeine_late, late_meal, workout, stress, travel | the night ending on d+1 and readiness of d+1 | `sleep_score`, `deep_min`, `rem_min`, `hrv`, `rhr`, `total_min`, `awake_min`, `bed_start` (minutes) |
| sick | nights d−1..d+1 | `strain index` (shown as "when you logged Felt sick, strain averaged X") |
| medication (dose days vs no-dose days) | same day + that night | `day_hr` (mean awake HR), `rhr`, `hrv`, `bed_start`, `total_min`, `sleep_score` |
| medication by dose time | days with a dose, split at the median dose time | `bed_start`, `total_min` ("Doses after 10:00: fell asleep 40 min later") |
| bp | none (shown as a series on the Heart screen) | — |

`insights.effect(with: list[float], without: list[float]) -> {"delta", "pct", "n_with", "n_without", "strong"}`
where `strong = |delta| > 0.5 × spread(without)` (same robust spread). The route returns, per kind, the two
strongest effects plus all effects for the detail view:
`GET /journal/insights → {"kinds": {alcohol: {"status","n_with","n_without","top":[{metric, delta, pct, unit}], "all":[...]}}}`.
Also `GET /journal/bp?days=` → BP entries as a series for the Heart screen.

Tests: effect with/without on fixtures; learning under 3; medication dose-time split; route shape.

### B4 App insights + medication on Heart
Files: `ui/TodayScreen.kt` (Journal card insights), `ui/HeartScreen.kt`.

- Under the Journal chips: **"What affects you"** — one row per kind with insights: "Alcohol · 6 nights" then
  the top effects as pills "HRV −18 %", "Deep −25 min". Learning rows: "Caffeine · log 2 more days to see the effect."
  Tap a row → inline expand of all effects with plain-English sentences ("On nights after alcohol your HRV
  averaged 38 ms vs 46 ms.").
- Heart screen:
  - 24 h HR chart: vertical dose marker(s) at each medication time from the journal (today + yesterday as they fall in
    the window), a shaded **active window** of `medication_window_h` after each dose in the heart colour at 8 %
    alpha, legend line "Dose 08:10 · active until ~16:10".
  - **Medication** card (only when `medication_started` is set): "Days with medication vs without" — two columns
    (daytime HR, resting HR, HRV, bedtime, time asleep) with deltas from `/journal/insights.medication`; when no
    no-dose days exist: "Since you started (18 Oct): resting HR 58 → 62 bpm" using `baseline_rhr` before/after
    the start date from `/trends?metric=rhr&days=90`.
  - **Blood pressure** card when ≥ 1 BP entry: latest "128/82", 30-day two-line chart (sys, dia) from `/journal/bp`.
- Check: compile; screenshots: Journal insights (learning + with effects), Heart with two dose markers, Medication
  card both variants, BP card; light + dark.

### Release R1 — v2.1.0 "Journal + Body signals"
`versionCode 8`, `versionName 2.1.0`, `assembleRelease`, apksigner cert `3c236b22`, GitHub release with APK,
homelab: `scp backend/` + `docker compose up -d --build` (never `down -v`), `/health` 200, `/today.signals` present,
`/journal` migrated the existing tags (count equal). Grafana: no change required.

---

## Phase C — Sleep and heart depth

### C1 Backend sleep depth: HR curve, naps, regularity, SpO₂, wear hours
Files: `backend/app/main.py`, `backend/app/scoring.py`, `backend/tests/test_scoring.py`, `test_api.py`.

- **Sessions.** `_night(d)` → `_sessions(d)`: all sessions ending on d, each `{start, end, total_min, awake_min, rem_min,
  deep_min}`; main = longest; **naps** = the others with `total_min ≥ 20`. `inputs.nap_min = Σ naps`;
  `sleep_balance` uses `sleep_min + nap_min` in its 14-day mean (`baseline("sleep_min")` is computed over
  `sleep_min + nap_min`; the `inputs` keep both separately). `/sleep` returns `naps: [{start,end,total_min}]`.
- **Overnight HR.** `/sleep` adds `hr: [{t,bpm}]` (5-min grid within the main session, `bpm > 0`) and
  `hr_low: {t, bpm}` (the first point within 5 % of the night's minimum — the same point `recovery_frac` uses).
- **Regularity** (new Sleep contributor): `midpoint` per night is already computed; SD of the last 7 nights'
  midpoints (minutes, including tonight; needs ≥ 4) → `_lin(sd, 120, 30)`; `null` otherwise. `sleep_extra.regularity
  = {"sd_min", "nights", "bed_start_median", "bed_end_median"}` for the app's band. Rescore on startup picks it up
  for the last 14 days (BACKFILL_DAYS).
- **SpO₂ night summary.** Samples `biometric_spo2_pct > 0` inside the main session → `sleep_extra.spo2 =
  {"mean", "min", "low_count" (samples < 90), "n"}`, `null` when `n == 0`. Feeds B1's `spo2` input (min).
- **Wear detection.** For hours 0..now of day d: `worn` if any `biometric_hr_bpm > 0` in that hour.
  `inputs.worn_hours`; `inactive_hours` counts only awake **and worn** hours; `/today` returns
  `wear: {"worn_hours", "awake_hours"}`.
- **Bed times** `inputs.bed_start`, `inputs.bed_end` (local minutes after noon, as `notify._bedtime` does) so trends
  and insights can use them; `/trends?metric=bed_start` allowed.
- Tests: naps split and minimum; sleep balance includes naps; regularity SD curve + `null` under 4 nights; SpO₂
  summary + `null`; wear hours and inactive-hours exclusion; `/sleep` shape with `hr`, `hr_low`, `naps`.

### C2 App Sleep screen depth
Files: `ui/SleepScreen.kt`, `ui/TodayScreen.kt` (contributor text + wear footnote only).

- **Hypnogram**: stage pills as today, plus a **heart-rate lane** under the stages sharing the x-axis: 1.5 dp line in
  `c.heart`, min–max scaled, a dot at `hr_low` with the label "Low 52 bpm · 2:40 AM". Tapping/dragging shows the
  time and stage + bpm in a floating label (Canvas pointer input; the same marker style D3 gives the Vico charts).
- **Naps** row under "Time asleep": "Nap 1:10 – 1:45 PM · 35 min" per nap; the hero line shows
  "+35 min nap" when present.
- **Regularity** card: 7-night strip, one row per night with a bar from bed to wake on a 6 PM–noon axis, median
  band behind them, headline "Regularity 82 · bed around 11:20 PM, up around 7:05 AM", learning copy under 4 nights.
- **Blood oxygen** card: "Average 96 % · lowest 93 %" and "N dips below 90 %", or "No SpO₂ last night — the ring's
  periodic SpO₂ may be off" when `null`.
- Today: `NAMES`/`CONTRIBUTOR_INFO` gain `regularity`; Vitals footer "Ring off for N h today" when
  `awake_hours − worn_hours ≥ 2`.
- Check: compile; screenshots: hypnogram with HR lane and low marker, naps, regularity (learning and filled),
  SpO₂ (filled and null); light + dark.

### Release R2 — v2.2.0 "Sleep depth"
`versionCode 9`, `2.2.0`; same release and homelab steps as R1; verify `/sleep.hr` non-empty for last night on the homelab.

---

## Phase D — Design with character

Principles: one hero per screen; numbers are the design; motion is spring-based and short (≤ 600 ms), none when the
system animator scale is 0; every colour is a `Theme.kt` token; no new screens, the existing six tabs get depth.

### D1 Theme foundations
Files: `ui/Theme.kt`, `app/src/main/res/font/` (new), `app/build.gradle.kts` (font resources only).

- **Type:** bundle **Nunito** (OFL) Bold + ExtraBold as `res/font/nunito_bold.ttf`, `nunito_extrabold.ttf`
  (~150 KB each). `AppType.display` (56 sp / 60 line, ExtraBold, −1 sp tracking), `AppType.largeTitle` and
  `AppType.title` switch to Nunito Bold; body/subhead/footnote stay on the platform sans. Rounded numerals are what
  gives the Fitness feel; text stays neutral.
- **Tokens:** `AppColors` gains `gradients: Map<Color, Pair<Color, Color>>` → simpler: per score colour a lighter
  partner (`sleepLight #8E8CFF`, `readinessLight #7CF2A2`, `activityLight #FF7A9A`, `heartLight #FF6B8A`), `glow`
  alpha 0.35, `moodGood/moodFair/moodLow` (readiness green / `#FFD60A` / activity pink at 10 % for the hero tint),
  `cardStroke` (white 6 % dark / black 4 % light), `signalCalm/Elevated/High`.
- **ScoreRing v2:** sweep-gradient stroke (`Brush.sweepGradient(color → light)` rotated so the light end is the
  arc head), a glow under the arc (duplicate arc, `BlurMaskFilter(stroke × 1.2)` through `drawIntoCanvas`, alpha
  `glow`, only when the ring is ≥ 120 dp), track at 12 %. Progress spring unchanged.
- **CountUp(value, style)**: `animateIntAsState` with a 600 ms ease-out, skipped under reduce-motion
  (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f` read once into `LocalReduceMotion`).
- **Haptics helper** `tick()` via `LocalHapticFeedback` (TextHandleMove) for selection changes and measurement lock.
- **InsetCard** gets a 1 dp `cardStroke` border; `Hairline` unchanged.
- Check: compile; a preview screen is not needed — D2 is the first consumer; screenshot the Today rings through D2.

### D2 Today hero
Files: `ui/TodayScreen.kt`.

- Header: greeting by hour ("Good morning") in `secondaryLabel`, then the date; the large title stays "Today".
- **Hero card:** Readiness ring 220 dp (gradient + glow), `display` number counting up, "Readiness" caption, a
  rule-based **coach line** under it: ≥ 80 "Well recovered. A good day to push." · 60–79 "Recovered enough. Keep it
  steady." · < 60 "Take it easy today." + the lowest contributor in words ("Your resting heart rate is higher than
  usual.") using `notify.WORDS` equivalents in the app. Behind the hero a radial **mood tint** (`moodGood/Fair/Low`)
  fading into the card.
- Below the hero: Sleep and Activity as two 96 dp rings side by side, each tappable to the existing contributor
  list (shared-element expand arrives in D4; D2 keeps the inline AnimatedVisibility).
- **Learning state** becomes a ring: "Day 6 of 14" progress in `tint` instead of the grey banner.
- Body signals (B2) and Journal (A2) cards keep their place under the rings; Vitals becomes a 2×2 grid of tiles
  (value in `title`, unit, 24-h sparkline for HR via a 40 dp Canvas path) instead of four list rows.
- Check: compile; screenshots light/dark/learning/low-readiness; the hero must not clip at 360 dp width.

### D3 Scrubbable charts and chart polish
Files: `ui/Charts.kt` (new: shared Vico pieces), `ui/HeartScreen.kt`, `ui/TrendsScreen.kt`, `ui/SleepScreen.kt`,
`ui/StressScreen.kt` (MiniChart only).

- `rememberScrubMarker(color, format)`: Vico 2.0 `rememberDefaultCartesianMarker` with a hairline guideline and a
  rounded label (`card` background, `cardStroke`, `footnote` text); shows value + unit + time/date; `tick()` on
  change. All line/column charts pass `marker =`.
- Shared axis styling moves into `Charts.kt` (`startAxis()`, `bottomAxis(formatter, spacing, offset)`), so the four
  screens stop repeating it; line thickness 2.5 dp, area fill 15 → 20 % with a vertical fade (`Brush.verticalGradient`).
- Trends: selected metric chip gets the gradient partner; the period average becomes a dashed decoration line.
- Check: compile; screenshots of a scrubbed Heart 24 h chart and a scrubbed Trends chart.

### D4 Motion and material
Files: `ui/App.kt`, `ui/Theme.kt`, `ui/TodayScreen.kt`, `gradle/libs.versions.toml`, `app/build.gradle.kts`.

- **Shared-element expand:** `SharedTransitionLayout` + `AnimatedContent` (Compose 1.7, BOM 2024.12.01) between
  the small Sleep/Activity rings and a full-card detail (ring grows into the hero slot, contributors slide up).
  Reduce-motion → plain switch.
- **Blurred tab bar:** `dev.chrisbanes.haze` (pin the current 1.x in the catalog) — `hazeSource` on the NavHost
  content, `hazeEffect` on the tab bar with `tabBar` tint; the collapsed `LargeTitleScaffold` bar gets the same.
  Fallback for devices where RenderEffect is unavailable is the current translucent colour (haze does this itself).
- **Tab icons** animate: selected icon scales 1 → 1.1 with a spring and fills; `tick()` on tab change.
- **Pull to refresh** on Today/Sleep/Heart/Stress/Trends (`PullToRefreshBox`, Material3) reloading the screen's data —
  today the screens only reload on tab switch.
- Check: compile; a short screen recording or sequential screenshots of the expand; screenshot of the blurred bar over
  scrolled content; light + dark.

### D5 Screen polish and empty states
Files: `ui/SleepScreen.kt`, `ui/HeartScreen.kt`, `ui/StressScreen.kt`, `ui/SettingsScreen.kt`, `ui/Theme.kt` (one `EmptyState` composable).

- Sleep: a night-gradient header band (deep indigo → black at 12 %) behind "Time asleep"; stage totals as a single
  stacked bar with the four stage colours plus the list.
- Heart: the "Now" ring uses the gradient ring and pulses (scale 1 → 1.03 spring loop) only while measuring; the bpm
  counts up.
- Stress: zone bar segments get rounded ends and a subtle inner gradient; the resilience capsules animate in.
- `EmptyState(icon, title, line)`: a 56 dp rounded icon tile on `secondaryLabel` 12 % + two lines; used by every
  "Not enough data yet" / "No sleep recorded" / "Scores need the backend" spot (replaces `PlaceholderTab`'s card text).
- Settings: section headers get the `title` style, rows 52 dp, the Sync log stays as is (temporary).
- Check: compile; screenshots of each screen light + dark and of two empty states.

### Release R3 — v3.0.0 "Design"
`versionCode 10`, `3.0.0`; release + homelab unchanged (backend did not change in D). Before building, the orchestrator
runs through all six tabs on the emulator in light and dark and looks for clipping, unreadable contrast and missing
tokens (`grep -n "Color(0x" app/src/main/java/com/nsheremet/circa/ui/*.kt` must only hit `Theme.kt`).

---

## Phase E — Widget and weekly report

### E1 Backend `/week` + weekly notification deep link
Files: `backend/app/main.py`, `backend/app/notify.py`, `backend/tests/test_api.py`, `test_notify.py`.

- `GET /week?end=YYYY-MM-DD` (default today) → the 7 days ending there: per day `{date, sleep, readiness, activity,
  steps, sleep_min, strain}`, means of each score over scored days, `best_night` / `worst_night` (by sleep score),
  `steps_total`, `sleep_avg_min`, plus the same block for the previous 7 days so the app can show deltas.
- `_weekly` notification gains `extra={"open":"trends"}` (A3 plumbing).
- Tests: shape, deltas, fewer than 7 days.

### E2 App "This week" card
Files: `ui/TrendsScreen.kt`, `ui/App.kt` (`open=trends`).

- Card at the top of Trends: seven mini readiness rings (28 dp, number inside, day letter under), averages row
  "Sleep 74 · Readiness 68 · Activity 81" with ▲/▼ deltas vs the previous week, "Best night Sat · Worst Tue",
  "Steps 61 k · Sleep 7 h 12 avg". Fewer than 3 scored days → learning copy.
- Weekly notification tap lands here.
- Check: compile; screenshot light + dark; `am start … --es open trends` opens Trends.

### E3 Home-screen widget (Glance)
Files: `app/.../widget/RingWidget.kt`, `widget/RingWidgetReceiver.kt` (new), `res/xml/ring_widget_info.xml`,
`res/layout/ring_widget_preview.xml` or a PNG preview, `AndroidManifest.xml`, `Prefs.kt` (`todayJson`),
`SyncWorker.kt` (snapshot + `updateAll` after a successful push — the only change), `gradle/libs.versions.toml`,
`app/build.gradle.kts` (`androidx.glance:glance-appwidget` 1.1.1, `glance-material3`).

- Snapshot: after `postPending`, `SyncWorker` calls `Api.today`, stores the JSON in `Prefs.todayJson` with
  `todayJsonMs`, then `RingWidget().updateAll(ctx)`. The widget never touches the network or BLE.
- Sizes: 2×2 → Readiness ring with number + "Sleep 74 · Activity 81" line; 4×2 → three rings with labels and the
  Body signals level word. Rings are drawn to a `Bitmap` with `android.graphics.Canvas` (gradient arc, same colours as
  the theme — the widget has its own tiny colour table mirroring `Theme.kt`, since Glance cannot read Compose tokens)
  and shown as `Image`. Footer "Updated 8:05" from `todayJsonMs`; stale (> 2 h) shows "Open to sync".
- Tap anywhere → `MainActivity`. (Live HR from the widget is out: BLE needs the app process and a foreground service.)
- Dark/light follow the launcher (`GlanceTheme` colours from the mini table).
- Check: compile; place both sizes on the emulator launcher, screenshot; `adb shell cmd jobscheduler`/Sync now then
  confirm the footer time updates.

### Release R4 — v3.1.0 "Widget + week"
`versionCode 11`, `3.1.0`; release + homelab deploy of `backend/` (E1).

---

## Phase F — App name and icon: **Circa** (decided, user 2026-11-08)

**Name: Circa.** "Around" (a ring), the root of *circadian*, and "approximately" — honest about scores that are
estimates against your own baseline. Tagline: **"Know your normal."** Written `Circa` in UI, `circa` in identifiers.
Trademark not searched (personal app, not on the Play Store); check the Play Store and USPTO before any public release.

**Icon concept.** A thin ring that is not quite closed: the arc starts thin at the bottom-left and thickens towards its
head at the top-right, where a small gap and a dot sit — the same gesture as the app's score rings, and a lowercase
"c" at a glance. Details:
- Canvas 108 × 108 dp adaptive layers; the mark lives inside the 66 dp safe circle. Ring outer radius 30 dp, stroke
  tapering 4 → 9 dp, round caps; the gap spans ~40° centred at 45° (top-right); the dot (Ø 6 dp) sits in the gap, 2 dp
  off the arc's head. Mark centred at (54, 54).
- Colour: background near-black `#0B0B10`; arc gradient along its length from Sleep indigo `#5E5CE6` (tail) to a mint
  `#7CF2A2` (head); dot in the head colour. Light launcher variant uses the same layers (the background is part of the icon).
- Monochrome layer: the arc + dot as a single white path (Android 13 themed icons tint it).
- Notification small icon `ic_stat_circa`: the arc + dot, white on transparent, 24 dp, 2 dp stroke equivalent.
- Source: `docs/brand/circa.svg` (mark), `docs/brand/circa-icon.svg` (full icon with background). Three variants are
  mocked first (gap size / taper / dot) on one HTML page rendered at 48, 108 and themed on light + dark launcher
  backgrounds; the user picks one before the ledger item runs.

Implementation (ledger F1):
- `applicationId` stays `com.nsheremet.circa` so installs upgrade in place; `app_name` → "Circa", launcher label, `README`
  title ("Circa — self-hosted health for the Colmi ring"), GitHub release titles from the next release on, the
  notification channel name, `Theme.Circa` style name unchanged (not user-visible). The GitHub repo may be
  renamed later by the user (GitHub redirects the old URL).
- **Adaptive icon**: `res/mipmap-anydpi-v26/ic_launcher.xml` with a vector foreground (`res/drawable/ic_launcher_fg.xml`),
  a flat background colour, and a `monochrome` layer so Android 13+ themed icons work; legacy `mipmap-*/ic_launcher.png`
  rasters generated from the same vector (Android Studio Image Asset, or `vd-tool`). Foreground keeps a 66 dp safe zone.
- **Notification small icon**: a white-on-transparent version of the mark (today the framework info icon is used).
- Splash: Android 12+ system splash picks the launcher icon up automatically (`Theme.SplashScreen` not required).
- In-app: the Settings screen footer shows the mark + "Circa 3.x.y"; the Today large title stays "Today".
- Order: F0 (icon mock-ups, user picks) runs any time; F1 lands before R3 so v3.0.0 ships under the new name and icon.

## Ledger order and parallelism

Batches the orchestrator can run (files do not overlap inside a batch):

1. **A1** (backend journal) ‖ **D1** (theme foundations)
2. **A2** (app journal; needs A1) ‖ **B1** (backend strain) ‖ **E1** (backend `/week`)* — *E1 and B1 both edit
   `main.py`/`notify.py`: run E1 after B1 instead.
3. **A3** (check-in notification; needs A2) ‖ **B3** (backend insights; needs B1 for `strain`, A1 for journal)
4. **B2** (Body signals; needs B1 + A2 since both edit `TodayScreen.kt`) → **B4** (insights + Heart; needs B3, B2 for TodayScreen)
5. **R1 v2.1.0**
6. **C1** (backend sleep depth) → **C2** (Sleep screen) → **R2 v2.2.0**
7. **D2** → **D3** → **D4** → **D5** → **R3 v3.0.0**
8. **E1** → **E2** ‖ **E3** → **R4 v3.1.0**

Design items come after the feature items that touch the same screens so the restyle is done once on the final content.
D1 runs early because it only touches `Theme.kt` and fonts and every later UI item benefits from the new tokens.

## Verification (per phase)

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q` — every new pure function has
  fixture tests; every new route has a shape test; migrations are tested on a copy of a v2 database (`tags` rows present).
- App: `.\gradlew.bat :app:compileDebugKotlin`; emulator Pixel_9a screenshots in light and dark, seeded via the local
  stack (`infra/`, `curl` a synthetic fortnight: 14 nights with HR/HRV/stress/SpO₂/steps + journal entries, one "strain"
  night with RHR +8 / HRV −30 %).
- Release: `assembleRelease`, apksigner cert `3c236b22`, install over the previous release on the emulator, GitHub
  release with the APK; homelab `scp backend/` + `docker compose up -d --build`; smoke: `/health`, `/today`, `/journal`,
  `/sleep`, `/week`.
- User (real ring): after R1 — log two journal days and check the Body signals card goes "calm"; after R2 — compare the
  hypnogram's HR low with the other app; after R3 — look at every tab in light and dark; after R4 — place the widget.

## Risks

- **Strain false positives** after alcohol, hard training or a late night; the journal `explain` line and the 2-night
  streak rule limit them, but the user must read it as a hint. Temperature (R09) is the real fix later.
- **Medication segmentation** shortens baselines; the signals may sit in "learning" for a week after the start date is set.
- **Sleep-score change** (regularity contributor) shifts scores for the last 14 days on the first restart after C1;
  SQLite is rewritten, VM `score_sleep` keeps the larger value for ties (dedup) — Grafana may show the old value for
  nights whose score dropped. Accepted (scores, not ring data).
- **Design dependencies:** haze and Glance are two new libraries; both are pinned in the catalog and have no transitive
  surprises on minSdk 31. Shared-element APIs are experimental in Compose 1.7 — guarded by `@OptIn`, with a plain fallback.
- **Journal replaces tags** — a v2.0.x app against a v2.1 backend loses the tag chips until updated; nothing else breaks.
- **Widget staleness** depends on background sync; with sync Off the widget says "Open to sync".
