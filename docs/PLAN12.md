# Circa v12: reports written on the phone

Status: plan (user direction, 2026-11-15). Only the trial (AI1 to AI3) is in the ledger. AI4 to AI10 go in after AI3's
verdict, and the plan below assumes that verdict is "the model is good enough". Each `###` item is one ledger item / one
session.

## Context and decisions

- Goal (user, 2026-11-15): a small open model running on the phone that tells you in plain words how yesterday and the
  last week went and what to do today, tonight and next week, by name. A Reports screen made of cards, each with a short
  text and a graph. A notification about 30 minutes after waking. A short body-signals (illness) report when there is
  something to say.
- Decisions (user, same day):
  - **No chat.** The model writes reports and nothing else.
  - **The smaller model first** (Gemma 4 E2B); the bigger one (E4B) only if the trial says the writing is too weak.
  - Every card has a graph, drawn from numbers the backend or the app already has.
- Decisions (mine, change them if you disagree):
  - **The model writes, the backend decides.** Small models get numbers wrong and invent health advice. `report.py`
    computes each card's facts and its verdict (push / steady / easy / rest, usual bedtime / earlier, next week's one
    focus) from the same rows the scores and notifications use. The model only turns them into sentences.
  - **Graphs never come from the model.** Each card's series is in the `/report` reply and drawn with Vico, as the
    other screens do.
  - **Every card works without the model.** `/report` carries a template text per card (`fallback`). No model file,
    a failed run or a failed number check shows the fallback, so the screen is never empty and the app still works
    with the feature off.
  - **Reports is a pushed screen, not a sixth tab.** A card at the top of Today opens it, and so does the morning
    notification.
  - **English only.** The name is a phone setting and never leaves the phone.
  - **Body signals keeps today's rules:** the copy says "may", never names an illness, and the line "see a doctor if
    you feel unwell" is fixed text added by the app, not written by the model.
- Not doing: chat, questions to the model, the model reading raw samples, a cloud model, a model on the homelab,
  fine-tuning, languages other than English, voice.

## What we know (research 2026-11-15, nothing run on the phone yet)

| Thing | Value | Source |
|---|---|---|
| Library | LiteRT-LM, Kotlin API stable, `com.google.ai.edge.litertlm:litertlm-android` | Google's LiteRT-LM Android guide |
| Use | `Engine(EngineConfig(modelPath, backend))`, `initialize()` (up to about 10 s, off the main thread), `createConversation().sendMessage(text)` or `sendMessageAsync` as a Flow | same |
| GPU backend | needs `<uses-native-library>` for `libvndksupport.so` and `libOpenCL.so`, both `required="false"` | same |
| Gemma 4 E2B | 2583 MB; Samsung S26 Ultra: 47 tokens/s on CPU, 52 on GPU; first token 1.8 s CPU / 0.3 s GPU; peak memory 1733 MB CPU / 676 MB GPU | LiteRT-LM overview table |
| Gemma 4 E4B | 3654 MB; same phone: 18 tokens/s CPU, 22 GPU | same |
| Model files | Hugging Face `litert-community` (`gemma-4-E2B-it-litert-lm`) | same |
| Gemini Nano (built into the Pixel) | ruled out: runs only while the app is the top foreground app (`BACKGROUND_USE_BLOCKED`), per-app quota, beta, not open | ML Kit GenAI docs |

Not known, AI2 and AI3 answer them: the Maven version to pin, the library's minimum Android version (ours is 31), the
speed and memory on the Pixel 9 Pro Fold's Tensor G4, whether a 2.6 GB model survives inside a background worker with the
screen off, Gemma 4's licence terms, whether the download needs a Hugging Face login.

## How it fits together

```
ring -> SyncWorker -> backend scores the day
                      backend GET /api/v1/report  ->  cards: kind, verdict, facts, fallback, chart
SyncWorker (after the notifications fetch)
   new card facts + model present  ->  ReportWriter writes each card  ->  reports.json on the phone
   morning notification            ->  body = today's written text, tap opens Reports
ReportScreen: card = written text (or fallback) + chart from the reply
```

### `/report` reply (AI1, charts added in AI4)

