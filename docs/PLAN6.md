# Circa v5.4: Activity tab + five-tab bar

Status: plan (user direction, 2026-11-09). Ledger items live in `NEXTSTEPS.md` under "Active Task: v5.4"; this file
holds the detail each item points to. `docs/PLAN.md`, `PLAN3.md`, `PLAN4.md`, `PLAN5.md` stay the references for what
already exists; nothing in them is re-derived here.

## Context and decisions (user, 2026-11-09)

- Steps, calories, distance and workouts have no home in the app: the ring sends them hourly and the backend scores
  them, but the app only shows the Activity ring and the Cardio card on Today. They get a screen of their own.
- Six tabs is one too many. The bar becomes five: **Today · Sleep · Activity · Body · Trends**.
  - **Body** = the existing Heart and Stress screens under one tab, switched by the iOS segmented control Trends already
    uses (`Segmented`). Each screen keeps its own code, large title, `DayPicker` and data loading; nothing is stacked.
  - **Settings** leaves the bar and opens from a gear in Today's title row (Apple Health puts the profile there). It
    stays a nav route; Back returns to Today. Settings content is unchanged.
- **Workouts move from Heart to Activity** (they are activity, not vitals). Heart keeps drawing workout spans on its
  24 h chart. A workout row can expand to show the heart rate during that session — that is the only new "during the
  activity" data; the ring has nothing else per workout.
- Standing rules hold: tokens-only UI (`Color(0x` only in `Theme.kt`), `Theme.kt` components, no new packages, one
  plain sentence per number, nothing shown before it has data, never delete ring data, agents never edit the ledger.

## What exists (do not rebuild)

- Raw series: `biometric_steps`, `biometric_calories`, `biometric_distance_meters` (hourly, timestamp = hour start),
  `biometric_hr_bpm` (5 min, 0 = off finger). `main._hourly(metric, d) -> {hour: value}` reads one hourly metric for
  one local day. `_score_day` already derives `worn` (hours with HR > 0) and `awake_h`.
- `/today?date=` returns `inputs.steps`, `inputs.calories`, `activity.contributors` (incl. `inactive_hours`),
  `cardio {status, load, minutes, target, days}`, `workouts [{start, end, minutes, kind, avg, max, load, confirmed}]`.
  `/hr?date=` returns the day's 5-min points. `/trends?metric=steps&days=30` works today (`steps` is in `INPUTS`).
  `PATCH /workouts` relabels.
- App: `DayPicker`, `dayLabel`, `InsetCard`, `EmptyState`, `Segmented` (Trends, internal), `duration(min)`, `clock`,
  `Prefs.stepGoal`; Heart's private `Section`, `Value`, `TrendCard`, `TintButton`, `Chart` (line with
  `DoseWindows` spans and a scrub marker), `WorkoutRow`, `KINDS`, the "What was this?" dialog and the
  "Add to journal" button; Stress's `ThroughTheDay` (Vico 48 columns, per-column colour, fixed 0..47 range) is the
  column style to copy for hourly steps (24 columns, 0..23). `LargeTitleScaffold(title, back, refreshing, onRefresh,
  content)` has a leading `back` slot and no trailing slot yet. `Api.get(ctx, path)`; `MainActivity` is singleTop and
  reads `open` / `date` extras into `link`; `App.kt` routes `trends | sleep | month | journal`.

## Phase A — backend

### A1 Backend `/activity` (`main.py`; test `test_api.py`)

- `GET /activity?date=YYYY-MM-DD` (default today; 422 on a bad date via `_day`). Response:
  `{"date": "...", "hours": [{"h": 0..23, "steps": float, "calories": float, "distance_m": float, "worn": bool}]}`.
  One entry per hour from `vm.midnight(d)` up to the current hour (whole day for past days); missing samples are `0.0`;
  `worn` = that hour has a `biometric_hr_bpm` sample > 0 (same rule as `_score_day`, read once with
  `vm.series(..., "5m")`). No totals and no goal in the response: the app sums and owns the goal.
