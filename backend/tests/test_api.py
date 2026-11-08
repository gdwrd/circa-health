from datetime import date, datetime, timedelta

import httpx
from fastapi.testclient import TestClient

from app import db, main, vm
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
    n = main._night(d)
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