```json
{"date": "2026-11-16", "cards": [
  {"kind": "today", "verdict": "easy",
   "facts": ["Readiness 58, your usual is 74", "Slept 5 h 40 min, 1 h 20 min under your usual", "..."],
   "fallback": "Take it easy today. You slept 5 h 40 min, ...",
   "chart": {"type": "bars", "label": "Readiness, last 7 days", "x": ["Mon", "..."], "y": [74, "..."], "mark": 6}}
]}
```

- `facts` are finished English phrases with the numbers already rounded and worded, at most 8 a card. The model never
  sees raw JSON or a number it has to compute with.
- A card is left out when it has nothing to say (`body` on a calm day, `week` with under 3 scored days).
- Calm mode: facts carry words ("lower than usual"), no scores. Guess first: `today` is left out until today's guess
  is in.

### Cards

| Kind | Size | When | Verdict (decided in `report.py`) | Facts from | Graph |
|---|---|---|---|---|---|
| `today` | big | every day with a scored night | `push` / `steady` / `easy` / `rest` | readiness and its contributors, last night, sleep debt, body signals level, `cardio.target` | readiness, last 7 days, today marked |
| `yesterday` | small | every day | none | yesterday's scores, steps, workouts, moments, `because` | yesterday's heart rate by hour with workouts marked |
| `night` | small | every day with a night | none | asleep, stages, score, times, `because` | sleep stages strip |
| `tonight` | small | every day | `usual` / `earlier` | `notify.tonight`, `debt`, `bedtime_window` | last 7 bedtimes against the window |
| `week` | small | every day, 3+ scored days | none | `_week(end)` against `previous`: means, best and worst night, steps | three scores a day, 7 days |
| `next_week` | small | Monday to Wednesday | one focus: `sleep` / `regular` / `move` / `keep` | the week's sleep against need, bedtime spread, cardio load against the base | sleep a night against the need line |
| `body` | small | body signals above calm, or an episode closed in the last 3 days | `watch` / `back` | `signals` flags, deltas, streak, `notify.explain`, lead time, `closed_today` | strain index, last 14 days |

Verdict rules reuse what exists (`notify._word` bands, `_strain`'s "above calm for 2+ nights", `notify.tonight`,
`cardio.target`); thresholds that do not exist yet are set in AI1 from the real days and written in its test.

### Prompt (one call a card)

```
You write one short note for {name} about their own health data. Second person, plain words, {N} sentences at most.
Use only the facts below. Do not add numbers, causes or medical advice. Do not name an illness.
Start with the advice: {verdict phrase}.
Facts:
- ...
```

- **Number check:** every number in the output must appear in the card's facts, else the fallback is shown. Same for
  a short banned-word list (illness names, "diagnos", "Oura").
- Length: `today` 3 sentences, the others 2. Output over 400 characters is cut at a sentence end.
- One fresh conversation a card, so one card's facts cannot leak into another.

### On the phone

- `ReportWriter` (new file): loads the engine once per run, writes the cards whose facts changed (hash of verdict +
  facts), closes the engine. Runs inside `SyncWorker` after the notifications fetch, never on the main thread.
- `reports.json` in `filesDir`: `date/kind -> {hash, text}`, last 14 days kept. The screen reads only this and the
  `/report` reply.
- Model file in `filesDir/models/`, downloaded on Wi-Fi from Settings, about 2.6 GB, with a delete button. Off by
  default. `Prefs`: `reportWriter` on / off, `reportName`.
- Morning notification: the backend's `morning` rule stays as it is. When today's `today` card is written, the app
  shows its text as the body and the tap opens Reports; otherwise the notification is unchanged.

## Items

### AI1 Backend: report facts (trial)
`backend/app/report.py` (pure functions: rows in, cards out, no I/O), `main.py` (`GET /api/v1/report?date=`),
`backend/tests/test_report.py`. All seven cards with `kind`, `verdict`, `facts`, `fallback`; no `chart` yet. Calm and
guess rules as above. **Check:** `python -m compileall -q backend/app && python -m pytest backend/tests -q`; tests
cover each verdict, a calm-mode card with no digits, and a quiet day with no `body` card.

