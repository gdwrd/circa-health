# Circa v12: reports written on the phone

Status: plan, trial passed (user, 2026-11-15: "I tested everything with my name included there, and it looks good").
Items AI3b to AI10 are in the ledger; each `###` item below is one ledger item / one session. The trial's measured
numbers are in the ledger's Facts. The trial code is on branch `ai-trial` (last commit `7809960`), never merged: items
copy from it with `git show ai-trial:<path>`.

## Context and decisions

- Goal (user, 2026-11-15): a small open model running on the phone that tells you in plain words, by name, how your
  body is doing: how yesterday, the night and the last week went, and what to do today, tonight and next week, plus a
  short body-signals (illness) report when there is something to say. A Reports page made of cards, each with a
  written note and a graph. A notification about 30 minutes after waking.
- Decisions (user):
  - **No chat.** The model writes reports and nothing else.
  - **Gemma 4 E2B** (the smaller model). It passed the trial; E4B stays a download option.
  - **No scores in the text.** The app already shows them. The notes give recommendations and say how the body is
    doing and how today is likely to go.
  - **Longer notes**, not one sentence.
  - **Every card has a graph**, reused from what the app already draws where possible.
  - **The page must look designed** (2026-11-15): the Apple Health / Fitness feel of the rest of the app, with our own
    character and extra colors. Not a list of grey boxes.
