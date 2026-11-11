# Circa v7: experiments, calm, and the things other rings don't do

Status: plan (user direction, 2026-11-09). Not yet in the ledger: each `###` item below is one ledger item / one
session. `docs/PLAN.md` … `PLAN7.md` stay the references for what already exists.

## Context and decisions

- Goal (user): features that expensive rings and apps don't offer, plus ones users don't know they need. The UI must
  stay "very good and with character" (Phase D look, as on Today and Breathe), never default Material.
- Research (2026-11-09, secondary sources): the loudest complaints elsewhere are paying to see your own data, scores
  that cause anxiety without action ("orthosomnia"; clinicians say one night is not significant), journals that are
  daily homework and don't read detected activities, missing nap detection and missing raw export.
- Positioning that follows: **your data, your server, honest about uncertainty, causal answers instead of scores.**
- Standing rules hold: tokens-only UI, `Theme.kt` components, no new packages, one sentence per number, nothing shown
  before it has data, never delete ring data, scores are never called "Oura scores", copy never diagnoses.
- Evidence is shown, never baked into scores (PLAN7 rule): none of these features changes Sleep / Readiness / Activity.

## What exists (do not rebuild)

- `insights.effect / compare / nights / kinds`: with-vs-without maths, robust spread, `learning` state, `strong` flag.
- `_score_day` already stores per day: `inputs` (rhr, hrv, awake, recovery, spo2, bed_start, bed_end, steps,
  cardio_load, stress_load, worn_hours…), `wear`, `signals` (strain index, streak), `workouts`, `sleep_extra.spo2`.
