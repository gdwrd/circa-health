# Circa — agent rules

Colmi R02 ring → Android app (`app/`, package `com.nsheremet.circa`, applicationId `com.nsheremet.circa`) → backend (`/api/v1/import/prometheus`, samples stored in `circa.db`).
The Python backend (`backend/`) serves that import path, stores the samples and computes Sleep / Readiness / Activity scores.
The plans are `docs/PLAN.md` … `docs/PLAN11.md` (app and backend: `PLAN10.md`; ring firmware: `PLAN11.md`); the task ledger is `NEXTSTEPS.md`.
Ring firmware work lives in `emu/` (emulator `ringemu.py`, patch builder `patch_fw.py`; see `emu/README.md`). Check: `python -m pytest -q -p no:faulthandler emu/test_ringemu.py`. A firmware patch goes on a ring only after it passes there, installed through the emulated update.

## Token Economy & Workflow
- Ponytail applies: smallest complete change, code first, minimal prose. No abstractions, options or "for later" code.
- One ledger item per session. After it lands, update `NEXTSTEPS.md` (summary line, next item promoted) so the user can `/clear`.
- Read only the files the ledger item names plus `## Facts that still hold`. Do not grep the repo to "get context".
- Never open: `app/build/**`, `.gradle/**`, `*.apk`, `*.jks`, `keystore.properties` (secrets; read by Gradle only), `infra/.env`.
  Inspect an APK with `apksigner verify --print-certs`, never by unzipping it.
- Secrets (`API_TOKEN`, keystore passwords) live in gitignored files only; never print or commit them.

## Kotlin / Android Rules
- Kotlin 2.0.21, AGP 8.13.2, minSdk 31, targetSdk 35, JVM 17. Versions go in `gradle/libs.versions.toml`, never inline.
- Check a change: `.\gradlew.bat :app:compileDebugKotlin` (PowerShell; `./gradlew.bat` from Git Bash). The compile is the main check. The only app tests are Robolectric UI tests in `app/src/test` (`.\gradlew.bat :app:testDebugUnitTest`, JVM, no device); run them when `ui/Theme.kt` or `ui/App.kt` changes.
- Release build (only when the ledger item is a release): `.\gradlew.bat assembleRelease` → `app/build/outputs/apk/release/app-release.apk`, then
  `& "$env:LOCALAPPDATA\Android\Sdk\build-tools\35.0.0\apksigner.bat" verify --print-certs app\build\outputs\apk\release\app-release.apk`.
  Cert SHA-256 must start `3c236b22` (same keystore as v1.0.0 or Android refuses the upgrade).
- Metric emission pattern: `metrics += Metric(name, labels, value, timestampMs)` in `SyncWorker.buildMetrics`; the `last*Ms` watermark in `Prefs` decides what is new (sleep has none: every session the ring holds is re-sent each sync). Follow it.
- Phase 2 UI is Jetpack Compose + Material3, restyled to an Apple Health look via `ui/Theme.kt` tokens only (no inline colors); keep `ColmiProtocol`, `ColmiConnection`, `SyncWorker`, `MetricsPusher`, `Prefs` unchanged except what the ledger item names.

## Python Rules (backend/)
- Python 3.12 in Docker, 3.13 locally. FastAPI, sqlite3 stdlib, httpx. No ORM, no pydantic models beyond what FastAPI needs, no extra packages without a ledger item saying so.
- Type hints on every function signature. `scoring.py` is pure functions (no I/O) so it is unit-testable.
- Check: `python -m compileall -q backend/app` then `python -m pytest backend/tests -q` (install once: `pip install -r backend/requirements.txt`).
- Test only what changed: `python -m pytest backend/tests/test_<area>.py -q`. The full backend suite is small; run it. Never run Android instrumented tests (none exist).
- Scores are approximations against personal 14-day baselines. Never label them "Oura scores" in code, UI or docs.

## Infra
- Compose file: `infra/docker-compose.yml`; `.env` next to it (gitignored), `infra/.env.example` is the template.
- Check: `docker compose -f infra/docker-compose.yml config -q` (export `API_TOKEN=x` first). Local stack: `docker compose -f infra/docker-compose.yml up -d --build`; backend `:8429` only. Backup = copy `circa.db` (`docker cp circa-backend:/data/circa.db .`).
- Homelab deploy: `scp` changed dirs to `d0p3@192.168.0.104:~/ring-health/`, then `ssh d0p3@192.168.0.104 "cd ~/ring-health/infra && docker compose up -d --build"`. Never `docker compose down -v` (deletes 10 y of ring data).
- Never regenerate committed data or re-import history as a check.

# Finishing work
Do not stop while you still owe work the user asked for. Be persistent. Status notes are fine. If the next step does not need an answer, keep going. For errors, timeouts or failing tools, diagnose first, then retry or use another tool. For a hard blocker (do-not-touch file, safety guardrail), leave it, say what you found, and find another safe way. Stop only when you need user input or the blocker is deliberate. This does not skip confirmation on risky or destructive actions.
