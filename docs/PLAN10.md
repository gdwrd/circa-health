# Ring Health v10: a helper, not a tracker

Status: plan (user direction, 2026-11-10), in the ledger since 2026-11-10 as "Active Task: v10": each `###` item below
is one ledger item / one session. `docs/PLAN.md` … `PLAN9.md` stay the references for what already exists. Line numbers
are as of `b2365fc`.

## Context and decisions

- Goal (user, 2026-11-10): "I started building a tracker that I didn't want." The app should help during the day: say
  how you feel, get something back, and have the evening and the next morning react to what happened. Numbers stay
  available, one tap away. Not wanted: praise, scores as goals, streaks.
- What triggered it: tired at 17:30 at the office with nowhere obvious to say so (the journal is a per-day checkbox,
  "Things to mark" needs naming in Settings first), and a 18:10–18:30 ride home the app never saw.
- Measured that day (homelab ring.db, read-only):
  - The ring read 87, 53, 95, 60, 84 bpm during the ride. The sensor fails on handlebars; no threshold fixes that.
  - Calories are steps × about 8 in every hour (84 → 749, 351 → 2867, 926 → 7338): the ring's movement data is a step
    count and nothing else. The ride hour looked like a short walk.
  - 17:30: stress at its afternoon low (23), HRV at its high (49); heart rate dipped as far at 16:00 and 16:35.
    One afternoon proves nothing: prediction from ring data is unproven.
- Decisions (user, same day):
  - **Moments, not checkboxes.** Feelings and intakes are taps with a time. The backend derives the day-level journal
    kinds from them, so `insights.py` keeps working unchanged.
  - **The app reacts at three moments:** at a feeling tap (reply card), in the evening (Tonight), the next morning.
    Never on an intake tap.
  - **Dose timing is shown** ("to understand when it's going to hit me"): the logged dose and the active window the
    user set. The app states times only and never suggests changing dose or timing.
  - **Use the phone's data too**, not only the ring's. First sources: activity recognition and the next alarm.
  - Your own note is the advice. No suggestion list written by us.
- Research behind the tone (2026-11-10, links under Sources): being told a night was bad makes the day feel worse
  whatever the night was; broken streaks make people quit; half of lapsed tracker users describe guilt.
- Standing rules hold: tokens-only UI from `AppTheme.colors` / `AppType` (no new token), `Theme.kt` components, one
  sentence per number, nothing shown before it has data, never delete ring data, never "Oura scores", copy never
  diagnoses, `tick()` on every tap. No feature here changes Sleep / Readiness / Activity; old days are not rescored
  for new fields.
