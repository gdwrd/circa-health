# Circa v5: load, recovery and the long view

Status: plan (user direction, 2026-11-09). Ledger items live in `NEXTSTEPS.md` under "Active Task: v5"; this file holds
the detail each item points to. `docs/PLAN.md` (scores), `docs/PLAN3.md` (journal, signals, design) and `docs/PLAN4.md`
(day history, ring alerts) stay the references for what already exists; nothing in them is re-derived here.

## Context and decisions (user, 2026-11-09)

- Build all seven of the "expensive ring" features that the R02 data allows, in this order:
  **cardio load + daily target** → **auto-detected workouts** → **restorative time** → **fitness trend** →
  **ideal bedtime window** → **sleep debt** → **monthly report**. The backend lands first per phase, the app follows.
- **UI must stay friendly.** Rules for every new piece: it lives on the screen where the user already looks for that
  topic (nothing new on the tab bar); one new card at most per screen, or a line inside an existing card; every number
  gets one plain sentence saying what to do with it; every estimate says what it is estimated from; nothing shows until
  it has enough data, and the learning state says how many days are left. Copy never says "Oura", "Whoop", "VO₂max
  test" or gives medical advice.
- **Honesty of estimates.** Heart-rate zones need a maximum heart rate, which comes from age (`208 − 0.7 × age`), so
  the user enters a birth year in Settings; until then the cardio features show a one-line prompt, not a guess. The
  fitness number is a resting-heart-rate formula and is labelled "estimate … a trend, not a measurement". Workout
  detection from 5-minute heart rate catches sessions of 20 min or more and names only "Walk" or "Workout".
- **Nothing is written to the journal automatically.** A detected workout offers an "Add to journal" button.
- **Cycling (user, 2026-11-09).** The user rides often, so **Ride** is a workout kind. The ring cannot see pedalling: a
  ride and a gym session both look like 20+ min of raised heart rate with few steps. So (1) Settings has one choice,
  "Low-step sessions are: Rides | Workouts" (`low_step_kind`, the user picks Rides), which the detector uses for every
  low-step session; (2) any session can be re-labelled on the Heart screen (Walk / Ride / Workout) and the label is
  stored, so rescoring keeps it; (3) copy says the kind is a guess until the user confirms it. No "Run": 5-minute data
  cannot separate a run from a brisk walk.
- **Settings reorganised (user, 2026-11-09, design left to the orchestrator).** Groups ordered by how often they are
  touched: **Ring** (what the ring did and when it syncs) → **You** (birth year, goals) → **Medication** → **Workouts**
  → **Notifications** (one switch per kind — new; the app now sends nine kinds) → **Setup** (ring address, server, Android
  battery; set once). "Measure heart rate now" leaves Settings (Heart has "Measure now"). Section footers explain
  each group in one sentence.
- **Release scope (user, 2026-11-09).** R6 includes `git push`, the GitHub release with the APK attached and the
  homelab deploy — the orchestrator does them this time.
- Standing rules hold: tokens-only UI (`Color(0x` only in `Theme.kt`), `Theme.kt` components, no new packages, no
  new tabs, never delete ring data, `scoring.py`/`cardio.py` pure and unit-tested, agents never edit the ledger.

## What exists (do not rebuild)

- `main._score_day(d)` already queries the day's 5-min `biometric_hr_bpm` (for `worn`), hourly steps and calories
  (`_hourly`), the sleep sessions (`_sessions`), the 14 prior rows (`prior`, with `base` = 14-day means of rhr/hrv/
  sleep_min/steps) and `awake(ts)`. Everything in v5 is computed there and stored in the row's `contributors`
  (`inputs` for per-day numbers, named keys for structures) — the same place `signals`, `stress`, `wear` live.
- `/today` (optionally `?date=`) returns the row + `inputs`; `/sleep?date=` returns the night; `/trends?metric=`
  accepts any key in `INPUTS` (with baselines from the `baselines` table) and `/week?end=` summarises 7 days.
