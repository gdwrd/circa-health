# Circa v16: mind

Status: plan (user, 2026-11-15). Nothing built. Items MH0 to MH13 are in the ledger; each `###` item below is one
ledger item / one session. Builds on v10 (`docs/PLAN10.md`: Right now, moments, reply card, dose timing) and v9
(things to mark, `insights.before`).

## Context and decisions

- Goal (user, 2026-11-15): the app should help with mental health, for someone with depression, ADHD and autism who
  is titrating an ADHD medication now. "Four types of mood is not enough." No meditation for now.
- Agreed (user: "I love all six of them"): a better mood check-in, a titration log, capacity and overload, passive
  phone signals, ADHD nudges, a periodic depression questionnaire.
- Decisions (mine; say so if one is wrong):
  - **No new screen and no new tab.** The check-in stays in Today's Right now card and the check-in sheet, the dose
    comparison goes on Heart's Medication card, charts go on Trends, switches in Settings.
  - **No new table.** Ratings are samples (as moments are), day tags are journal kinds, dose steps are one setting.
  - **The old four feelings go** (user, 2026-11-15: "let's drop old one"). Tired, flat, wired and good leave the
    app; the two scales and six body-state tags replace them. Their stored samples and notes are kept and still show
    on earlier days. The reply card opens for a tag, for Energy 1 or 2 (it keeps the `now_tired` note), for Energy 5
    (`now_wired`) and for Mood 1 or 2 (new note `now_low`). Backlog items SL1 / SL2 now count a day with an Energy
    1 or 2 tap as well as an old tired tap.
  - **The dose notification has a "Taken" button** (user, 2026-11-15).
  - **Settings is redesigned** (user, 2026-11-15): a root list of groups, each opening its own page (MH0).
  - **Shutdown / meltdown is a thing to mark**, not new code: the user names it in Settings › Things to mark. The
    widget, `/marks` ("what came before") and backlog item MM4 (heads-up) already exist for marks.
  - **The hyperfocus nudge already exists**: `notify._inactivity` ("No steps in the last 2 hours", 08:00 to 20:00).
    Nothing is built for it.
  - **Collect first.** Phase 1 only records and shows. Everything that concludes something (capacity, dip) is phase 2,
    behind a data gate, with thresholds set from the real data then.
- Rules for every item:
  - No diagnosis and no praise. No streaks, no "you missed a day"; a day without a check-in is never mentioned.
  - The app never advises on dose or timing (v10 rule). It shows what was logged, by dose step.
  - The on-phone model never words mood, load or questionnaire findings: those are fixed backend sentences
    (`quiet`-style lines), because the v12 trial showed the model hardens meanings.
  - Every tap is one tap and takes effect at once; no Save buttons, no required fields.
  - **Every UI change must look designed by Apple's designers, with our own character** (user, 2026-11-15, and the
    ledger's Apple-style UI direction): `ui/Theme.kt` tokens only, inset cards, hairlines, spring motion, the app's
    tinted icon chips; never a default Material look. Each app item is checked in light, dark and at 360 dp with
    font 1.3, from screenshots the reviewer reads.
  - The data stays in `circa.db` on the homelab. It leaves only through the export the user starts.
  - Scores (Sleep / Readiness / Activity) do not change; old days are not rescored.

## What exists (do not rebuild)

- Moments: sample `moment_<kind>` = 1 at the tap's second, 2 at the same second cancels (the larger value wins);
  `scoring.MOMENTS`, `scoring.FEELINGS = MOMENTS[:4]`, `main._moments`. App: `RightNow`, `NowTile`, `NowCard` in
  `ui/TodayScreen.kt` (about line 1570), `mark()` in `Marks.kt`.
- Journal: `journal(date, kind, value, note)`, one row per day and kind except `medication` and `breathe`;
  `db.KINDS`; the check-in sheet in `TodayScreen.kt` (`ToggleRow`, `KIND_LABEL`, `WHY`).