- Reuse `_hourly` for the three metrics; one new helper at most. ≤ 25 lines.
- Test: seed three hourly metrics + HR for one hour in the stub VM, assert 24 entries for a past date, values on the
  seeded hour, `worn` true only there, 422 on `date=bad`.

## Phase B — app

### B1 App five-tab bar + gear (`ui/App.kt`, `ui/Theme.kt`, `ui/TodayScreen.kt`, `ui/SettingsScreen.kt`,
`ui/HeartScreen.kt`, `ui/StressScreen.kt`)

- `TabItem` becomes Today, Sleep, Activity (`Icons.Rounded.DirectionsWalk` / `Outlined.DirectionsWalk`), Body
  (`Icons.Rounded.MonitorHeart` / `Outlined.MonitorHeart`), Trends. Settings is a plain `composable("settings")`
  route outside the enum, reached with `nav.navigate("settings")` (no popUpTo: Back pops to the tab it came from).
  `TabBar` highlights nothing while on `settings`.
- `LargeTitleScaffold` gains `action: (@Composable () -> Unit)? = null`, drawn at the trailing end of the large-title
  row and of the collapsed bar (same vertical centre as the title, `AppSpace.l` end padding). Today passes a 24 dp
  `Icons.Rounded.Settings` icon in `c.tint` with `contentDescription = "Settings"` and `tick()` haptic → the route.
  `SettingsScreen` gets `onBack` and shows `back = { "‹ Today" }` (copy Stress's Resilience back link).
- `BodyScreen(backend)`: `rememberSaveable { 0 }` segment; renders `HeartScreen(backend, header)` or
  `StressScreen(backend, header)` where `header = { Segmented(listOf("Heart", "Stress"), seg) { seg = it } }`.
  Heart and Stress each take the new `header: @Composable () -> Unit` parameter and emit it as their first list item
  (`item { header() }`, `AppSpace.m` bottom padding). Titles stay "Heart" and "Stress". Resilience detail unchanged.
- Deep links unchanged (`trends | sleep | month | journal`). Nothing opens Heart or Stress by link today.
- Check: compileDebugKotlin; Pixel_9a: five tabs, gear → Settings → Back to Today, Body segment switches and survives
  a tab change, light + dark, 360 dp bar labels fit.

### B2 App Activity screen (`ui/ActivityScreen.kt` new, `ui/HeartScreen.kt`, `ui/Theme.kt`, `ui/Api.kt`, `ui/App.kt`)

- `Api.activity(ctx, date)` → `/activity?date=`. Screen loads `/activity`, `/today?date=`, `/trends?metric=steps&days=30`
  on entry, on `date` change and pull-to-refresh (same pattern as Heart's `load()` / `loadDay()`).
- Move `Section`, `Value`, `TrendCard`, `TintButton` from Heart to `Theme.kt` as `internal` (Today keeps its own
  `TintButton` only if the signature differs; otherwise delete it too). Move `WorkoutRow`, `KINDS`, the "What was
  this?" dialog, `journalWorkout` state and the Workouts card out of Heart into Activity unchanged in behaviour.
  Heart keeps `workouts` only to draw the spans.
- `LargeTitleScaffold("Activity", ...)`, no-backend → `NoBackendTab`. Order of items:
  1. `DayPicker(date)`.
  2. `Section("Steps")`: footnote "Today" / "Last" as Heart; `Value(total, "steps", c.activity)`;
     subhead "{dayLabel} · {pct}% of your {goal} goal" (`Prefs.stepGoal`; `pct` = round(total / goal × 100)).
     Then `HourlySteps`: 24 Vico columns copied from Stress's `ThroughTheDay` (fixed x 0..23, y 0..max(500, peak)),
     column fill `c.activity` when `worn`, `c.secondaryLabel` at 35 % alpha when not worn (so an empty hour reads
     "ring off", not "lazy"); x labels 12 AM / 6 AM / 12 PM / 6 PM; marker "{steps} steps · {hour}". Footnote under
     the chart: "{n} inactive hours" = worn awake hours with < 50 steps (count from `hours`; awake = 08–22 local, the
     notify rule), omitted when 0. Empty day → `EmptyState(Icons.Rounded.DirectionsWalk, "No steps yet today" /
     "No activity for this day", "Steps are read every hour while you wear the ring.")` and items 3–4 are skipped.
  3. Two tiles (Heart's Resting HR / HRV row pattern): **Calories** `"{kcal} kcal"` (sum of `calories`, rounded) and
     **Distance** `"{km} km"` (sum of `distance_m` / 1000, one decimal; "—" when 0).
  4. Two tiles: **Cardio load** `{load}` with footnote "of {lo}–{hi} today" when `cardio.target` exists, else
     "Learning · {days}/5 days" when `status == learning`, else "Set your birth year in Settings"; **In zones**
     `duration(sum(minutes))`. Row omitted when `cardio.load` is null and status is `ok`.
  5. `Section("Workouts")` exactly as it was on Heart (header line "Cardio load N · M min in zones", rows, "Add to
     journal" / "In your journal"), shown only when the day has workouts. New: tapping a row toggles it open instead
     of opening the dialog; the open row shows a 120 dp `Chart` of the day's `/hr?date=` points inside
     `[start, end]` in `c.heart` (x = 5-min slots from `start`, marker "{bpm} bpm · {clock}") with the subhead
     "Heart rate during this {kind}: avg {avg} · max {max} bpm", and a `TintButton("What was this?")` that opens the
     existing dialog. Only one row open at a time. The `/hr` fetch happens once per day view (already needed by
     Heart; load it here too, no sharing).
  6. `TrendCard("Steps · 30 days", stepsTrend, "steps", c.activity)` — same component as Heart's RHR card.
- Copy: every tile has one footnote sentence; no "Oura"/"Whoop"; distance and calories say nothing about accuracy
  beyond the tile label (the ring reports them).
- Check: compileDebugKotlin; seed the local stack with hourly steps/calories/distance + HR + a workout; Pixel_9a light
  + dark: filled day, not-worn hours, empty day, past day, workout row open with the HR slice, relabel still works and
  survives a rescore, "Add to journal", trend card, no-backend.

## Phase R

### R10 Release v3.7.0 "Activity"

- versionCode 18 / 3.7.0; release build + `apksigner` cert `3c236b22`; emulator install -r over v3.6.0, 20 flings on
  Today and Activity, crash buffer empty. Homelab: back up `circa.db` inside the volume as R9 did, redeploy `backend/`
  (A1), smoke `/health`, `/activity`, `/activity?date=bad` 422, `/today` 200. `git push`, GitHub release with the APK.

## Ledger order and parallelism

A1 → B1 → B2 → R10. B1 and B2 both edit `HeartScreen.kt`, so they are sequential; B2 needs A1 for real data but can
compile against the shape above.

## Verification

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q`.
- App: `.\gradlew.bat :app:compileDebugKotlin`, then the Pixel_9a checks listed per item against the local stack.
- Never run instrumented tests; never regenerate or re-import history.

## Risks

- Material Icons Extended must contain `DirectionsWalk` and `MonitorHeart` (both exist in the current artifact); if a
  name is missing fall back to `Favorite` for Body.
- `Segmented` is `internal` in `TrendsScreen.kt`; B1 uses it as is (no move).
- The gear sits in the collapsed bar too; on 360 dp the large title and gear share one row — title is left-aligned
  and short ("Today"), so no overlap is expected; check it.
- Hourly `biometric_distance_meters` and `biometric_calories` may be absent for days before the metric was added;
  tiles show "—", not 0.
