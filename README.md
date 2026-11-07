# Ring Health Tracker

A self-hosted biometric pipeline for the **Colmi R02** smart ring.

```
┌──────────┐   BLE/GATT   ┌────────────────┐  
│ Colmi R02│ ──────────▶ │  Android app   │
│   ring   │              │ (this project) │
└──────────┘              └────────────────┘
                                  |
                                  | HTTP
                                  |
                                  V
┌──────────┐    PromQL    ┌────────────────┐
│  Grafana │ ◀────────── │ VictoriaMetrics│
│          │              │  (your host)   │
└──────────┘              └────────────────┘
```

The Android app pulls heart rate, HRV, SpO₂, stress, steps, calories, distance,
sleep stages and ring battery from the ring every 5 minutes, then pushes them as
Prometheus-style metrics to a self-hosted [VictoriaMetrics](https://victoriametrics.com/)
instance. A [Grafana](https://grafana.com/) dashboard is included.

No vendor cloud, no telemetry, no account — your biometric data never leaves
your network. In this case I'm using a VPN on the phone so that it is constantly
able to connect to the home network which hosts VictoriaMetrics, etc. The ring 
does keep data for a day or two though, so there is no need to sync constantly. 

## Why

The official Colmi app uploads everything to a third-party cloud. Open-source
projects like [Gadgetbridge](https://gadgetbridge.org/) display the data
locally but don't make it easy to feed into a long-term time-series store. This
project is a minimal Android app whose only job is:

1. wake up every 5 min,
2. connect to the ring,
3. drain whatever new samples the ring has buffered,
4. POST them to your VictoriaMetrics instance,
5. disconnect.

## Hardware

- **Ring**: Colmi R02 (other R0x variants likely work — same protocol family)
- **Phone**: any Android device with API 31+ (Android 12 or newer)
- **Server**: any host that can run VictoriaMetrics + Grafana (a Raspberry Pi
  is enough)

## Metrics collected

| Metric                       | Native granularity | Notes                          |
| ---------------------------- | ------------------ | ------------------------------ |
| `biometric_hr_bpm`           | 5 min              | Raw heart-rate samples         |
| `biometric_hrv_rmssd`        | 30 min             | RMSSD in milliseconds          |
| `biometric_stress`           | 30 min             | Ring's stress score (0–100)    |
| `biometric_spo2_pct`         | hourly             | Blood oxygen %                 |
| `biometric_steps`            | hourly             | Steps per hour bucket          |
| `biometric_calories`         | hourly             | kcal per hour bucket           |
| `biometric_distance_meters`  | hourly             | Meters per hour bucket         |
| `biometric_sleep_total_min`  | per session        | Minutes asleep                 |
| `biometric_sleep_deep_min`   | per session        | Minutes in deep sleep          |
| `biometric_sleep_rem_min`    | per session        | Minutes in REM sleep           |
| `biometric_sleep_light_min`  | per session        | Minutes in light sleep         |
| `ring_battery_pct`           | per sync           | Ring battery 0–100             |
| `ring_charging`              | per sync           | 1 = charging, 0 = discharging  |

All metrics carry the label `device="colmi_r02"`.

Aggregations like overnight RHR (`min_over_time(biometric_hr_bpm[8h])`) are
done in Grafana / PromQL rather than at write time, so you keep the raw data.

## Server side: VictoriaMetrics + Grafana

A ready-to-run stack lives in [`infra/`](infra/). It provisions the
VictoriaMetrics data source (UID `victoriametrics`) and the
[`grafana/biometrics.json`](grafana/biometrics.json) dashboard automatically.

```bash
# on the server, with the repo (or just infra/ + grafana/) copied over
cd infra
cp .env.example .env   # set GRAFANA_ADMIN_PASSWORD (and GRAFANA_PORT, default 3001)
docker compose up -d
```

VictoriaMetrics listens on `:8428`, Grafana on `:3001` (login `admin` / your
password). Point the app at `http://<server-ip>:8428`.

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

1. Launch **Ring Health Tracker** on the phone.
2. Grant the Bluetooth and notification permissions.
3. Tap **Scan for ring**, pick your Colmi R02 from the list (the MAC fills in).
4. Enter your VictoriaMetrics URL, e.g. `http://192.168.1.10:8428`.
5. Tap **Save settings**, then **Sync now** to verify.

If everything is healthy you should see the metric counts climb in
VictoriaMetrics:

```bash
curl 'http://YOUR_VM_HOST:8428/api/v1/query?query=count_over_time(biometric_hr_bpm[24h])'
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
- **`MetricsPusher`** posts to the VictoriaMetrics
  [JSON line protocol endpoint](https://docs.victoriametrics.com/#how-to-import-data-in-json-line-format).
- **`Prefs`** persists the ring MAC, VM URL and per-metric "last synced"
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
  in PromQL is on the TODO list — patches welcome.
- Cleartext HTTP is allowed by the network security config so that you can
  point at a local VictoriaMetrics over plain HTTP. If you front your
  endpoint with TLS you can tighten `app/src/main/res/xml/network_security_config.xml`.
- This is a personal project, not a medical device. Don't make health decisions
  off these numbers.

## Credits

- Protocol details reverse-engineered by the
  [Gadgetbridge](https://gadgetbridge.org/) community (GPL-3.0).
- VictoriaMetrics, Grafana, AndroidX, OkHttp — thanks to all the maintainers.

## License

MIT — see [`LICENSE`](LICENSE).
