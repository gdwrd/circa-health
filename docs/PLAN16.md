# Circa v18: everything in its place

Status: built and released as v5.0.0 on 2026-11-17 (planned by the user, 2026-11-16). Items UX1 to RL4 are in the ledger with what each one did; each `###` item below is one
ledger item / one session. App only, unless UX4 finds a backend fault. Builds on every earlier plan: nothing here
adds a feature, it moves, trims and corrects what exists.

## Context and decisions

- Asked (user, 2026-11-16): review the whole app as a designer would, page by page: does it fit together, what is
  in the wrong place, what to remove, add or present differently. Then the same on the emulator. Then: "write a
  detailed plan for all these changes", ending in a version bump and a new release.
- Where the findings come from: a read of every screen in `ui/`, then a walk of every tab, Reports, Breathe and all
  Settings pages on the Pixel_9a emulator (app 4.9.0, a read-only snapshot of the homelab `circa.db`, 2026-11-16
  evening). Not seen: dark mode, the check-in sheet, the questionnaire, the guess gate, Ring lab with a ring.
- The three faults behind most findings:
  1. Today carries up to 19 cards and answers three questions at once (how am I, log something, what have I
     learned). On the emulator it is about seven screens long.
  2. The readiness ring, the answer to "how am I", is on the second screen, under the report and the mood scales.
  3. Things live where they were built: insights at the bottom of Today, medication and blood pressure under
     Heart, questionnaire results in Settings, the mood chart in Trends.
- The layout (user agreed 2026-11-16: "Good"):

  | Tab | Question | Segments |
  |---|---|---|
  | Today | How am I, and what now? | none |
  | Sleep | How was last night? | none |
  | Activity | How much did I move? | none |
  | Body | What are my signals doing? | Heart · Stress · Mind |
  | Trends | What is changing, what have I learned? | Charts · Insights |

  Reports, Breathe and Settings stay pushed screens.
- Decisions (mine; say so if one is wrong):
  - **Version 5.0.0 (66).** The layout of every tab changes, so this is a major version.
  - **The Breathe icon leaves the title bars.** It stays on Stress and on the "I feel…" reply card. This reverses an
    earlier decision (`BreatheButtonTest` asserts the icon on all five tabs); the test changes with it.
  - **The tab bar hides on Reports, Breathe and Settings.** Today it shows there with no tab selected.
  - **One verdict on Today.** When the day has a written report, its verdict word and note replace the readiness
    coach line. Without a report the coach line stays. The two never show together.
  - **"Next" slot: at most two cards**, in this order: Body signals when raised (with Last time), Hold my place,
    the evening "how did today go", Tonight (after 18:00), today's experiment instruction.
  - **Nothing-to-say cards do not draw.** A learning state is one line; a card of dashes or zeros is hidden.
  - **"Restorative time" is renamed "Calm and still"** so it stops reading as the Restored zone above it.
  - **First run is a card, not a wizard**: three rows on Today that open the Settings pages that exist.
  - **One date across Sleep, Activity and Body.** Reports and Trends keep their own.
  - **Exports stay in Settings › Data.** Only the titration PDF gains a second button in Body › Mind.
- Rules for every item:
  - `ui/Theme.kt` tokens only, no inline colours. `ColmiProtocol`, `ColmiConnection`, `SyncWorker`,
    `MetricsPusher`, `Prefs` unchanged except where an item names them.
  - Moving a composable keeps its behaviour, its calm-mode branch and its reduce-motion branch.
  - Scores do not change; no data is deleted; never "Oura".
  - Check: `.\gradlew.bat :app:compileDebugKotlin`; `.\gradlew.bat :app:testDebugUnitTest` when `ui/Theme.kt`,
    `ui/App.kt` or a test's screen changes; emulator screenshots in light, dark and at font 1.3 of every screen
    the item changed.
  - Emulator: the installed app points at `http://10.0.2.2:8577`. Serve a fake or synthetic backend there (as
    TL1 and TL3 did); never the homelab, never a re-import.

