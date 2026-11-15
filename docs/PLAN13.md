# Circa v13: the day as a timeline

Status: plan (user, 2026-11-15). Nothing built. Items DT1 to DT7 are in the ledger; each `###` item below is one
ledger item / one session. Builds on v12 (`docs/PLAN12.md`): the Reports page, `ReportWriter.kt`, `reports.json`,
`GET /report`, the Regenerate button.

## Context and decisions

- Goal (user, 2026-11-15): the morning note is written from yesterday and last night only. During the day the user
  moves, logs coffee, a meal, a dose, a feeling. Today's report opens into a timeline of the day: the morning note,
  what happened since, and, on a press, a new note from the model about what changed and what to do now. Each new
  note knows the morning note, the earlier notes of the day and the time of day. Before sleep one more note arrives by
  itself.
- Decisions (user):
  - **The button is called "Update me".** ("Get advice" was rejected.)
  - **The evening entry is automatic and comes with a notification.**
  - **The morning note freezes** once written. It is never rewritten by a sync.
  - **Every other card is written once a day** ("the battery on the phone will simply die in one hour"). Syncs after
    the day's first write rewrite nothing. The Regenerate button still rewrites everything, by hand.
  - **History:** the notes of earlier days are kept and the page can go back day by day, the same way the other tabs
    do (`DayPicker`).
  - **The UI must look designed** (Apple Health / Fitness feel with our own character), not a list of grey boxes.
  - Carried over from v12 and v10: no chat; no scores in the text; the app's own font (no serif); the app never
    advises on medication; no praise, no streaks.