- Auto journal precedent: `phone_in_bed` rows written and removed by `_score_day` (`db.delete_phone_in_bed`).
- `notify.py` rule pattern (`_fired`, `_add`, `KINDS`, `muted` setting); app list in `SettingsScreen.kt:485`.
- SpO2 is already synced (`biometric_spo2_pct`, hourly mean of the ring's min+max), scored into strain and shown on
  Sleep (`BloodOxygen`). Only the hourly minimum is thrown away (`ColmiProtocol.parseSpo2Packet`).
- UI kit: `InsetCard`, `ScoreRing`, `CountUp`, `Section`, `Value`, `TintButton`, `EmptyState`, `DayPicker`, `Chart`,
  `DoseWindows` spans, `LocalReduceMotion`, `HapticFeedback.tick()`, mood / signal / night tokens, Nunito `AppType`.
- Today pieces to reuse: `coachLine`, `CheckInSheet` / `DayForm` / `ToggleRow`, `JournalChip`, `WhatAffectsYou`,
  `sentence()`, `metricValue / metricDelta / effectColor`, `TonightCard`, `BodySignals` 3-step capsule.

## Design with character (applies to every app item)

Each feature gets **one signature visual**, drawn on `Canvas` from existing tokens. No new screen is a plain list of
`Text` rows in a card.

| Feature | Signature visual | Colour |
|---|---|---|
| Experiments | "Coin strip": one dot per day, filled = do it, hollow = skip it, today pulses; verdict lands with `CountUp` | `tint`, verdict in `goodText` / `badText` |
| Calm mode | The Circa open arc breathing slowly inside the mood tint, a word instead of a number | `moodGood/Fair/Low`, `readiness` |
| Confidence | "~" before an uncertain number and a softer ring; three-bar signal glyph in details | score colour at reduced alpha |
| Body clock | Night band with two dots (weekday, weekend midpoint) joined by an arc labelled as a flight | `nightTop`, `sleep`, `sleepLight` |
| Early warning | Row of day dots walking up to the "sick" day, coloured by signal level | `signalCalm/Elevated/High` |
| Suggested entries | Dashed-outline chip that fills solid on tap with `tick()` | `secondaryLabel` → `tint` |
| Wind-down | Shaded span on the Heart day chart from "settling" to asleep | `sleepBand` |
| What-if | Two 72 dp rings side by side (Rest / Train), each with a faint arc for the range | `readiness`, `readinessLight` |
| Report | A4 page with the Circa mark, Nunito title, four small charts | light palette only |

Rules for all of them:
- Colours only from `AppTheme.colors`; a new token only when the table says so (none planned). Type only from `AppType`.
- Numbers that matter use `AppType.display` or `title` (Nunito) with `CountUp`; explanations are one `subhead` sentence.
- Motion: one spring or one 600 ms ease per card, replaced by a plain switch under `LocalReduceMotion`.
- Empty and learning states use `EmptyState` or a "Day N of M" line, never a blank card and never a spinner alone.
- Every tappable thing gives `tick()`; every Canvas has a `contentDescription` sentence for TalkBack.
- Each app item is checked on Pixel_9a in light + dark, at 360 dp width, with large system font and reduce-motion.
  What was not checked is written in the ledger line (as G2b did).

## Phase X — Experiments (v3.11.0)

### X1 Backend experiments (`db.py`, `insights.py`, `main.py`, `notify.py`; tests `test_insights.py`, `test_api.py`, `test_notify.py`)

- Table: `experiment(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, metric TEXT, start TEXT, days INTEGER, plan TEXT, ended TEXT)`.
  `plan` is a string of `1` (do it) / `0` (skip it), exactly half ones. Rows are ended, never deleted.
- `insights.plan(days: int, rng: random.Random) -> str` (pure; balanced shuffle).
- `insights.experiment(plan: str, start: date, nt: dict[date, dict], logged: set[date], today: date, metric: str) -> dict`
  (pure). Day `i` is `start + i`; it counts only if the journal agrees with the assignment (`(d in logged) == plan[i]`)
  and night `d + 1` exists. Result: `{status: running|done, day, days, today: "on"|"off"|None, kept, skipped,
  result: insights.compare(on_nights, off_nights, (metric,))}`. `compare` already returns `learning` under 3 a side.
- Kinds allowed: `alcohol, caffeine_late, late_meal, workout, breathe, phone_in_bed` (the last two log themselves).
  Metrics: `insights.NIGHT`. Days: even, 10 to 28, default 14. One running experiment at a time.
- `POST /experiments {kind, metric, days}` → 422 bad input, 409 one already running. `GET /experiments` →
  `{active: {...}|null, past: [...]}`. `DELETE /experiments/{id}` sets `ended` (stop early).
- `notify._experiment`: 07:00–12:00, once per day while running: "Experiment day 5 of 14 — today: no late coffee."
  On the day after the last night: "Your experiment is done" with `extra {"open": "today"}`. Add `"experiment"` to
  `notify.KINDS`.
- Tests: balanced plan; a non-compliant day is skipped; verdict equals `compare` on the kept days; 409; mute works.

### X2 App Experiment card (`ui/TodayScreen.kt`, `ui/Api.kt`, `ui/SettingsScreen.kt` one list entry)

- `Api.experiments / startExperiment / stopExperiment`.
- Entry: a "Test it yourself" row at the foot of `WhatAffectsYou`. Dialog: pick a habit (chips), pick what to watch
  (chips, default "Deep sleep"), length 14 days. Copy per kind for on/off ("Coffee after 14:00" / "No coffee after 14:00").
- Running card (under Journal): title in Nunito, today's instruction as the headline, the coin strip, "N of M days
  counted" in `footnote`. Skipped days draw as a faint cross, not an error colour.
- Done card: the delta with `CountUp` and `metricDelta`, one sentence from `sentence()`, and the honest line when
  `strong` is false: "No clear difference in this test." A "Run again" / "Done" pair.
- Add `"experiment" to "Experiment"` in the Settings notification list.

### R19 Release v3.11.0 "Experiments"

## Phase C — Calm and sure (v3.12.0)

### C1 Backend calm setting + confidence (`db.py`, `main.py`, `scoring.py`, `notify.py`; tests `test_scoring.py`, `test_api.py`, `test_notify.py`)

- Setting `calm` (`''` or `'1'`) in the `INSERT OR IGNORE` defaults, `_settings_out`, `SETTINGS`; `/today` adds `"calm": bool`.
- `notify._readiness` and `_morning` in calm mode carry no number: title is the band word (`Ready` ≥ 85, `Steady`
  70–84, `Take it easy` < 70), body keeps the lowest factor in words / the sleep duration.
- `scoring.confidence(coverage: float, present: int, total: int) -> str` (pure): `high` when coverage ≥ 0.8 and
  present/total ≥ 0.7; `low` when coverage < 0.5 or present/total < 0.5; else `medium`.
  - sleep: coverage = share of night hours with a `> 0` HR sample; present/total = non-null sleep contributors.
  - readiness: same night coverage; present/total over its seven contributors.
  - activity: `wear.worn_hours / wear.awake_hours`; present/total over its four contributors.
- Saved as `contributors["confidence"] = {sleep, readiness, activity}`; `/today` passes it through. Older days simply
  have none (no history rescore).

### C2 App Calm mode + confidence (`ui/TodayScreen.kt`, `ui/SettingsScreen.kt`, `ui/Theme.kt`, `widget/RingWidget.kt`)

- Settings › You: "Calm mode" switch with the footnote "Words instead of numbers. Numbers stay one tap away."
- Calm Today: the hero keeps its 220 dp footprint and mood tint but draws the open Circa arc (idle 0.97 ↔ 1.0 breath,
  as the Breathe orb) with the band word in `AppType.largeTitle` and `coachLine` below. Small rings become two word
  pills ("Sleep · Solid", "Activity · Light"). Tapping any of them opens the existing details with numbers.
- Body signals, Tonight, Journal and Experiment cards are unchanged; an elevated signal is always shown.
- Widget in calm mode: arc + word, no numbers.
- Confidence (both modes): `low` → number prefixed with "~" and ring at 60 % alpha; `ScoreDetails` gets a three-bar
  glyph and one sentence ("Ring worn 9 of 16 waking hours, so this is a rough estimate."). `ScoreRing` gains one
  `alpha` parameter; nothing else in `Theme.kt` changes.

### R20 Release v3.12.0 "Calm"

## Phase K — Body clock and early warning (v3.13.0)

### K1 Backend jetlag, lead time, wind-down (`scoring.py`, `insights.py`, `main.py`, `notify.py`; tests `test_scoring.py`, `test_insights.py`)

- `scoring.social_jetlag(nights: list[tuple[int, float]]) -> dict | None` (pure): `(weekday of the wake date,
  midpoint in minutes after noon)` over 28 days; median of Sat/Sun wake dates minus median of the rest. None under
  4 weekday and 2 weekend nights. Midpoint = `(inputs.bed_start + inputs.bed_end) / 2`. Returned in `/sleep` as `jetlag`.
- `insights.lead_time(sick: set[date], strain: dict[date, float | None]) -> dict | None` (pure): per sick episode
  (first day of a run), count the consecutive days before it with index ≥ 25, up to 7. `{days: median, n}`; None under
  2 episodes. Added as `out["sick"]["lead"]`. `notify._strain` appends "Last times this came about N days before you
  logged feeling sick." when it exists.
- `scoring.wind_down(hr: list[tuple[float, float]], rhr: float) -> float | None` (pure): start of the last run of
  samples ≤ 1.2 × rhr that reaches sleep start (one outlier allowed), from 18:00 on. Stored as `inputs["wind_down"]`
  (minutes after noon). `insights`: add `wind_down` to `NIGHT`, `UNITS` (min), `FLOORS` (15) and `nights()`.
  `/today.tonight` adds `wind_down_usual` (median of 14 nights, None under 5).
  `shortcut:` HR is logged every 5–15 min, so this is accurate to about 15 min.

### K2 App Body clock, early warning, wind-down (`ui/SleepScreen.kt`, `ui/TodayScreen.kt`, `ui/HeartScreen.kt`)

- Sleep: "Body clock" card with the night band visual; sentence "Your weekends run 1 h 40 min later, like flying to
  Istanbul every Friday." (city picked from a five-entry table by hours; under 30 min: "Your clock is steady.")
- Today › What affects you › sick row: the day-dot strip and "Your body signals rose about 2 days before you felt sick."
- Today › Tonight card: "Your body usually starts to settle around 22:10." Heart day chart: the settling span.
  `wind_down` becomes a clock-type metric in `metricValue / metricDelta / effectColor` (later = bad).

### R21 Release v3.13.0 "Body clock"

## Phase Z — It logs itself (v3.14.0)

### Z1 Backend auto journal + SpO2 minimum (`db.py`, `scoring.py`, `main.py`; tests `test_journal.py`, `test_scoring.py`)

- `db.add_auto(d, kind)`: `INSERT OR IGNORE` with `note='auto'` (never replaces a manual entry);
  `db.delete_auto(d, kind)` removes only `note='auto'` rows (as `delete_phone_in_bed`).
- Workouts: a detected span ≥ 20 min or a confirmed one writes an auto `workout` row for that date; gone when a
  rescore finds none.
- Suggestions: `inputs["evening_hr"]` = mean awake HR in the 3 h before sleep start. `scoring.robust` over the prior
  14 days (floor 3 bpm); z ≥ 1.5 and no workout ending inside those 3 h → `contributors["suggest"] =
  {"kinds": ["alcohol", "late_meal"], "delta": bpm}` on the wake date. `/today` passes it through. Never auto-logged.
- SpO2: when `biometric_spo2_min_pct` samples exist for the night, `min` and `low_count` use them; else unchanged.

### Z2 App suggested entries + SpO2 minimum (`ui/TodayScreen.kt`, `ui/SleepScreen.kt`, `ColmiProtocol.kt`, `SyncWorker.kt`)

- Check-in sheet for yesterday: suggested kinds show as dashed chips above the toggles with "Evening heart rate was
  +6 bpm. Any of these?" One tap logs it (existing `Api.addJournal`), "Neither" dismisses for that date (remembered
  in `Prefs`). Auto workout entries show a small "auto" tag on their `JournalChip`.
