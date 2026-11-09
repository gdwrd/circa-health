from datetime import date, datetime, timedelta

import httpx
from fastapi.testclient import TestClient

from app import cardio, db, main, scoring, vm
from app.main import app

H = {"Authorization": "Bearer secret"}
STEP = {"5m": 300, "30m": 1800, "1h": 3600}


def test_ingest_rescores_and_today_has_sleep_score(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    d = date.today()
    ss = (vm.midnight(d) - timedelta(minutes=30)).timestamp()  # 23:30 yesterday
    es = (vm.midnight(d) + timedelta(hours=7)).timestamp()

    def fake(expr: str, start: datetime, end: datetime, step: str, lookback: str | None = None) -> list[dict]:
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
        asleep_now = ss <= datetime.now().timestamp() <= es  # latest fake sample is "now"
        assert j["activity"]["score"] is not None and j["vitals"]["hr"] == (54 if asleep_now else 70)
        assert b"score_sleep" in sent[-1]
        s = c.get("/sleep", headers=H).json()
        assert s["session"]["total_min"] == 420 and s["stages"]
        t = c.get("/trends?metric=sleep&days=7", headers=H).json()
        assert t["points"][-1]["value"] == j["sleep"]["score"]
        assert c.get("/trends?metric=bogus", headers=H).status_code == 422
        assert db.days(d, d)


def test_hr_drops_nonpositive_and_clamps_hours(monkeypatch) -> None:
    seen: list[float] = []

    def fake(expr: str, start: datetime, end: datetime, step: str, lookback: str | None = None) -> list[dict]:
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


def test_day_history_routes(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "h.db"))
    db.init()
    seen: list[tuple[datetime, datetime]] = []

    def fake(expr: str, start: datetime, end: datetime, step: str, lookback: str | None = None) -> list[dict]:
        seen.append((start, end))
        t = start.timestamp()
        return [{"metric": {}, "values": [[t, "0"], [t + 1800, "61"]]}]

    monkeypatch.setattr(vm, "query_range", fake)
    d = date(2026, 11, 3)
    db.save_day(d, (81, 72, 65), {"inputs": {"rhr": 52.0, "hrv": 48.0}}, {})
    with TestClient(app) as c:
        assert [x["bpm"] for x in c.get("/hr?date=2026-11-03&hours=1", headers=H).json()["points"]] == [61.0]
        assert seen[-1] == (vm.midnight(d), vm.midnight(d + timedelta(1)))
        c.get(f"/hr?date={date.today() + timedelta(1)}", headers=H)  # future day: capped at now
        assert seen[-1][1] <= datetime.now(vm.tz()) and seen[-1][1] < vm.midnight(date.today() + timedelta(2))
        assert c.get("/hr?date=bogus", headers=H).status_code == 422
        st = c.get("/stress?date=2026-11-03", headers=H).json()
        assert st["date"] == "2026-11-03" and [p["v"] for p in st["points"]] == [61.0]
        assert c.get("/stress?date=bogus", headers=H).status_code == 422
        j = c.get("/today?date=2026-11-03", headers=H).json()
        assert j["date"] == "2026-11-03" and j["sleep"]["score"] == 81 and j["readiness"]["score"] == 72
        assert j["inputs"] == {"rhr": 52.0, "hrv": 48.0}
        assert c.get("/today?date=bogus", headers=H).status_code == 422