- Medication: journal rows `medication` with value `HH:MM`; settings `medication_started`, `medication_time`,
  `medication_window_h`; `insights.kinds()["medication"]` (with / without a dose over `insights.MED`, `by_time`);
  `MedicationCard` in `ui/HeartScreen.kt`; Settings › Medication.
- Blood pressure: journal kind `bp`, `insights.bp`.
- Things to mark: settings `mark_1..3`, samples `mark_N`, `GET /marks`, `insights.before`, `widget/MarkWidget.kt`.
- Phone: `biometric_screen_on` 1 / 0 events (Settings › Phone switch plus Usage access), journal kind `phone_in_bed`,
  phone-detected walks and rides (`MoveReceiver`).
- Notifications: `notify.py` (`KINDS`, `_add`, `_fired`, `evaluate`), shown by the phone at the next sync; Settings
  has one switch per kind (pattern: RO1, `ringoff`).
- Findings: `report._findings` (only the unusual, at most 2, fixed `quiet` lines).
- Export: Settings › Data, a one-page PDF built with `PdfDocument` (`ui/SettingsScreen.kt` about line 737).

## Data model

- **Scales** are samples with a value 1 to 5 at the tap's second; 9 at the same second cancels (readers ignore
  values above 5). Metrics: `mood_energy` (1 flat … 5 wired), `mood_valence` (1 bad … 5 good), `rate_focus`,
  `rate_appetite`, `rate_crash` (1 none … 5 strong). A day's value is the mean of its mood samples and the latest of
  each rating.
- **Feelings**: `scoring.FEELINGS` becomes an explicit tuple of six: overstimulated, numb, anxious, irritable,
  focused, foggy. All are `moment_<kind>` samples with the reply card and a `now_<feeling>` note. The old four move
  to `scoring.OLD_FEELINGS`: no longer offered, still read for earlier days (`_moments`, the timeline, notes).
  Nothing is deleted from `circa.db`.