- Decisions (mine, change them if you disagree):
  - **The model writes, the backend decides.** `report.py` computes each card's verdict, its observations with their
    meaning attached, and the only advice the model may give. The model connects them into a note.
  - **Graphs never come from the model**, and not from `/report` either: the Reports page fetches the same endpoints
    the Sleep, Activity, Stress and Trends screens use and draws their existing charts.
  - **Advice is shown twice on purpose:** woven into the note by the model, and as fixed rows under it straight from
    the backend, so a suggestion the model drops is still on the card.
  - **Every card works without the model.** `/report` carries a template text per card (`fallback`). No model file, a
    failed run or a failed check shows the fallback.
  - **Where it lives:** a hero card at the top of Today (the verdict word and the first lines of today's note) opens
    the full Reports page; the morning and weekly notifications open the same page. Not a sixth tab.
  - **Notes are set in the platform serif** (no bundled font), everything else stays in the app's sans. It marks
    "written for you" apart from data. This is the page's main character trait; drop it in AI6 if it reads wrong.
  - **CPU, not GPU:** the same speed on the Pixel 9 Pro Fold at a third of the memory.
  - **English only.** The name is a phone setting and never leaves the phone.
  - **Body signals keeps today's rules:** the copy says "may", never names an illness, and "see a doctor if you feel
    unwell" is fixed text added by the app, not written by the model.
- Not doing: chat, questions to the model, the model reading raw samples, a cloud model, a model on the homelab,
  fine-tuning, other languages, voice, an archive browser for old reports (the last 14 days are kept on the phone but
  only today's are shown).

## What we know (measured on the real phone, 2026-11-15)

| Thing | Value |
|---|---|
| Library | LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android` 0.19.1, minimum Android API 24 |
| Kotlin | the library needs Kotlin 2.3 (every release is built with 2.2 or newer); 2.3.21 worked on `ai-trial` with one forced change, `kotlinOptions { jvmTarget }` → `kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }` |
| App size | the release APK grows to about 108 MB (native code); the model is not in the APK |
| Use | `Engine(EngineConfig(modelPath, backend = Backend.CPU())).use { it.initialize(); it.createConversation(ConversationConfig(systemInstruction = Contents.of(...), samplerConfig = SamplerConfig(topK = 40, topP = 0.9, temperature = 0.4))).use { c -> c.sendMessage(text).toString() } }` |
| Model | Gemma 4 E2B, `https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm`, 2588147712 bytes, Apache-2.0, no login. E4B: `.../gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm`, 3659530240 bytes |
| Speed | CPU: load 0.9 s, 634 MB, 17 to 18 tokens a second, 6 to 12 s for a 3 to 5 sentence note, 43 s for five cards |
| Writing | with the second prompt: numbers copied exactly, no scores, 3 to 5 sentences. Faults seen: a "can" hardened to "is", one invented adjective ("a low 57 bpm"), one of two advice lines dropped, a stiff style ("indicating", "suggesting") |
| Download | `DownloadManager`, Wi-Fi only, to `model.download`, renamed to `model.litertlm` at the expected size: worked |

Not known: whether the 43-second run survives inside a background worker with the screen off (started once, result
never read). AI5 settles it.

## How it fits together

```
ring -> SyncWorker -> backend scores the day
        backend GET /report  ->  cards: kind, verdict, facts, advice, fallback
SyncWorker (after the notifications fetch)
   card facts changed + writer on + model present  ->  ReportWriter writes each card  ->  reports.json
   morning / weekly notification                   ->  body = the written note's first sentences, tap opens Reports
Today: hero card (verdict word + start of today's note)  ->  Reports page
Reports page: card = note (written or fallback) + advice rows + a graph from the screen that owns that data
```

### `/report` (done: AI1, AI1b)

`{"date", "cards": [{"kind", "verdict", "facts", "advice", "fallback"}]}`. No score numbers anywhere; `facts` are
observations with meanings ("HRV 43 ms, 9 % above your usual: a sign your body is well rested"), at most 12, changed
ones first; `advice` at most 3, decided by rules. A card with nothing to say is left out. Calm mode: no digits. Guess
first: `today` is left out until today's guess is in.

### Cards

| Kind | Verdict | Accent | Graph (existing component, data it already takes) |
|---|---|---|---|
| `today` (hero) | `push` / `steady` / `easy` / `rest` | by verdict: push = activity, steady = readiness, easy = breathe, rest = sleep | readiness, last 7 days, today marked: `MiniChart` (StressScreen) over `/week` days |
| `night` | none | sleep | `Hypnogram` (SleepScreen) from `/sleep` |
| `yesterday` | none | activity | `ThroughTheDay` (StressScreen) from `/stress?date=`; `HourlySteps` (ActivityScreen) from `/activity?date=` when there are no stress samples |
| `tonight` | `usual` / `earlier` | sleepLight | `Bedtime` (SleepScreen): last bedtimes against the window and tonight's plan |
| `week` | none | tint | two `MiniChart`s side by side: sleep a night and steps a day, from `/week` days |
| `next_week` | `sleep` / `regular` / `move` / `keep` | breathe | by focus: `regular` = `Regularity` (SleepScreen); otherwise `MiniChart` of sleep a night with the need as a `dashedLine` (`move`: cardio load a day from `/trends`) |
| `body` | `watch` / `back` | heart | strain index, last 14 days: `MiniChart` over `/trends` (or two `/week` replies if `/trends` has no strain metric) |

Page order: `today`, `night`, `yesterday`, `tonight`, then `week` and `next_week`; `body` moves to second place when
its verdict is `watch`.

### Prompt and checks (from the trial, `ai-trial:app/src/main/java/com/nsheremet/circa/ui/ReportTrialScreen.kt`)

- System instruction: role (a short personal note to {name} about how their body is doing), length (`today` 5 to 7
  sentences, the others 3 to 5, one paragraph), "do not list the facts one by one; connect them, say what that means,
  then the advice", a focus line per kind, and the rules: only the facts and advice given, numbers and times copied
  exactly as digits, never a score, no medical advice, no illness names, no invented advice.
- User message: `Main message:` (the verdict's lead sentence), `Facts:`, `Advice to give, in your own words:`.
- To add in AI5, from the faults seen: "keep words like 'can', 'may' and 'likely' as they are", "plain short
  sentences, no 'indicating' or 'suggesting'", "give every advice line".
- Checks, any failure shows the fallback: every run of digits in the note is in the facts or advice; no spelled-out
  number (two … twelve, teens, tens, hundred, thousand) unless that word is in the facts; no "score"; no banned word
  (illness names, "diagnos", "Oura"); at least 2 sentences; at most 900 characters, cut at a sentence end.
- One fresh conversation a card. Temperature 0.4, top-k 40, top-p 0.9.

### On the phone

- `ReportWriter` (new file): loads the engine once per run on CPU, writes the cards whose facts changed (hash of
  verdict + facts + advice + name), closes the engine. Never on the main thread.
- `reports.json` in `filesDir`: `date/kind -> {hash, text}`, last 14 days kept.
- Model in `getExternalFilesDir(null)/model.litertlm` (the trial's path, so the downloaded file is reused).
- `Prefs`: `reportWriter` on / off (off by default), `reportName`.

## The page (design brief for AI6 and AI7)

The rest of the app is Apple Health in feel: grouped background, white inset cards, large collapsing title, score
rings, hairlines, `ui/Theme.kt` tokens only. The Reports page keeps all of that and adds its own character:

- **Hero card (`today`).** The verdict as one large word ("Steady", "Push", "Easy", "Rest") in the large-title weight,
  in the verdict's accent color, over a soft top-to-bottom wash of that accent (about 12 % to 0 %) that fades into the
  card. Under it the note, then the advice rows, then the 7-day readiness chart. The same card, cut to the verdict word
  and the note's first two sentences, sits at the top of Today and expands into the page (shared-element transition;
  Compose 1.7.6 has `SharedTransitionLayout`).
- **Notes in serif.** `FontFamily.Serif` at the body size with slightly looser line height, in the label color. One
  new type token in `Theme.kt` (`AppType.note`). Everything else on the card stays in the app's sans.
- **Advice rows.** Under the note, one row an advice line: a filled round check-style icon in the card's accent, the
  text in the headline weight. From the backend's `advice`, never from the model.
- **Accent per card** from the table above: a small rounded icon and the card's title in the accent (Health's
  "Highlights" pattern), and the graph drawn in the same accent. New tokens only where a color does not exist yet
  (verdict washes); no inline colors.
- **Graphs sit inside the card**, under the note, about 120 dp high, with no axis clutter beyond what the reused
  component already draws; a hairline separates note and graph.
- **Written-on-this-phone mark:** a footnote at the bottom of the page, "Written on this phone by Gemma. Nothing left
  the device.", and nothing on each card. A card showing the fallback has no mark and no apology.
- **While writing:** the fallback is shown first; when the written note arrives it cross-fades in (spring, as the app's
  other transitions). No typewriter effect, no spinner over text.
- **Unfolded Fold and tablets:** at 600 dp and wider the small cards flow in two columns under the full-width hero.
- **Empty and off states:** writer off or no model: the page still shows every card with its fallback and graph, plus
  one quiet row at the bottom, "Turn on written reports", opening Settings.
- **Calm mode:** no numbers in notes or advice (the backend already sends none); graphs follow the rule each reused
  component already has.

## Items

### AI3b Kotlin 2.3 on main
`gradle/libs.versions.toml` (`kotlin = "2.3.21"`), `app/build.gradle.kts` (`kotlinOptions` → `kotlin { compilerOptions
{ jvmTarget.set(JvmTarget.JVM_17) } }`), `CLAUDE.md` (the Kotlin version line). The compiler alone, no library, so a
regression shows apart from the feature. It worked unchanged on `ai-trial`. **Check:** `.\gradlew.bat
:app:compileDebugKotlin`, `:app:testDebugUnitTest`.

### AI4 App: settings and model download
`gradle/libs.versions.toml` + `app/build.gradle.kts` (LiteRT-LM 0.19.1), `AndroidManifest.xml` (the two
`uses-native-library` lines), `Prefs.kt` (`reportWriter`, `reportName`), `ui/SettingsScreen.kt` (section "Reports":
on / off, name, model row). New `ui/ReportModel.kt`: the download, size check, rename, cancel and delete from the
trial screen, with Gemma 4 E2B as the default and E4B as the second choice. The row shows not downloaded / progress /
installed (which model) / delete. Turning the writer on with no model offers the download. **Check:** compile; JVM
test for the progress text (from `ai-trial`); on the emulator the section shows its states.

### AI5 App: the writer
New `ReportWriter.kt` (prompt, checks, cut, `reports.json`, from the trial plus the three prompt lines and the
number-word fix above), `ui/Api.kt` (`report`), `SyncWorker.kt` (after the notifications fetch: when the writer is on
and the model is there, write the cards whose hash changed; one Sync log line with cards written, seconds and
fallbacks used). JVM tests for the checks and the hash. **Check:** compile and `:app:testDebugUnitTest`; then on the
real phone (user-driven, the one open question of the trial): a sync with the screen off writes today's cards, seen
as the Sync log line. If the worker is killed, the fallback is writing when the page is opened (AI6 covers it).

### AI6 App: the Reports page
New `ui/ReportScreen.kt`, `ui/App.kt` (route `reports`), `ui/TodayScreen.kt` (the hero card on top), `ui/Theme.kt`
(`AppType.note`, verdict wash tokens). Everything in "The page" except the graphs: hero, serif notes, advice rows,
accents, order, two columns at 600 dp, fallback and off states, cross-fade, the footnote, the doctor line on `body`.
If a card has no written note when the page opens and the writer is on, write it then. **Check:** compile,
`:app:testDebugUnitTest` (`Theme.kt` and `App.kt` change), emulator screenshots light and dark, phone and unfolded
width, against a seeded local stack, looked at before calling it done.

### AI7 App: a graph in every card
`ui/ReportScreen.kt`, and `SleepScreen.kt`, `StressScreen.kt`, `ActivityScreen.kt` only to make `Hypnogram`,
`Bedtime`, `Regularity`, `ThroughTheDay`, `MiniChart`, `HourlySteps` `internal` and, where needed, take an accent
color and a height; their own screens must look the same afterwards. The page fetches the endpoints those screens
already use (through `Api`, which caches) and feeds each component the shape it takes today; where a shape differs,
adapt at the call, not in the component. Graphs per the Cards table. **Check:** compile, `:app:testDebugUnitTest`,
emulator screenshots of the page and of Sleep, Stress and Activity (unchanged), light and dark.

### AI8 Morning and weekly notifications
`backend/app/notify.py` (`_morning` and `_weekly`: `open` = `reports`), `backend/tests/test_notify.py`, `SyncWorker.kt`
(pending-notification loop: write first; for `morning` the body becomes the first two sentences of today's written
note, for `weekly` of the `week` note; unchanged when nothing is written), `ui/App.kt` (the `reports` link target).
**Check:** backend check; compile; on the emulator a seeded morning opens the page from the notification.

### AI9 Polish on the real phone
User-driven start: the build on the Pixel 9 Pro Fold for two mornings. Then one pass over what the user reports and
what the screenshots show: spacing, the serif size, wash strength, graph heights, folded and unfolded, light and dark,
calm mode, a day with a `body` card (seeded on the emulator). `ui/ReportScreen.kt`, `ui/Theme.kt` only. **Check:**
compile, `:app:testDebugUnitTest`, before / after screenshots.

### AI10 Release and homelab deploy
R39's pattern: version bump, `assembleRelease`, cert `3c236b22`, deploy the backend after a circa.db backup, GitHub
release by hand. The trial screen is not in it (it never left `ai-trial`). **Check:** `apksigner verify --print-certs`.

## Risks

- The model writes something false or alarming about health. Held by: verdicts, meanings and advice from the backend,
  the checks, the fixed doctor line, advice rows that do not depend on the model, no free questions.
- Killed in the background before it finishes. AI5 measures it; the way out is writing when the page is opened.
- 2.6 GB on the phone and a 108 MB app for a few paragraphs a day. The download is a choice in Settings.
- Kotlin 2.3 on `main` changes the compiler for the whole app. It is its own item, checked before anything lands on it.
