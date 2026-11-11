# Circa v9: guess first, drive check, last time, hold my place, mark the moment

Status: plan (user direction, 2026-11-10), in the ledger since 2026-11-10 as "Active Task: v9": each `###` item below
is one ledger item / one session. `docs/PLAN.md` … `PLAN8.md` stay the references for what already exists. Two more ideas from the same
research ("I'm OK", "Who woke whom") are parked in `docs/IDEAS.md`. Line numbers are as of `191e943`.

## Context and decisions

- Goal (user, 2026-11-10): features no wearable app ships, odd if need be, useful every week.
- Research (2026-11-10: reviews, forums, studies; links under Sources) and the feature each finding leads to:
  - Being told you slept badly makes the day worse whatever the real night was, and advice pieces tell people to rate
    how they feel before opening the app. No app found does that for them. → **Guess first**
  - Under 4 h of sleep in 24 h is about 11 times the crash risk, 4 to 5 h about 4 times; 17 to 19 h awake tests like
    0.05% blood alcohol. Only fleet products for truckers use sleep data this way. → **Too tired to drive**
  - Alcohol depresses recovery for up to 4 or 5 days, people ask how long it takes them, and no app shows recovery
    time per event. → **Last time**
  - People fall asleep to podcasts and lose their place. One iOS app pauses audiobooks with an Apple Watch; nothing
    tells you the next morning, for any player. → **Hold my place**
  - Predicting a migraine or a flare from wearable data is at the research stage. → **Mark the moment**
- Positioning is PLAN8's: your data, your server, honest about uncertainty, causal answers instead of scores.
- Standing rules hold: tokens-only UI from `AppTheme.colors` / `AppType`, `Theme.kt` components, no new packages, one
  sentence per number, nothing shown before it has data, never delete ring data, scores are never called "Oura
  scores", copy never diagnoses. None of these features changes Sleep / Readiness / Activity; old days are not
  rescored for new fields.
- `SyncWorker`, `MetricsPusher`, `ColmiProtocol` and `ColmiConnection` are not edited by any item. `Prefs` gains only
  the properties an item names.

### Switches (2026-11-10: asked for by the user, this split proposed and not changed)

A switch only where a feature changes what the user already sees every day or needs a sensitive permission. No
general "Labs" screen: every switch is a second state to check by hand, and the app has no tests.

| Feature | New switch? | How it is turned on or off |
|---|---|---|
| Guess first | Yes, off by default | Backend setting `guess`, a switch beside Calm mode (`SettingsScreen.kt:331`) |
| Too tired to drive | No | Picking the car is the switch; "None" is off |
| Last time | No | Shows only after a logged event; its notification is the `receipt` row in the existing list |
| Hold my place | Yes, off by default | `Prefs.holdPlace` plus Android's notification access, as "Phone as sleep sensor" does with usage access |
| Mark the moment | No | Nothing exists until a thing is named; MM4's notification is the `mark` row in the list |

## What exists (do not rebuild)

- Backend settings: defaults in the `INSERT OR IGNORE` of `db.SCHEMA` (`db.py:26`, runs on every start, so a new key
  appears on an existing circa.db), `_settings_out` (`main.py:750`), validators in `SETTINGS` (`main.py:778`).
  `calm` is the model for `guess`: `/today.calm`, `notify.py:59` and `:254`, the widget (`RingWidget.kt:87`).
- Per day, `daily.contributors` holds `inputs`, `session` (`start`, `end`, `total_min` = minutes asleep, `awake_min`…),
  `sleep_extra.naps` (`start`, `end`, `total_min`), `signals` (`index`, `level`: calm under 25, elevated to 50, high
  above; `flags`, `deltas`, `usual`, `streak`) and `workouts`. Read through `db.days(start, end)`.
- `insights.py` (pure): `nights(rows)` keyed by wake date, `compare(w, wo, metrics)` with `learning` under 3 a side
  and `strong` / `top`, `lead_time` (runs of days as one episode), `NIGHT`, `UNITS`, `FLOORS`, `MIN_N = 3`, 90 days.
- `scoring.EXPLAIN` (`scoring.py:233`): the journal kinds that commonly raise the strain inputs. `notify.LOGGED` and
  `TodayScreen.kt` `LOGGED` (`:1047`) hold their words; `flagLine` (`:1054`) phrases one flagged input.
- Notifications: rule functions in `notify.py`, `_add`, `_fired`, `KINDS` (`:342`), `evaluate` (`:346`); the app pulls
  them after a sync (`postPending`). Muting is per kind (`NOTIFY_KINDS`, `SettingsScreen.kt:645`). Band words:
  `notify._word` and the app's `calmWord` (`TodayScreen.kt:510`), both 85 / 70.
