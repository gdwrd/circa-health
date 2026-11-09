# Circa — self-hosted health for the Colmi ring

*Know your normal.* (Formerly Ring Health Tracker.)

A self-hosted biometric pipeline for the **Colmi R02** smart ring.

```
┌──────────┐   BLE/GATT   ┌────────────────┐
│ Colmi R02│ ──────────▶ │  Android app   │
│   ring   │              │ (this project) │
└──────────┘              └────────────────┘
                                  |
                                  | POST /api/v1/import/prometheus
                                  | (GET /today, /sleep, /trends back)
                                  V
                          ┌────────────────┐   read/write  ┌─────────┐
                          │    backend     │ ◀───────────▶ │ ring.db │
                          │  (FastAPI)     │               │ (SQLite)│
                          └────────────────┘               └─────────┘
```

The Android app pulls heart rate, HRV, SpO₂, stress, steps, calories, distance,
sleep stages and ring battery from the ring every 5 minutes, then pushes them as
Prometheus-style metrics to a self-hosted Python [backend](#backend), which
stores every sample in one SQLite file and computes daily Sleep / Readiness /
Activity scores from it.

No vendor cloud, no telemetry, no account — your biometric data never leaves
your network. In this case I'm using a VPN on the phone so that it is constantly
able to connect to the home network which hosts the backend. The ring 
does keep data for a day or two though, so there is no need to sync constantly. 

## Why

The official Colmi app uploads everything to a third-party cloud. Open-source
projects like [Gadgetbridge](https://gadgetbridge.org/) display the data
locally but don't make it easy to feed into a long-term time-series store. This
project is a minimal Android app whose only job is:

1. wake up every 5 min,
2. connect to the ring,
3. drain whatever new samples the ring has buffered,
4. POST them to your backend,
5. disconnect.

## Hardware

- **Ring**: Colmi R02 (other R0x variants likely work — same protocol family)
- **Phone**: any Android device with API 31+ (Android 12 or newer)
- **Server**: any host that can run the backend container (a Raspberry Pi is
  enough)

## Metrics collected

| Metric                       | Native granularity | Notes                          |
| ---------------------------- | ------------------ | ------------------------------ |
| `biometric_hr_bpm`           | 5 min              | Raw heart-rate samples         |
| `biometric_hrv_rmssd`        | 30 min             | RMSSD in milliseconds          |
| `biometric_stress`           | 30 min             | Ring's stress score (0–100)    |
| `biometric_spo2_pct`         | hourly             | Blood oxygen %                 |
| `biometric_steps`            | hourly             | Steps per hour bucket          |
| `biometric_calories`         | hourly             | Raw ring units per hour; ÷ 100 = kcal |
| `biometric_distance_meters`  | hourly             | Meters per hour bucket         |
| `biometric_sleep_total_min`  | per session        | Minutes asleep                 |
| `biometric_sleep_deep_min`   | per session        | Minutes in deep sleep          |
| `biometric_sleep_rem_min`    | per session        | Minutes in REM sleep           |
| `biometric_sleep_light_min`  | per session        | Minutes in light sleep         |
| `biometric_sleep_awake_min`  | per session        | Minutes awake in the session   |
| `biometric_sleep_end_ts`     | per session        | Session end, unix seconds      |
| `biometric_sleep_stage`      | per segment        | 1 light / 2 deep / 3 REM / 4 awake, 0 at session end |
| `ring_battery_pct`           | per sync           | Ring battery 0–100             |
| `ring_charging`              | per sync           | 1 = charging, 0 = discharging  |

The `biometric_*` and `ring_*` metrics carry the label `device="colmi_r02"`.

Raw samples are kept as pushed (no aggregation at write time) in the `samples`
table of `ring.db` (`metric`, `ts` in ms, `value`); query them with
`sqlite3`, e.g. `select count(*) from samples where metric='biometric_hr_bpm'`.

## Server side

A ready-to-run stack lives in [`infra/`](infra/).

```bash
# on the server, with the repo (or just infra/ + backend/) copied over
cd infra
cp .env.example .env   # set API_TOKEN
docker compose up -d
```

The backend listens on `:8429`. Point the app at `http://<server-ip>:8429` plus
`API_TOKEN` from `infra/.env` (app v2 or newer; the backend is required).

**Backup**: copy `ring.db` out of the backend volume, e.g.
`docker cp ring-backend:/data/ring.db .`.

Moving off an old VictoriaMetrics stack? `backend/migrate_vm.py` copies its
history into `ring.db` (`python migrate_vm.py` in the backend container, with
`VM_URL` pointing at the old instance).

## Backend

[`backend/`](backend/) is a small FastAPI + SQLite service (port `8429`, one
`docker compose up`). After each push it computes:

- **Sleep**, **Readiness** and **Activity** scores (0–100) from sleep, heart
  rate, HRV, steps and calories, each with its contributors,
- stress zones (restored / relaxed / engaged / stressed) and a resilience level,
- trailing 14-day personal baselines.

Samples and results are stored in SQLite only and read through the backend routes. Routes
(`/today`, `/sleep`, `/trends`, ...) need `Authorization: Bearer <API_TOKEN>`;
only `/health` is open. TLS is not handled here (use a VPN such as Tailscale).

The scores are approximations against your own 14-day baselines, not a
reference implementation of any vendor's score. A contributor that needs a
baseline stays empty (`null`, "learning") until at least 5 of the trailing 14
days have data, so expect the first week to be incomplete.

## Building the Android app

### Prerequisites

- Android Studio (any recent version)
- An Android SDK with platform 35 installed
- A phone with USB debugging enabled

### Build & install

1. Open the project root in Android Studio. Gradle sync should pick up the
   wrapper automatically.
2. Plug in your phone, accept the USB debugging prompt.
3. Hit Run, or from the command line:

   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### First-time setup in the app

1. Launch **Circa** on the phone.
2. Grant the Bluetooth and notification permissions.
3. Tap **Scan for ring**, pick your Colmi R02 from the list (the MAC fills in).
4. Enter your backend URL, e.g. `http://192.168.1.10:8429`, and the token.
5. Tap **Save settings**, then **Sync now** to verify.

If everything is healthy the sample counts climb:

```bash
docker exec ring-backend python -c "import sqlite3;print(sqlite3.connect('/data/ring.db').execute(\"select count(*) from samples where metric='biometric_hr_bpm'\").fetchone())"
```

## How it works

- **`SyncWorker`** is an `androidx.work` `CoroutineWorker` that self-chains
  one-time work requests every 5 min (a `PeriodicWorkRequest` can't fire
  faster than 15 min, so we chain manually).
- **`ColmiConnection`** wraps the Android `BluetoothGatt` API in coroutine
  channels so the request/notify dance reads as straight-line code.
- **`ColmiProtocol`** holds packet builders and parsers for the Colmi R0x
  wire format (heavily inspired by
  [Gadgetbridge's `ColmiR0xDeviceSupport`](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/branch/master/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/colmi)).
- **`MetricsPusher`** posts Prometheus text lines to the backend's
  `/api/v1/import/prometheus`.
- **`Prefs`** persists the ring MAC, server URL and per-metric "last synced"
  cursors so we don't re-push the same samples.

The ring keeps roughly the last 24 h of detailed samples in its own flash; the
sync worker pulls today + yesterday on each run and the cursor de-dupes what
we've already shipped.

## Notes & caveats

- The ring will *not* talk to two BLE clients at once. Uninstall (or at least
  unpair from) Gadgetbridge / the official Colmi app before using this one.
- `minSdk = 31` because `BLUETOOTH_CONNECT` is an API-31 runtime permission.
- Sleep metrics are emitted **once per detected sleep session**, timestamped at
  the session's start time. The "Sleep stages over time" panel therefore shows
  one bar per night, anchored at the moment you fell asleep, rather than a
  tidy per-calendar-day total. Aggregating sessions onto calendar-day buckets
  is on the TODO list — patches welcome.
- Cleartext HTTP is allowed by the network security config so that you can
  point at a local backend over plain HTTP. If you front your
  endpoint with TLS you can tighten `app/src/main/res/xml/network_security_config.xml`.
- This is a personal project, not a medical device. Don't make health decisions
  off these numbers.

## Credits

- Protocol details reverse-engineered by the
  [Gadgetbridge](https://gadgetbridge.org/) community (GPL-3.0).
- AndroidX, OkHttp — thanks to all the maintainers.

## License

MIT — see [`LICENSE`](LICENSE).