- Decisions (mine, the user agreed to the outline: "Okay, I like it"):
  - **The backend decides, the model words it.** As in v12: `report.py` works out what happened since the last note
    and picks the only advice the model may give. Gemma 4 E2B is too small to reason over a raw timeline (v12 trial:
    it hardened meanings and mislabelled a normal value).
  - **Time of day is a rule, not a prompt line.** The backend drops advice that no longer fits the hours left before
    the planned bedtime (no training close to bed, no coffee after the caffeine cut-off).
  - **The timeline fills itself.** Events already recorded (moments, workouts, phone-detected walks and rides, the
    night's end) appear without a press. The button only adds the model's note.
  - **No repeats.** The phone sends the advice lines already given today; the backend offers a line again only when
    the state behind it changed. Nothing new since the last note: the button says so and the model does not run.
  - **"Update me" syncs the ring first**, then asks the backend, then writes. About 40 s in all (sync about 20 to
    30 s, one note 6 to 12 s); the placeholder bars cover it.
  - **It lives on the Reports page.** The Today card becomes the timeline; the other cards stay below it. No new
    screen, no new tab.
  - **Today's "Right now", "Day so far" and reply cards stay as they are** in v13. Whether the timeline replaces
    them is decided after a week of use (DT6).
  - **Not in v13:** the next morning's note referring to yesterday's advice ("you kept the evening easy"). After a
    week of real timelines.

## What exists (read before any item)

- `GET /report?date=` → `{date, cards: [{kind, verdict, facts, advice, fallback}]}`; kinds `today`, `night`,
  `yesterday`, `tonight`, `week`, `next_week`, `body` (`backend/app/report.py`, pure functions, `cards()`).
- `ReportWriter.kt`: `writeReports(ctx, force)` behind one `Mutex`; `filesDir/reports.json` =
  `{"<date>/<kind>": {"hash", "text"}}`, 14 days kept (`keepDays`), saved after every card; checks `checkedNote`
  (digits and number words must be in the facts or advice, no "score", banned words, 2 sentences, 900 characters).
- When notes are written today (v12, to be changed by DT1): on every successful sync, each card whose hash (name,
  verdict, facts, advice) changed; all cards on the first sync of a new date; when the page opens with a card
  unwritten; on Regenerate.
- `ui/ReportScreen.kt`: cards, `NoteSkeleton` placeholder bars, Regenerate in the title row, graphs per card.
- Moments (`moment_<kind>`: tired, flat, wired, good, coffee, alcohol, meal, dose) and workouts are in the backend
  (`main.py` `_moments`, `/today`); phone-detected walks and rides come from `MoveReceiver` / `Prefs.phoneMoves`.
- Notifications are created by `backend/app/notify.py` and shown by the phone at a sync (`SyncWorker`
  pending-notification loop), so they arrive at the next sync after their time, not at the minute.
- Limits (ledger Facts): heart rate every 5 to 15 min and unreliable on handlebars, stress every 30 min, steps
  hourly. "Pushed hard" can only come from phone-detected moves, steps and stress samples.

## The timeline (design)

- **Where:** the Reports page's first card. Its header (icon, "Today", verdict word, wash) stays. Under it runs a
  vertical rail in the verdict's accent with one node per entry, newest at the bottom.
- **Entries:**
  - **Morning note** (first node, a sun): the time it was written and the frozen note, then its advice rows and
    graph as today.
  - **Events** (small nodes): one compact row each, time on the left, the tinted icon chip the Today screen already
    uses for that kind, a short label ("Coffee", "Walk · 24 min", "Dose"). Several events inside the same half hour
    share one row.
  - **Updates** (a larger node, a refresh-style mark): time, the note, its advice rows. No graph.
  - **Evening** (a moon, `sleepLight`): the automatic wind-down note. The separate Tonight card leaves the page
    once this entry exists.
- **The end of the rail (today only):** a softly pulsing dot for "now" and the "Update me" button as a filled pill in
  the accent. While it works: the pill reads "Updating…", a new node appears with `NoteSkeleton` bars, the note
  fades in. Nothing new: one line under the pill, "Nothing new since 13:10", no node.
- **Earlier days:** `DayPicker` under the title as on Sleep. The same page with that day's stored notes, the rail
  without the pill; Regenerate is hidden. A day with no stored notes shows the cards' plain text.
- **Character:** the rail and nodes carry the day's verdict color; events are quiet (secondary label, small chips),
  notes are the loud part. Tokens from `Theme.kt` only; reduce-motion stops the pulse and the shimmer.

## Items

### DT1 App: write once a day, keep the history
`ReportWriter.kt`: a card that has an entry for its date is not written again by a sync or by opening the page,
whatever its hash; `force` (Regenerate) still rewrites all. The day's first write waits until `/report` carries the
`night` card (the night is in); a card that first appears later (`today` after a guess, `body`) is written once then.
`ui/ReportScreen.kt`: a stored note shows whatever the current hash; `DayPicker` under the title; earlier days read
`reports.json` and `/report?date=`, never write, hide Regenerate. JVM tests for the skip rule. **Check:** compile,
`:app:testDebugUnitTest`, emulator screenshots of today and an earlier day, light and dark.

### DT2 Backend: what happened since
`backend/app/report.py` (pure) and `main.py`: `GET /timeline?date=` → the day's events in time order (moments,
workouts, the night's end; reuse what `/today` already builds); `POST /report/update` with `{since, given, moves}`
(`since` = the last note's time, `given` = advice lines already given today, `moves` = the phone's walks and rides
since then) → `{at, nothing_new, evening, facts, advice, fallback}`. Facts carry their meaning as in v12 (steps and
stressed time since, heart rate against usual, events counted, hours to the planned bedtime). Advice by rules:
effort early → go easy; coffee count or past the cut-off → no more coffee; long stressed time → a walk or the
breathing exercise; little movement by afternoon and enough hours before bed → move; inside 2 h of the planned
bedtime → wind-down lines only; never about medication; a `given` line returns only when its state changed.
`backend/tests/test_report.py`: one test a rule, the time rule, `nothing_new`, no repeat. **Check:** backend check.

### DT3 App: the update writer
`ReportWriter.kt`: `writeUpdate(ctx, evening = false)`: `/report/update`, then one note (system instruction: what
changed since <time> and what to do now; the morning message and the earlier advice are given as context and must
not be repeated; same rules and checks as v12), stored as `"<date>/update-<epoch s>": {at, text, advice, evening}`
(`keepDays` already covers the prefix); `nothing_new` writes nothing and returns it. `ui/Api.kt`: `timeline`,
`reportUpdate`. JVM tests for the prompt's parts and the stored shape. **Check:** compile, `:app:testDebugUnitTest`.

### DT4 App: the timeline and "Update me"
`ui/ReportScreen.kt` (+ `ui/Theme.kt` only if a token is truly missing): everything in "The timeline" above. The
button runs a ring sync first (the existing run-now path, waiting for its end with a time limit), then
`writeUpdate`. **Check:** compile, `:app:testDebugUnitTest`, emulator screenshots against a seeded stack with
events: morning only, events, a hand-placed update, the updating state, "Nothing new", an earlier day; light and
dark, phone and unfolded width; looked at before done.

### DT5 The evening entry
`backend/app/notify.py`: `_evening`, once a day about 2 h before the planned bedtime, kind `evening`,
`open` = `reports`; `backend/tests/test_notify.py`. `SyncWorker.kt` (pending-notification loop): for `evening`,
`writeUpdate(evening = true)` first, body = the note's first two sentences, the backend's line when nothing is
written. `ui/ReportScreen.kt`: the evening node, the Tonight card hidden once it exists. **Check:** backend check,
compile, a seeded evening opens the page on the emulator.

### DT6 Polish on the real phone (user-driven start: a week of use)
Takes over v12's AI9. `ui/ReportScreen.kt`, `ui/Theme.kt`: one pass over what the user reports and the screenshots
show (rail, node sizes, spacing, graph insets and the empty band under graphs, folded and unfolded, light and dark,
calm mode). Decide here whether Today's "Day so far" and reply cards stay. Battery over a day with the writer on,
recorded in Facts. **Check:** compile, `:app:testDebugUnitTest`, before / after screenshots.

### DT7 Release and homelab deploy (user-driven)
R39's pattern: circa.db backup, backend deploy, version bump, `assembleRelease`, cert `3c236b22`, GitHub release.
The backend goes first: the app's `/timeline` and `/report/update` calls need it.