- Samples: Prometheus lines into `samples(metric, ts ms, value)`, larger value wins on the phone (`SampleStore.add`)
  and on the server (`samples.put`); `samples.raw(metric, start, end)` returns `{unix_s: value}`. Any metric name is
  stored. `SettingsScreen.kt:896` (`pushLiveHr`) is the precedent for one direct `MetricsPusher.push` outside a sync.
- After every successful sync `SyncWorker.kt:271` stores the `/today` body in `Prefs.todayJson` / `todayJsonMs` and
  redraws the widget. Local notifications use channel `health`; ids 1 to 4 are taken, server ones are `1000 + id`.
- Today (`TodayScreen.kt:180`): list items in order: greeting, learning card, hero (`:257`), small rings (`:284`),
  cardio, tonight, `BodySignals` (`:305`), vitals, journal (`:338`), experiment, `WhatAffectsYou` (`:1205`). Insight
  labels: `METRIC` (`:1147`), `BETTER` (`:1155`), `metricValue`, `metricDelta`, `sentence` (`:1192`), `TEST_METRICS`
  (`:1287`). Reusable: `SuggestChip` (`:698`, dashed chip that fills), `LeadStrip` (`:1396`), `TimePickDialog` (`:1017`).
- Settings: "Phone" section with the usage-access pattern and the "Allow restricted settings" hint (`:284`), "You"
  with Calm mode (`:298`), "Notifications" with one local switch, "Ring battery" (`:381`), the choice dialog
  (`pickInterval`, `:443`), `ChevronRow`, `FieldRow`, `SwitchRow`, `saveGoal` (800 ms after the last keystroke).
- Deep links: notification extras `open` / `date` → `MainActivity.readLink` → `App.kt:82`. `open=today` has no case
  there yet (it falls to `else`, so a warm app stays on its tab).
- Widget: Glance 1.1.1 (`RingWidget`, `RingWidgetReceiver`, `res/xml/ring_widget_info.xml`).

## Design with character (applies to every app item)

One signature visual per feature, drawn on `Canvas` from existing tokens, as in PLAN8.

| Feature | Signature visual | Colour |
|---|---|---|
| Guess first | "Your dot": you place a hollow dot on the empty readiness ring; on reveal the arc sweeps to the score and the gap is the story | `secondaryLabel` before the reveal (no good or bad colour), then `readiness` |
| Guess history | "Dumbbells": 14 mornings, hollow dot = your guess, filled dot = the score, a thin line between | `readiness`, `secondaryLabel` |
| Last time | "Walk-down": day dots from the event (a hollow ring) stepping down to calm, the mirror of `LeadStrip`; a closed one ends with a dashed tear line and the total | `signalHigh` / `signalElevated` / `signalCalm`, `secondaryLabel` |
| Hold my place | "The tape": the episode as a bar, filled to a bookmark notch, the stretch that played after you fell asleep hatched | `tint`, `secondaryLabel`, `emptySegment` |
| Mark the moment | "Clock line": a 24-hour line with one dot per mark, stacking where they cluster; the Mark chip is `SuggestChip` | `tint`, `secondaryLabel` |

Too tired to drive has no card on purpose: its surface is one notification and two Settings rows.

Rules as PLAN8: colours only from `AppTheme.colors` (no new token), type only from `AppType`; one spring or one 600 ms
ease per card, a plain switch under `LocalReduceMotion`; `EmptyState` or a "N so far" line for learning states;
`tick()` on every tap; a `contentDescription` sentence on every Canvas. Each app item is checked on Pixel_9a against the
local stack seeded with a synthetic fortnight, in light + dark, at 360 dp width, with large system font and
reduce-motion. What was not checked goes in the ledger line.

## Phase GF — Guess first (v3.18.0)

### GF1 Backend guess, verdict, `felt` (`db.py`, `scoring.py`, `insights.py`, `main.py`, `notify.py`; tests `test_scoring.py`, `test_insights.py`, `test_api.py`, `test_notify.py`)

- Setting `guess` (`''` or `'1'`): defaults, `_settings_out`, `SETTINGS`.
- Table `guess(date TEXT PRIMARY KEY, value INTEGER, verdict INTEGER, ts INTEGER)`. `value` NULL = "Just show me"
  (skipped). `verdict` is a band, 0 / 1 / 2, NULL until answered. `db.add_guess` is `INSERT OR IGNORE`: the first
  answer of a day stands, there is no second guess after the reveal. `db.set_verdict` updates the row.