- Ring: `Spo2Sample` gains `min`; `buildMetrics` emits `biometric_spo2_min_pct` beside the mean (same pattern, same
  timestamp). These are the only edits to `ColmiProtocol` / `SyncWorker` in this plan.
- Sleep › Blood oxygen: hourly minimum dots under the existing sentence.

### R22 Release v3.14.0 "Logs itself"

## Phase P — Plan and share (v3.15.0)

### P1 Backend what-if + CSV export (`scoring.py`, `main.py`; tests `test_scoring.py`, `test_api.py`)

- Replaces the parked "Readiness forecast". No regression: "days like this" from the user's own history.
  `scoring.what_if(history: list[tuple[float, float, int]], hard_from: float, need_min: float) -> dict` (pure):
  history = `(cardio_load, sleep_min that night, next-day readiness)` over 90 days; buckets rest/train
  (`load ≥ hard_from`, the low edge of `cardio.target`'s "recovered" band) × full/short night (`≥ need − 30`).
  Each bucket `{median, lo, hi, n}` (quartiles) or None under 5 days. In `/today.tonight.what_if`.
- `GET /export.csv?days=90` (1–365, auth as the rest): one row per day: scores, sleep_min, nap_min, rhr, hrv, spo2
  min, steps, cardio_load, stress_load, bp sys/dia, journal kinds joined with `;`. stdlib `csv`, `text/csv`.

### P2 App what-if + report (`ui/TodayScreen.kt`, `ui/SettingsScreen.kt`, `ui/Api.kt`, `AndroidManifest.xml`, `res/xml/share_paths.xml` new)

- Tonight card: the two-ring Rest / Train visual with "After 9 hard days like this: 68, usually 61–74." Hidden until
  both buckets exist; a short-night footnote when the plan is under the need.
- Settings › Data: "Share report" (PDF) and "Export CSV". PDF via platform `PdfDocument`, A4, one page: Circa mark,
  period, four small charts (resting HR, HRV, time asleep, blood pressure) drawn with one local helper, the footer
  "Estimates from a consumer ring, not medical measurements." Shared through `FileProvider` (androidx.core, already a
  dependency) from `cacheDir/share/`. `Api` gets one text GET for the CSV.

### R23 Release v3.15.0 "Plan & share"

## Phase B — Raw sensor probe (no release)

### B1 Probe (throwaway branch, nothing merged)

- Read the raw-enable command bytes from `edgeimpulse/example-data-collection-colmi-r02` and tahnok's notes, send
  them from a temporary Settings row, log 10 s of notifications as hex to `SyncLog`.
- Gate: accelerometer or PPG frames at ≥ 10 Hz → write B2 (breathing rate during Breathe, the orb follows the real
  breath). Otherwise add one line to "Facts that still hold" and drop the idea.

## Ledger order and parallelism

X1 → X2 → R19 → C1 → C2 → R20 → K1 → K2 → R21 → Z1 → Z2 → R22 → P1 → P2 → R23. B1 any time.
Backend items are independent of each other; X2, C2, K2, Z2 and P2 all edit `TodayScreen.kt`, so app items are
sequential. Each backend item is deployed to the homelab before its app item is checked on the phone.

## Verification

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q`; every new pure function has tests.
- App: `.\gradlew.bat :app:compileDebugKotlin`, then the Pixel_9a checks from "Design with character" against the
  local stack seeded with a synthetic fortnight.
- Never run instrumented tests; never regenerate or re-import history; old days are not rescored for new fields.

## Risks

- **Experiments need honest compliance.** Manual kinds rely on the evening journal; skipped days shrink the sample
  and the card says so. Fourteen days gives at most 7 a side, so only large effects will be "strong".
- **Calm mode hides a falling trend.** Body signals and the strain notification stay on to cover that.
- **Evening-HR suggestions can be wrong** (stress, heat, a late walk). They are questions, never entries.
- **What-if buckets fill slowly**: about a month before both sides have 5 days.
- **Wind-down resolution** is limited by the ring's HR interval.
- **B1 is unverified**: the raw stream may not exist on this firmware.

## Not doing

Regression forecast, night-shape anomaly, LLM coach (stay parked); calendar / location context for the journal;
multiple experiments at once; changing any score formula; history rescore.