- Rule changes this plan makes, on purpose:
  - PH2 adds one package (`play-services-location`). It is the ledger item that allows it.
  - PH2 and PH3 each add lines to `SyncWorker.kt`; RN2 changes `Marks.kt`. `MetricsPusher`, `ColmiProtocol`,
    `ColmiConnection` stay untouched (AQ1's probe line lives on a branch).
  - Calendar and location were ruled out in v7. Calendar is now parked, not ruled out; location stays out.

### Switches

| Feature | New switch? | How it is turned on or off |
|---|---|---|
| Right now, reply card, Day so far | No | Always there; they replace the Journal card |
| Scores and vitals | No | Always shown on Today; the fold row and `Prefs.showNumbers` were removed 2026-11-11 |
| Evening / morning reaction | No | Shows only when something was logged |
| Rides and walks from the phone | Yes, off by default | `Prefs.phoneMoves` plus Android's physical-activity permission |
| Next alarm | No | No permission; falls back to the wake times in Settings |

## What exists (do not rebuild)

- A mark is a sample: `mark(ctx, slot, tsMs, cancel)` (`Marks.kt:14`) stores `mark_N` = 1 at the whole second in
  `SampleStore`, pushes it once, and leaves it for the next sync when the push fails; value 2 at the same timestamp
  cancels. Ingest rescoring covers every date in the body (`dates_in_body`, BG20), so a pushed sample is scored
  before the 204.
- Auto journal rows: `db.add_auto` / `db.delete_auto` (`db.py:194`, `:202`), used for the workout row at
  `main.py:222`. A manual entry is never touched. `insights.kinds` (`insights.py:206`) counts auto rows like any
  other.
- Journal kinds (`db.KINDS`, `db.py:71`): several `medication` rows a day, value `HH:MM`; every other kind once per
  day. `POST /journal` (`main.py:725`), `Api.addJournal` (`Api.kt:97`).
- Effects per kind: `insights.kinds(...)[kind]` = `{status: learning, need, n_with, n_without}` or
  `{status: ok, n_with, top, all}`; an effect is `{metric, unit, delta, n_with, strong, …}` (`insights.py:54`).
  `top` holds at most two strong ones. `notify._stillup` (`notify.py:145`) already reads one this way.
- Tonight: `notify.tonight(d)` (`notify.py:95`) plans from `wake_weekday` / `wake_weekend` and returns None when
  they are unset; `/today.tonight` (`main.py:432`); `TonightCard` (`TodayScreen.kt:1182`) from 18:00;
  `_bedtime` (`notify.py:112`).
- Morning: `_readiness` (`notify.py:50`) and `_morning` (`notify.py:247`) both fire, both with numbers.
- Drive facts: `/today.drive` = `{as_of, wake, sleep_min, worn}` (`main.py:367`), today only.
- Notes to yourself: table `note`, `db.note` / `db.set_note`, `PUT /notes/{key}` (`main.py:389`), `Api.putNote`
  (`Api.kt:115`), the 280-character dialog inside `LastTimeCard` (`TodayScreen.kt:1360`).
- Medication settings: `medication_time`, `medication_window_h` ("Active window", `SettingsScreen.kt:426`),
  `medication_started`.
- Workouts: `cardio.workouts` (`cardio.py:52`), manual ones merged at `main.py:214` (replace an overlapping detected
  session; bpm / load only as measured), relabel and `none` through `workout_kind`, `POST /workouts` kinds walk /
  ride / workout.
- Today (`TodayScreen.kt:218`): greeting (`:296`), learning card (`:305`), guess gate (`:320`), hero (`:325`),
  small rings (`:377`), cardio (`:380`), tonight (`:383`), Body signals (`:398`), Last time (`:400`), vitals
  (`:403`), journal (`:435`), marks (`:458`), experiment (`:459`), What affects you (`:471`). The pill used by the
  verdict chips is at `:366`; `TimePickDialog` at `:1256`; `CheckInSheet` at `:963`.
- Phone data today: screen on / off from usage events (`SyncWorker.kt:426`), behind `phoneSensor` and usage access.
- A BroadcastReceiver that reads Prefs and writes a Sync log line: `DriveCheckReceiver` (`DriveCheck.kt:63`).

## Phase 1: moments, reply, reaction (release R38, v3.19.0)

### RN1 Backend: moments, derived journal rows, `/today.now`

Files: `scoring.py`, `main.py`, `notify.py`, `tests/test_scoring.py`, `tests/test_api.py`, `tests/test_notify.py`.

- `scoring.MOMENTS = ("tired", "flat", "wired", "good", "coffee", "alcohol", "meal")`; the first four are
  `scoring.FEELINGS`. A moment is a sample `moment_<kind>` = 1 at the tap's second; 2 cancels (as marks).
- `scoring.derived(moments: list[tuple[int, str]]) -> set[str]` (minute of the local day, kind), pure:
  `caffeine_late` for a coffee at or after 14:00 (the app's "Caffeine after 2 PM?"), `alcohol` for any alcohol,
  `late_meal` for a meal at or after 21:00 (chosen here; nothing in the app defines "late").
- `scoring.still_hours(steps_h: dict[int, float], hour: int) -> int | None`, pure: full hours since the last hour of
  today with 500 or more steps; None when today has none. 500 is a starting guess from one afternoon (desk hours
  84–351, the ride hour 926).
- `main._moments(d)`: today's made, not cancelled moments, ascending, `[{ts, kind}]`.
- `_score_day(d)`: for each of the three derived kinds, `db.add_auto` when `derived` holds it, else `db.delete_auto`.
- `/today.moments` = `_moments(d)`. `/today.now` (today only, else null) =
  `{dose, dose_end, still_h, notes}`: `dose` = latest `medication` journal value of today (`HH:MM`) or null,
  `dose_end` = dose + `medication_window_h` as `HH:MM` or null, `notes` = `{feeling: text}` for the feelings that
  have one. Sleep and time awake come from the existing `/today.drive`.
- `PUT /notes/{key}` also accepts `now_tired`, `now_flat`, `now_wired`, `now_good`.
- `notify._checkin` stays quiet when yesterday has any moment sample ("How was yesterday?" is a nag once the day
  was tapped in).
- `wired` does not derive `stress`: a moment is not a verdict on the day. `stress`, `sick`, `travel` stay day facts.
- Tests: `derived` (13:59 / 14:00 coffee, 20:59 / 21:00 meal), `still_hours`, a cancelled moment is left out, the
  auto row appears and goes away across a rescore while a manual row stays, the four note keys, `_checkin` silent.

### RN2 App: Right now and the reply card

Files: `Marks.kt`, `ui/TodayScreen.kt`, `widget/MarkWidget.kt`, `ui/Api.kt` (only if a helper is missing).

- `mark(ctx, metric: String, tsMs, cancel)`: the metric name replaces the slot; `MarkCard` and `MarkAction` pass
  `"mark_$slot"`. Nothing else about marks changes.
- `RightNow` item directly under the greeting, above the guess gate (it gives no score away):
  - "How are you right now?": Tired, Flat, Wired, Good.
  - "Just had": Coffee, Alcohol, Meal, Dose.
  - Pills as the verdict chips (`:366`). A tap marks now; the row then reads "Tired · 17:32 · Undo" for 10 s. A long
    press opens `TimePickDialog` for an earlier time today (a time still ahead is refused, as `MarkCard` does).
  - Dose: `Api.addJournal(ctx, day, "medication", HH:mm)`; a failed call shows the existing "Not saved" toast.
- `NowCard`, shown after a feeling tap until dismissed or Today is left:
  - One facts line from `/today.drive` and `/today.now`, only the parts that exist:
    "6 h 23 min of sleep · awake 12 h · dose at 08:05, active until about 16:05 · no walk for 3 h".
  - Your note for this feeling ("Last time you wrote: …"), or "What would help right now? Leave yourself a note
    for next time." Edit through the `LastTimeCard` dialog (lift it into a private `NoteDialog` if it is inline),
    saved with `Api.putNote(ctx, "now_<feeling>", text)`.
  - "Breathe" → `onBreathe`.
- Works from the cached `/today` when the server is away (the moment is stored and sent later, as a mark is).
- No signature Canvas visual: this is a row of buttons and a card of text, on purpose.
- Check: compile; Pixel_9a against the seeded local stack: each pill, undo, earlier time, future time refused, dose,
  reply card with and without a note, server stopped (tap kept, card from cache); light, dark, 360 dp with font 1.3.

### RN3 App: Day so far, numbers folded

Files: `ui/TodayScreen.kt`, `Prefs.kt` (`showNumbers`, removed 2026-11-11).

- `DaySoFar` replaces the Journal item (`:435`): today's moments, doses and workouts in time order, one line each
  ("08:05 Dose · active until about 16:05", "15:10 Coffee", "17:32 Tired", "18:10 Ride · 20 min"), the day facts
  logged today as chips under them, and "Add or fix the day" opening the existing `CheckInSheet` (unchanged: it is
  where yesterday and the day facts are logged). Empty state: "Nothing yet today."
- Hero, small rings, cardio and vitals are always shown after the guess gate. The "Scores and vitals" fold row and
  `Prefs.showNumbers` were removed 2026-11-11 (user: always visible). The guess gate, Body signals, Last time, Tonight and the rest stay where they are.
- `MarkCard` stays for a named thing; with none named it shows nothing, as today.
- Check: compile; emulator as RN2.

### EV1 Backend: because, one morning notification

Files: `main.py`, `notify.py`, `tests/test_api.py`, `tests/test_notify.py`.

- `main._because(day) -> list[dict]`: for each `insights.NIGHT_KINDS` kind in `day`'s journal (auto rows included),
  from the same `insights.kinds` call as `/journal/insights`:
  `{kind, status, need, n_with, effect}`; `effect` = the strong `bed_start` effect, else the strong `sleep_score`
  effect, else null.
- `/today.because` = `{today: _because(today), yesterday: _because(today − 1)}` (today only, else null).
- `notify.LOGGED` gains `caffeine_late: "late coffee"`. One sentence builder, shared by both rules:
  "After late coffee you fall asleep about 25 min later." / "… your sleep score is about 6 points lower." (and
  "earlier" / "higher" for the other sign).
- `_bedtime`: the body gains the first sentence with an effect from `because.today`.
- `_morning`: the body gains the first sentence with an effect from `because.yesterday` when the Sleep score is under
  70; guess-first and calm bodies get none (it would give the night away).
- `_readiness` leaves `evaluate`; `readiness` stays in `KINDS` (a stored `muted` value must still validate, as
  `bedtime_soon` did in BG4). One morning notification from now on.
- Tests: `_because` learning / ok / nothing strong, both notification bodies, no second morning notification.

### EV2 App: Tonight and the morning line

Files: `ui/TodayScreen.kt`, `ui/SettingsScreen.kt`.

- `TonightCard` gains one line per `because.today` entry: with an effect, its sentence plus "(6 nights)"; learning:
  "Late coffee today. Still learning what it does to your nights (2 of 3)."; ok with no effect: "Late coffee today.
  No clear effect on your nights so far."
- Before noon, `DaySoFar` opens with the `because.yesterday` entries that have an effect:
  "Yesterday: late coffee. After it you fall asleep about 25 min later."
- `NOTIFY_KINDS` (`SettingsScreen.kt:757`) loses the readiness row.
- Check: compile; emulator with seeded effects: evening lines in the three states, the morning line, nothing shown
  with nothing logged.

### R38 Release v3.19.0 + homelab deploy

R18's pattern: ring.db backup, backend deploy, `assembleRelease`, cert `3c236b22`, push, GitHub release.

## Phase 2: the phone as a sensor (release R39, v3.20.0)

### AQ1 Probe: does the ring fill 15-minute movement slots?

Files: `SyncWorker.kt` on a branch, not merged (as `probe-raw`).

`parseActivityPacket` reads byte 4 as a quarter-of-day index and divides it by 4 (`ColmiProtocol.kt:248`), so four
records would land on one timestamp and the larger value would win. One Sync log line with the distinct byte-4
values of a day's read answers both questions: is there 15-minute movement data, and are steps under-counted today.
Read-only. Record the answer in Facts; if slots are filled, the fix is its own ledger item.

### PH1 Backend: phone moves as workouts

Files: `scoring.py`, `main.py`, `tests/test_scoring.py`, `tests/test_api.py`.

- A move is a sample `phone_walk` / `phone_ride` / `phone_run` at its start second, value = minutes.
- `scoring.moves(segs: list[tuple[int, int, str]]) -> list[tuple[int, int, str]]`, pure: same-kind segments at most
  5 min apart become one (traffic lights); a ride or run under 5 min and a walk under 10 min are dropped.
- `_score_day`: after the manual loop (`main.py:214`), each move is listed with `phone: true, confirmed: true`
  (run → kind `workout`), replaces a detected session it overlaps and yields to a manual one. `avg_bpm`, `max_bpm`
  and `load` are filled only when the window's mean bpm reaches the workout floor (40 % of reserve); otherwise
  None / 0: no load invented from a sensor that reads 60 on handlebars. Relabel and `none` go through `workout_kind`
  as for detected sessions.
- Tests: merge and minimums, overlap both ways, believable and not believable heart rate, `none` across a rescore.

### PH2 App: notice rides and walks

Files: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `AndroidManifest.xml`, `Moves.kt` (new), `Prefs.kt`,
`Marks.kt`, `SyncWorker.kt` (one line), `ui/SettingsScreen.kt`, `ui/ActivityScreen.kt`.

- Dependency `com.google.android.gms:play-services-location` 21.3.0, or the newest 21.x that resolves with
  AGP 8.13.2. Permission `android.permission.ACTIVITY_RECOGNITION`.
- `Moves.kt`: `registerMoves(ctx)` asks for ENTER and EXIT of walking, running and on-bicycle through the activity
  transition API with a mutable `PendingIntent` to `MoveReceiver`; ENTER stores start and kind in Prefs, EXIT writes
  the sample (start, minutes) and a Sync log line. Check the API names against the current docs before coding; this
  paragraph is from memory.
- `Marks.kt`: the store-and-push body becomes `pushSample(ctx, metric, value, tsMs)`; `mark` calls it.
- `SyncWorker`: `registerMoves(ctx)` on each run while the switch is on (registration is lost on reboot and app
  update; registering again replaces the old one).
- Settings › Phone: "Notice rides and walks" (asks for the permission; off unregisters).
- Activity: a phone move's row says "noticed by your phone" where a manual one says "added by you".
- Check: compile; the emulator cannot ride a bike, so the only real check is a real commute, user-driven: read the
  Sync log lines against the clock.

### PH3 Next alarm sets tonight's plan

Files: `SyncWorker.kt` (one metric beside the screen events), `notify.py`, `tests/test_notify.py`.

- App: `AlarmManager.nextAlarmClock?.triggerTime` → `phone_next_alarm` = alarm time in unix seconds, stamped at the
  current hour (at most 24 rows a day; `shortcut:` comment for the larger-value-wins case inside one hour).
- Backend: `notify.tonight(d)` takes its wake time from the latest `phone_next_alarm` of the last 12 h when that
  alarm falls between 03:00 and 14:00 on d + 1; otherwise from the settings as now. With an alarm set, the plan
  works with no wake time in Settings.
- Tests: alarm in the window, alarm outside it (an afternoon timer), no alarm, a stale sample.

### R39 Release v3.20.0 + homelab deploy

## Later, each behind its gate

- **SL1 What comes before a tired tap** (gate: 3 days with a tired tap; 5 with a dose as well). `insights.before`
  over tired days ("On tired days you slept 50 min less"), and the median time from dose to tired tap ("Your tired
  taps fall about 9 h 30 min after the dose"), shown on the reply card.
- **SL2 Heads-up before the slump** (gate: 6 tired days in the real ring.db). `notify._slump`: 30 min before the
  usual time, counted from the dose when one is logged, from the clock otherwise; the body is your own note. It
  takes MM4's place for feelings; MM4 stays as written for named things.

## Not doing

- Praise, a note of the day, streaks, badges, onboarding, an LLM coach.
- Advice written by us, and anything that advises on medication.
- Ride detection from ring data (heart rate fails on handlebars; steps cannot tell a ride from a walk).
- Removing Mark the moment or the check-in sheet. Revisit after a month of Right now.
- Location, Health Connect, notification counts, time zone, microphone, light sensor, barometer, call log.

Parked in the ledger: "Did it help?", calendar, phone-use patterns.

## Sources

- Sham sleep feedback changes how the day feels: https://www.ndcn.ox.ac.uk/publications/publication_modal/854408
- Orthosomnia: https://www.rushu.rush.edu/news/sleep-trackers-can-prompt-sleep-problems
- Tracker abandonment: https://arxiv.org/pdf/1904.13226
- Guilt among lapsed users: https://www.newshub.co.nz/home/health/2016/09/fitbit-free-former-users-reveal-their-guilt.html
- Broken streaks: https://www.psychologytoday.com/us/blog/ulterior-motives/202306/how-broken-streaks-sap-motivation
- Oura on its daily insight messages: https://ouraring.com/blog/inside-the-ring-the-story-behind-ouras-daily-insight-messages/
- Garmin Morning Report: https://techradar.com/opinion/garmins-morning-report-has-changed-my-training-plan-forever
- Gentler Streak: https://thenextweb.com/news/slovenian-fitness-tracker-won-apple-watch-app-of-the-year-award
