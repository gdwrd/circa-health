# Ring Health v5.5: Breathe (4-7-8) + explanations

Status: plan (user direction, 2026-11-09). Ledger items live in `NEXTSTEPS.md` under "Active Task: v5.5"; this file
holds the detail each item points to. `docs/PLAN.md` … `PLAN6.md` stay the references for what already exists.

## Context and decisions (user, 2026-11-09)

- The user wants the tap-to-explain text that scores have on **Vitals** (Today) and on each **sleep stage** (Sleep).
- A **Breathe** feature: the 4-7-8 technique the user already practises (in through the nose 4 s, hold 7 s, out through
  the mouth 8 s, repeat). Entry is a button **left of the gear in Today's title row**, not on the Heart card. Rounds
  are configurable; the 4-7-8 timings are fixed. Live heart rate from the ring is shown during the session.
- Sessions are **journal entries that log themselves** (Apple's Mindful Minutes pattern) with a one-tap feeling before
  and after (Apple's State of Mind reduced to one tap). No manual "Meditated?" toggle.
- The effect is **shown as evidence, never baked into scores**: Sleep / Readiness / Activity measure outcomes and a
  behaviour must not feed the outcome it is judged by. Evidence = heart rate during and after, the ring's stress
  reading in the hour before vs after, and (via the existing journal insights) the nights that followed.
- Research (2026-11-09): slow breathing in general has reliable short-term HRV / blood-pressure effects and a modest
  effect on negative emotion (2024 meta-analysis, 31 studies, n≈1100). 4-7-8 itself is thinly studied: one small 2022
  study positive, a 2025 study of 84 students found 6 breaths/min beat 4-7-8 on HRV with no mood or BP change. No
  trial on sleep. **Copy never says 4-7-8 is proven**; it says what the user's own body did.
- Standing rules hold: tokens-only UI, `Theme.kt` components, no new packages, one sentence per number, nothing shown
  before it has data, never delete ring data, agents never edit the ledger.

## What exists (do not rebuild)

- `runLiveHr(ctx, address, onBpm, onProgress)` in `SettingsScreen.kt`: connects, streams `0x69` live packets, stops
  after 30 s or ~6 valid readings, always sends stop in `finally`. `MeasureNow` on Heart uses it. Live packets arrive
  about once a second.
- Journal: `db.KINDS`, `journal(date, kind, value, note, ts)` with a unique index on `(date, kind)` for every kind but
  `medication`; `POST /journal` validation per kind; Today chips (`Entry.label()` adds the clock for `medication`),
  `CheckInSheet`, `WhatAffectsYou` (`AFFECT_KINDS`, `AFTER`, `METRIC`, `effectColor`); `insights.kinds` compares
  nights after days with / without a kind (`NIGHT_KINDS`, `compare`, `effect`), pure, no I/O.
- `_score_day` stores `inputs` per day; anything in `INPUTS` is a `/trends` metric for free. `vm.forward_import(body)`
  writes Prometheus lines into VictoriaMetrics. `vm.series` returns **only the first result series**, so a new label set
  on `biometric_hr_bpm` would be dropped or shadow the ring's data: breath samples get their **own metric name**.
- Heart: `DoseWindows` (private) shades spans on the 24 h `Chart`; `Section`, `Value`, `TintButton`, `TrendCard`,
  `InsetCard`, `ScoreRing`, `EmptyState`, `DayPicker`, `duration`, `clock`, `Hairline` are shared in `Theme.kt`.
  Stress: `ThroughTheDay` 48 Vico columns; the "Load and recovery" tile row.
- `LargeTitleScaffold(title, back, action, …)`: `action` is one composable at the trailing end of the large title row
  and the collapsed bar; Today passes the 44 dp gear. `App.kt` pops `settings` before any tab switch.
- `Prefs.stepGoal` is the pattern for a small persisted int.

## Phase E — explanations (app only)

### E1 Vitals + sleep stage explanations (`ui/TodayScreen.kt`, `ui/SleepScreen.kt`)

- Today Vitals tiles become `clickable`; a tapped tile unfolds a footnote (`AnimatedVisibility`, `AppType.footnote`,
  `c.secondaryLabel`) inside the tile, same as `Contributor`. State: one `expanded: String?` per grid. New map
  `VITALS_INFO` next to `SCORE_INFO`:
  - `hr`: "Your latest reading from the ring, in beats per minute. The ring samples every 5 minutes while worn; the
    line is the last 24 hours. Resting adults are usually around 50 to 80."
  - `hrv`: "Heart rate variability, the beat-to-beat variation in milliseconds, measured every 30 minutes. Higher
    usually means better recovery. Compare it with your own baseline, not with other people."
  - `spo2`: "Blood oxygen saturation, measured hourly during sleep. 95 to 100 percent is normal. A single low reading
    usually means a loose ring, not a problem."
  - `battery`: "Charge left in the ring. You get a reminder below 15 percent."
- Sleep "Time in stages" rows become `clickable` with the same unfolding footnote; map `STAGE_INFO`:
  - Awake: "Minutes awake after you first fell asleep, including brief wakes you do not remember. Under about
    20 minutes a night is typical."
  - REM: "Rapid eye movement sleep, when most dreaming happens. It supports memory and mood, is typically 20 to
    25 percent of the night, and comes mostly toward morning."
  - Light: "The stage between waking and deeper sleep, usually about half the night. Heart rate and breathing slow
    down here."
  - Deep: "The most restorative stage, when the body repairs itself and heart rate is lowest. Most of it comes in the
    first half of the night. 60 to 90 minutes is typical."
  - Footnote under the card (always shown): "The ring estimates stages from movement and heart rate. This is an
    approximation, not a sleep lab."
- Check: compileDebugKotlin; Pixel_9a light + dark: a tile and a row open and close, both tiles in a row grow together.

## Phase G — Breathe

### G1 Backend sessions, journal kind, insights (`db.py`, `main.py`, `insights.py`; tests `test_api.py`, `test_insights.py`)

- Schema: `CREATE TABLE IF NOT EXISTS breath_session(start INTEGER PRIMARY KEY, "end" INTEGER, rounds INTEGER,
  hr_start REAL, hr_end REAL, hr_min REAL, feel_before INTEGER, feel_after INTEGER, stress_before REAL,
  stress_after REAL)`. `KINDS += "breathe"`. The journal unique index must allow several sessions a day: migration
  `DROP INDEX IF EXISTS journal_day_kind` + recreate with `WHERE kind NOT IN ('medication', 'breathe')` (precedent:
  `_migrate_tags`). db helpers: `add_breath(row)`, `breaths(start: date, end: date) -> list[dict]`,
  `set_breath_stress(start, before, after)`.
- `POST /breathe` body `{start, end (unix s), rounds, feel_before, feel_after, samples: [[t_s, bpm], …]}`.
  422 unless: ints, `start < end`, `end - start <= 3600`, `rounds` 1..30, feelings `null` or 1..5, ≤ 1200 samples
  with bpm 30..220 inside `[start, end]`. Writes the row (`hr_start` = first sample, `hr_end` = last, `hr_min` = min;
  all `null` without samples), the journal row (`date` = local date of `start`, `kind = "breathe"`, `value = "HH:MM"`
  of start, `ts = start`), forwards the samples as `biometric_breath_hr_bpm{device="colmi_r02"} <bpm> <t_ms>` via
  `vm.forward_import`, calls `rescore({date})`, returns the session in the `/hr` shape below.
- `_score_day(d)`: for each session of `d`, `stress_before` = mean of `biometric_stress` samples > 0 in
  `[start - 3600, start)`, `stress_after` = mean in `(end, end + 3600]`; `null` when a side has no sample yet (the
  ring reports stress every 30 min and only on the next sync, so `after` fills in on a later rescore).
  `inputs["breath_min"]` = sum of session minutes on `d` (0.0 when none); `INPUTS += "breath_min"` so
  `/trends?metric=breath_min` works.
- `GET /hr?date=` adds `"breaths": [{start, end, rounds, hr_start, hr_end, hr_min, feel_before, feel_after,
  stress_before, stress_after, points: [{t, bpm}]}]`, points from `vm.series("biometric_breath_hr_bpm", start, end,
  "5s")`. `GET /today?date=` adds `"breathe": {"today_min", "week_min", "sessions"}` (week = the 7 days ending on the
  date; omitted when there has never been a session).
- `insights.NIGHT_KINDS += "breathe"` (nights after a breathing day vs without, same metrics). `insights.kinds` gains
  a `breaths: list[dict]` parameter; `out["breathe"]["stress"] = {"delta": round(mean(after - before), 1), "n": n}`
  over sessions with both values when `n >= 3`, else `None`. `journal_insights` passes `db.breaths(start, d)`.
- Tests: POST 422 cases (bad span, rounds 0, bpm 300), 200 shape + journal row visible in `GET /journal` + `/hr`
  `breaths` with points from the stub VM, two sessions on one day both stored; insights `stress.delta` with 3
  sessions and `None` with 2. ≤ 120 backend lines.

### G2 App Breathe screen + Today button (`ui/BreatheScreen.kt` new, `ui/TodayScreen.kt`, `ui/App.kt`, `ui/Theme.kt`,
`ui/SettingsScreen.kt`, `ui/Api.kt`, `Prefs.kt`)

- `Theme.kt`: token `breathe` (iOS system mint: light `0xFF00C7BE`, dark `0xFF63E6E2`). Only new colour.
- Today title row: `action` becomes a `Row` of two 44 dp circles, Breathe (`Icons.Rounded.SelfImprovement`, fall back
  `Icons.Rounded.Spa`, `contentDescription = "Breathe"`, `c.tint`, `tick()`) then the gear. Breathe shows only when
  `backend == true` (saving needs it); the gear always. `App.kt`: `composable("breathe")`, popped before a tab switch
  like `settings`.
- `Prefs.breathRounds: Int` (key `breath_rounds`, default 5, clamp 3..12).
- `runLiveHr` gains `durationMs: Long = 30_000` and `stopWhenStable: Boolean = true` (current callers unchanged);
  the breathe screen passes `durationMs = rounds * 19_000 + 15_000`, `stopWhenStable = false`, and collects
  `(timestampMs, bpm)` from `onBpm` (extend to `(Long, Int)` or add `onSample`). Cancelling the job runs the existing
  `finally` (stop + disconnect). Nothing is pushed by `runLiveHr` in this mode (`pushLiveHr` only when
  `stopWhenStable`).
- `BreatheScreen(onBack)` = `LargeTitleScaffold("Breathe", back = "‹ Today")`, three states:
  1. **Start**: 160 dp circle in `c.breathe` at 15 % alpha with "4 · 7 · 8" inside; subhead "In through the nose
     4 s, hold 7 s, out through the mouth 8 s."; `InsetCard` row **Rounds** with `−` / `+` in `c.tint` (3..12,
     haptic tick, saved to Prefs) and footnote "About {m:ss}" (= rounds × 19 s); `InsetCard` **How do you feel?**
     with five faces (`SentimentVeryDissatisfied`, `SentimentDissatisfied`, `SentimentNeutral`, `SentimentSatisfied`,
     `SentimentVerySatisfied`, 32 dp, selected in `c.breathe`, others `c.secondaryLabel`, optional); `TintButton`
     **Begin** (same guards as `MeasureNow`: address set, Bluetooth permission).
  2. **Running**: the circle scales 0.6 → 1.0 over 4 s (In), holds (Hold), 1.0 → 0.6 over 8 s (Out) with
     `Animatable` + `tween(LinearEasing)`; phase word (`AppType.title`) and a whole-second countdown inside
     (`AppType.largeTitle`); "Round {n} of {m}" under it; live "{bpm} bpm · started at {first}" in `c.heart`
     (`—` until the first valid sample); `tick()` at each phase start; `LocalView.current.keepScreenOn` true while
     running (`DisposableEffect`); reduce-motion: alpha 0.5 → 1.0 instead of scale. **Stop** (plain row button).
     Stop before the first round completes discards the session (back to Start); later it ends at the current round.
     Live HR runs in parallel and keeps streaming through the finish screen until Done or Stop.
  3. **Finished**: `ScoreRing(1f, c.breathe)` with "{m:ss}" and "{n} rounds" inside; "{hr_start} → {hr_end} bpm" in
     `AppType.title` and footnote "Lowest {min} · after {s} s" (time from start to the first lowest sample); footnote
     "Stress change arrives with your next sync."; `InsetCard` **And now?** faces; `TintButton` **Done** →
     `Api.postBreathe(ctx, start, end, rounds, feelBefore, feelAfter, samples)` → `onBack()`. A failed POST stays on
     the screen with "Could not save. Try again." under the button (same rule as F9b).