- `scoring.baseline(values_14d)` = trailing mean, None under 5 values. `scoring._lin`, `_band` exist.
- `insights.compare(with, without, metrics)` gives per-metric effects (`strong` when the delta clears `FLOORS`).
- Notifications: `_rule(now, dates)` + `_fired(kind, now)` + `_add(kind, title, body, now, extra)`; `_bedtime`
  fires 30 min before the 14-day median bedtime; `_readiness` posts "Readiness N" with the lowest factor;
  `_weekly` Monday 08–12 with `extra {open: trends}`. App deep links: `open = journal | trends | sleep`.
- App: `DayPicker`, `dayLabel`, `InsetCard`, `EmptyState`, `ScoreRing`, `CountUp`, `duration(min)`, `clock(...)`,
  `tick()`; Heart's private `Chart` draws dose windows as shaded spans; Trends `METRICS` list drives chips + chart;
  Trends `WeekCard`; Settings > Goals rows (Step goal, Sleep need) with their number editors; `Segmented`.
- Raw series: `biometric_hr_bpm` (5 min, 0 = off finger), `biometric_steps` (hourly), `biometric_stress` (30 min).

## Formulas (all in `backend/app/cardio.py` unless noted; pure, typed, tested)

- `max_hr(birth_year: int, today: date) -> float` = `208 − 0.7 × age` (age = today.year − birth_year).
- `zones(max_hr: float, rhr: float) -> list[float]` = five lower bounds `rhr + f × (max_hr − rhr)` for
  f = 0.5, 0.6, 0.7, 0.8, 0.9 (heart-rate reserve). `zone_of(bpm, bounds) -> int` 0–5 (0 = below zone 1).
  `rhr` here is the 14-day baseline resting HR (`base["rhr"]`); None → no zones.
- `load(bpm: list[float], bounds) -> dict {load: float, minutes: [z1..z5]}`: each 5-min sample adds
  `0.5 × zone` (zone 0 adds nothing); minutes per zone = 5 × samples. Typical day 3–20; an hour in zone 3 = 18.
  Only awake samples (caller filters with `awake(t)`), `bpm > 0`.
- `target(readiness: int | None, mean_load_14d: float | None) -> dict | None`: None when either is None.
  `base = max(mean, 5)`. readiness ≥ 80 → `(1.1, 1.4) × base`, band `recovered`; 60–79 → `(0.8, 1.1)`, `steady`;
  < 60 → `(0.3, 0.7)`, `easy`. `lo`, `hi` rounded to ints, `hi ≥ lo + 2`. Returns `{lo, hi, band}`.
- `workouts(hr: dict[float, float], steps_h: dict[int, float], bounds, awake) -> list[dict]`: sort samples; a sample
  is *active* when its zone ≥ 2; a segment is a run of active samples in which a single non-active sample between two
  active ones stays inside; keep segments with ≥ 4 active samples (20 min); merge segments whose gap is ≤ 10 min.
  Each → `{start, end (last t + 300), minutes, avg_bpm, max_bpm, load, kind, confirmed}`; `kind` = `walk` when the
  covered hours average ≥ 1000 steps/h, else the `low_step_kind` setting (`ride` | `workout`, default `workout`);
  `confirmed` = true when a stored user label (`workout_kind` table, see F1) replaced the guess. Sleep samples are
  excluded by the caller.