### AI2 App: trial screen (trial, branch `ai-trial`, not merged)
`gradle/libs.versions.toml` + `app/build.gradle.kts` (LiteRT-LM, version pinned), `AndroidManifest.xml` (the two
native-library lines), `Api.kt` (`report`), `ui/SettingsScreen.kt` (one row "Report trial"), new
`ui/ReportTrialScreen.kt`. The screen loads `getExternalFilesDir(null)/model.litertlm`, fetches `/report`, runs every
card through the prompt above on CPU and on GPU, and shows per card: the text, the fallback, seconds, tokens a second,
number check pass or fail; on top: load time and the app's memory. A second button runs the same from a one-off
`WorkManager` job and writes the result to the Sync log, to see whether it survives in the background. **Check:**
`.\gradlew.bat :app:compileDebugKotlin`; the minimum Android version and the pinned version recorded in Facts.

### AI3 Trial on the phone (trial, user-driven)
Deploy AI1 to the homelab after a circa.db backup. Download `gemma-4-E2B-it-litert-lm`, `adb push` it to the app's
external files folder, install the `ai-trial` build. Five mornings: open the trial screen, read the texts next to the
fallbacks, run the background button once with the screen off. **Check:** recorded in Facts: load time, tokens a
second and memory on CPU and GPU, background run yes / no, number-check failures out of the cards written, the
licence and download terms, and the user's verdict: E2B, try E4B (repeat AI3 with it), or stop. STOP.

### AI4 Backend: graphs in the report
`report.py`, `test_report.py`: the `chart` object for each card (types `bars`, `line`, `stages`, `range`), built from
the rows already loaded. **Check:** backend check; each card kind has a chart with equal-length `x` and `y`.

### AI5 App: model download and settings
`Prefs.kt` (`reportWriter`, `reportName`), `ui/SettingsScreen.kt` (section "Reports": on / off, name, download with
progress and size, delete), `DownloadManager` on Wi-Fi only into `filesDir/models/`, size check after the download.
**Check:** compile; on the emulator the row shows the states not downloaded / downloading / ready.

### AI6 App: the writer
New `ReportWriter.kt` (prompt, number check, cut, `reports.json`), `SyncWorker.kt` (call after the notifications
fetch when the setting is on and the file is there), merged LiteRT-LM dependency from `ai-trial`. One JVM test for
the number check and the cut. **Check:** `.\gradlew.bat :app:compileDebugKotlin` and `:app:testDebugUnitTest`.

### AI7 App: Reports screen
New `ui/ReportScreen.kt`, `ui/App.kt` (route `reports`), `ui/TodayScreen.kt` (one card on top: today's text, tap
opens Reports), `ui/Theme.kt` tokens only. Big `today` card, then the small ones in the table's order, each with its
text and Vico graph; "written on this phone" footnote; fallback text when nothing is written. **Check:** compile,
`:app:testDebugUnitTest`, emulator screenshots light and dark against a seeded local stack.

### AI8 App: morning notification
`SyncWorker.kt` (pending-notification loop): for kind `morning`, write first, then body = today's written text and
the tap opens `reports`; unchanged when nothing is written. **Check:** compile; on the emulator a seeded morning
shows the written body.

### AI9 Week and next week on Monday
`notify.py` (`_weekly` tap opens `reports`), `SyncWorker.kt` (the same body swap for kind `weekly`, from the `week`
and `next_week` cards). **Check:** backend check and compile.

### AI10 Release and homelab deploy
R39's pattern: version bump, `assembleRelease`, cert `3c236b22`, deploy the backend after a circa.db backup, GitHub
release by hand. The APK grows by the library's native code, not by the model. **Check:** `apksigner verify
--print-certs`.

## Risks

- The model writes something false or alarming about health. Held by: verdicts and facts from the backend, the number
  check, the banned words, the fixed doctor line, no free questions.
- Too slow or killed in the background on the Tensor G4. AI3 measures it; the way out is writing when the app is
  opened instead of in the worker.
- 2.6 GB on the phone for a few sentences a day. The download is a choice in Settings, and the feature is judged
  against the fallback texts in AI3.
