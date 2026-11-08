# Ring Health v2: Oura-style app + scoring backend

## Context

v1.0.0 (released, running on the homelab) is a headless pipeline: ring → Android app → VictoriaMetrics →
Grafana. The user wants a proper Oura-like experience for their Colmi R02: daily Readiness / Sleep /
Activity scores with contributors, stress and resilience, trends, notifications, all computed from local
data only. Decisions already made: single user, Python backend, Android only, the app keeps working with
no backend (push straight to VictoriaMetrics as today).

Hardware limits that bound the scope: the R02 has no temperature sensor, no respiratory rate, no PWV.
So no Symptom/Health Radar, cycle insights or Cardiovascular Age. Oura's formulas are not public; ours
are approximations scored against personal 14-day baselines and must not be labelled "Oura scores".

## Architecture

```
ring ──BLE──▶ Android app ──POST /api/v1/import/prometheus──▶ backend ──forward──▶ VictoriaMetrics
                  ▲                                             │ rescore affected days
                  └── GET /today /sleep /trends /notifications ◀─┘  SQLite (scores, baselines, tags, notif)
```

- **Backend speaks the VictoriaMetrics import path.** The app already POSTs Prometheus text to
  `$baseUrl/api/v1/import/prometheus` ([MetricsPusher.kt:50](app/src/main/java/dev/ring/health/MetricsPusher.kt:50)).
  The backend implements the same path, forwards the body verbatim to VM, and marks the touched dates for
  rescoring. "No backend" mode = the user points the app at VM directly; nothing else changes. The app
  detects backend presence by `GET $baseUrl/today` returning 200 vs 404.
- **Scores live in the backend** (SQLite, one row per day) and are also written to VM as `score_*`
  metrics so Grafana shows them too. Single source of truth, survives app reinstall.
- **Notifications without Google.** On every 5-min sync the app also `GET /notifications/pending`,
  posts them as local notifications, then `POST /notifications/{id}/ack`. ≤5 min latency is fine.
- **Auth**: one bearer token (`API_TOKEN` in `infra/.env`, typed into app Settings). TLS is Tailscale's job.
- **Repo layout**: keep `app/` where it is; add `backend/`; add a `backend` service to
  `infra/docker-compose.yml`. One `docker compose up -d` still brings everything up.

## Phase 1 — data gaps + backend + Grafana scores (implement first)

### 1a. App v1.1: emit what scoring needs (small change, same keystore)
In `SyncWorker.buildMetrics` sleep section ([SyncWorker.kt:336-345](app/src/main/java/dev/ring/health/SyncWorker.kt:336)):
- `biometric_sleep_awake_min` (stage `SLEEP_AWAKE` already parsed, never emitted)
- `biometric_sleep_end_ts` (value = `endMs/1000`, timestamp = `startMs`) so timing/efficiency are computable
- `biometric_sleep_stage{...}` state series: one sample per stage segment, value 1 light / 2 deep / 3 REM / 4 awake
  at `segment.startMs`, plus a final `0` at `session.endMs` → hypnogram in Grafana (state timeline) and in the app
- Bump `versionCode 2`, `versionName 1.1`; release `v1.1.0` with the existing keystore.

### 1b. Backend (`backend/`, Python 3.12, FastAPI, SQLite, httpx, no ORM)
Files: `backend/app/main.py` (routes), `scoring.py` (pure functions, unit-tested), `vm.py` (PromQL
query + import forward), `db.py` (sqlite3 schema + helpers), `notify.py` (rule evaluation),
`tests/test_scoring.py`, `Dockerfile`, `requirements.txt`.

Routes:
| Route | Purpose |
|---|---|
| `POST /api/v1/import/prometheus` | forward to VM; parse dates from the body; enqueue rescore (BackgroundTask) |
| `GET /today` | scores + contributors for today/last night, latest vitals, baseline status ("learning: N/14 days") |
| `GET /sleep?date=` | session(s): stages timeline, totals, contributors |
| `GET /trends?metric=&days=` | daily series for score/RHR/HRV/steps/sleep (for charts) |
| `GET /notifications/pending`, `POST /notifications/{id}/ack` | phase 3 uses them; stub returns `[]` in phase 1 |
| `GET /health` | liveness |
All except `/health` require `Authorization: Bearer <token>`.

SQLite tables: `daily(date PK, sleep_score, readiness_score, activity_score, contributors JSON, computed_at)`,
`baselines(metric, date, value)`, `tags(ts, tag, note)`, `notifications(id, kind, title, body, created_at, acked_at)`,
`settings(key, value)` (step goal 8000, sleep need 450 min, bedtime target).

Scoring (all contributors 0-100, score = mean of available contributors; a contributor needing a baseline is
`null` until ≥5 days of data in the trailing 14 — the app shows "learning"):

**Sleep** (per main session, night = session with longest duration ending on that date):
- total: 100 at ≥ sleep need (450 min), linear to 0 at 240
- efficiency: asleep/(asleep+awake); 100 at ≥95 %, 0 at ≤75 %
- restfulness: awake minutes; 100 at ≤10, 0 at ≥60
- REM: 100 at ≥90 min, linear to 0 · deep: 100 at ≥90 min, linear to 0
- timing: sleep midpoint; 100 inside 00:00–03:00 local, −33/h outside
- latency: not available from the ring → omitted