- `restorative(hr: dict[float, float], rhr: float | None, steps_h: dict[int, float], awake) -> int | None`: None without
  `rhr` (last night's `inputs.rhr`, else `base["rhr"]`). A sample is *restorative* when awake, `bpm ≤ 1.10 × rhr` and
  the hour's steps < 100 (missing hour = 0 steps). Runs of ≥ 3 consecutive samples (15 min) count; result = 5 × samples.
- `fitness(max_hr: float | None, rhr14: float | None) -> float | None` = `15.3 × max_hr / rhr14`, one decimal (Uth et
  al. resting-HR estimate). Uses the 14-day baseline so it moves slowly. Labelled an estimate everywhere.
- `scoring.sleep_debt(asleep_7: list[float | None], need_min: float) -> dict | None`: nights = non-None values
  (sleep + naps); None under 3 nights; `debt_min = max(0, Σ(need − asleep))` (surplus nights offset shortfalls);
  `suggest_min = 0` when debt < 30 else `min(60, round(debt / 3 / 5) × 5)`. Returns `{debt_min, nights, suggest_min}`.
- `scoring.bedtime_window(nights: list[tuple[float, float]]) -> dict`: input = (bed_start minutes after noon,
  *quality*) per night, where quality = mean of the sleep contributors `total, efficiency, restfulness, rem, deep`
  (timing and regularity are left out so the window is not the timing curve echoed back). Under 14 nights →
  `{status: "learning", nights}`. Candidate windows = every 60-min span starting on a 30-min grid between the earliest
  and latest bedtime; a candidate needs ≥ 5 nights; score = mean quality inside. Best candidate vs mean quality of the
  nights outside it: difference < 3 or no nights outside → `{status: "flat", nights}`; else
  `{status: "ok", start_min, end_min, score_in, score_out, nights, nights_in}` (minutes after noon, like `bed_start`).

## Phase F — backend

### F1 Backend cardio (`backend/app/cardio.py` new, `main.py`, `db.py`, `notify.py`; tests `test_cardio.py`, `test_api.py`, `test_notify.py`)
- Settings keys `birth_year` (default `''`) and `low_step_kind` (default `workout`): `db.SCHEMA` INSERT OR IGNORE,
  `GET /settings` returns them, `PATCH /settings` accepts a 4-digit year between 1900 and this year or `''` to clear,
  and `ride` | `workout` (422 otherwise).
- Table `workout_kind(date TEXT, start INTEGER, kind TEXT, PRIMARY KEY(date, start))` in `db.SCHEMA`;
  `db.workout_kinds(d) -> dict[int, str]`, `db.set_workout_kind(d, start, kind)`. `_score_day` applies a stored label
  to the detected workout whose `[start − 600, end]` contains the label's `start` (sets `confirmed`).
- `PATCH /workouts` body `{date, start, kind}` (kind ∈ walk | ride | workout; 422 otherwise) → upsert, `_score_day(d)`
  for that date, return `{workouts: [...]}` for the day.
- `_score_day`: one 5-min HR query for the day (reuse the `worn` query) → `day_hr = {t: v for awake t, v > 0}`;
  `mh = cardio.max_hr(int(by), d)` when `birth_year` is set; `bounds = cardio.zones(mh, base["rhr"])` when both exist.
  Store `inputs["cardio_load"]`, `inputs["restorative_min"]`, `inputs["fitness"]`,
  `contributors["cardio"] = {load, minutes, bounds} | None`, `contributors["workouts"] = [...]` (empty list without
  bounds). Add `cardio_load` to the `base` keys so `/trends` has its baseline.
- `/today` (+`?date=`): `"cardio": {status: "ok" | "needs_age" | "learning", load, minutes, target, days}` —
  `needs_age` when `birth_year` is empty, `learning` when the 14-day mean load is None (fewer than 5 days with
  `cardio_load`), else `target = cardio.target(readiness_score, mean)`; `days` = how many of the 14 prior rows have
  `cardio_load` (the app's "n/5 days"); `"workouts": row["c"]["workouts"]`.
- `INPUTS` (trends) gains `cardio_load`, `restorative_min`, `fitness`.
- `notify._readiness` body appends ` Cardio target {lo}–{hi}.` when a target exists.
- Tests: zones/zone_of at the bounds; load of a known sample list; target per band and None cases; workouts: a 30-min
  run with one dip inside detected once, two 10-min bursts ignored, two segments 5 min apart merged, `walk` vs
  low-step kind by the setting, a stored label wins and sets `confirmed`; restorative: a 20-min calm run counts, a
  10-min run does not, a step-heavy hour does not; fitness known value; `/settings` birth_year + low_step_kind
  round-trip + 422; `PATCH /workouts` relabels and survives a rescore, 422 on a bad kind; `/today` cardio `needs_age`
  → `learning` → `ok` shape; readiness notification body with target. Backend suite green.

### F2 Backend sleep debt + bedtime window (`scoring.py`, `main.py`, `notify.py`; tests `test_scoring.py`, `test_api.py`, `test_notify.py`)
- `main._bedtime_window() -> dict`: rows of the last 60 days with a session and `c.sleep` contributors →
  `scoring.bedtime_window(...)`. `main._debt(end: date) -> dict | None`: 7 rows ending `end`, `sleep_min + nap_min`.
- `/sleep?date=` adds `"debt": _debt(d)` and `"bedtime": _bedtime_window()`. `/today` adds
  `"tonight": {"bedtime": <window>, "debt": _debt(today)}`.
- `notify._bedtime`: when the window is `ok`, target = today's noon + `start_min` (yesterday's noon before 12:00, as
  now); fire in `[target − 30 min, target)` with title "Wind down" and body "Your best nights start around {HH:MM}.";
  otherwise the existing median behaviour and copy. Still once per day.
- Tests: sleep_debt (surplus offsets shortfall, < 3 nights None, suggest cap 60); bedtime_window (learning < 14,
  flat when quality is even, ok picks the 60-min span with the best quality, outside mean right); `/sleep` and
  `/today` carry the fields; `_bedtime` uses the window and falls back. Backend suite green.

### F6 Backend month (`main.py`, `notify.py`; tests `test_api.py`, `test_notify.py`)
- `GET /month?month=YYYY-MM` (default: the current month; 422 on a bad value): `_month(first_day)` over the rows of that
  month (future days excluded) →
  `{month, start, end, days: [{date, sleep, readiness, activity, steps, sleep_min, cardio_load, workouts: n}],
  means: {sleep, readiness, activity}, previous: {means, scored_days}, scored_days,
  best_week: {start, end, readiness} | None (best 7-day window fully inside the month with ≥ 4 scored days),
  steps_total, sleep_avg_min, restorative_avg_min,
  workouts: {count, minutes, load}, effects: [{kind, metric, delta, n_with, n_without}]}`.
  `effects` = for each journal kind logged ≥ 3 days that month (excluding `medication`, `bp`, `note`), the strongest
  `insights.compare(with, without, ("sleep_score", "readiness_score", "hrv", "rhr", "bed_start"))` entry marked
  `strong`, nights split by the journal day's *next* night as `insights.nights` does; at most 3, by |z|.
  `workouts` also carries `by_kind: {walk, ride, workout}` counts.
- `notify._monthly`: on day 1 between 08:00 and 12:00 local, once (no `monthly` row in the last 20 days), when the
  previous month has ≥ 10 scored days: title "Your {Month name}", body "Sleep 78 · Readiness 74 · Activity 69 avg. 12
  workouts." (the workouts clause only when count > 0), `extra {open: month}`. Add to `evaluate`.
- **Muted notifications**: settings key `muted` (default `''`, comma-separated kinds; validator: every item in
  `readiness, bedtime, inactivity, strain, checkin, weekly, morning, monthly`). `evaluate` iterates `(rule, kind)`
  pairs and skips a muted kind. `/settings` returns `muted`.
- Tests: `/month` shape and means on a seeded month, previous-month block, best_week, workouts totals and `by_kind`,
  one strong effect; bad month 422; `_monthly` fires on the 1st once, not on the 2nd, not under 10 days; a muted kind
  does not fire and an unmuted one still does; bad `muted` 422. Backend suite green.

## Phase G — app

### F5 App Heart workouts + Stress restorative (`ui/HeartScreen.kt`, `ui/StressScreen.kt`, `ui/Api.kt`)
- **Heart, per picked day** (`/today?date=` is already fetched): shade each workout on the 24 h chart as a span in
  `c.heart` at 12 % alpha behind the line (same mechanism as the dose windows, which keep their colour). Under the
  Resting HR / HRV tiles, when `workouts` is non-empty, a section **"Workouts"** with one `InsetCard`: a header line
  "Cardio load {load} · {Σ minutes} min in zones" (`AppType.footnote`, secondary), then one row per workout —
  `Icons.Rounded.DirectionsWalk` for `walk`, `Icons.Rounded.DirectionsBike` for `ride`, `Icons.Rounded.FitnessCenter`
  for `workout`; title "Walk · 42 min" / "Ride · 1 h 10 min" / "Workout · 35 min"; trailing "avg 118 bpm" (secondary) and
  a `›`; second line footnote "2:10–2:52 PM · max 141 · load 6" (clock()), with " · guessed" appended while
  `confirmed` is false. Rows separated by `Hairline`. **Tap a row** → a dialog titled "What was this?" with three rows
  Walk / Ride / Workout (the current kind ticked, `c.tint`) → `Api.setWorkoutKind(ctx, date, start, kind)`
  (`PATCH /workouts`), then the card re-renders from the response. Footer: if that date has no journal `workout` entry
  (journal is already loaded, 30 days) a `TintButton` "Add to journal" → `Api.addJournal(ctx, date, "workout")`, then
  the footer reads "In your journal" (footnote). No workouts → no section (the day's load is on Today and in Trends).
- **Stress:** the "Daytime stress load" card becomes a two-tile row (same tile style as Heart's Resting HR / HRV):
  left "Daytime stress load" — `duration(stressed_hours × 60)`, footnote "in the stressed zone while awake";
  right "Restorative time" — `duration(inputs.restorative_min)`, footnote "calm heart rate, little movement".
  `—` when null. The section title becomes **"Load and recovery"**.
- Check: `compileDebugKotlin`; Pixel_9a light + dark: Heart today with two workouts (one walk, one ride — set
  `low_step_kind` to `ride` first) + spans on the chart, the "What was this?" dialog, a row after relabelling to
  Workout (no " · guessed"), "Add to journal" and after tapping it; Heart on a day without workouts (no section);
  Stress tiles filled and null. Seed: set `birth_year` via `PATCH /settings`, push a synthetic day with a 40-min HR
  climb to zone 3 at 14:00 with 3 000 steps in that hour and a 70-min zone-2/3 block at 18:00 with 100 steps.

### F3 App Sleep debt + Bedtime (`ui/SleepScreen.kt`)
- **Last 7 nights card** (follows the picker, from `/sleep.debt`): under "Hours asleep · goal 7.5 h" add a headline
  and a line — debt ≥ 30 min: **"3 h 10 min behind"** / "Over the last 7 nights against your 7 h 30 min need. An extra
  60 min tonight clears about a third." (second sentence only when `suggest_min > 0`; on a past day: "An extra 60 min
  the next night would have cleared about a third."); debt < 30: **"Caught up"** / "You've slept close to your need
  this week."; `debt == null` (< 3 nights): nothing added.
- **Bedtime card**, new section **"Bedtime"** after Regularity (window is from today, independent of the picker):
  `ok` → headline "Your best nights start {clock(start)}–{clock(end)}"; a strip: 8 dp track spanning 8 PM → 2 AM
  (stretched to include the window and the dots when they fall outside), the window as a `c.sleep` 35 % band, the
  7 bedtimes of the shown week as 6 dp `c.sleep` dots (from the week sessions' `start`), footnote labels at the
  strip ends and middle (clock()); line "Sleep quality {score_in} in that window · {score_out} outside · {nights}
  nights" (footnote, secondary). `learning` → headline "Learning your best bedtime", line "{nights}/14 nights — keep
  wearing the ring to bed." `flat` → headline "No clear best bedtime yet", line "Your sleep looks similar at every
  bedtime over {nights} nights."
- Check: `compileDebugKotlin`; Pixel_9a light + dark: debt behind + caught up (two seeded weeks), Bedtime ok with the
  dots, learning, flat; a past day's debt copy.

### F4 App Today cardio + tonight (`ui/TodayScreen.kt`)
- **Cardio load card**, one `item` right after the Sleep/Activity rings card, before Body signals. `InsetCard`,
  `AppSpace.l` padding: footnote "Cardio load" (secondary); number line — `CountUp(load)` in `AppType.title` + " of
  {lo}–{hi}" (secondary, `AppType.subhead`; omitted without a target); **target bar**: 8 dp rounded track in the
  ring-track tone (`c.secondaryLabel` 12 %), the target band `[lo, hi]` in `c.activity` 35 %, the fill `0..load` in a
  `c.activity → c.light(c.activity)` gradient, scale `0..max(hi × 1.4, load × 1.1, 10)`; coach line (`AppType.subhead`):
  `recovered` "Recovered — room for a bigger day." / `steady` "Steady — a normal day keeps you balanced." / `easy`
  "Take it easy — recovery first."; when `load > hi` append " You're past today's target."; footnote
  "{Σ minutes} min in heart-rate zones". States: `needs_age` → footnote "Set your birth year in Settings to get
  heart-rate zones." (the card still shows the plain load when zones exist? no — without zones there is no load; the
  card shows the title and that line only); `learning` → number + "Learning your usual load · {days}/5 days"
  (`/today.cardio.days`).
  Tap → the card expands (plain `AnimatedVisibility`, like `ScoreDetails`) with five rows "Zone 1 · Easy … Zone 5 ·
  Max" + minutes, each with a 10 dp dot in `c.activity` at alpha 0.35 / 0.5 / 0.65 / 0.8 / 1.0.
- **Tonight line**: under the Cardio load card, when `LocalTime.now().hour >= 18` and `/today.tonight` has anything:
  footnote (secondary, `AppSpace.xs` start padding) "Tonight: bed {clock(start)}–{clock(end)} suits you · {duration}
  sleep debt" — parts dropped when null; debt < 30 → "sleep debt cleared" instead of the duration.
- Check: `compileDebugKotlin`; Pixel_9a light + dark: card in `needs_age`, `learning`, `ok` for each band (seed
  readiness 85 / 70 / 50 by pushing nights; `birth_year` via `PATCH /settings` with curl — the Settings row comes in
  S2), expanded zones, past-target copy; Tonight line (screenshot at ≥ 18:00 local or report it unverified).

### S2 App Settings reorganised (`ui/SettingsScreen.kt`, `Prefs.kt`, `SyncWorker.kt`)
New group order, each a `Section` with a one-sentence footer; rows keep the existing `FieldRow` / `ChevronRow` /
`SwitchRow` / `ButtonRow` / `ValueRow` components (no new row types):
1. **Ring** — "Last sync" (value), "Sync now" (button; the live sync text appears as this group's footer while it
   runs, as "Actions" did), "Sync every" (chevron; the low-battery / charging suffix stays), "Sync log" (chevron).
   Footer: "Each sync reads new data from the ring and pushes it to the server."
2. **You** — "Birth year" (`FieldRow`, 4 digits, "Not set" placeholder, `PATCH /settings {birth_year}` on a valid
   year, `''` when cleared), "Step goal", "Sleep need (h)". Footer: "Your birth year sets your maximum heart rate for
   cardio zones. Goals shape the Activity and Sleep scores."
3. **Medication** — unchanged rows and footer.
4. **Workouts** — `ChevronRow` "Low-step sessions are" → picker dialog Rides | Workouts → `PATCH /settings
   {low_step_kind}`. Footer: "The ring can't tell cycling from a gym session — both show few steps. Pick what you do
   most; you can change any session on the Heart screen."
5. **Notifications** — `SwitchRow` per kind, in this order and wording: "Morning sleep" (morning), "Readiness"
   (readiness), "Wind down" (bedtime), "Time to move" (inactivity), "Body signals" (strain), "Journal check-in"
   (checkin), "Your week" (weekly), "Your month" (monthly) — all backend: a switch off adds the kind to `muted`,
   on removes it, `PATCH /settings {muted}` at once; then "Ring battery" (app: `Prefs.batteryAlerts`, default true;
   `SyncWorker.onBattery` returns early when false — covers both the low and the charged alert). Backend rows are
   hidden when `/settings` fails (no backend). Footer: "Notifications arrive with a sync, so they can run a few minutes
   late." When POST_NOTIFICATIONS is not granted the footer instead reads "Allow notifications for Circa in Android
   settings to receive these." and the switches are disabled.
6. **Setup** — "Address" (ring), "Scan for ring", "URL", "Token", "Android battery" (Unrestricted / Optimised, the
   existing exemption tap). Footer: "Point the server at the backend (:8429) or straight at VictoriaMetrics (:8428).
   Android delays background sync while battery optimisation is on."
7. Footer mark + "Circa {version}" unchanged. "Measure heart rate now" is removed (Heart has "Measure now").
- `muted` and `low_step_kind` come from the same `/settings` object the Medication group already loads (`med`);
  rename that state to `settings` if it helps readability — otherwise leave it.
- Check: `compileDebugKotlin`; Pixel_9a light + dark of the whole screen (two screenshots each, scrolled), the
  Workouts picker, a notification switch toggled and `/settings.muted` confirmed with curl, Birth year typed, the
  no-backend variant (Notifications shows only Ring battery), 360 dp width (longest row: "Low-step sessions are ·
  Workouts").

### F7 App Trends month + new chips (`ui/TrendsScreen.kt`, `ui/App.kt`, `ui/Api.kt`)
- `METRICS` gains `Metric("cardio_load", "Cardio load", "Load", "", { it.activity }, columns = true)`,
  `Metric("restorative_min", "Restorative time", "Restorative", "min", { it.readiness }, columns = true)`,
  `Metric("fitness", "Fitness estimate", "Fitness", "", { it.heart })` with a new optional `note` shown as a footnote
  under the chart: fitness → "Estimated from resting heart rate and age. A trend, not a measurement."; cardio_load →
  "Heart-rate zone minutes, weighted by zone."; restorative → "Awake time with a calm heart rate and little movement."
- **Month card** under the week card: `Api.month(ctx, ym)`; collapsed header (one row, tap toggles, chevron rotates):
  "{Month}" (`AppType.headline`) + "Sleep 78 · Readiness 74 · Activity 69" (subhead, secondary; "Learning · {n}/10
  days" when `scored_days < 10`). Expanded: a `MonthPicker` row mirroring `DayPicker` (private; same 44 dp chevrons,
  label "MMMM yyyy", › dead at the current month, ‹ dead 12 months back), then: means row with ▲/▼ vs `previous`
  (`goodText`/`badText`, like `WeekCard`), "Best week {d–d MMM} · Readiness {n}", "Steps {k} · Sleep {duration} avg ·
  Restorative {duration} avg", "{n} rides · {n} walks · {n} workouts · {duration} · load {load}" (zero kinds
  dropped; only when count > 0), "What held up:"
  + one line per effect "Alcohol → sleep score −6 over 5 nights" (≤ 3) or "Keep journaling to see what mattered this
  month." Under 10 scored days the expanded body shows only the means row and the learning line.
- `App.kt`: `"month" -> { go(TabItem.Trends); onLinkHandled() }` and pass `openMonth = true` into `TrendsScreen` so
  the month card starts expanded (state hoisted as `rememberSaveable`).
- Check: `compileDebugKotlin`; Pixel_9a light + dark: three new chips with their notes; month card collapsed, expanded
  with effects, learning month, ‹ to the previous month; `am start --es open month` cold + warm lands on Trends with the
  card expanded.

## Phase R

### R6 Release v3.3.0 "Load & recovery"
- versionCode 13 / 3.3.0; same keystore (cert `3c236b22`); `install -r` over v3.2.0 on Pixel_9a; 20 fast flings on
  Today, Settings and Trends with the month card open, crash buffer empty. Homelab backend redeploy (backup `circa.db`
  first, never `down -v`; `scp` only the dirs that changed since v3.2.0), smoke `/health`, `/today` (has `cardio`,
  `tonight`), `/sleep` (has `debt`, `bedtime`), `/month`, `/settings` (has `birth_year`, `low_step_kind`, `muted`) 200.
- Then, as the user asked: `git push origin main`, `gh release create v3.3.0 ring-health-v3.3.0.apk --target main
  --title v3.3.0 --notes-file …` with notes in the v3.2.0 style (one bullet per feature, "Installs over v3.2.0."),
  verify the release shows the asset and is Latest.

## Ledger order and parallelism

Batches (files do not overlap inside a batch; one emulator user per batch):

1. **F1** (backend cardio)
2. **F2** (backend sleep debt + bedtime) ‖ **F5** (Heart workouts + Stress restorative; needs F1)
3. **F6** (backend month; needs F2 for `main.py`) ‖ **F3** (Sleep debt + Bedtime; needs F2)
4. **F4** (Today cardio + tonight; needs F1 + F2)
5. **S2** (Settings reorganised; needs F1 for birth_year/low_step_kind and F6 for muted)
6. **F7** (Trends month + chips; needs F6, F1)
7. **R6 v3.3.0** (build, deploy, push, publish)

## Verification

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q`; every formula has fixture tests
  that fail on the old code; every route has a shape test.
- App: `./gradlew.bat :app:compileDebugKotlin`; Pixel_9a light + dark screenshots the agent looks at; the local stack
  seeded with a synthetic fortnight plus the cases each item names; `birth_year` set via `PATCH /settings`.
- Release: as R5, plus the `/month` and `/settings` smoke.
- User (real ring): after R6 — set the birth year and "Low-step sessions are: Rides"; go for a ride and a brisk walk
  and check both appear on Heart with the right kind (relabel if not); compare the bedtime window with what feels
  right; mute one notification kind and confirm it stops; read the first monthly report on 1 December.

## Risks

- **Zones depend on the 14-day baseline resting HR**: a week of illness or a medication change shifts the zones with
  it; the medication-aware baseline window (v3) already limits this for rhr. Accepted.
- **Workout detection misses short or low-intensity sessions** (5-min sampling, zone ≥ 2 for 20 min) and may count a
  long brisk walk to work as a workout — that is what the "Walk" label is for. No false "Run".
- **Rides are a labelled guess.** Every low-step session takes the Settings default, so with "Rides" chosen a gym
  session shows as a ride until relabelled; the " · guessed" suffix and the one-tap relabel keep this honest. A
  relabel sticks across rescores because it is stored by date + start; if the ring later re-sends a night and the
  detected start shifts by more than 10 min, the label is lost (accepted).
- **Muting is per kind on the backend**, so the switches need the backend online to save; a toggle while offline flips
  back (same behaviour as the Medication switch today).
- **Fitness is a resting-HR formula**: it will track RHR, not fitness, over weeks; the note says so. If it confuses more
  than it helps, drop the chip (one line in `METRICS`).
- **Bedtime window circularity** is avoided by scoring quality without the timing contributor, but the window still
  reflects the user's habits (few nights at unusual times → `flat`/`learning`). Copy says "best nights start", not
  "you should sleep at".
- **Rescore cost**: `_score_day` gains one HR pass; the day's HR is already fetched. Startup backfill (14 days) stays
  under a few seconds.
- **Today grows by one card and one line.** If the user finds Today long, the Cardio load card collapses to its header
  (title + number + bar) — the expand is already the design.