- Copy on the Start screen (one footnote under the circle, tap to unfold like `Contributor`): "Slow breathing lowers
  your heart rate and raises heart rate variability for a few minutes afterwards; that part is well studied. Whether
  it helps you sleep is something your own data will show in What affects you."
- Check: compileDebugKotlin; Pixel_9a light + dark (no ring: Begin shows the address guard; the phase animation can be
  checked by stubbing `runLiveHr` locally, revert); then one run on the real ring: does live HR stream for 95 s+?

### G3 App Heart, Stress, Today insights (`ui/HeartScreen.kt`, `ui/StressScreen.kt`, `ui/TodayScreen.kt`,
`ui/TrendsScreen.kt`, `ui/Theme.kt`)

- Move `DoseWindows` to `Theme.kt` as `internal`. Heart: breath spans shaded on the 24 h chart
  (`DoseWindows(spans, c.breathe.toArgb(), alpha = 31, line = false)`); after the Heart rate section, when the day has
  `breaths`: `Section("Breathing")` with one row per session: "{clock} · {rounds} rounds · {m:ss}" (`AppType.body`)
  and a second line "HR {a} → {b}" + " · stress {x} → {y}" (only when both present) + the two faces (16 dp) when set.
  Tap toggles a 120 dp `Chart` of `points` (x = seconds since start, `c.breathe`, marker "{bpm} bpm · {s} s").
