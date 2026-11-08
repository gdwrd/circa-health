from datetime import date, datetime, timedelta

import httpx
from fastapi.testclient import TestClient

from app import db, main, scoring, vm
from app.main import app

H = {"Authorization": "Bearer secret"}
STEP = {"5m": 300, "30m": 1800, "1h": 3600}


def test_ingest_rescores_and_today_has_sleep_score(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    d = date.today()
    ss = (vm.midnight(d) - timedelta(minutes=30)).timestamp()  # 23:30 yesterday
    es = (vm.midnight(d) + timedelta(hours=7)).timestamp()

    def fake(expr: str, start: datetime, end: datetime, step: str) -> list[dict]:
        out, t = [], start.timestamp()
        while t <= end.timestamp():
            hour = datetime.fromtimestamp(t).hour
            first = ss <= t < ss + 300
            v = {
                "biometric_sleep_end_ts": es if first else None,
                "biometric_sleep_total_min": 420 if first else None,
                "biometric_sleep_awake_min": 30 if first else None,
                "biometric_sleep_rem_min": 90 if first else None,
                "biometric_sleep_deep_min": 80 if first else None,
                "last_over_time(biometric_sleep_stage[3h])": 2 if first else None,
                "biometric_hr_bpm": 54 if ss <= t <= es else 70,
                "biometric_hrv_rmssd": 45,
                "biometric_stress": 80 if hour < 10 else 30,
                "biometric_steps": 500 if 8 <= hour <= 20 else None,
                "biometric_calories": 90 if 8 <= hour <= 20 else None,
                "biometric_spo2_pct": 97,
                "ring_battery_pct": 80,
            }[expr]
            if v is not None:
                out.append([t, str(v)])
            t += STEP[step]
        return [{"metric": {}, "values": out}] if out else []

    sent: list[bytes] = []
    monkeypatch.setattr(vm, "query_range", fake)
    monkeypatch.setattr(vm, "forward_import", lambda b: sent.append(b) or httpx.Response(204))
    with TestClient(app) as c:
        body = f"biometric_steps 5 {int(datetime.now().timestamp() * 1000)}\n"
        assert c.post("/api/v1/import/prometheus", content=body, headers=H).status_code == 204
        j = c.get("/today", headers=H).json()
        print(j)
        assert j["sleep"]["score"] is not None and j["status"] == "learning" and j["baseline_days"] == "1/14"
        assert j["activity"]["score"] is not None and j["vitals"]["hr"] == 70
        assert b"score_sleep" in sent[-1]
        s = c.get("/sleep", headers=H).json()
        assert s["session"]["total_min"] == 420 and s["stages"]
        t = c.get("/trends?metric=sleep&days=7", headers=H).json()
        assert t["points"][-1]["value"] == j["sleep"]["score"]
        assert c.get("/trends?metric=bogus", headers=H).status_code == 422
        assert db.days(d, d)


def test_hr_drops_nonpositive_and_clamps_hours(monkeypatch) -> None:
    seen: list[float] = []

    def fake(expr: str, start: datetime, end: datetime, step: str) -> list[dict]:
        seen.append((end - start).total_seconds() / 3600)
        t = start.timestamp()
        return [{"metric": {}, "values": [[t, "0"], [t + 300, "61"]]}]

    monkeypatch.setattr(vm, "query_range", fake)
    with TestClient(app) as c:
        p = c.get("/hr?hours=24", headers=H).json()["points"]
        assert [x["bpm"] for x in p] == [61.0]
        c.get("/hr?hours=999", headers=H)
        assert seen[-1] == 48
        assert c.get("/hr").status_code == 401


def test_night_without_end_ts(monkeypatch) -> None:
    """Sessions pushed by app v1.0 carry only *_min series; the night still resolves."""
    d = date.today()
    t0 = (vm.midnight(d) - timedelta(minutes=30)).timestamp()
    monkeypatch.setattr(vm, "series", lambda expr, s, e, step: {t0: 420.0} if expr == "biometric_sleep_total_min" else {})
    n = main._sessions(d)[0]
    assert n is not None and n["end"] == t0 + 420 * 60 and n["total_min"] == 420


def _strain_day(monkeypatch, d: date, calm: bool = False) -> None:
    """Score d from a faked night: strained = HR falling late to 75, HRV 20 ms, 60 min awake; calm = usual values."""
    t0 = (vm.midnight(d) - timedelta(hours=1)).timestamp()
    hr = [55.0] * 4 if calm else [90.0, 85.0, 80.0, 75.0]
    night = {"biometric_sleep_total_min": {t0: 420.0}, "biometric_sleep_awake_min": {t0: 20.0 if calm else 60.0},
             "biometric_hr_bpm": {t0 + i * 300: v for i, v in enumerate(hr)},
             "biometric_hrv_rmssd": {t0: 50.0 if calm else 20.0}}
    monkeypatch.setattr(vm, "series", lambda e, s, en, st: night.get(e, {}))
    monkeypatch.setattr(vm, "forward_import", lambda b: httpx.Response(204))
    main._score_day(d)


def _calm_history(d: date, signals: dict | None = None, first: int = 1) -> None:
    for i in range(first, 15):
        c = {"inputs": {"rhr": 55.0 + i % 2, "hrv": 50.0 + i % 3, "awake": 20.0, "recovery": 0.3}}
        if i == first and signals:
            c["signals"] = signals
        db.save_day(d - timedelta(i), (None, None, None), c, {})


def test_strain_signals_streak_explain_and_trend(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "s.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    d = date.today()
    _calm_history(d, {"streak": 1})
    db.add_journal((d - timedelta(1)).isoformat(), "alcohol", None, None, 1)
    db.add_journal((d - timedelta(1)).isoformat(), "caffeine_late", None, None, 2)
    db.add_journal(d.isoformat(), "sick", None, None, 3)  # today's entry does not explain last night
    _strain_day(monkeypatch, d)
    sig = db.days(d, d)[0]["c"]["signals"]
    assert set(sig["flags"]) == {"rhr", "hrv", "awake", "recovery"} and sig["streak"] == 2 and sig["level"] == "high"
    monkeypatch.setattr(main, "_vitals", lambda: {})
    with TestClient(app) as c:
        j = c.get("/today", headers=H).json()["signals"]
        assert j["status"] == "ok" and j["reason"] is None and j["explain"] == ["alcohol"]
        assert {"index", "level", "flags", "streak", "available", "nights"} <= set(j) and j["nights"] == 14
        t = c.get("/trends?metric=strain&days=3", headers=H).json()["points"]
        assert t[-1]["value"] == sig["index"] and t[-2]["value"] is None


def test_strain_streak_resets_and_medication_window(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "m.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    d = date.today()
    _calm_history(d, {"streak": 3})
    _strain_day(monkeypatch, d, calm=True)
    sig = db.days(d, d)[0]["c"]["signals"]
    assert sig["streak"] == 0 and sig["flags"] == {} and sig["level"] == "calm"
    # d-1 missing below: a gap restarts the streak at 1
    monkeypatch.setenv("DB_PATH", str(tmp_path / "gap.db"))
    db.init()
    _calm_history(d, {"streak": 3}, first=2)
    _strain_day(monkeypatch, d)
    assert db.days(d, d)[0]["c"]["signals"]["streak"] == 1
    _calm_history(d, {"streak": 3})
    db.set_settings({"medication_started": (d - timedelta(3)).isoformat()})
    _strain_day(monkeypatch, d)
    sig = db.days(d, d)[0]["c"]["signals"]
    assert sig["reason"] == "medication" and "rhr" not in sig["flags"] and "hrv" not in sig["flags"]
    assert sig["nights"] == 3 and sig["available"] == 2  # awake + recovery still scored
    monkeypatch.setattr(main, "_vitals", lambda: {})
    with TestClient(app) as c:
        assert c.get("/today", headers=H).json()["signals"]["status"] == "learning"


def test_sleep_depth_naps_regularity_spo2_wear(tmp_path, monkeypatch) -> None:
    """Yesterday (all 24 h past): 23:00-06:20 main night, 35 min nap kept, 10 min nap dropped, ring off 10:00 on.
    Re-sent copies overlapping the night (23:10) and the nap (+5 min) are not naps."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "c1.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    d = date.today() - timedelta(1)
    m = vm.midnight(d).timestamp()
    t0, nap, short = m - 3600, m + 13 * 3600, m + 16 * 3600
    for i in range(1, 6):  # same 23:00-06:20 nights; 400 min + 30 min naps
        ps = vm.midnight(d - timedelta(i)).timestamp() - 3600
        db.save_day(d - timedelta(i), (None, None, None), {"inputs": {"sleep_min": 400.0, "nap_min": 30.0},
                    "session": {"start": ps, "end": ps + 440 * 60}}, {})
    dup, ndup = t0 + 600, nap + 300
    data = {"biometric_sleep_end_ts": {t0: t0 + 440 * 60, nap: nap + 40 * 60, short: short + 600,
                                       dup: t0 + 435 * 60, ndup: ndup + 38 * 60},
            "biometric_sleep_total_min": {t0: 420.0, nap: 35.0, short: 10.0, dup: 415.0, ndup: 30.0},
            "biometric_sleep_awake_min": {t0: 20.0, nap: 5.0},
            "biometric_hr_bpm": {**{t0 + i * 300: v for i, v in enumerate([60.0, 58.0, 52.0, 50.0, 51.0, 55.0])},
                                 m + 3 * 3600: 50.0, m + 8 * 3600: 70.0, m + 8 * 3600 + 300: 72.0,
                                 m + 9 * 3600: 70.0, m + 10 * 3600: 0.0},
            "biometric_spo2_pct": {t0 + 300: 95.0, t0 + 3600: 88.0, t0 + 7200: 0.0, m + 10 * 3600: 99.0},
            "biometric_steps": {m + 8 * 3600: 300.0, m + 9 * 3600: 10.0, m + 12 * 3600: 0.0}}
    monkeypatch.setattr(vm, "series", lambda e, s, en, st: {t: v for t, v in data.get(e, {}).items()
                                                            if s.timestamp() <= t <= en.timestamp()})
    monkeypatch.setattr(vm, "forward_import", lambda b: httpx.Response(204))
    main._score_day(d)
    c = db.days(d, d)[0]["c"]
    i, x = c["inputs"], c["sleep_extra"]
    assert i["sleep_min"] == 420 and i["nap_min"] == 35 and [n["total_min"] for n in x["naps"]] == [35]
    assert c["readiness"]["sleep_balance"] == scoring._lin(430, 240, db.setting("sleep_need_min"))  # 400 + 30 nap
    assert x["regularity"] == {"sd_min": 0, "nights": 6, "bed_start_median": 660, "bed_end_median": 1100}
    assert c["sleep"]["regularity"] == 100
    assert x["spo2"] == {"mean": 91.5, "min": 88.0, "low_count": 1, "n": 2} and i["spo2"] == 88
    assert x["hr_low_t"] == t0 + 600
    assert i["bed_start"] == 660 and i["bed_end"] == 1100  # 23:00 / 06:20 as minutes after noon
    # worn: hours 3, 8, 9; awake = 24 - 7 (night) - 2 (naps); hour 12 has 0 steps but the ring was off
    assert i["worn_hours"] == 3 and c["wear"] == {"worn_hours": 2, "awake_hours": 15}
    assert c["activity"]["inactive_hours"] == 100  # 1 inactive worn hour; counting ring-off hours would give 0
    monkeypatch.setattr(main, "_today", lambda: d)
    monkeypatch.setattr(main, "_vitals", lambda: {})
    with TestClient(app) as cl:
        s = cl.get(f"/sleep?date={d}", headers=H).json()
        assert s["naps"] == x["naps"] and len(s["hr"]) == 7 and s["hr_low"] == {"t": t0 + 600, "bpm": 52.0}
        assert s["sleep_extra"]["regularity"] == x["regularity"] and s["sleep_extra"]["spo2"] == x["spo2"] is not None
        assert cl.get("/today", headers=H).json()["wear"] == {"worn_hours": 2, "awake_hours": 15}
        assert cl.get("/trends?metric=bed_start&days=2", headers=H).json()["points"][-1]["value"] == 660


def test_spo2_null_without_samples(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "o2.db"))
    db.init()
    d = date.today() - timedelta(1)
    t0 = vm.midnight(d).timestamp() - 3600
    monkeypatch.setattr(vm, "series", lambda e, s, en, st: {t0: 420.0} if e == "biometric_sleep_total_min" else
                        {t0: 0.0} if e == "biometric_spo2_pct" else {})
    monkeypatch.setattr(vm, "forward_import", lambda b: httpx.Response(204))
    main._score_day(d)
    c = db.days(d, d)[0]["c"]
    assert c["sleep_extra"]["spo2"] is None and c["inputs"]["spo2"] is None and c["sleep_extra"]["regularity"]["sd_min"] is None
    assert c["sleep"]["regularity"] is None and c["sleep_extra"]["naps"] == [] and c["inputs"]["nap_min"] == 0


def test_week_shape_means_nights_and_previous(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "w.db"))
    db.init()
    end = date(2026, 11, 8)
    for i, (sl, rd) in enumerate([(80, 70), (60, None), (90, 75)]):  # 8th, 7th, 6th; 2nd-5th missing
        c = {"inputs": {"steps": 1000.0 * (i + 1), "sleep_min": 400.0 + 20 * i}, "signals": {"index": 10 + i}}
        db.save_day(end - timedelta(i), (sl, rd, None), c, {})
    db.save_day(end - timedelta(7), (50, 60, 70), {"inputs": {"steps": 9000.0}}, {})  # previous week
    with TestClient(app) as c:
        w = c.get("/week?end=2026-11-08", headers=H).json()
        assert [x["date"] for x in w["days"]] == [(end - timedelta(6 - i)).isoformat() for i in range(7)]
        assert w["days"][-1] == {"date": "2026-11-08", "sleep": 80, "readiness": 70, "activity": None,
                                 "steps": 1000.0, "sleep_min": 400.0, "strain": 10}
        assert w["days"][0]["sleep"] is None and w["scored_days"] == 3
        assert w["means"] == {"sleep": 76.7, "readiness": 72.5, "activity": None}
        assert w["best_night"] == {"date": "2026-11-06", "score": 90}
        assert w["worst_night"] == {"date": "2026-11-07", "score": 60}
        assert w["steps_total"] == 6000.0 and w["sleep_avg_min"] == 420.0
        p = w["previous"]
        assert p["start"] == "2026-10-26" and p["end"] == "2026-11-01" and p["scored_days"] == 1
        assert p["means"] == {"sleep": 50.0, "readiness": 60.0, "activity": 70.0} and p["steps_total"] == 9000.0
        assert p["sleep_avg_min"] is None and "previous" not in p
        e = c.get("/week?end=2026-10-25", headers=H).json()
        assert e["scored_days"] == 0 and e["best_night"] is None and e["steps_total"] is None
        assert c.get("/week", headers=H).json()["end"] == date.today().isoformat()
        assert c.get("/week?end=bogus", headers=H).status_code == 422