- `scoring.band(score: float) -> int` (pure): 2 at 85 or more, 1 at 70 to 84, else 0. `notify._word` uses it.
- `scoring.guess_stats(rows: list[tuple[int, int, int | None]]) -> dict` (pure). `rows` = (guess, readiness, verdict)
  for mornings with both numbers, last 30 days. Under 5 rows: `{status: "learning", n, need}` (5 is my pick, not
  measured). Else `{status: "ok", n, off: mean |guess − readiness|, bias: mean(guess − readiness), same: mornings in
  the same band, split, you, ring}`. `split` counts mornings where the two bands differ and a verdict exists; `you` /
  `ring` count those where the verdict equals the guess's / the score's band.
- `POST /guess {date, value}`: `date` must be today, `value` 0..100 or null; else 422. Returns the `/today` body.
  `POST /guess/verdict {date, verdict}`: today only, `verdict` 0..2, needs a row with a value; else 422. Returns `/today`.
- `/today` adds `guess: {on, pending, value, verdict, stats, days}`. `pending` = setting on, `date` is today, a
  readiness score exists and no row for today. `days` = the last 14 `[guess, readiness]` pairs, oldest first.
- `felt`: `db.days()` attaches `felt` (that date's guess value or None) to each row; `insights.nights()` copies it;
  `NIGHT` gains `"felt"`, `UNITS["felt"] = "pts"`, `FLOORS["felt"] = 5.0`. Every `compare` over `NIGHT` then reports
  it, and `POST /experiments` accepts it as a metric with no further change.
- `notify._readiness` and `_morning` with `guess` on (checked before the calm branch): no score and no band word.
  Readiness: title "Your scores are in", body "Guess first, then see them.", extra `{"open": "today"}`. Morning:
  title "Last night", body the sleep duration, extra `{"open": "today"}`. `_strain` is unchanged: a warning is never
  held back.
- Tests: `band` edges; `guess_stats` learning, `off` / `bias`, `split` / `you` / `ring`; first answer stands; skip
  stores NULL; wrong date 422; `pending` flips after the POST; verdict without a guess 422; settings round trip; both
  notifications carry no digit of the score and no band word; `felt` appears as an effect with 3 mornings a side.

### GF2 App Guess first (`ui/TodayScreen.kt`, `ui/Api.kt`, `ui/SettingsScreen.kt`, `ui/Theme.kt`, `ui/App.kt`, `widget/RingWidget.kt`)

- `Api.guess(ctx, date, value: Int?)`, `Api.verdict(ctx, date, v: Int)`; both return the new `/today` object. `date`
  is the `date` field of the `/today` body (the server's day), never the phone's clock.
- Settings › You: `SwitchRow("Guess first", …)` under Calm mode, same `key(patchFailed)` + `patchMed("guess", …)`.
  Footer gains: "Guess first: say how recovered you feel before the scores show."
- Gate: in `TodayScreen`, right after the learning card: when `guess.pending` is true, emit one item, `GuessCard`,
  and `return@LargeTitleScaffold`. Nothing else is composed, so no ring, mood tint, coach line, cardio target, signal
  or tonight card can give the answer away. The check-in sheet lives outside the list and still opens.
- `GuessCard`: headline "How recovered do you feel?", the 220 dp `GuessDial`, `TintButton("Lock it in")` (enabled
  once a dot is placed), a text button "Just show me" (posts `value = null`).
  - `GuessDial`: the readiness ring's faint track; tap or drag on the ring places the dot, 0..100 clockwise from 12
    o'clock as `ScoreRing` sweeps. No dot and "?" in the centre until the first touch (a preset position would
    anchor the guess). Centre: the number in `AppType.display` with the band word under it; calm mode shows the word
    only. Arc and dot in `secondaryLabel`. `tick()` on each 5-point step. Semantics: a progress range with
    `setProgress`, so TalkBack can adjust it.
  - A failed POST keeps the card and shows a Toast "Not saved. Try again."
  - After a successful POST: `data` = the reply, `ctx.todayJson` / `todayJsonMs` = the reply and
    `RingWidget().updateAll(ctx)`, so the widget stops saying "Guess first" at once.
- Reveal: the normal list composes. `ScoreRing` gains `mark: Float? = null` (a hollow dot on the track at that
  fraction; the only `Theme.kt` change). The hero passes `guess.value / 100f`, and the ring's own spring is the reveal.
  Under the coach line: "You said 74." Calm mode: "You said Steady." and no dot (`CalmArc` is not a gauge).
- `ScoreDetails("readiness")`, when `guess.on`: block "Your guesses". Learning: "Guess on 5 mornings to see how close
  you get. N so far." Ok: "Usually within N points of the ring, over M mornings."; when |bias| is 3 or more: "You tend
  to feel better (worse) than the ring says, by about N points."; when `split` is 3 or more: "When you and the ring
  disagreed, your read fit the day Y of S times, the ring's R." Then the dumbbells from `days`.
- Evening verdict: an item right after the hero when a guess exists, `verdict` is null and the hour is 18 or later:
  "How did today actually go?" with three chips, "Drained" / "OK" / "Strong" (0 / 1 / 2) → `Api.verdict`, `tick()`.
- `METRIC` gains `"felt" to ("Felt" to "how recovered you felt")`, `BETTER` `"felt" to 1`; `metricValue` and
  `metricDelta` treat it as `sleep_score` does; `sentence()` gets a branch: "The morning after … you felt N vs M out
  of 100."; `TestDialog` offers "felt" only when `guess.on`.
- `App.kt`: add the case `"today" -> { go(TabItem.Today); onLinkHandled() }`.
- Widget: `pending` → the rings draw the open mark as in calm mode, the score texts read "Guess first" and the Body
  signals text is left out.
- Check: turn it on; gate; tap and drag; TalkBack adjust; lock in; reveal with the dot; skip; verdict chips (emulator
  clock after 18:00); widget before and after; calm mode on; the standard light / dark / 360 dp / large font /
  reduce-motion pass.

### R32 Release v3.18.0 "Guess first" + homelab deploy

## Phase TD — Too tired to drive (v3.19.0)

### TD1 Backend drive facts (`scoring.py`, `main.py`; tests `test_scoring.py`, `test_api.py`)

- `scoring.drive(now: float, sessions: list[tuple[float, float, float]], worn: set[int]) -> dict | None` (pure).
  `sessions` = (start, end, minutes asleep) that ended at or before `now`; `worn` = epoch hours (`ts // 3600`) holding
  an HR sample above 0. No session → None (no data is not no sleep). `wake` = the latest end. `sleep_min` = each
  session's minutes times the share of it inside `[now − 24 h, now]`, summed. `worn` in the result = every full hour
  strictly between the wake hour and the current hour is in the set. Returns `{as_of: now, wake, sleep_min, worn}`.
- `main._drive()`: sessions from the stored rows of yesterday and today (`contributors.session` and
  `sleep_extra.naps`, so it matches what the app shows and re-runs no session logic), one
  `samples.raw("biometric_hr_bpm", wake, now)` for the hours. `/today` adds `drive` (None for any date but today).
- Why `worn`: a night without the ring, or a ring that died at 03:00, looks like no sleep. Both rules below stay
  silent unless the ring was on for every hour since the last wake.
- Tests: no session → None; a session half outside the window counts half; a nap is added; one missing hour →
  `worn` false; `/today.drive` present today and null for a past date.

### TD2 App drive check (`DriveCheck.kt` new, `AndroidManifest.xml`, `Prefs.kt`, `ui/SettingsScreen.kt`)

- `Prefs`: `carAddress` ("" = off), `carName`, `driveWarnedWake` (the `wake` already warned for).
- `DriveCheck.kt`:
  - `driveFacts(ctx, nowMs)`: reads `drive` from `ctx.todayJson`. Null when `drive` is null, when its `as_of` is
    older than 90 min (my pick: a sync runs every 5 min, so older means the ring is not syncing) or when `worn` is
    false. Else hours awake = `now − wake` and `sleep_min` as stored.
    `shortcut: sleep_min is as of the snapshot, up to 90 min old; recompute the window on the phone if that ever matters`.
  - `driveWarning(facts)`: thresholds as constants with their source in one comment.
    - 18 h awake or more, woke within the last 24 h → "Awake for about N hours" / "Since you woke at 06:30. In lab
      tests, people awake for 17 to 19 hours reacted like people at 0.05% blood alcohol."
    - 18 h or more and the last recorded sleep ended over 24 h ago → "No sleep recorded for N hours" / "If that is
      right: in lab tests, people awake for 17 to 19 hours reacted like people at 0.05% blood alcohol." (a night
      the ring failed to record looks the same, so the copy names its basis; the sleep edit on the Sleep tab fixes it)
    - else under 240 min → "Short on sleep for driving" / "The ring measured X h Y min of sleep in the last 24 hours.
      In US crash data, drivers who slept under 4 hours had about 11 times the crash risk of those who slept 7 or more."
    - else under 300 min → same title / "… drivers who slept 4 to 5 hours had about 4 times the crash risk …"
    - else nothing. 5 to 6 hours (1.9 times) is left out on purpose: a warning most mornings is a warning ignored.
  - `DriveCheckReceiver`: on `BluetoothDevice.ACTION_ACL_CONNECTED` for `carAddress` (`IntentCompat.getParcelableExtra`),
    writes one Sync log line ("Drive check: car connected, 7 h 10 min asleep, awake 2 h, no warning") and, when there
    is a warning, `wake != driveWarnedWake` and notifications are allowed, posts it on channel `health` with id 5.
    One warning per wake period. It never posts an all-clear.
