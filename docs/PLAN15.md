# Circa v17: the week, whole

Status: plan (user, 2026-11-16). Nothing built. Items PM1 to RL2 are in the ledger; each `###` item below is one
ledger item / one session. Builds on v12 (`docs/PLAN12.md`: the on-phone writer and the Reports page), v13
(`docs/PLAN13.md`: the timeline, "Update me"), v14 (notes that say something new) and v10 (`docs/PLAN10.md`: Right now).

## Context and decisions

- Asked (user, 2026-11-16):
  - "This week" and "Next week" leave Reports. One **Last week** report replaces them: much bigger, an overview with
    explanations of how the week went, several graphs, the week's start and end dates visible.
  - It is written on Monday only. A manual Regenerate still gives the model last week (Mon to Sun), never the
    unfinished week.
  - Regenerate today resets the morning note's time, which removes the user's actions and hides the timeline.
    The morning report always stays on top; Regenerate rewrites the text only, not the inputs; every activity and
    action of the day stays.
  - The timeline shows steps and activities.
  - Phone walks stopped arriving (a 35-minute walk on 2026-11-16 never showed).
  - More "I just had" tiles, only ones that affect mood or body, and the user chooses which tiles show.
  - A Monday notification for the weekly report. Weekly reports are kept for longer, with a separate way to see
    older ones.
  - The focus line of "Next week" stays, as the close of the Last week report.
- Decisions (mine; say so if one is wrong):
  - **Sections, not one long note.** The on-phone model is small and every note is checked for numbers that are not
    in its facts; a long note fails more often. Last week is an overview plus five sections, each a short note with
    its own graph.
  - **"Monday only" = the first sync on or after Monday.** A week with a stored report is never written again by a
    sync; only Regenerate rewrites it. A missed Monday sync does not lose the week.
  - **Weekly reports live in their own file** (`filesDir/weeks.json`), never pruned. `reports.json` keeps its 14 days.
    The text is on the phone only (a reinstall loses it); the graphs are redrawn from the backend.
  - **Regenerate reuses the stored inputs.** Each written card keeps its facts, so a rewrite cannot change with
    later data. Update notes are never touched by Regenerate.
  - **Steps are hourly**, because the ring reports steps per hour: an hour with 500 or more steps is one timeline row.
  - **New tiles: snack, energy drink / tea, nicotine, nap.** Energy drink / tea counts as caffeine wherever coffee
    does. Nicotine and nap are hidden by default. Water and supplements are left out (weak signal, many taps; the
    dose tile covers medication).
  - **Phone walks: register once, log every event.** The cause is not proven (see "Phone walks: what is known"); the
    fix removes the suspect and the log settles it on the next real walk.
- Rules for every item (unchanged from v12 to v16):
  - No scores in any written text; never "Oura". No medical advice, no illness names, no advice on medication.
  - The model words only what the backend decided: facts and advice lines come from `report.py`.
  - Mood, load and questionnaire findings are fixed backend sentences (`quiet` cards), never worded by the model
    (PLAN14).
  - Calm mode: no numbers in facts (`ctx["calm"]`, as every existing card).
  - Every UI change looks designed (Apple Health feel, our own character): `ui/Theme.kt` tokens only, checked in
    light, dark and at 360 dp with font 1.3 from screenshots.
  - Scores do not change; old days are not rescored; no data is deleted.

## What exists (do not rebuild)