def test_activity_hours(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    d = date(2026, 11, 3)
    t9 = (vm.midnight(d) + timedelta(hours=9)).timestamp()
    data = {"biometric_steps": {t9: 1200.0}, "biometric_calories": {t9: 80.0},
            "biometric_distance_meters": {t9: 900.0}, "biometric_hr_bpm": {t9 - 3600: 0.0, t9 + 600: 72.0}}
    lookbacks: dict[str, str | None] = {}

    def series(e: str, s: datetime, en: datetime, st: str, lb: str | None = None) -> dict:
        lookbacks[e] = lb
        return {t: v for t, v in data.get(e, {}).items() if s.timestamp() <= t <= en.timestamp()}
    monkeypatch.setattr(vm, "series", series)
    with TestClient(app) as c:
        h = c.get("/activity?date=2026-11-03", headers=H).json()
        assert lookbacks["biometric_steps"] == "59m"  # an empty hour must not repeat the hour before
        assert h["date"] == "2026-11-03" and len(h["hours"]) == 24
        assert h["hours"][9] == {"h": 9, "steps": 1200.0, "calories": 80.0, "distance_m": 900.0, "worn": True}
        assert h["hours"][8] == {"h": 8, "steps": 0.0, "calories": 0.0, "distance_m": 0.0, "worn": False}
        assert [x["h"] for x in h["hours"] if x["worn"]] == [9]
        assert c.get("/activity?date=bad", headers=H).status_code == 422


def test_query_range_sends_max_lookback(monkeypatch) -> None:
    sent: list[dict] = []

    class R:
        def raise_for_status(self) -> None: ...
        def json(self) -> dict: return {"data": {"result": []}}
    monkeypatch.setattr(httpx, "get", lambda url, params, timeout: sent.append(params) or R())
    t = datetime(2026, 11, 9)
    vm.query_range("biometric_steps", t, t, "1h", "59m")
    vm.query_range("biometric_hr_bpm", t, t, "5m")
    assert sent[0]["max_lookback"] == "59m" and "max_lookback" not in sent[1]


def test_night_without_end_ts(monkeypatch) -> None:
    """Sessions pushed by app v1.0 carry only *_min series; the night still resolves."""
    db.init()  # _sessions reads sleep_override
    d = date.today()
    t0 = (vm.midnight(d) - timedelta(minutes=30)).timestamp()
    monkeypatch.setattr(vm, "series", lambda expr, s, e, step, lb=None: {t0: 420.0} if expr == "biometric_sleep_total_min" else {})
    n = main._sessions(d)[0]
    assert n is not None and n["end"] == t0 + 420 * 60 and n["total_min"] == 420


def _strain_day(monkeypatch, d: date, calm: bool = False) -> None:
    """Score d from a faked night: strained = HR falling late to 75, HRV 20 ms, 60 min awake; calm = usual values."""
    t0 = (vm.midnight(d) - timedelta(hours=1)).timestamp()
    hr = [55.0] * 4 if calm else [90.0, 85.0, 80.0, 75.0]
    night = {"biometric_sleep_total_min": {t0: 420.0}, "biometric_sleep_awake_min": {t0: 20.0 if calm else 60.0},
             "biometric_hr_bpm": {t0 + i * 300: v for i, v in enumerate(hr)},
             "biometric_hrv_rmssd": {t0: 50.0 if calm else 20.0}}
    monkeypatch.setattr(vm, "series", lambda e, s, en, st, lb=None: night.get(e, {}))
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
    monkeypatch.setattr(vm, "series", lambda e, s, en, st, lb=None: {t: v for t, v in data.get(e, {}).items()
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
    monkeypatch.setattr(vm, "series", lambda e, s, en, st, lb=None: {t0: 420.0} if e == "biometric_sleep_total_min" else
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


def test_cardio_settings_today_and_workout_relabel(tmp_path, monkeypatch) -> None:
    """Yesterday, no night: 54 bpm all day, 10:00-10:30 at 150 bpm (zone 3) with 200 steps that hour."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "f1.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    d = date.today() - timedelta(1)
    m = vm.midnight(d).timestamp()
    w0 = m + 10 * 3600
    data = {"biometric_hr_bpm": {m + i * 300: 150.0 if w0 <= m + i * 300 < w0 + 1800 else 54.0 for i in range(288)},
            "biometric_steps": {w0: 200.0}}
    monkeypatch.setattr(vm, "series", lambda e, s, en, st, lb=None: {t: v for t, v in data.get(e, {}).items()
                                                            if s.timestamp() <= t <= en.timestamp()})
    monkeypatch.setattr(vm, "forward_import", lambda b: httpx.Response(204))
    monkeypatch.setattr(main, "_vitals", lambda: {})

    def prior(load: float | None) -> None:
        for i in range(1, 15):
            db.save_day(d - timedelta(i), (None, None, None), {"inputs": {"rhr": 50.0, "cardio_load": load}}, {})

    prior(None)
    with TestClient(app) as c:
        s = c.get("/settings", headers=H).json()
        assert s["birth_year"] == "" and s["low_step_kind"] == "workout"
        for bad in ({"birth_year": 1899}, {"birth_year": date.today().year + 1}, {"birth_year": "85"},
                    {"birth_year": True}, {"birth_year": 1990.5}, {"low_step_kind": "run"}, {"low_step_kind": "walk"}):
            assert c.patch("/settings", json=bad, headers=H).status_code == 422, bad
        main._score_day(d)
        j = c.get(f"/today?date={d}", headers=H).json()
        assert j["cardio"] == {"status": "needs_age", "load": None, "minutes": None, "target": None, "days": 0}
        assert j["workouts"] == [] and j["inputs"]["restorative_min"] > 0 and j["inputs"]["fitness"] is None
        assert c.patch("/settings", json={"birth_year": "1985"}, headers=H).json()["birth_year"] == "1985"
        r = c.patch("/settings", json={"birth_year": 1990, "low_step_kind": "ride"}, headers=H).json()
        assert r["birth_year"] == "1990" and r["low_step_kind"] == "ride"
        main._score_day(d)
        j = c.get(f"/today?date={d}", headers=H).json()
        assert j["cardio"] == {"status": "learning", "load": 9.0, "minutes": [0, 0, 30, 0, 0], "target": None, "days": 0}
        (w,) = j["workouts"]
        assert (w["start"], w["end"], w["kind"], w["confirmed"]) == (int(w0), int(w0) + 1800, "ride", False)
        assert j["inputs"]["fitness"] == cardio.fitness(cardio.max_hr(1990, d), 50.0)
        assert c.get("/trends?metric=cardio_load&days=2", headers=H).json()["points"][-1]["value"] == 9.0

        prior(10.0)
        main._score_day(d)
        row = db.days(d, d)[0]
        db.save_day(d, (None, 85, None), row["c"], {})  # a scored readiness gives the target band
        j = c.get(f"/today?date={d}", headers=H).json()["cardio"]
        assert j["status"] == "ok" and j["days"] == 14 and j["target"] == {"lo": 11, "hi": 14, "band": "recovered"}
        assert c.get("/trends?metric=cardio_load&days=2", headers=H).json()["points"][-1]["baseline"] == 10.0

        r = c.patch("/workouts", json={"date": d.isoformat(), "start": int(w0) - 300, "kind": "walk"}, headers=H)
        assert [(x["kind"], x["confirmed"]) for x in r.json()["workouts"]] == [("walk", True)]
        main._score_day(d)  # the label survives a rescore
        assert [(x["kind"], x["confirmed"]) for x in db.days(d, d)[0]["c"]["workouts"]] == [("walk", True)]
        for bad in ({"date": d.isoformat(), "start": int(w0), "kind": "run"},
                    {"date": "bogus", "start": int(w0), "kind": "walk"},
                    {"start": int(w0), "kind": "walk"}, {"date": d.isoformat(), "start": "x", "kind": "walk"}):
            assert c.patch("/workouts", json=bad, headers=H).status_code == 422, bad
        assert c.patch("/settings", json={"birth_year": ""}, headers=H).json()["birth_year"] == ""


def test_sleep_debt_and_bedtime_fields(tmp_path, monkeypatch) -> None:
    """14 nights to today: even days 22:00 (quality 90), odd days 23:30 (60); last 3 nights 400 / 420+30 nap / 470."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "f2.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    monkeypatch.setattr(vm, "series", lambda e, s, en, st, lb=None: {})
    monkeypatch.setattr(main, "_vitals", lambda: {})
    d = date.today()
    asleep = {0: (400.0, 0.0), 1: (420.0, 30.0), 2: (470.0, None)}
    for i in range(14):
        q = 90.0 if i % 2 == 0 else 60.0
        s = (vm.midnight(d - timedelta(i + 1)) + timedelta(hours=22, minutes=0 if i % 2 == 0 else 90)).timestamp()
        sm, nap = asleep.get(i, (None, None))
        db.save_day(d - timedelta(i), (None, None, None),
                    {"sleep": {k: q for k in ("total", "efficiency", "restfulness", "rem", "deep")},
                     "session": {"start": s, "end": s + 8 * 3600}, "inputs": {"sleep_min": sm, "nap_min": nap}}, {})
    window = {"status": "ok", "start_min": 600, "end_min": 660, "score_in": 90, "score_out": 60, "nights": 14,
              "nights_in": 7}
    debt = {"debt_min": 30, "nights": 3, "suggest_min": 10}  # 450 need: 50 + 0 - 20 (nap counts); nights 3-6 have no sleep_min
    with TestClient(app) as c:
        s = c.get(f"/sleep?date={d}", headers=H).json()
        assert s["debt"] == debt and s["bedtime"] == window
        assert c.get(f"/sleep?date={d - timedelta(10)}", headers=H).json()["debt"] is None  # no sleep_min that week
        assert c.get("/today", headers=H).json()["tonight"] == {"bedtime": window, "debt": debt, "plan": None}
        c.patch("/settings", json={"wake_weekday": "07:00", "wake_weekend": "07:00"}, headers=H)
        assert c.get(f"/sleep?date={d}", headers=H).json()["plan"] == {  # 1140 - 450 - 10 = 680, in bed 665 > window end 660
            "asleep_by_min": 680.0, "in_bed_by_min": 665.0, "sleep_min": 460.0, "extra_min": 10, "wake_min": 1140,
            "window": "later"}


def test_month_shape_means_best_week_workouts_effects(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "m.db"))
    db.init()
    first = date(2026, 10, 1)
    for i in range(31):  # October: sleep 70/90 alternating with alcohol nights, readiness rising, one workout a day
        d = first + timedelta(i)
        s = vm.midnight(d - timedelta(1)) + timedelta(hours=23)
        bad = i % 2 == 1  # night after an alcohol day (logged on even days)
        c = {"inputs": {"steps": 1000.0, "sleep_min": 420.0, "restorative_min": 30.0, "cardio_load": 5.0},
             "session": {"start": s.timestamp(), "end": s.timestamp() + 8 * 3600, "deep_min": 60.0, "rem_min": 90.0,
                         "total_min": 420.0, "awake_min": 20.0},
             "workouts": [{"minutes": 30, "load": 2.5, "kind": ("walk", "ride")[i % 2]}]}
        db.save_day(d, (60 if bad else 90, 50 + i, None), c, {})
        if i % 2 == 0 and i < 30:
            db.add_journal(d.isoformat(), "alcohol", None, None, 0)
    db.save_day(date(2026, 9, 30), (50, 40, 30), {}, {})  # previous month
    with TestClient(app) as c:
        m = c.get("/month?month=2026-10", headers=H).json()
        assert m["month"] == "2026-10" and m["start"] == "2026-10-01" and m["end"] == "2026-10-31"
        assert len(m["days"]) == 31 and m["days"][0] == {"date": "2026-10-01", "sleep": 90, "readiness": 50,
                                                         "activity": None, "steps": 1000.0, "sleep_min": 420.0,
                                                         "cardio_load": 5.0, "workouts": 1}
        assert m["scored_days"] == 31 and m["means"] == {"sleep": 75.5, "readiness": 65.0, "activity": None}
        assert m["best_week"] == {"start": "2026-10-25", "end": "2026-10-31", "readiness": 77.0}
        assert m["steps_total"] == 31000.0 and m["sleep_avg_min"] == 420.0 and m["restorative_avg_min"] == 30.0
        assert m["workouts"] == {"count": 31, "minutes": 930, "load": 77.5, "by_kind": {"walk": 16, "ride": 15, "workout": 0}}
        (e,) = m["effects"]
        assert e["kind"] == "alcohol" and e["metric"] == "sleep_score" and e["delta"] < 0 and e["n_with"] == 15
        assert m["previous"] == {"means": {"sleep": 50.0, "readiness": 40.0, "activity": 30.0}, "scored_days": 1}
        e = c.get("/month?month=2026-08", headers=H).json()
        assert e["scored_days"] == 0 and e["best_week"] is None and e["effects"] == [] and e["workouts"]["count"] == 0
        assert c.get("/month", headers=H).json()["month"] == date.today().strftime("%Y-%m")
        for bad in ("2026-13", "bogus", "2026-1", "2026-10-01"):
            assert c.get(f"/month?month={bad}", headers=H).status_code == 422, bad


def test_sleep_override_edit_rescore_delete(tmp_path, monkeypatch) -> None:
    """Ring night 23:00-06:20 (420 total, 20 awake, 90 rem, 80 deep) edited to 23:30-05:00; the day before has no night."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "so.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    d = date.today() - timedelta(1)
    m = int(vm.midnight(d).timestamp())
    t0 = m - 3600
    data = {"biometric_sleep_end_ts": {t0: t0 + 440 * 60}, "biometric_sleep_total_min": {t0: 420.0},
            "biometric_sleep_awake_min": {t0: 20.0}, "biometric_sleep_rem_min": {t0: 90.0},
            "biometric_sleep_deep_min": {t0: 80.0}, "biometric_hr_bpm": {t0 + i * 300: 55.0 for i in range(80)}}
    stages = {t0 + 300: 1.0, m - 900: 2.0, m + 6 * 3600: 3.0}  # before / inside / after the edited span

    def series(e: str, s: datetime, en: datetime, st: str, lb: str | None = None) -> dict:
        if e.startswith("last_over_time"):
            return stages  # unfiltered, so /sleep has to clip both ends
        return {t: v for t, v in data.get(e, {}).items() if s.timestamp() <= t <= en.timestamp()}

    monkeypatch.setattr(vm, "series", series)
    monkeypatch.setattr(vm, "forward_import", lambda b: httpx.Response(204))
    monkeypatch.setattr(main, "_vitals", lambda: {})
    main._score_day(d)
    with TestClient(app) as c:
        ring = c.get(f"/sleep?date={d}", headers=H).json()
        assert ring["edited"] is False and ring["session"]["total_min"] == 420
        assert [p["t"] for p in ring["stages"]] == [t0 + 300, m - 900, m + 6 * 3600]
        start, end = m - 1800, m + 5 * 3600
        s = c.patch("/sleep", json={"date": d.isoformat(), "start": start, "end": end}, headers=H).json()
        n = s["session"]
        assert s["edited"] is True and (n["start"], n["end"]) == (start, end)
        assert (n["total_min"], n["awake_min"], n["rem_min"], n["deep_min"]) == (310, 20, 90, 80)
        assert s["score"] != ring["score"] and [p["t"] for p in s["stages"]] == [m - 900]
        assert db.days(d, d)[0]["inputs"]["sleep_min"] == 310
        body = f"biometric_steps 5 {(m + 12 * 3600) * 1000}\n"  # a re-ingest rescores d; the edit holds
        assert c.post("/api/v1/import/prometheus", content=body, headers=H).status_code == 204
        assert c.get(f"/sleep?date={d}", headers=H).json()["session"] == n
        short = c.patch("/sleep", json={"date": d.isoformat(), "start": end - 3600, "end": end}, headers=H).json()
        assert [short["session"][k] for k in ("total_min", "awake_min", "rem_min", "deep_min")] == [40, 20, 60, 60]
        back = c.delete(f"/sleep?date={d}", headers=H).json()
        assert back["edited"] is False and back["session"] == ring["session"] and back["score"] == ring["score"]

        p = d - timedelta(1)  # no ring night: the edit is the whole session
        pm = int(vm.midnight(p).timestamp())
        e = c.patch("/sleep", json={"date": p.isoformat(), "start": pm - 3600, "end": pm + 6 * 3600}, headers=H).json()
        assert e["edited"] is True and e["score"] is not None
        assert [e["session"][k] for k in ("total_min", "awake_min", "rem_min", "deep_min")] == [420, 0, 0, 0]

        for bad in ({"date": "bogus", "start": start, "end": end}, {"start": start, "end": end},
                    {"date": d.isoformat(), "start": "x", "end": end}, {"date": d.isoformat(), "start": True, "end": end},
                    {"date": d.isoformat(), "start": float(start), "end": end},
                    {"date": d.isoformat(), "start": end, "end": end},
                    {"date": d.isoformat(), "start": end - 16 * 3600 - 1, "end": end},
                    {"date": d.isoformat(), "start": start, "end": m + 86400 + 3600}):
            assert c.patch("/sleep", json=bad, headers=H).status_code == 422, bad
        assert c.delete("/sleep?date=bogus", headers=H).status_code == 422
        assert c.delete("/sleep", headers=H).status_code == 422
        assert c.get(f"/sleep?date={d}", headers=H).json()["edited"] is False