- Manifest: `<receiver android:name=".DriveCheckReceiver" android:exported="false">` with the action
  `android.bluetooth.device.action.ACL_CONNECTED`. That action is on Android's implicit-broadcast exception list, so
  a manifest receiver gets it with the app not running; `BLUETOOTH_CONNECT` is already granted for the ring.
- Settings: new section "Driving" after "Phone". Footer: "When your phone connects to this Bluetooth device, Circa
  checks last night's sleep and how long you have been awake, and warns once if you are short. It never says you are
  fine to drive. It needs the ring on since you woke up." Rows: `ChevronRow("Car", name or "None")` → the choice dialog
  listing "None" and the bonded devices; and a plain row "Right now" with the facts ("Last 24 h: 7 h 10 min asleep ·
  awake 2 h", or "No fresh ring data"), plus the warning title under it when one would show.
- Check: compile; emulator against the seeded stack: a 4 h 30 min night → "Right now" shows it with the warning
  title; 7 h → numbers only; an old snapshot → "No fresh ring data". The emulator has no Bluetooth, so the receiver is
  a real-phone check: pick any paired device (headphones) as the car, connect it, read the Sync log line.

### R33 Release v3.19.0 "Drive check" + homelab deploy

## Phase LT — Last time (v3.20.0)

### LT1 Backend episodes, prices, notes (`db.py`, `insights.py`, `main.py`, `notify.py`; tests `test_insights.py`, `test_api.py`, `test_notify.py`)

- `insights.episodes(logged: dict[date, set[str]], sig: dict[date, dict], today: date) -> list[dict]` (pure). `logged`
  = the `scoring.EXPLAIN` kinds in the journal per date; `sig` = `contributors.signals` per wake date. An episode is a
  run of consecutive logged days (as `lead_time` treats sick days); `kinds` is the union, `key` the sorted kinds
  joined with `+`. After its last day, walk the wake dates `last + 1, last + 2, …` and count nights with index 25 or
  more:
  - first night has no index → the episode is left out (unknown, not zero);
  - index under 25 → `closed` (0 nights when the first night was already calm);
  - a kind logged on the evening before a counted night after the first, or a later night without an index → `cut`;
  - 7 nights → `capped`; a date after today → `open`.
  Each: `{key, kinds, first, last, nights, state, deltas, usual}`; `deltas` / `usual` are the first night's flagged
  inputs, the same numbers Body signals showed that morning.
- `insights.prices(eps) -> dict[str, dict]` (pure): per kind, the median `nights` of its single-kind `closed` or
  `capped` episodes; `{status: "learning", n}` under `MIN_N`. Mixed and cut episodes never enter a price.
  `/journal/insights` adds it as `kinds[k]["cost"]`.
- `insights.last_time(eps, sig, today) -> dict | None` (pure), first match wins:
  - `event`: an `open` episode → `{why, key, kinds, nights, last, before, price}`; `before` = the latest earlier
    `closed` / `capped` episode with the same key (`{first, nights}`), `price` for a single kind.
  - `closed`: an episode whose first calm night is today, with 1 night or more. A 0-night episode shows no card; it
    only counts in the price.
  - `signals`: no open episode, today's level elevated or high with `streak` 2 or more → `before` = the latest
    earlier run of 2 or more days at 25 or more. None when there is neither a `before` nor a note.
- Table `note(key TEXT PRIMARY KEY, text TEXT, ts INTEGER)`. `PUT /notes/{key} {text}`: `key` is `signals` or sorted
  `EXPLAIN` kinds joined with `+`, `text` up to 280 characters; empty text removes the user's own note; else 422.
- `/today` adds `last_time` (the dict above plus `note`), for today only.
- `notify._receipt`, kind `receipt` in `KINDS`: once on the day an episode closes with 1 night or more. Title "Back
  in your usual range", body "After alcohol on Saturday: 2 nights." (words from `LOGGED`; a run of days reads "After
  feeling unwell, 3 to 6 Sep: 4 nights.") plus "Your usual: 2 nights." when a price exists.
  Cheap exit first: today calm and yesterday at 25 or more. With `guess` on it waits until today has a guess row
  (the rule runs again on the next sync).
- Tests: closed after 2; 0 nights; unknown first night left out; capped; open; a run is one episode; two kinds give a
  `+` key and no price; an event during recovery cuts; prices need 3; the three `last_time` cases and None; note
  validation; the notification fires once, not for 0 nights, respects mute and waits for the guess.

### LT2 App Last time (`ui/TodayScreen.kt`, `ui/Api.kt`, `ui/SettingsScreen.kt`)

- `Api.putNote(ctx, key, text)`.
- `LastTimeCard(d["last_time"])` as an item right after `BodySignals`; nothing when it is null. The walk-down strip
  on top, then by `why`:
  - `event`: title "After alcohol" (words from `LOGGED`, joined with `join`); "Night 2: still above your usual.";
    the first-night lines through `flagLine`; "Last time (12 Oct): back to usual after 2 nights."; "Usually 2 nights
    for you."
  - `closed`: "Back in your usual range."; "After alcohol on Saturday: 2 nights."; the tear line and the total.
  - `signals`: "This has happened before."; "Last time your signals ran high (3 to 6 Sep), it lasted 4 nights."
  - The note, when there is one: the text and "You wrote this last time." A text button "Leave a note for next time"
    / "Edit note" opens an `AlertDialog` with a 280-character field.
- `WhatAffectsYou`: a kind with an `ok` `cost` gets one line under its pills, "Usually costs you 2 nights · 4 times"
  (0 reads "Usually no extra nights").
- `NOTIFY_KINDS` gains `"receipt" to "Back to normal"`.
- Check: seed a baseline fortnight, an `alcohol` entry, two raised nights (higher HR, lower HRV) and a calm one:
  `event` on nights 1 and 2, `closed` on the calm day; write and edit a note, see it on a second seeded episode; three
  episodes for the cost line; the standard pass.

### R34 Release v3.20.0 "Last time" + homelab deploy

## Phase HP — Hold my place (v3.21.0, phone only: no backend change, nothing leaves the phone)

### HP1 App playback log (`PlaybackListener.kt` new, `AndroidManifest.xml`, `Prefs.kt`, `ui/SettingsScreen.kt`)

- Gate first, on the real phone: turn the switch on and grant notification access to the installed (sideloaded)
  build. If Android greys it out, App info › ⋮ › "Allow restricted settings" must unlock it. If it cannot be granted
  at all, stop and record that in Facts; the feature has no other way to see what is playing.
- `PlaybackListener : NotificationListenerService`. `MediaSessionManager.getActiveSessions(component)` needs an
  enabled listener; the class overrides no notification callback, so it never reads a notification. In
  `onListenerConnected`: `addOnActiveSessionsChangedListener`, and a `MediaController.Callback` on each controller
  (removed again when the sessions change). Nothing is written while `Prefs.holdPlace` is off.
- On a playback-state or metadata change for an item of 10 min or longer (`METADATA_KEY_DURATION`; my pick, it skips
  music tracks): append one line to `filesDir/playback.log`: `ts_ms, package, title, duration_ms, playing 0/1,
  position_ms, speed`, the position brought to now (`position + (elapsedRealtime − lastPositionUpdateTime) × speed`).
  Write when playing or the title changes, else at most once a minute per package. Over 256 KB the older half is
  dropped; lines older than 36 h are ignored. The active controllers stay in a `@Volatile` list for HP2.
- Manifest: the `<service>` with `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` and its intent filter.
- `Prefs.holdPlace` (default false), `Context.notificationAccess`
  (`NotificationManagerCompat.getEnabledListenerPackages`).
- Settings › Phone: `SwitchRow("Hold my place", …)`; on without access → `Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS`
  and a `ChevronRow("Notification access", "Off")`, as the usage-access rows do. Off deletes the log. Footer gains:
  "Hold my place shows where in a podcast or audiobook you fell asleep. Android calls the permission notification
  access; Circa only uses it to see what is playing and never reads your notifications."
- Check: compile; emulator: `adb shell cmd notification allow_listener com.nsheremet.circa/com.nsheremet.circa.PlaybackListener`,
  play something long in any player, read the lines with `run-as`. If no emulator player publishes a duration, that
  part moves to the real phone.

### HP2 App the bookmark (`PlaybackListener.kt`, `ui/TodayScreen.kt`, `Prefs.kt`, `AndroidManifest.xml`)

- `bookmark(lines: List<Play>, onsetMs: Long, wakeMs: Long): Hold?` (pure): the latest line at or before the onset.
  Not playing, or its position brought to the onset is past its duration → null (it had stopped before you slept).
  Else `at` = that position minus 5 min, floored at 0, and `ranOn` = time from the onset to the first later line
  that is not playing, capped at the wake time.
  `shortcut: 5 min back because you stop following before the ring calls it sleep; tune after real nights`.
- Today, when `holdPlace` and access are on: `Api.sleep(ctx, today)` → `session.start` / `end` → `bookmark` → an item
  right after the hero unless `Prefs.holdDismissed` is today. `HoldCard`: the tape; the title (two lines at most);
  "You fell asleep about 32 min in."; footnote "It played on for 1 h 52 min."; `TintButton("Open Pocket Casts")`; a
  dismiss ✕. "Jump there" (`transportControls.seekTo`) shows only while an active controller of that package still
  holds the same title; after a night of autoplay it does not, and the card says where to scrub to.
- Manifest: `<queries>` with the MAIN / LAUNCHER intent, so the player's label and launch intent resolve.
- Check: throwaway backend with a short "night" that started a few minutes ago, a player running across its start,
  open Today: the card, the open button, dismiss; the standard pass.

### R35 Release v3.21.0 "Hold my place" (app only)

## Phase MM — Mark the moment (v3.22.0)

### MM1 Backend marks and what came before (`db.py`, `insights.py`, `main.py`; tests `test_insights.py`, `test_api.py`)

- A mark is a sample: metric `mark_1` … `mark_3`, value 1 at the tap time. Value 2 at the same timestamp cancels it:
  larger wins on the phone and the server, and nothing is deleted. No write endpoint and no new table.
- Settings `mark_1` … `mark_3` (a name up to 24 characters, `''` = unused) and `mark_N_since` (ISO date). `PATCH
  /settings` sets `since` to today when a name goes from empty to set; a rename keeps it. Only marks on or after
  `since` count, so a reused slot starts clean while its old samples stay.
- `insights.before(marked: set[date], nt: dict[date, dict], day: dict[date, dict]) -> dict` (pure). "With" = the
  first day of each run of marked days: the night that ended that morning (`nt[d]`) plus the day before's
  `stress_load` and `steps` (`day[d − 1]`). "Without" = the same for unmarked days not next to a marked one.
  `compare` over `BEFORE = ("total_min", "awake_min", "deep_min", "hrv", "rhr", "bed_start", "sleep_score", "felt",
  "stress_load", "steps")`; `UNITS` / `FLOORS` gain `stress_load` ("h", 0.5) and `steps` ("steps", 1000).
- `pattern`, when a `top` effect exists: for its metric, a day "looks like it" when its value is past the midpoint of
  the two means, on the marked side. `{metric, hits, marked, alike}`: marked days that looked like it, all marked
  days, all days that looked like it. The card shows both sides: how often it came before, how often nothing followed.
- `GET /marks` → `{things: [{slot, name, since, n, marks: last 20 timestamps, …before(), pattern}]}` over 90 days.
- Tests: a run counts once; neighbours stay out of "without"; learning under 3; value 2 cancels; `since` filters; the
  pattern counts; naming sets `since`, renaming keeps it; a pushed `mark_1` line shows up in `/marks`.

### MM2 App marks on Today (`Marks.kt` new, `ui/TodayScreen.kt`, `ui/Api.kt`, `ui/SettingsScreen.kt`, `Prefs.kt`)

- `Marks.kt`: `suspend fun mark(ctx, slot, tsMs, cancel = false)`: whole-second timestamp; `SampleStore.add` first,
  so a mark survives no network; then one `MetricsPusher.push` of that row (as `pushLiveHr`) and `markSent` on
  success. A failed push leaves it for the next sync.
- Settings: section "Things to mark" with three `FieldRow`s, saved through `patchMed` 800 ms after the last
  keystroke as `saveGoal` does; the names are cached in `Prefs.markNames` for the widget. Footer: "Name something
  that comes and goes: a migraine, an afternoon crash, heartburn. Tap it when it happens and Circa shows what your
  body did the day before. Clear the name to stop; old marks are kept."
- `Api.marks(ctx)`; Today, after Journal: one `MarkCard` per named thing. Header: the name and a `SuggestChip` "Mark
  now" → `mark()`, then "Marked 14:32 · Undo" for 10 s (Undo sends the cancel); long press → `TimePickDialog` for an
  earlier time today. The clock line. Learning: "Mark it on 3 days to see what comes before. N so far." Ok: the `top`
  effects as pills (`metricDelta`), one sentence each ("The night before, you slept 52 min less than usual."), and the
  pattern line: "Nights like that came before 4 of your 6 marks. 11 others passed without one." Footnote: "From your
  own days. Other things may play a part."
- Check: name a thing, mark, undo, mark an earlier time; seed `mark_1` lines on 3 days after short nights with curl:
  effects and the pattern line; with the backend stopped a mark is kept and arrives after the next sync; standard pass.

### MM3 App mark widget (`widget/MarkWidget.kt` new, `AndroidManifest.xml`, `res/xml/mark_widget_info.xml` new, `res/values/strings.xml`)

- A 1×1 Glance widget for the first named thing: its name; a tap runs an `ActionCallback` → `mark()` and the widget
  reads "Marked 14:32" until the next hour. Undo is in the app. Its own label in the widget picker ("Circa: Mark").
  `shortcut: slot 1 only, add a configuration screen if a second thing needs a widget`.
- Check: add it on the emulator home screen, tap, see the mark on Today.

### R36 Release v3.22.0 "Mark the moment" + homelab deploy

### MM4 Heads-up notification (later: only after one thing has 6 marked days in the real circa.db)

- `notify._mark`, kind `mark` (`KINDS`; `NOTIFY_KINDS` "Marked things"): on a morning that looks like the pattern,
  when `hits / alike` is 1 in 3 or more and `alike` is 6 or more (starting guesses, mine; set them from the real
  marks), once a day: "Nights like last night came before 4 of your 6 migraines. 11 passed without one." Waits for
  the guess when `guess` is on. Files: `notify.py`, `tests/test_notify.py`, `ui/SettingsScreen.kt`.

## Ledger order and parallelism

GF1 → GF2 → R32 → TD1 → TD2 → R33 → LT1 → LT2 → R34 → HP1 → HP2 → R35 → MM1 → MM2 → MM3 → R36; MM4 when its gate is
met. GF1 comes before LT1 and MM4 (their notifications wait for the guess) and before MM1 (`felt`). GF2, LT2, HP2 and
MM2 all edit `TodayScreen.kt` and every app item but MM3 edits `SettingsScreen.kt`, so app items are sequential.
Each backend item is deployed to the homelab before its app item is checked on the phone. Version numbers assume
v8's R30 has not shipped; if it has, each moves up one. Releases follow R18's pattern.

## Verification

- Backend: `python -m compileall -q backend/app && python -m pytest backend/tests -q`; every new pure function has tests.
- App: `.\gradlew.bat :app:compileDebugKotlin`, then the Pixel_9a checks from "Design with character".
- Real phone, user-driven (the emulator cannot do them): the Bluetooth trigger (TD2), notification access on the
  sideloaded build and a real night (HP1, HP2), the mark widget (MM3).
- Never run instrumented tests; never regenerate or re-import history; old days are not rescored for new fields.

## Risks

- **Guess first.** The evening verdict is given after you saw the score, so it leans toward the ring. The gate
  covers Today, the widget and two notifications; the Sleep tab still shows last night's score one tap away.
  `felt` effects need many guessed mornings.
- **Too tired to drive.** The ring's sleep minutes are rough, so the copy says "the ring measured". It is silent on
  days the ring came off since waking (charging included) and when no sync ran in the last 90 min. Silence is not
  an all-clear, and the copy says so in Settings. A worn night the ring did not record as sleep reads as "no sleep
  recorded"; nothing in the data tells that apart from a real all-nighter.
- **Last time.** "Back to usual" is the strain index under 25, a threshold, not a fact. Real life overlaps events, so
  many episodes are mixed or cut and prices fill slowly (3 clean episodes per kind).
- **Hold my place.** It depends on notification access for a sideloaded app (HP1's gate) and on players that publish
  a title, position and duration. The ring's sleep start can be off by 10 to 15 min; the card says "about".
- **Mark the moment.** Day-level only, nothing before 3 marked days, and odds from a handful of events are noisy,
  which is why both counts are always shown. Copy never names a cause.

## Not doing

- Guess first: hiding scores on the other tabs; a reminder or a next-morning ask for the verdict; a score formula
  trained on the guesses.
- Too tired to drive: an all-clear message; a live ring sync at car connect; Android Auto detection.
- Last time: a receipt history screen; cost in points or minutes; prices for combinations of kinds.
- Hold my place: pausing playback when you fall asleep (the ring has no live sleep signal); naps; a notification;
  sending what you listen to anywhere.
- Mark the moment: more than three things; a widget per thing; hours-before analysis within a day; a Quick Settings
  tile.

## Sources (research and platform facts, 2026-11-10)

- Sham sleep feedback in insomnia (Gavriloff 2018): https://www.ndcn.ox.ac.uk/publication/854408
- Told-you-slept-badly study digest: https://bps.org.uk/research-digest/placebo-sleep-can-boost-your-mental-performance
- "Check in with yourself before checking the app": https://thenatural.com/blogs/blog/sleep-score-myth-what-your-tracker-can-tell-you-2026-10-01-3pm
- AAA Foundation crash risk by hours slept: https://safetyandhealthmagazine.com/articles/15016-research-links-lack-of-sleep-to-increased-risk-of-vehicle-crashes
- 17 to 19 h awake vs 0.05% blood alcohol (Williamson & Feyer 2000): https://pmc.ncbi.nlm.nih.gov/articles/PMC1739867
- Alcohol and recovery over days (WHOOP): https://www.whoop.com/thelocker/the-four-day-hangover-hrv-alcohol/
- Closest prior art: DreamRead https://dreamread.app/ ; flare-up monitor claim https://apps.apple.com/app/id6758370070
- `ACTION_ACL_CONNECTED` still reaches manifest receivers:
  https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
- `getActiveSessions` needs an enabled notification listener:
  https://developer.android.com/reference/android/media/session/MediaSessionManager
- Restricted settings on sideloaded apps (the page names accessibility; notification access is HP1's gate):
  https://support.google.com/android/answer/12623953