- Stress: "Load and recovery" becomes `tiles.chunked(2)` with a third tile **Mindful minutes** = `duration(today_min)`
  with line "{week_min} min this week" (from `/today breathe`; tile omitted when the key is absent). `ThroughTheDay`
  gets a 1.5 dp `c.breathe` line at each session start (`DoseWindows` with `(x, x)` windows, `line = true`).
- Today: `AFFECT_KINDS += "breathe"`, `AFTER["breathe"] = "after breathing"`, `KIND_LABEL["breathe"] = "Breathe"`,
  `Entry.label()` appends the clock for `breathe` as for `medication`, `WHY["breathe"] = "Sessions log themselves;
  we compare the hour and the night after with the rest."` (shown nowhere yet, kept for the sheet's future).
  `WhatAffectsYou` row for `breathe`: when `stress` is present its first line is "Stress {delta:+d} in the hour after ·
  {n} sessions" (`effectColor` with lower-is-better), then the usual night effects.
- Trends: metric chip **Mindful min** → `breath_min`, columns, only if the chip list is a data map (one entry);
  otherwise skip and say so.
- Check: compileDebugKotlin; seed the local stack via `POST /breathe` (two sessions, one with stress both sides via
  seeded `biometric_stress`); Pixel_9a light + dark: Heart card + spans + open row, Stress tile + lines, Today chip
  "Breathe 9:41 PM", What affects you row in learning and ok states.

## Phase R

### R11 Release v3.8.0 "Breathe"

- versionCode 19 / 3.8.0; release build + `apksigner` cert `3c236b22`; emulator install -r over v3.7.0, 20 flings on
  Today + open Breathe, crash buffer empty. Homelab: back up `ring.db` inside the volume as R10 did, redeploy
  `backend/` (G1; the index migration runs at start), smoke `/health`, `POST /breathe` 422, `/hr?date=` has `breaths`,
  `/today` 200. `git push`, GitHub release with the APK.

## Ledger order and parallelism

E1 → G1 → G2 → G3 → R11. E1 is independent and lands first. G2 and G3 both edit `TodayScreen.kt` and `Theme.kt`, so
they are sequential; G2 can compile against the shapes above before G1 is deployed.

## Verification

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q`.
- App: `.\gradlew.bat :app:compileDebugKotlin`, then the Pixel_9a checks per item against the local stack.
- Never run instrumented tests; never regenerate or re-import history.

## Risks

- **Live HR beyond 30 s is untested on the ring.** If the ring stops streaming on its own, G2 re-sends
  `liveHrStartRequest()` when no packet arrives for 5 s (one line in the loop); if it refuses while the SyncWorker is
  connected, the Begin guard shows the existing "ring may be busy" line.
- Stress before/after rests on two ring samples each side (30 min grid); say "in the hour after", never "immediately".
- The journal unique-index migration touches a live table on the homelab: back up `ring.db` first (R11 does).
- `SelfImprovement` / `Sentiment*` icons must exist in `material-icons-extended`; fall back to `Spa` and to five
  numbered circles if not.
- Sleep effects need weeks of sessions; the insights card's learning state covers this and nothing else is shown.