## What exists (do not rebuild)

- Navigation: `ui/App.kt` (`TabItem`, routes `today`, `sleep`, `activity`, `body`, `trends`, `reports`, `breathe`,
  `settings`; notification links `trends`, `sleep`, `month`, `journal`, `workout`, `today`, `reports`, `phq`).
- Shared pieces in `ui/Theme.kt`: `LargeTitleScaffold` (title, back, action, pull to refresh, the "Showing data
  from … · server unreachable" line from `Api.stale`), `Section(title)`, `InsetCard`, `ScoreRing`, `DayPicker`,
  `EmptyState`, `TrendCard`, `Chart`, `TintButton`, `Value`. `Segmented` is in `ui/TrendsScreen.kt`.
- Today (`ui/TodayScreen.kt`, 2522 lines): `RightNow`, `NowCard`, `GuessCard`, the readiness card, `HoldCard`,
  `SmallRings`, `CardioLoad`, `TonightCard`, `BodySignals`, `LastTimeCard`, the Vitals grid, `DaySoFar`,
  `MarkCard`, `ExperimentCard`, `WhatAffectsYou`, `TestDialog`, `CheckInSheet`, `coachLine`.
- Body: `ui/BodyScreen.kt` (15 lines: the Heart / Stress switch), `ui/HeartScreen.kt` (`MeasureNow`,
  `BreathingCard`, `MedicationCard`, `ByDose`, `BloodPressureCard`), `ui/StressScreen.kt` (`ThroughTheDay`,
  `ResilienceDetail`).
- Trends: `ui/TrendsScreen.kt` (`WeekCard`, `MonthCard`, `MoodCard`, `TrendChart`, `METRICS`). The backend's
  `/trends` already accepts up to 365 days.
- Settings: `ui/SettingsScreen.kt` (pages Ring, You, Right now tiles, Medication, Mind, Notifications, Phone,
  Reports, Data; `SyncLogScreen`), `ui/RingLabScreen.kt`.
- Tests: `app/src/test/.../ui/BreatheButtonTest.kt`, `BreatheWaveTest.kt`, `HeartWaveTest.kt`.

## Items

### UX1 App: wrong words and numbers

- Files: `ui/TrendsScreen.kt`, `ui/TodayScreen.kt` (`KIND_LABEL`), `ui/ReportScreen.kt`, `ui/Theme.kt`,
  `ui/ActivityScreen.kt`, `ui/HeartScreen.kt`, `ui/StressScreen.kt`, `ui/SettingsScreen.kt`.
- Trends "In your journal" shows `phone_in_bed`: give it the label "Phone in bed" (the experiment list has it).
- Report text shows "22:45" on a 12-hour phone. One helper in `ui/Theme.kt` that rewrites each `HH:MM` in a text
  through `clock(hhmm)`; use it where report notes and advice lines are drawn (Reports and the Today card).
- Thousands separators on step counts and the goal ("10,852", "8,000") in `Value` callers on Activity.
- Heart: the two tiles read "Resting HR · last night" and "HRV · last night".
- Stress: tile "Restorative time" becomes "Calm and still".
- Settings › You › Workouts footer: "on the Heart screen" becomes "on the Activity screen".
- Check: compile; screenshots of Trends, Reports, Activity, Heart, Stress.

### UX2 App: one step goal, and a line when the server is down

- Files: `ui/App.kt`, `ui/Api.kt`, `ui/Theme.kt`.
- The step goal and sleep need are copied from the server into `Prefs` only when Settings › You opens, so Activity
  can show another goal than Settings (seen: 8000 against 10,000). Copy them once at app start, in `App`, with the
  code Settings already uses.
- With no cache and no server, Today is a blank page. `Api` keeps a second state beside `stale` ("failed, nothing
  cached"); `LargeTitleScaffold` shows "Server unreachable" where the stale line goes.
- Check: compile, unit tests; screenshots of Today with the fake backend stopped, with and without a cache.

### UX3 App: Reports says one thing

- Files: `ui/ReportScreen.kt`, `ui/SettingsScreen.kt`, `ui/ReportModel.kt` (read).
- Settings shows "Write reports" on while Reports ends with "Turn on written reports": the switch is on and the
  model is not on the phone. The Reports row says what is missing ("Download the model to get written notes") and
  opens Settings › Reports.
- Settings › Reports: each download row shows its state (not downloaded, downloading with percent, on this phone).
- Check: compile; screenshots of both pages with and without the model file.

### UX4 Check: cardio load 0 on a day with four workouts

- Files: `backend/app/cardio.py`, `backend/tests/test_cardio.py`, `backend/app/main.py` (read first).
- Seen on 2026-11-16: two walks and two rides (average 78 to 98 bpm, max 116), birth year 1992, "Cardio load 0 ·
  0 min in zones", every workout row "load 0". Work out by hand from that day's heart rate (homelab db, read only)
  what the load should be.
- Right as it is (no minute reached zone 1): the app shows "Below your zones today" in place of two zeros, in
  `ui/ActivityScreen.kt`; finding into Facts.
- Wrong: fix in `cardio.py` with a test from that day's numbers; the release then includes a homelab deploy.
- STOP after the finding if the fix is more than one function.

### UT1 App: Today in order

- Files: `ui/TodayScreen.kt`, `ui/ActivityScreen.kt`.
- Order: greeting, readiness card (the guess card in its place while pending), `SmallRings`, the conditional cards
  as they are for now, `RightNow` (and its reply card), `DaySoFar`. The report card stays above readiness until UT2.
- Status line under the date, one of: "Ring off since …", "Day n of 14 · learning your normal". The learning card
  and the "Ring off for n h today" footnote go.
- The Vitals grid goes, with `VITALS_INFO` and `Sparkline` if nothing else uses them.
- `CardioLoad` moves to `ui/ActivityScreen.kt` and replaces the "Cardio load" and "In zones" tiles there; Today
  no longer shows it.
- "Add or fix the day" reads "Edit day".
- Check: compile, unit tests; screenshots of Today (morning with guess pending, day, evening) and Activity.

### UT2 App: one verdict, and the "next" slot

- Files: `ui/TodayScreen.kt`, `ui/ReportScreen.kt` (only if `ReportCardView` needs a parameter).
- The readiness card and the report card become one card: the ring, then the report's verdict word and note with
  the chevron to Reports. No report for the day: the coach line as today. Calm mode and the guess mark unchanged.
- Under the rings, at most two of: `BodySignals` when its level is above calm, with `LastTimeCard` inside the same
  card; `HoldCard`; the evening question; `TonightCard` after 18:00; one line "Experiment today: …" that opens
  Trends › Insights (the link lands in UR1; until then it opens Trends).
- `BodySignals` while calm or learning does not draw.
- Check: compile, unit tests; screenshots with a raised signal, with a report and without one.

### UR1 App: Trends gets Insights

- Files: `ui/TrendsScreen.kt`, `ui/InsightsScreen.kt` (new, holds only moved code), `ui/TodayScreen.kt`,
  `ui/App.kt`.
- `Segmented(listOf("Charts", "Insights"))` under the title. Charts is the page as it is.
- Insights: `ExperimentCard`, `WhatAffectsYou` with `TestDialog`, moved out of `TodayScreen.kt` with their helpers
  (`sentence`, `effectColor`, `CoinStrip`, `LeadStrip` and the like). Rows that only say "log n more days" go last,
  as one line: "Not enough days yet: Late meal, Breathe".
- A new link `insights` opens Trends on that segment; the experiment notification and the Today line use it. Check
  what the experiment notification links to now (`MainActivity`, `SyncWorker`) before changing it.
- Check: compile, unit tests (`TrendsScreen` is in `BreatheButtonTest`); screenshots of both segments.

### UT3 App: marked things as tiles

- Files: `ui/TodayScreen.kt`, `ui/InsightsScreen.kt`.
- Each named thing is a tile in `RightNow` after the feelings (tap: now; long press: an earlier time; Undo as the
  other tiles). The mark appears in `DaySoFar`, where a tap fixes it (`FixTapDialog` already handles `mark_n`).
- The `MarkCard` cards leave Today. Their clock line and "what comes before" sentences move to Insights, one card
  per thing.
- Check: compile; screenshots of Right now with zero, one and three things; Insights with one thing.

### UB1 App: Body, one title; Heart in order

- Files: `ui/BodyScreen.kt`, `ui/HeartScreen.kt`, `ui/StressScreen.kt`.
- The large title is "Body" on every segment; the segmented control is the first row under it.
- Heart: `DayPicker`, the day's heart-rate card, `BreathingCard`, then the two 30-day cards. The two tiles above
  them go (the cards carry the same values). "Measure now" becomes the title-bar action and opens `MeasureNow` in a
  bottom sheet.
- The heart-rate chart gets a one-line legend for its bands: dose, workout, breathing, settling.
- Check: compile, unit tests (`HeartWaveTest`); screenshots of Heart top and the measure sheet.

### UB2 App: Body › Mind

- Files: `ui/BodyScreen.kt`, `ui/MindScreen.kt` (new, mostly moved code), `ui/HeartScreen.kt`,
  `ui/TrendsScreen.kt`, `ui/SettingsScreen.kt`, `ui/App.kt`.
- Third segment "Mind": `MoodCard` (from Trends), questionnaire results with "Answer now" (the list and its remove
  dialog from Settings › Mind), `MedicationCard` with `ByDose` (from Heart), `BloodPressureCard` stays on Heart.
- `ByDose` hides a row where every step is "—" and a step with no dose days.
- A "Titration summary (PDF)" button under the medication card, calling what Settings › Data calls.
- Settings › Mind keeps "Things to mark" and the questionnaire switch. Footers that say "Heart's Medication card"
  say "Body › Mind".
- The `phq` link still opens the sheet; nothing else links here.
- Check: compile, unit tests; screenshots of Mind with and without medication, Trends, Settings › Mind.

### UB3 App: Stress in order

- Files: `ui/StressScreen.kt`.
- Order: `DayPicker` as its own row, Resilience, the zones card, `ThroughTheDay`, "Load and recovery".
- "Load and recovery" draws only tiles above zero; with none it does not draw. No empty third cell.
- A "Breathe" `TintButton` under Resilience (`LocalOpenBreathe`).
- `ResilienceDetail`'s back link reads "‹ Body".
- Check: compile; screenshots with and without stress data.

### UR2 App: Trends charts that fit their data

- Files: `ui/TrendsScreen.kt`, `ui/Theme.kt` (`TrendCard`, `Chart`), `ui/ActivityScreen.kt`.
- Order: range and metric chips, the chart, `WeekCard`, `MonthCard`.
- Range "30 D · 90 D · 1 Y" (`days = 365`; the backend allows it).
- The x axis starts at the first day with data, not at the start of the range, here and in every `TrendCard`.
- Daily values are drawn point to point, not as a smoothed curve that dips past the values; steps as bars.
- Round axis ticks; no cut label ("Nov …").
- `WeekCard`: the title is "Last 7 days"; the changes say "against the week before"; a "Read the week" row opens
  Reports.
- Check: compile, unit tests; screenshots of 30 D, 90 D, 1 Y with 9 days and with 90 days of data.

### US1 App: Sleep in order

- Files: `ui/SleepScreen.kt`.
- "Edit" is the title-bar action; the two inline "Edit" links go.
- Order: hero, Stages, Time in stages, Blood oxygen, then a "Your pattern" group: Bedtime, Regularity, Last 7
  nights. Body clock only when the clock has shifted, and then right under the hero.
- Blood oxygen without dips is one line ("Average 98 % · lowest 97 %"); the dot row draws only with a dip.
- Bedtime: the time is the headline, "Learning your best bedtime · 9/14 nights" the small line.
- Check: compile; screenshots of a night, an empty night, a night with naps.

### UA1 App: Activity gets a hero

- Files: `ui/ActivityScreen.kt`.
- Hero card as Sleep's: steps and "n% of your goal" on the left, the Activity score ring on the right (from
  `/today` for today; no ring on a day without a score).
- Under the hourly chart, one row: Distance, Calories, and the `CardioLoad` card from UT1 below it.
- Workout rows: drop "load 0" and "added by you" (the detail keeps them). "Add to journal" goes: a day with a
  workout is added to the journal when the list loads.
- The 30-day card does not repeat today's steps as "Latest"; it shows the average.
- Check: compile; screenshots of today, a past day, a day without steps.

### UG1 App: Settings in order

- Files: `ui/SettingsScreen.kt`.
- Ring: a last group "Developer" with Sync log, Ring lab, Clear cached data, Send all history; the last two in
  `badText`.
- "Right now tiles" moves into You as a row; the root loses that group row.
- Phone and You: one footer under each switch or group it explains, not one block at the end.
- Data without a backend shows greyed with "Needs the server", not hidden.
- Check: compile; screenshots of the root, Ring, You, Phone.

### UC1 App: one look

- Files: `ui/Theme.kt`, `ui/App.kt`, every screen that titles a card by hand, `BreatheButtonTest.kt`.
- Every titled card uses `Section(title)`. The Settings `Section(header, footer)` is renamed `SettingsGroup` so
  the two stop sharing a name.
- A text that acts is a `TintButton` or a row with a chevron; a row that expands shows a small chevron that turns.
- The Breathe icon leaves `LargeTitleScaffold`; `BreatheButtonTest` asserts it on Stress and on the reply card.
- The tab bar draws only on the five tab routes.
- "Measure now" and "Add workout" share one button colour (`tint`).
- Check: compile, unit tests; screenshots of all five tabs, light and dark.

### UN1 App: one date across tabs

- Files: `ui/App.kt`, `ui/SleepScreen.kt`, `ui/ActivityScreen.kt`, `ui/BodyScreen.kt`, `ui/HeartScreen.kt`,
  `ui/StressScreen.kt`.
- The date is one saved state in `App`, passed to the three tabs. Opening the app again starts at today. The
  `workout` link still sets it.
- Check: compile, unit tests; pick yesterday on Sleep, see yesterday on Activity and Body.

### UN2 App: first run

- Files: `ui/TodayScreen.kt`, `ui/App.kt`.
- With no ring address or no server URL, Today shows one card in place of the empty state: "Set up Circa" with
  rows "Find your ring", "Connect the server", "About you", each opening its Settings page with a tick when done.
- Check: compile, unit tests; screenshots on a fresh install (`adb uninstall` first).

### RL4 Release v5.0.0

- After every item above. `app/build.gradle.kts`: versionCode 66, versionName "5.0.0".
- `.\gradlew.bat assembleRelease`, `apksigner verify --print-certs`, cert `3c236b22`, `gh release create v5.0.0`
  with notes listing what moved where.
- Only if UX4 changed the backend: backup of `circa.db`, deploy to the homelab, as RL3.
- Ledger: Facts line for the version; v18 moves to Done.

## Order

1. UX1, UX2, UX3, UX4: independent, but UX1 and UX2 both touch `ui/Theme.kt`, so UX1 → UX2; UX3 and UX4 beside.
2. UT1 → UT2 → UR1 → UT3 (all in `ui/TodayScreen.kt`, one after another).
3. UB1 → UB2 → UB3.
4. UR2, US1, UA1 (US1 can run beside UR2; UA1 after UR2, both touch `ui/ActivityScreen.kt`).
5. UG1 → UC1 → UN1 → UN2.
6. RL4.

## Not in this plan

- New data or scores, a redesign of Reports (only its duplicate cards are left alone), the widgets, dark-mode
  polish beyond the screenshot check, a year-in-review, a wizard for first run.
- Check-in sheet and questionnaire layout: not reviewed; a later pass after a week on 5.0.0.