**Readiness** (for the day following the night):
- resting HR: 10th percentile of `biometric_hr_bpm` during the session vs 14-day baseline; 100 at ≤ baseline, −10 per bpm above
- HRV balance: mean `biometric_hrv_rmssd` during the session vs 14-day baseline; 100 at ratio ≥1, linear to 0 at 0.6
- sleep: last night's sleep score
- sleep balance: 14-day mean total sleep vs need
- previous-day activity: steps vs goal; 100 at 80–120 % of goal, lower when far above or below
- activity balance: 14-day mean steps vs goal
- recovery index: time from sleep start until HR first reaches within 5 % of the night's minimum; 100 if in the
  first half of the night, linear to 0 at the end
- temperature: not available → omitted

**Activity** (today, rolling): steps vs goal; inactive hours (awake hourly buckets with <50 steps; 100 at ≤5 h,
0 at ≥12 h); calories vs 7-day mean; "goal days" (days in the last 7 that hit the step goal; 100 at ≥5).
Training frequency/volume: omitted (no workouts).

**Stress / resilience**: ring stress 0-100 → zones restored ≤25, relaxed ≤50, engaged ≤75, stressed >75
(awake hours only). Daytime stress load = hours in "stressed". Resilience (needs 5 of 14 days) = level
limited/adequate/solid/strong/exceptional from a 2-axis lookup of mean daily stress load vs mean readiness.

Baselines = trailing 14-day mean of the input, stored per day in `baselines` so trends can show them.

Backend also writes `score_sleep`, `score_readiness`, `score_activity`, `stress_zone`, `baseline_*` into VM.

### 1c. Infra + Grafana
- `infra/docker-compose.yml`: add `backend` (build `../backend`, port `8429:8000`, env `VM_URL=http://victoriametrics:8428`,
  `API_TOKEN=${API_TOKEN:?}`, volume `backend-data:/data`); `.env.example` gains `API_TOKEN`.
- `grafana/biometrics.json`: new top row "Scores" (3 gauges + 30-day score trend) and a hypnogram state-timeline panel.
- README: new architecture diagram, backend section, "point the app at the backend (8429) or at VM (8428)".
- Deploy to homelab (`~/ring-health`), set token in `.env`, `docker compose up -d --build`.

## Phase 2 — new Android UI (same package `dev.ring.health`, so v1 users upgrade in place)
- Jetpack Compose + Material3 (add `org.jetbrains.kotlin.plugin.compose` 2.0.21, compose BOM, navigation-compose,
  Vico for charts, kotlinx-serialization for JSON). Keep `ColmiProtocol`, `ColmiConnection`, `SyncWorker`,
  `MetricsPusher`, `Prefs` unchanged except: `Prefs` gains `apiToken`; `MetricsPusher` sends the bearer header when set.
- Screens: **Today** (3 score rings with contributors, latest vitals, learning banner), **Sleep** (hypnogram, totals,
  timing, 7-night bars), **Heart** (HR 24 h, RHR + HRV 30-day with baseline band), **Stress** (zones today,
  resilience), **Trends** (pick metric, 30/90 days), **Settings** (scan ring, server URL, token, step goal,
  sleep need, Sync now, Measure HR — i.e. today's MainActivity content). Direct-to-VM mode shows Settings +
  a "Scores need the backend" state on the other tabs.
- Look: Apple Health / Fitness style (user, 2026-11-08) — iOS grouped backgrounds, white/#1C1C1E inset cards, large titles,
  Activity-style score rings, no elevation; all tokens in `ui/Theme.kt` over Material3 components. No Apple fonts or symbols copied.
- Release `v2.0.0`.

## Phase 3 — notifications
Backend rules in `notify.py`, evaluated after each ingest: readiness summary once per morning when a new
night was scored; bedtime reminder 30 min before median 14-day sleep start; inactivity ≥2 awake hours
without steps (hourly data → up to 1 h late, accepted); ring battery ≤15 %. App: `SyncWorker` fetches
pending → local notifications → ack. Request `POST_NOTIFICATIONS` at first launch (fixes the v1 gap too).

## Phase 4 — tags, resilience detail, trends polish
Tags UI (`POST /tags`), tag overlay on trend charts, resilience contributors screen, weekly summary.

## Verification (phase 1)
- `pytest backend/tests` for scoring functions with fixture nights (good night, short night, no baseline).
- Local: `docker compose up --build` in `infra/`, curl a synthetic night + HR series into
  `http://localhost:8429/api/v1/import/prometheus` with the token; `GET /today` returns scores; Grafana
  "Scores" row renders; VM has `score_sleep`.
- Homelab: deploy, repoint the phone app from `:8428` to `:8429`, run Sync now, confirm the series still
  arrive in VM (forward works) and `/today` shows "learning" with the real ring data.
- App v1.1: `assembleRelease`, `apksigner verify`, install over v1.0.0, check `biometric_sleep_stage` appears after a sync.

## Out of scope
Multi-user, iOS, FCM, TLS inside the stack, workouts/raw accelerometer streaming, anything needing temperature
or respiratory rate.