- **Load** are journal kinds `load_social`, `load_sensory`, `load_masking` (day-level switches, one row a day).
- **Dose steps**: setting `medication_doses`, a JSON list `[{"from": "2026-10-20", "mg": 30}, …]`, ascending by
  date; the dose of a day is the last step that starts on or before it. Setting `medication_name` (free text, for
  the summary's heading). Dose rows in the journal do not change.
- **Questionnaire**: samples `phq9_total` (0 to 27) and `phq9_item9` (0 to 3) at the time it was finished. Single
  answers are not stored.

## Phase 1: record and show (release MH10)

### MH0 App: Settings as groups with their own pages

- Files: `ui/SettingsScreen.kt`, `ui/Theme.kt` (tokens for the group icon tiles), `app/src/test/.../ui/SettingsGearTest.kt`
  if it breaks. Can run beside MH1. Before every later item that adds a Settings row.
- Root page: the large "Settings" title, then one status card with character (the ring's name, battery, last sync,
  drawn with the app's ring motif, not a plain row), then the groups as inset rows: a tinted rounded-square icon
  tile, the name, a short current value on the right, a chevron.
- Groups and what moves into each (every existing row keeps its behaviour and text):
  - **Ring** — today's Ring section, Sync log, Ring lab, firmware.
  - **You** — birth year, goals, wake times, Workouts.
  - **Medication** — today's section (MH5 adds Name and Dose steps).
  - **Mind** — Things to mark (MH9 adds the questionnaire).
  - **Notifications** — every per-kind switch, grouped Morning / Day / Evening.
  - **Phone** — Phone and Driving.
  - **Reports** — today's section.
  - **Data** — exports (MH6 adds the titration summary).
- Each group is a page inside `SettingsScreen` (a `page` state, as `SyncLogScreen` already is; no new nav route):
  large collapsing title, back chevron, system Back returns to the root, the slide the app already uses between
  screens. The section footers stay as the explaining text.
- Check: `.\gradlew.bat :app:compileDebugKotlin`, `.\gradlew.bat :app:testDebugUnitTest`; emulator: every group
  opened, one row changed in each and found changed after reopening, Back from each; screenshots light, dark,
  360 dp with font 1.3.

### MH1 Backend: scales, six feelings, load kinds, `GET /mind`

- Files: `backend/app/scoring.py`, `main.py`, `db.py`, `notify.py` only if a reader of `FEELINGS` breaks,
  `backend/tests/test_mind.py` (new); `ingest` only if it filters metric names.
- `scoring.SCALES` (the five metrics above), `FEELINGS` as the six, `OLD_FEELINGS` as the four, `MOMENTS` =
  feelings + old feelings + coffee, alcohol, meal (so earlier days still read). The `now_` note check accepts the
  six, `tired`, `wired` and `low`. `/today.now.notes` returns those. `notify._checkin` counts a scale tap as a moment.
- `db.KINDS` gains the three `load_*` kinds; they stay out of `insights.NIGHT_KINDS` for now.
- `GET /mind?days=90` → `{"days": [{"date", "energy", "valence", "focus", "appetite", "crash", "feelings": {kind: n},
  "load": [kind, …], "mg"}], "phq": [{"ts", "total"}]}`; a day with nothing logged has nulls and empty lists.
  `/today.now` gains `"mood": {"energy", "valence"}` (latest of today, or null).
- Tests: a cancelled scale sample is ignored; day mean and latest rating; an unknown feeling is not a moment.
- Check: `python -m compileall -q backend/app && python -m pytest backend/tests -q`.

### MH2 Backend: dose steps and the comparison by dose

- Files: `backend/app/db.py` (two settings), `main.py` (settings read / validate, `/insights`, `/mind` fills `mg`),
  `insights.py`, `backend/tests/test_insights.py`. After MH1 (both edit `main.py`).
- `insights.levels(steps, per_day)`, pure: for each dose step, over the days in it that have a logged dose: `n`, and
  the mean of `insights.MED` (day heart rate, resting heart rate, HRV, bedtime, sleep time, sleep score), of the five
  scales, and of systolic / diastolic from `bp` rows. A value needs 3 days or it is null. Returned as
  `kinds.medication.levels = [{"from", "to", "mg", "n", "means": {…}}]`.
- Validation of `medication_doses`: a list, dates ISO and ascending, `mg` a number from 1 to 500; `medication_name`
  at most 40 characters.
- Check: as MH1.

### MH3 App: the check-in in Right now

- Files: `ui/TodayScreen.kt`, `ui/Theme.kt` (colour tokens for the six new feelings and the two scales). After MH1;
  can run beside MH2.
- "I feel" becomes: two rows of five dots, **Energy** (flat … wired) and **Mood** (low … good), then the six
  feelings as tiles with their own icon and colour. The four old tiles are removed. A tap on a dot or tile logs it
  now; the existing "… · 17:32 · Undo" line and the long-press "earlier time" work for all of them. A dot pushed
  again the same day adds a sample; it does not replace one.
- Scale samples go through `pushSample` (as `mark()` does) with the value 1 to 5, cancel 9.
- The reply card opens for a feeling tile, Energy 1 / 2 (note `now_tired`), Energy 5 (`now_wired`) and Mood 1 / 2
  (`now_low`). `DaySoFar` shows the new feelings, the old ones on earlier days, and scale taps as one line
  ("Energy 2 · Mood 4") when both fall within the same five minutes.
- The dots are the item's design work: they must read as ours (the accent of the scale filling up to the tapped
  dot, a spring pop as `NowTile` has), not as a Material slider or radio row.
- Accessibility: each dot is a button with a content description ("Energy 2 of 5"); 48 dp targets; colours from
  tokens only.
- Check: `.\gradlew.bat :app:compileDebugKotlin`, `.\gradlew.bat :app:testDebugUnitTest` (`Theme.kt` changes);
  Pixel_9a against the seeded local stack: each dot, each chip, undo, earlier time; light, dark, 360 dp with font 1.3.

### MH4 App: load and evening ratings in the check-in sheet

- Files: `ui/TodayScreen.kt` (check-in sheet). After MH3 (same file).
- Section "The day took": three switches, Social, Sensory, Masking (`load_*` rows, `ToggleRow`, `WHY` texts).
- Section "Medication today", only when `medication_started` is set and the day has a dose: Focus, Appetite, Evening
  crash as five-dot rows (the dot row of MH3), stored as `rate_*` samples at the time of the tap, or at 21:00 of that
  date when the sheet is open on an earlier day.
- Check: compile; emulator: toggles saved and shown again, ratings shown again, an earlier day.

### MH5 App: dose steps, By dose, mood on Trends

- Files: `ui/SettingsScreen.kt`, `ui/HeartScreen.kt`, `ui/TrendsScreen.kt`, `ui/Api.kt`. After MH2; can run beside
  MH3 / MH4 (no shared file).
- Settings › Medication: "Name" (text), "Dose steps": a list of "30 mg · from 20 Oct", add (mg, date with the
  existing date dialog) and delete.
- Heart › Medication card: a "By dose" table, one row per step: mg, days, resting heart rate, sleep time, bedtime,
  mood, focus, appetite, crash, blood pressure; "—" where under 3 days. Footnote: "Averages from your own data.
  Other things may play a part. Not medical advice." (the card's existing line).
- Trends: a "Mood" card: energy and mood per day as two lines over the range the page already uses, dose-step
  changes as vertical lines (`DoseWindows` with `line = true`). Days without a check-in are gaps.
- Check: compile; emulator with two seeded dose steps and a fortnight of scale samples.

### MH6 App: the titration summary

- Files: `ui/SettingsScreen.kt`. After MH5.
- Settings › Data: "Titration summary (PDF)", shown when `medication_doses` has a step. One A4 page with the
  existing `PdfDocument` code: name, the steps with dates, the By dose table, usual dose time, blood pressure
  readings by step, the last 14 days' mood line, and "Estimates from a consumer ring and the user's own ratings, not
  medical measurements." Shared with the existing share sheet.
- Check: compile; the PDF opened on the emulator and read.

### MH7 Backend + Settings: dose and meal nudges

- Files: `backend/app/notify.py`, `backend/tests/test_notify.py`, `ui/SettingsScreen.kt`, `SyncWorker.kt` (the
  notification's action only), `DoseActionReceiver.kt` (new, as `WorkoutActionReceiver`), `AndroidManifest.xml`.
  After MH6 (same app file).
- `dose` ("Dose logged?", "Nothing logged today. Usual time 08:00."): `medication_started` set, no `medication` row
  today, 60 min or more after `medication_time`, before 14:00, once a day. It never says to take anything.
- The `dose` notification has one button, **Taken**: it adds a `medication` journal row at the minute of the press
  (`Api.addJournal`) and dismisses the notification; a failed write keeps the notification and shows "Not saved".
- `meal` ("Nothing eaten since your dose?", "No meal logged since 08:05."): a dose today, 4 h or more since it, still
  inside the active window, no `moment_meal` since the dose, once a day.
- Both in `KINDS` and `evaluate`, each with a Settings switch (RO1's pattern). They arrive with the next sync, so
  they can be late; accepted, as for every other kind.
- Check: backend check; `.\gradlew.bat :app:compileDebugKotlin`; emulator: the notification shown, Taken pressed,
  the dose on Today.

### MH8 Backend: the questionnaire

- Files: `backend/app/notify.py`, `main.py` (`/mind.phq`, done in MH1 if trivial), `backend/tests/test_notify.py`.
- Kind `phq` ("Two-minute check", "The depression questionnaire, every two weeks."): 14 days or more since the last
  `phq9_total` sample, between 10:00 and 20:00, only when the setting `phq` is on (default off; it is turned on in
  MH9's Settings row). Never repeated inside the 14 days.
- Check: backend check.

### MH9 App: the questionnaire

- Files: `ui/PhqSheet.kt` (new), `ui/SettingsScreen.kt`, `ui/TrendsScreen.kt`. After MH7 and MH8.
- The PHQ-9: nine questions, four answers each (0 to 3), the published wording unchanged (free to use, no permission
  needed; cite "Developed by Drs. Robert L. Spitzer, Janet B.W. Williams, Kurt Kroenke and colleagues" on the
  sheet). One question a page, a tap answers and moves on, Back works, nothing is stored until the ninth answer.
- Result page: the total, the questionnaire's own band (0–4 minimal, 5–9 mild, 10–14 moderate, 15–19 moderately
  severe, 20–27 severe) with "The questionnaire's bands, not a diagnosis.", and the change from last time.
- When question 9 is above 0, the result page shows first, above everything: "You marked thoughts of being better
  off dead or of hurting yourself. Please talk to your doctor or someone you trust today. If you might act on them,
  call your local emergency number now." with a link to `findahelpline.com`. This text is never a notification.
- Settings › Mind: switch "Depression questionnaire every two weeks" (setting `phq`), row "Answer now".
  The `phq` notification opens the sheet.
- Trends › Mood card: each total as a labelled point under the lines.
- Check: compile; emulator: a full run, Back, leaving half way (nothing stored), question 9 above 0, the trend point.

### MH10 Release and homelab deploy

- RO2's pattern, after a `circa.db` backup. It also carries v15's RO1 if that is still unreleased.

## Phase 2: conclusions, each behind its gate

### MH11 Capacity (gate: 14 days with a mood sample or a load tag in the real `circa.db`)

- Files: `backend/app/insights.py`, `report.py`, `main.py`, tests.
- `insights.before` gains two inputs for the day before: `load` (number of `load_*` tags over the 3 days before)
  and `valence`. So `/marks` for a thing named "Shutdown" says what came before those days with no further code.
- One finding for the `today` card, a fixed sentence, at most one a day: "The last 3 days were heavy (social,
  masking) and you slept 50 min less than usual. A day to plan less." Starting rule (mine, to be reset from the real
  days): two or more of: sleep short by `UNUSUAL`, load tags on 2 of the last 3 days, HRV low by `UNUSUAL`. With
  3 or more marked shutdowns, the rule becomes `before.pattern` of that mark.

### MH12 Dip (gate: 21 days of `biometric_screen_on` and 14 days with a mood sample)

- Files: `backend/app/insights.py`, `notify.py`, `report.py`, tests, `ui/SettingsScreen.kt`.
- `insights.dip(days)`, pure: the last 10 days against the 30 before them, one signal each for: steps down, screen
  time up, screen use after the planned bedtime up, bedtime later or more scattered, sleep much longer or shorter,
  mood down, more quiet days (under 1,000 steps and no phone-detected walk or ride; location stays out, so this is
  not "did not leave the house"). A dip is 3 or more signals. All thresholds from the real data at that time.
- Told once per 14 days: a `week` card finding and a notification kind `dip` with a Settings switch: "The last 10
  days look different from your usual: less movement, later nights, lower mood. That is often how a low stretch
  starts. Worth telling someone if it goes on." Never the word depression, never a score.
- Takes the phone-use half of the parked "Calendar and phone-use patterns"; calendar stays parked.

### MH13 Mood widget (gate: two weeks of MH3 in use, and the user still wants it)

- Files: `widget/MoodWidget.kt` (new, as `MarkWidget`), manifest, widget info xml. Five dots for Mood; a tap logs
  `mood_valence` now.

## Not doing

- Meditation (user: later). A new tab or screen. Location, calendar, microphone, typing or app-by-app usage.
- Water reminders (nothing measures it). Deleting the old feelings' samples or notes.
- Other questionnaires (GAD-7, ASRS), storing single PHQ answers, sending anything to a clinician by itself.
- Any advice on dose, timing, or stopping a medication. Weight, pulse-on-demand or blood pressure reminders.
- A change to the on-phone model's prompts.

## Sources

- PHQ-9, free to reproduce and use: https://www.phqscreeners.com/
- Alexithymia is common in autism, so naming an emotion is hard; hence scales and body states:
  https://www.ncbi.nlm.nih.gov/pmc/articles/PMC6331035/
