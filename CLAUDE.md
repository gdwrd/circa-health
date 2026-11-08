# Ring Health Tracker — agent rules

Colmi R02 ring → Android app (`app/`, package `dev.ring.health`) → VictoriaMetrics (`/api/v1/import/prometheus`) → Grafana.
v2 adds a Python backend (`backend/`) that sits on that same import path and computes Sleep / Readiness / Activity scores.
The plan is `docs/PLAN.md`; the task ledger is `NEXTSTEPS.md`.

## Token Economy & Workflow
- Ponytail applies: smallest complete change, code first, minimal prose. No abstractions, options or "for later" code.
- One ledger item per session. After it lands, update `NEXTSTEPS.md` (summary line, next item promoted) so the user can `/clear`.
- Read only the files the ledger item names plus `## Facts that still hold`. Do not grep the repo to "get context".
- Never open: `app/build/**`, `.gradle/**`, `*.apk`, `*.jks`, `keystore.properties` (secrets; read by Gradle only), `infra/.env`.
  Inspect an APK with `apksigner verify --print-certs`, never by unzipping it.
- Secrets (`API_TOKEN`, Grafana password, keystore passwords) live in gitignored files only; never print or commit them.

## Kotlin / Android Rules
- Kotlin 2.0.21, AGP 8.13.2, minSdk 31, targetSdk 35, JVM 17. Versions go in `gradle/libs.versions.toml`, never inline.
- Check a change: `.\gradlew.bat :app:compileDebugKotlin` (PowerShell; `./gradlew.bat` from Git Bash). No unit tests exist in the app and none are planned; the compile is the check.
- Release build (only when the ledger item is a release): `.\gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk`, then
  `& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk`.
  Cert SHA-256 must start `3c236b22` (same keystore as v1.0.0 or Android refuses the upgrade).
- Metric emission pattern: `metrics += Metric(name, labels, value, timestampMs)` in `SyncWorker.buildMetrics`; the `last*Ms` watermark in `Prefs` decides what is new. Follow it.
- Phase 2 UI is Jetpack Compose + Material3, restyled to an Apple Health look via `ui/Theme.kt` tokens only (no inline colors); keep `ColmiProtocol`, `ColmiConnection`, `SyncWorker`, `MetricsPusher`, `Prefs` unchanged except what the ledger item names.

## Python Rules (backend/)
- Python 3.12 in Docker, 3.13 locally. FastAPI, sqlite3 stdlib, httpx. No ORM, no pydantic models beyond what FastAPI needs, no extra packages without a ledger item saying so.
- Type hints on every function signature. `scoring.py` is pure functions (no I/O) so it is unit-testable.
- Check: `python -m compileall -q backend/app` then `python -m pytest backend/tests -q` (install once: `pip install -r backend/requirements.txt`).
- Test only what changed: `python -m pytest backend/tests/test_<area>.py -q`. The full backend suite is small; run it. Never run Android instrumented tests (none exist).
- Scores are approximations against personal 14-day baselines. Never label them "Oura scores" in code, UI or docs.

## Infra & Grafana
- Compose file: `infra/docker-compose.yml`; `.env` next to it (gitignored), `infra/.env.example` is the template.
- Check: `docker compose -f infra/docker-compose.yml config -q` (export `GRAFANA_ADMIN_PASSWORD=x API_TOKEN=x` first). Local stack: `docker compose -f infra/docker-compose.yml up -d --build`; VM `:8428`, backend `:8429`, Grafana `:3001`.
- Dashboard source of truth is `grafana/biometrics.json` (provisioned read-only). Edit the JSON; never export from the Grafana UI over it. Check: `python -c "import json;json.load(open('grafana/biometrics.json'))"` then look at the panel in a browser — there is no headless renderer.
- Homelab deploy: `scp` changed dirs to `d0p3@192.168.0.104:~/ring-health/`, then `ssh d0p3@192.168.0.104 "cd ~/ring-health/infra && docker compose up -d --build"`. Never `docker compose down -v` (deletes 10 y of ring data).
- Never regenerate committed data or re-import history as a check. VictoriaMetrics instant queries hide samples newer than 30 s.
