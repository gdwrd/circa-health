# Ring Health v5: load, recovery and the long view

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
  Each → `{start, end (last t + 300), minutes, avg_bpm, max_bpm, load, kind}`; `kind` = `walk` when the covered
  hours average ≥ 1000 steps/h, else `workout`. Sleep samples are excluded by the caller.
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
- Settings key `birth_year` (default `''`): `db.SCHEMA` INSERT OR IGNORE, `GET /settings` returns it, `PATCH /settings`
  accepts a 4-digit year between 1900 and this year (422 otherwise) or `''` to clear.
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
  `workout` by steps; restorative: a 20-min calm run counts, a 10-min run does not, a step-heavy hour does not;
  fitness known value; `/settings` birth_year round-trip + 422; `/today` cardio `needs_age` → `learning` → `ok`
  shape; readiness notification body with target. Backend suite green.

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
- `notify._monthly`: on day 1 between 08:00 and 12:00 local, once (no `monthly` row in the last 20 days), when the
  previous month has ≥ 10 scored days: title "Your {Month name}", body "Sleep 78 · Readiness 74 · Activity 69 avg. 12
  workouts." (the workouts clause only when count > 0), `extra {open: month}`. Add to `evaluate`.
- Tests: `/month` shape and means on a seeded month, previous-month block, best_week, workouts totals, one strong
  effect; bad month 422; `_monthly` fires on the 1st once, not on the 2nd, not under 10 days. Backend suite green.

## Phase G — app

### F5 App Heart workouts + Stress restorative (`ui/HeartScreen.kt`, `ui/StressScreen.kt`)
- **Heart, per picked day** (`/today?date=` is already fetched): shade each workout on the 24 h chart as a span in
  `c.heart` at 12 % alpha behind the line (same mechanism as the dose windows, which keep their colour). Under the
  Resting HR / HRV tiles, when `workouts` is non-empty, a section **"Workouts"** with one `InsetCard`: a header line
  "Cardio load {load} · {Σ minutes} min in zones" (`AppType.footnote`, secondary), then one row per workout —
  `Icons.Rounded.DirectionsWalk` for `walk`, `Icons.Rounded.FitnessCenter` for `workout`; title "Walk · 42 min";
  trailing "avg 118 bpm" (secondary); second line footnote "2:10–2:52 PM · max 141 · load 6" (clock()). Rows
  separated by `Hairline`. Footer: if that date has no journal `workout` entry (journal is already loaded, 30 days)
  a `TintButton` "Add to journal" → `Api.addJournal(ctx, date, "workout")`, then the footer reads "In your journal"
  (footnote). No workouts → no section (the day's load is on Today and in Trends).
- **Stress:** the "Daytime stress load" card becomes a two-tile row (same tile style as Heart's Resting HR / HRV):
  left "Daytime stress load" — `duration(stressed_hours × 60)`, footnote "in the stressed zone while awake";
  right "Restorative time" — `duration(inputs.restorative_min)`, footnote "calm heart rate, little movement".
  `—` when null. The section title becomes **"Load and recovery"**.
- Check: `compileDebugKotlin`; Pixel_9a light + dark: Heart today with two workouts (one walk, one workout) +
  spans on the chart + "Add to journal" and after tapping it; Heart on a day without workouts (no section); Stress
  tiles filled and null. Seed: set `birth_year` via `PATCH /settings`, push a synthetic day with a 40-min HR climb to
  zone 3 at 14:00 with 3 000 steps in that hour and a 25-min zone-2 block at 18:30 with 100 steps.

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

### F4 App Today cardio + tonight; Settings birth year (`ui/TodayScreen.kt`, `ui/SettingsScreen.kt`)
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
- **Settings > Goals**: row "Birth year" (value `1990` or "Not set"), same editor as Step goal, 4 digits, PATCH
  `/settings {birth_year}`; footer line under Goals: "Your birth year sets your maximum heart rate for cardio zones."
- Check: `compileDebugKotlin`; Pixel_9a light + dark: card in `needs_age`, `learning`, `ok` for each band (seed
  readiness 85 / 70 / 50 by pushing nights), expanded zones, past-target copy; Tonight line (set the emulator clock
  with `adb shell` is not allowed — screenshot at ≥ 18:00 local or report it unverified); Settings row + editor.

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
  Restorative {duration} avg", "Workouts {count} · {duration} · load {load}" (only when count > 0), "What held up:"
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
  Today and Trends with the month card open, crash buffer empty. Homelab backend redeploy (backup `ring.db` first,
  never `down -v`), smoke `/health`, `/today` (has `cardio`, `tonight`), `/sleep` (has `debt`, `bedtime`), `/month`,
  `/settings` 200. GitHub release with the APK `ring-health-v3.3.0.apk`, notes in the v3.2.0 style.

## Ledger order and parallelism

Batches (files do not overlap inside a batch; one emulator user per batch):

1. **F1** (backend cardio)
2. **F2** (backend sleep debt + bedtime) ‖ **F5** (Heart workouts + Stress restorative; needs F1)
3. **F6** (backend month; needs F2 for `main.py`) ‖ **F3** (Sleep debt + Bedtime; needs F2)
4. **F4** (Today cardio + tonight, Settings birth year; needs F1 + F2)
5. **F7** (Trends month + chips; needs F6, F1)
6. **R6 v3.3.0**

## Verification

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q`; every formula has fixture tests
  that fail on the old code; every route has a shape test.
- App: `./gradlew.bat :app:compileDebugKotlin`; Pixel_9a light + dark screenshots the agent looks at; the local stack
  seeded with a synthetic fortnight plus the cases each item names; `birth_year` set via `PATCH /settings`.
- Release: as R5, plus the `/month` and `/settings` smoke.
- User (real ring): after R6 — set the birth year, take a 30-min brisk walk and check it appears on Heart with a
  sensible zone; compare the bedtime window with what feels right; read the first monthly report on 1 December.

## Risks

- **Zones depend on the 14-day baseline resting HR**: a week of illness or a medication change shifts the zones with
  it; the medication-aware baseline window (v3) already limits this for rhr. Accepted.
- **Workout detection misses short or low-intensity sessions** (5-min sampling, zone ≥ 2 for 20 min) and may count a
  long brisk walk to work as a workout — that is what the "Walk" label is for. No false "Run".
- **Fitness is a resting-HR formula**: it will track RHR, not fitness, over weeks; the note says so. If it confuses more
  than it helps, drop the chip (one line in `METRICS`).
- **Bedtime window circularity** is avoided by scoring quality without the timing contributor, but the window still
  reflects the user's habits (few nights at unusual times → `flat`/`learning`). Copy says "best nights start", not
  "you should sleep at".
- **Rescore cost**: `_score_day` gains one HR pass; the day's HR is already fetched. Startup backfill (14 days) stays
  under a few seconds.
- **Today grows by one card and one line.** If the user finds Today long, the Cardio load card collapses to its header
  (title + number + bar) — the expand is already the design.