- Writer: `ReportWriter.kt`. `writeReports` (the day's cards into `reports.json` as `"date/kind": {hash, text, at,
  advice}`), `writeUpdate` (`"date/update-<at>"`), `checkedNote` (numbers, banned words, 2 sentences or more, cut at
  900 characters), `keepDays` (14 days), `dropDay` (what Regenerate does today).
- Backend cards: `report.cards` (`today`, `yesterday`, `night`, `tonight`, `week`, `next_week`, `body`), `_card`,
  `_quiet`, `_spread`, `_week` (differences from the week before), `_next_week` (verdict `sleep` / `regular` /
  `move` / `keep`), `report.timeline`, `report.update`. `main._week(end)` gives 7 days of per-day values.
- Reports page: `ui/ReportScreen.kt`. `pageOrder`, `title`, `ReportCardView`, graphs per card kind, the rail
  (`RailRow`, `NodeHeader`, `EventRow`, `EventChip`, `halfHours`, `UpdateEntry`), `DayPicker` for earlier days.
- Notifications: backend `notify.KINDS` / `evaluate`; `_weekly` already fires Monday 08 to 12 ("Your week",
  averages). `SyncWorker` shows them and, for `evening`, runs `writeUpdate` first and shows the note.
- Moments: sample `moment_<kind>` = 1, 2 at the same second cancels; `scoring.MOMENTS`; `report.LABEL`.
- Phone moves: `Moves.kt` (`registerMoves`, `MoveReceiver`), samples `phone_walk` / `phone_run` / `phone_ride`
  (value = minutes, timestamp = start); `scoring.moves` merges pieces 5 minutes apart and drops a walk under 10
  minutes; `main` turns them into workouts, which `/timeline` already lists.

## Phone walks: what is known (2026-11-16, read over wireless adb and from the homelab db)

- Phone: v4.8.0, `ACTIVITY_RECOGNITION` granted, the switch on, "Moves: registered" in logcat, the receiver's
  `PendingIntent` live, broadcasts delivered to `MoveReceiver` (11:32:13 and 12:21:18).
- Homelab: 69 `phone_walk` samples, all between 2026-11-11 01:01 and 2026-11-12 00:40; none since. The ring counted
  2,866 steps in the hour to 10:00 on 2026-11-16.
- `Moves.kt` has not changed since it worked (only the package rename).
- Suspect, not proven: `SyncWorker.doWork` calls `registerMoves` on every sync. The 12:21:18 broadcast came 6 ms
  after a registration and the 11:32 one lines up with a sync start, so the broadcasts seen look like echoes of
  registering, not real transitions. An echoed ENTER resets the open move's start; a registration near the end of a
  walk may swallow its EXIT. Syncs run every 5 to 8 minutes while the phone is in use.
- Not readable from outside: what the events contain (Android strips the extras), the app's Sync log (release build).

## Items

### PM1 App: register for moves once, log every event

- Files: `Moves.kt`, `Prefs.kt` (one new value), `SyncWorker.kt` (the `registerMoves` call at the top of `doWork`
  only).
- `registerMoves` from a sync runs only when the registration is not known to be alive: `Prefs.movesRegistered`
  holds `"<Settings.Global.BOOT_COUNT>|<versionCode>"` of the last successful registration; a sync registers when
  it differs (after a reboot or an app update) and stores it in the success listener. The Settings switch still
  registers at once and stores it; `unregisterMoves` clears it.
- `MoveReceiver` writes one Sync log line per event before any other logic: "Moves: walk started 10:02" / "Moves:
  walk ended 10:37". The existing "Moves: walk 35 min from 10:02" line stays. A broadcast without a result logs
  "Moves: empty broadcast".
- Check: `.\gradlew.bat :app:compileDebugKotlin`.

### RG1 App: Regenerate rewrites the text only

- Files: `ReportWriter.kt`, its unit test in `app/src/test`.
- Every card entry also stores its inputs: `verdict`, `facts`, `fallback`, `quiet` (next to `hash`, `text`, `at`,
  `advice`).
- `writeReports(force = true)`: no `dropDay`. For each of today's card entries (never `update-*`): blank its `text`
  (the page shows its loading bars), then write a new note from the **stored** inputs and put it back with the same
  `at`, `advice` and inputs. An entry written before this build has no stored inputs: take them from `/report` once,
  keep its `at`. A card `/report` has that has no entry yet is written as today (new `at`).
- `dropDay` goes if nothing else uses it. `updateSince` and `givenAdvice` are unchanged (the `at` no longer moves).
- If the page treats blank text differently from a missing entry, the one-line fix in `ui/ReportScreen.kt` belongs
  to this item.
- Tests: Regenerate keeps `at`; keeps every `update-*` entry; uses stored facts, not new ones; an old entry without
  inputs is filled once.
- Check: `.\gradlew.bat :app:compileDebugKotlin`, `.\gradlew.bat :app:testDebugUnitTest`.

### TL1 App: the timeline starts at wake, the morning note stays on top

- Files: `ui/ReportScreen.kt` (the `entries` list of the Today card, about line 639).
- The morning node is always the first row. Under it: every event of the day from the `wake` event on (all events
  when there is none), then updates, in time order. The `morningAt` filter goes.
- Check: compile, `:app:testDebugUnitTest`; emulator screenshot with an event before the morning note's time.

### RL1 Release v4.8.1 (app only)

- After PM1, RG1 and TL1. Version bump, `assembleRelease`, cert `3c236b22`, GitHub release as the earlier ones. No
  backend change, no deploy.

### PM2 Real walk check (user-driven)

- After RL1 is on the phone: one walk of 10 minutes or more with the phone. Read the Sync log (the user's
  screenshot, or logcat over wireless adb) and the homelab `phone_walk` samples.
- Walk arrives: record it in Facts, close the item. No walk: the log lines say what the phone sent (no events, ENTER
  without EXIT, wrong kind); write the finding into Facts and a new ledger item for the real cause.

### TL2 Backend: active hours in `/timeline`

- Files: `backend/app/report.py` (`timeline`), `backend/app/main.py` (the `/timeline` route passes the day's hourly
  steps), `backend/tests/test_report.py`.
- One event per hour with `STILL_STEPS` (500) or more steps: `{t: hour start, kind: "steps", label: "2,866 steps",
  steps: 2866}` (calm: "An active hour", no number). An hour that a walk or run workout overlaps is left out (the
  workout row already says it).
- Workout events keep their label ("Walk · 35 min"); nothing else changes.
- Check: backend check (`compileall`, `pytest backend/tests -q`).

### TL3 App: steps rows on the rail

- Files: `ui/ReportScreen.kt` (`EventChip`, `EventRow`). After TL1 and TL2.
- A `steps` event gets its own chip (walk icon, the activity color token) and shows its label; it joins a half-hour
  row like any event.
- Check: compile, `:app:testDebugUnitTest`; emulator screenshot light and dark.

### JH1 Backend: four new moments

- Files: `backend/app/scoring.py`, `backend/app/report.py`, tests of both. After TL2 (same file).
- `scoring.MOMENTS` gains `snack`, `energy`, `nicotine`, `nap`; `report.LABEL`: "Snack", "Energy drink", "Nicotine",
  "Nap".
- `energy` counts as caffeine wherever `coffee` does: `scoring`'s `caffeine_late` rule (at or after 14:00),
  `report.update`'s coffee count and cut-off advice (wording becomes "No more caffeine today"), the last-time /
  effect code that reads `moment_coffee` only if it is one line; else left and noted.
- `nap`, `snack`, `nicotine` are recorded and shown; no advice rule yet (JH3).
- Check: backend check.

### JH2 App: the tiles and their switches

- Files: `ui/TodayScreen.kt` (the "I just had" rows), `ui/SettingsScreen.kt` (a "Right now tiles" page of
  switches, MH0's group pattern), `Prefs.kt` (`hiddenTiles`, a string set; default `nicotine`, `nap`),
  `ui/ReportScreen.kt` and `TodayScreen.kt` chips for the four new kinds. After TL3 (same file).
- Eight tiles: coffee, alcohol, meal, dose, snack, energy drink, nicotine, nap; shown in rows of four, hidden ones
  left out; the last row keeps tile width (no stretched tiles). Every tile can be hidden, dose included.
- Tap, time pick and Undo work as for the existing tiles.
- Check: compile, `:app:testDebugUnitTest`; emulator screenshots: all eight on, the default six, at 360 dp font 1.3.

### LW1 Backend: the fixed week, sleep and activity sections

- Files: `backend/app/report.py`, `backend/app/main.py`, `backend/tests/test_report.py`. After JH1.
- `GET /report/week?start=YYYY-MM-DD` (a Monday; default: the Monday of the last full week before today; 422 for a
  non-Monday or a week not finished) → `{start, end, days: [7 × {date, ...}], cards: [...]}`. Pure builder
  `report.week_cards`.
- `days` carries what the graphs draw: `sleep_min`, `need_min`, `bed_min` (fall-asleep time as minutes after noon),
  `steps`, `load`, `workout_min`.
- Cards (kind, then facts; at most five facts each, a reason with a fact where the data gives one):
  - `week_sleep`: average night against the need and against the week before, the shortest and longest night by
    weekday, bedtime spread (`_spread`), nights under the need.
  - `week_activity`: steps total and against the week before, the most and least active day, workouts (count,
    minutes, kinds), cardio load against the 14-day mean.
- `week` and `next_week` leave `report.cards` and `/report` (their tests move to the week tests). A week with fewer
  than 3 scored days returns no cards.
- Check: backend check.

### LW2 Backend: body and mind sections

- Files: as LW1.
- `days` gains `rhr`, `hrv`, `stressed_min`, `energy`, `valence`.
- `week_body`: resting heart rate and HRV against the 14-day baseline (as differences), stressed time against the
  week before, the day that stood out, body-signal days if any.
- `week_mind`: a `quiet` card (fixed sentences, the model is not called): days with a check-in, energy and mood
  against the week before in words, the most-tapped feeling tags. No card when the week has no check-in.
- Check: backend check.

### LW3 Backend: what affected what, the overview, the focus line

- Files: as LW1.
- `week_effects`: what the week's own taps and events were followed by, from the existing effect code (`because`,
  `insights`): nights after alcohol, caffeine at or after 14:00, a late meal, a workout; at most three, each only
  with 2 or more such nights in the week. No card when there is none.
- `week_overview` (first card): the one or two strongest facts of each section, already used wording allowed
  (the overview is the summary). Its `advice` is the focus line: `_next_week`'s verdict and line, worded for the
  week now starting ("This week, put sleep first."). The `d.weekday() > 2` rule goes; the verdict rule stays.
- Order in `cards`: overview, sleep, activity, body, mind, effects.
- Check: backend check.

### LW4 App: the week writer

- Files: `ReportWriter.kt`, `ui/Api.kt` (`reportWeek(start?)`), `SyncWorker.kt` (one call after `writeReports`),
  unit test. After RG1 and LW3.
- `writeWeek(ctx, force = false)`: `/report/week`; entries in `filesDir/weeks.json` as `"<start>/<kind>": {text, at,
  advice, verdict, facts, fallback, quiet}`, never pruned. Without `force`: nothing when the week's overview entry
  exists. With `force` (Regenerate): RG1's rule, text only from the stored inputs.
- Length: week sections 3 to 5 sentences (`reportSystem` gets the length by kind); `kindFocus` lines for the six
  kinds; `week` and `next_week` leave `kindFocus`. The same `Mutex`, checks and fallback as a day card.
- Regenerate on the Reports page calls `writeWeek(force = true)` after `writeReports(force = true)`.
- Check: compile, `:app:testDebugUnitTest`.

### LW5 App: the Last week card, sleep and activity graphs

- Files: `ui/ReportScreen.kt`. After JH2 and LW4.
- `week` and `next_week` leave `pageOrder` and `title`. In their place one **Last week** card: the date range as
  its subtitle ("9 to 15 Nov"), the overview note, the focus line as its advice row. It opens the week page: the
  overview, then one card per section (note, advice, graph), in the backend's order.
- Graphs from the reply's `days` (the page's existing chart code): sleep = seven bars with the need line and bedtime
  dots; activity = seven step bars with workout days marked.
- Shown every day of the week, with loading bars while it is written and the fallback text when a check failed.
- Check: compile, `:app:testDebugUnitTest`; emulator screenshots light, dark, 360 dp font 1.3.

### LW6 App: body, mind and effects graphs

- Files: `ui/ReportScreen.kt`. After LW5.
- Body: resting heart rate and HRV as two small lines against their baseline band, stressed minutes as bars. Mind:
  energy and mood as two dotted lines over the seven days (gaps left open). Effects: no graph, the facts as rows.
- Check: as LW5.

### PW1 App: past weeks

- Files: `ui/ReportScreen.kt`. After LW6.
- A "Past weeks" row at the bottom of the week page and under the Last week card: a list of the weeks in
  `weeks.json`, newest first, by date range. A tap opens that week's page: stored text, graphs from
  `/report/week?start=`. Past weeks are never written and have no Regenerate. Empty list: the row is hidden.
- Check: compile, `:app:testDebugUnitTest`; emulator screenshot with two stored weeks.

### LW7 The Monday notification

- Files: `backend/app/notify.py`, `backend/tests/test_notify.py`, `SyncWorker.kt` (the `weekly` kind only).
- `_weekly`: title "Last week", body "Your report for 9 to 15 Nov is ready." (no averages, no scores), `open` =
  `reports`; the Monday 08 to 12 window and the once-a-week guard stay.
- `SyncWorker`, for `weekly`: `writeWeek` first, then the overview note's first two sentences as the expandable
  text (the `evening` pattern); the backend's line when nothing was written.
- Check: backend check; compile; emulator: the notification shown and opening Reports.

### RL2 Release v4.9.0 and homelab deploy

- After everything above. Homelab backup of `circa.db`, `backend/app` + `tests` copied, `docker compose up -d
  --build`, `/health`; `/report/week` and `/timeline` answered on the real data. App version bump,
  `assembleRelease`, cert `3c236b22`, GitHub release.

### JH3 Backend: what the new tiles are followed by (gated)

- Blocked: needs 5 days with a tap of one new kind in the real circa.db.
- Files: `backend/app/insights.py`, `report.py`, tests. Nights after nicotine, a nap or a late snack join
  `week_effects` and `insights`, with thresholds set from the real data then.

## Order

- PM1, RG1 and TL1 side by side (no shared file) → RL1 → PM2 (user-driven, does not block the rest).
- Backend, one after another (all in `report.py`): TL2 → JH1 → LW1 → LW2 → LW3.
- App, one after another (shared files): TL3 → JH2 → LW4 → LW5 → LW6 → PW1 → LW7 → RL2.
- The backend line and TL3 / JH2 can run side by side; LW4 waits for LW3.

## Not in this plan

- Water, supplements, any tile without an effect on mood or body.
- A chat, a weekly PDF, a monthly report change.
- Sync of weekly texts to the backend (a reinstall loses the old texts; the data and graphs stay).
