import time
from datetime import date, datetime, timedelta

from fastapi.testclient import TestClient

from app import cardio, db, main, scoring, samples
from app.main import app
from conftest import load

H = {"Authorization": "Bearer secret"}


def test_ingest_rescores_and_today_has_sleep_score(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    d = date.today()
    m = samples.midnight(d).timestamp()
    ss, es, now = m - 1800, m + 7 * 3600, time.time()  # night 23:30 yesterday - 07:00
    hr = {t: 54.0 if t <= es else 70.0 for t in range(int(ss), int(now), 300)}
    load({
        "biometric_sleep_end_ts": {ss: es}, "biometric_sleep_total_min": {ss: 420.0},
        "biometric_sleep_awake_min": {ss: 30.0}, "biometric_sleep_rem_min": {ss: 90.0},
        "biometric_sleep_deep_min": {ss: 80.0}, "biometric_sleep_stage": {ss: 2.0},
        "biometric_hr_bpm": hr, "biometric_hrv_rmssd": {ss + 1800 * i: 45.0 for i in range(60)},
        "biometric_stress": {m + 1800 * i: 80.0 if i < 20 else 30.0 for i in range(48)},
        "biometric_steps": {m + 3600 * h: 500.0 for h in range(8, 21)},
        "biometric_calories": {m + 3600 * h: 90.0 for h in range(8, 21)},
        "biometric_spo2_pct": {ss + 3600: 97.0}, "ring_battery_pct": {now - 60: 80.0}})
    with TestClient(app) as c:
        body = f'biometric_steps{{device="colmi_r02"}} 5 {int(now * 1000)}\n'
        assert c.post("/api/v1/import/prometheus", content=body, headers=H).status_code == 204
        for bad in (b"", b"garbage\n", b"biometric_steps NaN 1\n", b"biometric_steps abc 1\n"):
            assert c.post("/api/v1/import/prometheus", content=bad, headers=H).status_code == 400, bad
        assert c.post("/api/v1/import/prometheus", content=body).status_code == 401
        j = c.get("/today", headers=H).json()
        assert j["sleep"]["score"] is not None and j["status"] == "learning" and j["baseline_days"] == "1/14"
        assert j["activity"]["score"] is not None and j["vitals"]["hr"] == (54 if max(hr) <= es else 70)
        assert j["vitals"]["battery"] == 80
        s = c.get("/sleep", headers=H).json()
        assert s["session"]["total_min"] == 390 and s["stages"]  # 420 span - 30 awake
        t = c.get("/trends?metric=sleep&days=7", headers=H).json()
        assert t["points"][-1]["value"] == j["sleep"]["score"]
        assert c.get("/trends?metric=bogus", headers=H).status_code == 422
        assert db.days(d, d)


def test_hr_drops_nonpositive_and_clamps_hours() -> None:
    now = time.time()
    load({"biometric_hr_bpm": {now - 3600: 0.0, now - 1800: 61.0, now - 47 * 3600: 62.0, now - 50 * 3600: 63.0}})
    with TestClient(app) as c:
        assert [x["bpm"] for x in c.get("/hr?hours=24", headers=H).json()["points"]] == [61.0]
        assert [x["bpm"] for x in c.get("/hr?hours=999", headers=H).json()["points"]] == [62.0, 61.0]  # clamped to 48 h
        assert c.get("/hr").status_code == 401


def test_day_history_routes() -> None:
    d = date(2026, 11, 3)
    m = samples.midnight(d).timestamp()
    nxt = samples.midnight(date.today() + timedelta(1)).timestamp() + 3600
    load({"biometric_hr_bpm": {m: 0.0, m + 1800: 61.0, nxt: 77.0}, "biometric_stress": {m: 0.0, m + 1800: 61.0}})
    db.save_day(d, (81, 72, 65), {"inputs": {"rhr": 52.0, "hrv": 48.0}}, {})
    with TestClient(app) as c:
        assert [x["bpm"] for x in c.get("/hr?date=2026-11-03&hours=1", headers=H).json()["points"]] == [61.0]
        assert c.get(f"/hr?date={date.today() + timedelta(1)}", headers=H).json()["points"] == []  # future day: capped at now
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
    t9 = (samples.midnight(d) + timedelta(hours=9)).timestamp()
    load({"biometric_steps": {t9: 1200.0, t9 + 7200: 50.0}, "biometric_calories": {t9: 80.0},
          "biometric_distance_meters": {t9: 900.0}, "biometric_hr_bpm": {t9 - 3600: 0.0, t9 + 600: 72.0}})
    with TestClient(app) as c:
        h = c.get("/activity?date=2026-11-03", headers=H).json()
        assert h["date"] == "2026-11-03" and len(h["hours"]) == 24
        assert h["hours"][9] == {"h": 9, "steps": 1200.0, "calories": 0.8, "distance_m": 900.0, "worn": True}  # raw / 100 = kcal
        assert h["hours"][8] == {"h": 8, "steps": 0.0, "calories": 0.0, "distance_m": 0.0, "worn": False}
        assert h["hours"][10]["steps"] == 0.0 and h["hours"][11]["steps"] == 50.0  # an empty hour does not repeat the one before
        assert [x["h"] for x in h["hours"] if x["worn"]] == [9]
        assert c.get("/activity?date=bad", headers=H).status_code == 422


def test_night_without_end_ts(monkeypatch) -> None:
    """Sessions pushed by app v1.0 carry only *_min series; the night still resolves."""
    d = date.today()
    t0 = (samples.midnight(d) - timedelta(minutes=30)).timestamp()
    load({"biometric_sleep_total_min": {t0: 420.0}})
    n = main._sessions(d)[0]
    assert n is not None and n["end"] == t0 + 420 * 60 and n["total_min"] == 420 and n["start"] == t0


def test_total_min_is_span_minus_awake() -> None:
    """The app pushes total = end - start, awake included; asleep is what is left."""
    d = date.today() - timedelta(1)
    t0 = samples.midnight(d).timestamp() - 3600
    load({"biometric_sleep_end_ts": {t0: t0 + 480 * 60}, "biometric_sleep_total_min": {t0: 480.0},
          "biometric_sleep_awake_min": {t0: 30.0}})
    main._score_day(d)
    c = db.days(d, d)[0]["c"]
    assert c["inputs"]["sleep_min"] == 450 and c["sleep"]["efficiency"] == scoring._lin(450 / 480, 0.75, 0.95)


def test_pre_midnight_sleep_is_not_awake_time(monkeypatch) -> None:
    """Yesterday 20:00-24:00 worn at 54 bpm, asleep from 23:00 (a session ending today): the last hour is not day time."""
    monkeypatch.delenv("TZ", raising=False)
    d = date.today() - timedelta(1)
    m = samples.midnight(d).timestamp()
    t0 = m + 23 * 3600
    for i in range(1, 15):
        db.save_day(d - timedelta(i), (None, None, None), {"inputs": {"rhr": 50.0}}, {})
    load({"biometric_sleep_end_ts": {t0: t0 + 420 * 60}, "biometric_sleep_total_min": {t0: 420.0},
          "biometric_hr_bpm": {m + 20 * 3600 + i * 300: 54.0 for i in range(48)},
          "biometric_stress": {m + 22 * 3600: 80.0, t0 + 1800: 20.0}, "biometric_steps": {m + 20 * 3600: 10.0}})
    main._score_day(d)
    c = db.days(d, d)[0]["c"]
    assert c["inputs"]["restorative_min"] == 180 and c["wear"] == {"worn_hours": 3, "awake_hours": 23}
    assert c["stress"] == scoring.stress_zones([80.0])
    quiet = scoring.activity_score({"steps": 10.0, "calories": None, "awake_hourly_steps": [10.0, 0.0, 0.0]},
                                   db.setting("step_goal"), None, None)
    assert c["activity"]["inactive_hours"] == quiet["contributors"]["inactive_hours"]


def _strain_day(monkeypatch, d: date, calm: bool = False) -> None:
    """Score d from a faked night: strained = HR falling late to 75, HRV 20 ms, 60 min awake; calm = usual values."""
    t0 = (samples.midnight(d) - timedelta(hours=1)).timestamp()
    hr = [55.0] * 4 if calm else [90.0, 85.0, 80.0, 75.0]
    night = {"biometric_sleep_total_min": {t0: 420.0}, "biometric_sleep_awake_min": {t0: 20.0 if calm else 60.0},
             "biometric_hr_bpm": {t0 + i * 300: v for i, v in enumerate(hr)},
             "biometric_hrv_rmssd": {t0: 50.0 if calm else 20.0}}
    load(night)
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
    m = samples.midnight(d).timestamp()
    t0, nap, short = m - 3600, m + 13 * 3600, m + 16 * 3600
    for i in range(1, 6):  # same 23:00-06:20 nights; 400 min + 30 min naps
        ps = samples.midnight(d - timedelta(i)).timestamp() - 3600
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
    load(data)
    main._score_day(d)
    c = db.days(d, d)[0]["c"]
    i, x = c["inputs"], c["sleep_extra"]
    assert i["sleep_min"] == 400 and i["nap_min"] == 30 and [n["total_min"] for n in x["naps"]] == [30]  # span - awake
    assert c["readiness"]["sleep_balance"] == scoring._lin(430, 240, db.setting("sleep_need_min"))  # 400 + 30 nap
    assert x["regularity"] == {"sd_min": 0, "nights": 6, "bed_start_median": 660, "bed_end_median": 1100}
    assert c["sleep"]["regularity"] == 100
    assert x["spo2"] == {"mean": 91.5, "min": 88.0, "low_count": 1, "n": 2} and i["spo2"] == 88
    assert x["hr_low_t"] == t0 + 600
    assert i["bed_start"] == 660 and i["bed_end"] == 1100  # 23:00 / 06:20 as minutes after noon
    # worn: hours 3, 8, 9; awake = 24 - 7 (night) - 2 (naps); hour 12 has 0 steps but the ring was off
    assert i["worn_hours"] == 3 and c["wear"] == {"worn_hours": 2, "awake_hours": 15}
    assert c["confidence"] == {"sleep": "low", "readiness": "low", "activity": "low"}  # HR in 2 of 8 night hours; 2/15 worn
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
    t0 = samples.midnight(d).timestamp() - 3600
    load({"biometric_sleep_total_min": {t0: 420.0}, "biometric_spo2_pct": {t0: 0.0}})
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
    m = samples.midnight(d).timestamp()
    w0 = m + 10 * 3600
    data = {"biometric_hr_bpm": {m + i * 300: 150.0 if w0 <= m + i * 300 < w0 + 1800 else 54.0 for i in range(288)},
            "biometric_steps": {w0: 200.0}}
    load(data)
    monkeypatch.setattr(main, "_vitals", lambda: {})
    monkeypatch.setattr(main, "rescore", lambda dates: None)  # PATCH /settings would rescore the faked prior days

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
        r = c.patch("/workouts", json={"date": d.isoformat(), "start": int(w0), "kind": "none"}, headers=H)
        assert r.json()["workouts"] == []  # "not a workout" drops it, and it stays dropped across a rescore
        main._score_day(d)
        assert db.days(d, d)[0]["c"]["workouts"] == []

        # entered by hand at 14:00-14:45 (54 bpm measured): listed as measured, relabelled, then removed
        m0 = int(m) + 14 * 3600
        body = {"date": d.isoformat(), "start": m0, "end": m0 + 2700, "kind": "ride"}
        (w,) = c.post("/workouts", json=body, headers=H).json()["workouts"]
        assert w == {"start": m0, "end": m0 + 2700, "minutes": 45, "avg_bpm": 54, "max_bpm": 54.0, "load": 0.0,
                     "kind": "ride", "confirmed": True, "manual": True}
        assert [e["kind"] for e in db.journal(d.isoformat()) if e["date"] == d.isoformat()] == ["workout"]
        for bad in (body | {"end": m0 + 60}, body | {"end": m0 + 13 * 3600}, body | {"kind": "none"},
                    body | {"date": (d - timedelta(1)).isoformat()}, body | {"start": "x"}):
            assert c.post("/workouts", json=bad, headers=H).status_code == 422, bad
        r = c.patch("/workouts", json={"date": d.isoformat(), "start": m0, "kind": "walk"}, headers=H)
        assert [(x["kind"], x["manual"]) for x in r.json()["workouts"]] == [("walk", True)]
        # one that covers the detected 10:00-10:30 session replaces it (the "none" label on it is lifted first)
        db.set_workout_kind(d, int(w0), "ride")
        r = c.post("/workouts", json=body | {"start": int(w0) - 600, "end": int(w0) + 2400}, headers=H)
        assert [(x["start"], x.get("manual")) for x in r.json()["workouts"]] == [(int(w0) - 600, True), (m0, True)]
        for s in (int(w0) - 600, m0):
            r = c.patch("/workouts", json={"date": d.isoformat(), "start": s, "kind": "none"}, headers=H)
        assert [(x["start"], x.get("manual")) for x in r.json()["workouts"]] == [(int(w0), None)]  # the detected one is back
        assert c.patch("/settings", json={"birth_year": ""}, headers=H).json()["birth_year"] == ""


def test_sleep_debt_and_bedtime_fields(tmp_path, monkeypatch) -> None:
    """14 nights to today: even days 22:00 (quality 90), odd days 23:30 (60); last 3 nights 400 / 420+30 nap / 470."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "f2.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    monkeypatch.setattr(main, "_vitals", lambda: {})
    d = date.today()
    asleep = {0: (400.0, 0.0), 1: (420.0, 30.0), 2: (470.0, None)}
    for i in range(14):
        q = 90.0 if i % 2 == 0 else 60.0
        s = (samples.midnight(d - timedelta(i + 1)) + timedelta(hours=22, minutes=0 if i % 2 == 0 else 90)).timestamp()
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
        assert c.get("/today", headers=H).json()["tonight"] == {"bedtime": window, "debt": debt, "plan": None,
                                                                "wind_down_usual": None, "what_if": None}
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
        s = samples.midnight(d - timedelta(1)) + timedelta(hours=23)
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
    m = int(samples.midnight(d).timestamp())
    t0 = m - 3600
    data = {"biometric_sleep_end_ts": {t0: t0 + 440 * 60}, "biometric_sleep_total_min": {t0: 420.0},
            "biometric_sleep_awake_min": {t0: 20.0}, "biometric_sleep_rem_min": {t0: 90.0},
            "biometric_sleep_deep_min": {t0: 80.0}, "biometric_hr_bpm": {t0 + i * 300: 55.0 for i in range(80)}}
    stages = {t0 + 300: 1.0, m - 900: 2.0, m + 6 * 3600: 3.0}  # a stage carries on the 5 min grid for up to 3 h

    load({**data, "biometric_sleep_stage": stages})
    monkeypatch.setattr(main, "_vitals", lambda: {})
    main._score_day(d)
    with TestClient(app) as c:
        ring = c.get(f"/sleep?date={d}", headers=H).json()
        assert ring["edited"] is False and ring["session"]["total_min"] == 400
        assert ring["stages"][0] == {"t": t0 + 300, "stage": 1} and {p["stage"] for p in ring["stages"]} == {1, 2, 3}
        start, end = m - 1800, m + 5 * 3600
        s = c.patch("/sleep", json={"date": d.isoformat(), "start": start, "end": end}, headers=H).json()
        n = s["session"]
        assert s["edited"] is True and (n["start"], n["end"]) == (start, end)
        assert (n["total_min"], n["awake_min"], n["rem_min"], n["deep_min"]) == (310, 20, 90, 80)
        assert s["score"] != ring["score"] and {p["stage"] for p in s["stages"]} == {1, 2} and all(start <= p["t"] < end for p in s["stages"])
        assert db.days(d, d)[0]["inputs"]["sleep_min"] == 310
        body = f"biometric_steps 5 {(m + 12 * 3600) * 1000}\n"  # a re-ingest rescores d; the edit holds
        assert c.post("/api/v1/import/prometheus", content=body, headers=H).status_code == 204
        assert c.get(f"/sleep?date={d}", headers=H).json()["session"] == n
        short = c.patch("/sleep", json={"date": d.isoformat(), "start": end - 3600, "end": end}, headers=H).json()
        assert [short["session"][k] for k in ("total_min", "awake_min", "rem_min", "deep_min")] == [40, 20, 60, 60]
        back = c.delete(f"/sleep?date={d}", headers=H).json()
        assert back["edited"] is False and back["session"] == ring["session"] and back["score"] == ring["score"]

        p = d - timedelta(1)  # no ring night: the edit is the whole session
        pm = int(samples.midnight(p).timestamp())
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


def test_sleep_override_drops_sessions_inside_its_span(tmp_path, monkeypatch) -> None:
    """Ring sessions 23:00-03:00 and 03:20-07:00 edited to 23:00-07:00: the second is not a nap; a 13:00 nap stays."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "span.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    d = date.today() - timedelta(1)
    m = int(samples.midnight(d).timestamp())
    a, b, nap = m - 3600, m + 200 * 60, m + 13 * 3600
    load({"biometric_sleep_end_ts": {a: a + 240 * 60, b: b + 220 * 60},
          "biometric_sleep_total_min": {a: 240.0, b: 220.0}})
    monkeypatch.setattr(main, "_vitals", lambda: {})
    db.set_sleep_override(d, a, m + 7 * 3600)
    main._score_day(d)
    i = db.days(d, d)[0]["inputs"]
    assert (i["sleep_min"], i["nap_min"]) == (480, 0)
    load({"biometric_sleep_end_ts": {nap: nap + 1800}, "biometric_sleep_total_min": {nap: 30.0}})
    main._score_day(d)
    i = db.days(d, d)[0]["inputs"]
    assert (i["sleep_min"], i["nap_min"]) == (480, 30)


def test_breathe_sessions_journal_hr_today(tmp_path, monkeypatch) -> None:
    import sqlite3
    monkeypatch.delenv("TZ", raising=False)
    monkeypatch.setenv("DB_PATH", str(tmp_path / "b.db"))
    with sqlite3.connect(tmp_path / "b.db") as con:  # pre-G1 database: old journal index
        con.executescript("CREATE TABLE journal(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, kind TEXT, value TEXT,"
                          " note TEXT, ts INTEGER); CREATE UNIQUE INDEX journal_day_kind ON journal(date, kind)"
                          " WHERE kind != 'medication';")

    d = date.today() - timedelta(1)
    s1 = int((samples.midnight(d) + timedelta(hours=10)).timestamp())
    s2 = s1 + 4 * 3600
    body = {"start": s1, "end": s1 + 600, "rounds": 4, "feel_before": 2, "feel_after": None,
            "samples": [[s1 + 10, 72], [s1 + 20, 65], [s1 + 30, 68]]}
    with TestClient(app) as c:
        load({"biometric_stress": {s1 - 3000: 60.0, s1 + 3600: 40.0}})  # 50 min before / 60 min after the session
        assert "breathe" not in c.get(f"/today?date={d}", headers=H).json()  # never a session yet
        for bad in ({"end": s1 + 3601}, {"end": s1}, {"rounds": 0}, {"feel_after": 6}, {"samples": [[s1 + 10, 300]]},
                    {"samples": [[s1 - 10, 70]]}, {"start": "x"}):
            assert c.post("/breathe", json={**body, **bad}, headers=H).status_code == 422, bad
        r = c.post("/breathe", json=body, headers=H)
        assert r.status_code == 200
        b = r.json()
        assert (b["hr_start"], b["hr_end"], b["hr_min"], b["rounds"], b["feel_before"]) == (72, 68, 65, 4, 2)
        assert (b["stress_before"], b["stress_after"]) == (60, 40)
        assert b["points"] == [{"t": s1 + 10, "bpm": 72.0}, {"t": s1 + 20, "bpm": 65.0}, {"t": s1 + 30, "bpm": 68.0}]
        c.post("/breathe", json=body, headers=H)  # re-sent: still one journal row
        c.post("/breathe", json={**body, "start": s2, "end": s2 + 600, "samples": []}, headers=H)
        j = [e for e in c.get("/journal", headers=H).json()["entries"] if e["kind"] == "breathe"]
        assert [(e["date"], e["value"]) for e in j] == [(d.isoformat(), "14:00"), (d.isoformat(), "10:00")]
        assert c.post("/journal", json={"date": d.isoformat(), "kind": "breathe"}, headers=H).status_code == 422
        br = c.get(f"/hr?date={d}", headers=H).json()["breaths"]
        assert [x["start"] for x in br] == [s1, s2] and br[1]["hr_start"] is None and br[1]["points"] == []
        t = c.get(f"/today?date={d}", headers=H).json()
        assert t["breathe"] == {"today_min": 20.0, "week_min": 20.0, "sessions": 2} and t["inputs"]["breath_min"] == 20.0
        assert c.get("/today", headers=H).json()["breathe"]["today_min"] == 0.0
        assert c.get("/trends?metric=breath_min", headers=H).status_code == 200


def test_experiments_start_422_409_stop() -> None:
    with TestClient(app) as c:
        today = main._today().isoformat()
        assert c.get("/experiments").status_code == 401
        assert c.get("/experiments", headers=H).json() == {"active": None, "past": []}
        for bad in ({"kind": "sick"}, {"kind": None}, {"metric": "steps"}, {"days": 13}, {"days": 8}, {"days": 30},
                    {"days": "14"}):
            r = c.post("/experiments", json={"kind": "alcohol", "metric": "deep_min", **bad}, headers=H)
            assert r.status_code == 422, bad
        r = c.post("/experiments", json={"kind": "caffeine_late", "metric": "deep_min"}, headers=H)
        assert r.status_code == 200
        a = r.json()["active"]
        assert (a["kind"], a["metric"], a["start"], a["days"], a["ended"]) == ("caffeine_late", "deep_min", today, 14, None)
        assert len(a["plan"]) == 14 and a["plan"].count("1") == 7
        assert (a["status"], a["day"], a["kept"], a["skipped"]) == ("running", 1, [], [])
        assert a["today"] == ("on" if a["plan"][0] == "1" else "off")
        assert a["result"] == {"status": "learning", "need": 3, "n_with": 0, "n_without": 0}
        assert c.post("/experiments", json={"kind": "alcohol", "metric": "hrv", "days": 10}, headers=H).status_code == 409
        assert c.delete(f"/experiments/{a['id']}").status_code == 401
        j = c.delete(f"/experiments/{a['id']}", headers=H).json()
        assert j["active"] is None and [(p["id"], p["ended"]) for p in j["past"]] == [(a["id"], today)]
        j = c.post("/experiments", json={"kind": "alcohol", "metric": "hrv", "days": 10}, headers=H).json()  # stopped: free
        assert j["active"]["days"] == 10 and [p["id"] for p in j["past"]] == [a["id"]]  # ended, never deleted


def test_calm_setting_round_trip_and_today() -> None:
    with TestClient(app) as c:
        assert c.get("/settings", headers=H).json()["calm"] == "" and c.get("/today", headers=H).json()["calm"] is False
        for bad in ({"calm": 1}, {"calm": True}, {"calm": "0"}, {"calm": "yes"}, {"calm": None}):
            assert c.patch("/settings", json=bad, headers=H).status_code == 422, bad
        assert c.patch("/settings", json={"calm": "1"}, headers=H).json()["calm"] == "1"
        assert c.get("/settings", headers=H).json()["calm"] == "1" and c.get("/today", headers=H).json()["calm"] is True
        assert c.patch("/settings", json={"calm": ""}, headers=H).json()["calm"] == ""
        assert c.get("/today", headers=H).json()["calm"] is False


def test_confidence_saved_and_passed_through_today(monkeypatch) -> None:
    """Yesterday (all 24 h past): 23:00-07:00 night, ring worn all night and all day, no baselines yet."""
    monkeypatch.delenv("TZ", raising=False)
    d = date.today() - timedelta(1)
    m = samples.midnight(d).timestamp()
    ss, es = m - 3600, m + 7 * 3600
    load({"biometric_sleep_end_ts": {ss: es}, "biometric_sleep_total_min": {ss: 450.0},
          "biometric_sleep_awake_min": {ss: 30.0}, "biometric_sleep_rem_min": {ss: 90.0},
          "biometric_sleep_deep_min": {ss: 80.0},
          "biometric_hr_bpm": {t: 54.0 if t < es else 70.0 for t in range(int(ss), int(m) + 86400, 300)},
          "biometric_steps": {m + 3600 * h: 500.0 for h in range(8, 21)}})
    main._score_day(d)
    row = db.days(d, d)[0]
    # sleep 6/7 contributors (regularity learning); readiness 2/7 (no baselines); activity 2/4 (no week of history)
    want = {"sleep": "high", "readiness": "low", "activity": "medium"}
    assert row["c"]["confidence"] == want and row["c"]["wear"] == {"worn_hours": 17, "awake_hours": 17}
    db.save_day(d - timedelta(3), (81, 72, 65), {}, {})  # a day scored before confidence existed
    db.save_day(d - timedelta(2), (None, None, 40), {"confidence": {"sleep": None, "readiness": None, "activity": "low"}}, {})
    with TestClient(app) as c:
        assert c.get(f"/today?date={d}", headers=H).json()["confidence"] == want
        assert c.get(f"/today?date={d - timedelta(3)}", headers=H).json()["confidence"] is None
        assert c.get(f"/today?date={d - timedelta(2)}", headers=H).json()["confidence"]["sleep"] is None


def test_today_what_if_from_history(monkeypatch) -> None:
    """21 days to today, 450 min nights: odd days back are hard (load 20), even ones easy (5); readiness is 60 the
    day after a hard day and 80 after an easy one. 14-day mean load 12.5 -> a hard day starts at round(1.1 x 12.5) = 14."""
    monkeypatch.setattr(main, "_vitals", lambda: {})
    d = date.today()
    with TestClient(app) as c:
        assert c.get("/today", headers=H).json()["tonight"]["what_if"] is None  # no cardio baseline yet
        for i in range(21):
            db.save_day(d - timedelta(i), (None, 60 if i % 2 == 0 else 80, None),
                        {"inputs": {"cardio_load": 20.0 if i % 2 else 5.0, "sleep_min": 450.0}}, {})
        w = c.get("/today", headers=H).json()["tonight"]["what_if"]
        assert w == {"rest": {"full": {"median": 80, "lo": 80, "hi": 80, "n": 10}, "short": None},
                     "train": {"full": {"median": 60, "lo": 60, "hi": 60, "n": 10}, "short": None}}
        assert c.get(f"/today?date={d - timedelta(30)}", headers=H).json()["tonight"]["what_if"] == w  # tonight is always today's


def test_export_csv_rows_bounds_auth() -> None:
    d = date.today()
    db.save_day(d - timedelta(1), (81, 72, 65), {
        "inputs": {"sleep_min": 412.0, "nap_min": 0, "rhr": 52.0, "hrv": 47.26666, "spo2": 93.0, "steps": 6500.0,
                   "cardio_load": 9.0, "stress_load": 1.5},
        "sleep_extra": {"spo2": {"mean": 95.0, "min": 88.0, "low_count": 1, "n": 6}}}, {})
    db.save_day(d, (None, None, 40), {"inputs": {"spo2": 93.0, "steps": 100.0}}, {})  # scored before sleep_extra.spo2.min
    y = (d - timedelta(1)).isoformat()
    db.add_journal(y, "bp", "150/95", None, 1)
    db.add_journal(y, "bp", "128/82", None, 2)  # the day's later reading replaces the first
    db.add_journal(y, "alcohol", None, "two, with dinner", 3)  # notes stay out of the file
    db.add_journal((d - timedelta(5)).isoformat(), "sick", None, None, 4)  # outside days=3
    with TestClient(app) as c:
        r = c.get("/export.csv?days=3", headers=H)
        assert r.status_code == 200 and r.headers["content-type"].startswith("text/csv")
        lines = r.text.splitlines()
        assert lines == [
            "date,sleep_score,readiness_score,activity_score,sleep_min,nap_min,rhr,hrv,spo2_min,steps,cardio_load,"
            "stress_load,bp_sys,bp_dia,journal",
            f"{d - timedelta(2)},,,,,,,,,,,,,,",
            f"{y},81,72,65,412.0,0,52.0,47.3,88.0,6500.0,9.0,1.5,128,82,alcohol;bp",
            f"{d},,,40,,,,,93.0,100.0,,,,,"]
        assert len(c.get("/export.csv", headers=H).text.splitlines()) == 91  # default 90 days + header
        assert len(c.get("/export.csv?days=365", headers=H).text.splitlines()) == 366
        for bad in (0, 366, "x"):
            assert c.get(f"/export.csv?days={bad}", headers=H).status_code == 422, bad
        assert c.get("/export.csv").status_code == 401


def test_dates_in_body_skips_short_and_out_of_range_lines() -> None:
    ms = int(time.time() * 1000)
    assert samples.dates_in_body(f"\na 5\nb 1 {10**30}\nc 1 {ms}\n".encode()) == {date.today()}


def test_settings_goal_change_rescores_last_14_days(monkeypatch) -> None:
    seen: list[set[date]] = []
    monkeypatch.setattr(main, "rescore", seen.append)
    with TestClient(app) as c:
        c.patch("/settings", json={"step_goal": 9000, "calm": "1"}, headers=H)
        c.patch("/settings", json={"step_goal": 9000.0}, headers=H)  # same value: no rescore
        c.patch("/settings", json={"calm": ""}, headers=H)  # not a scoring key
    assert seen == [{date.today() - timedelta(i) for i in range(14)}]


def test_rescore_holds_the_lock_while_scoring(monkeypatch) -> None:
    seen: list[bool] = []
    monkeypatch.setattr(main, "_score_day", lambda d: seen.append(main.RESCORE_LOCK.locked()))
    main.rescore({date.today()})
    assert seen == [True] and not main.RESCORE_LOCK.locked()


def test_month_unknown_workout_and_journal_kind() -> None:
    db.save_day(date(2026, 10, 2), (None, None, None), {"workouts": [{"minutes": 30, "load": 2.0, "kind": "run"}]}, {})
    db.add_journal("2026-10-02", "bogus", None, None, 0)
    with TestClient(app) as c:
        r = c.get("/month?month=2026-10", headers=H)
        assert r.status_code == 200 and r.json()["workouts"]["count"] == 1


def test_non_ascii_token_is_401() -> None:
    with TestClient(app) as c:
        assert c.get("/settings", headers={"Authorization": "Bearer s\xe9cret".encode("latin-1")}).status_code == 401


def test_out_of_range_sleep_edit_and_month_422() -> None:
    with TestClient(app) as c:
        for s, e in ((10**30 - 3600, 10**30), (-10**12 - 3600, -10**12)):
            assert c.patch("/sleep", json={"date": "2026-11-03", "start": s, "end": e}, headers=H).status_code == 422, e
        for bad in ("0001-01", "9999-12"):
            assert c.get(f"/month?month={bad}", headers=H).status_code == 422, bad


def test_huge_days_is_clamped_and_out_of_range_dates_422() -> None:
    with TestClient(app, raise_server_exceptions=False) as c:
        t = c.get("/trends?metric=sleep&days=99999999999", headers=H)
        assert t.status_code == 200 and len(t.json()["points"]) <= 365
        for url in ("/today?date=", "/sleep?date=", "/week?end="):
            for bad in ("0001-01-01", "9999-12-31"):
                assert c.get(url + bad, headers=H).status_code == 422, url + bad


def test_medication_started_needs_dashed_date() -> None:
    with TestClient(app) as c:
        assert c.patch("/settings", json={"medication_started": "20261101"}, headers=H).status_code == 422
        assert c.patch("/settings", json={"medication_started": "2026-11-01"}, headers=H).status_code == 200


def test_vitals_drop_nonpositive() -> None:
    now = time.time()
    load({"biometric_hr_bpm": {now - 120: 61.0, now - 60: 0.0}})
    assert main._vitals()["hr"] == 61.0


def test_phone_in_bed_row_keeps_its_id_across_rescores() -> None:
    d = date.today()
    ss = samples.midnight(d).timestamp() - 1800
    load({"biometric_sleep_end_ts": {ss: ss + 27000}, "biometric_sleep_total_min": {ss: 450.0},
          "biometric_sleep_stage": {ss: 2.0}, "biometric_screen_on": {ss - 600: 1.0, ss + 1200: 0.0}})
    ids = []
    for _ in range(2):
        main._score_day(d)
        ids.append([e["id"] for e in db.journal((d - timedelta(1)).isoformat()) if e["kind"] == "phone_in_bed"])
    assert len(ids[0]) == 1 and ids[0] == ids[1]


def test_hourly_sums_the_repeated_hour_on_dst_fall_back(monkeypatch) -> None:
    monkeypatch.setenv("TZ", "Europe/Berlin")
    d = date(2026, 10, 25)  # 02:00 happens twice (CEST, then CET)
    m = samples.midnight(d).timestamp()
    load({"biometric_steps": {m + 2 * 3600: 100.0, m + 3 * 3600: 200.0, m + 4 * 3600: 50.0}})
    assert main._hourly("biometric_steps", d) == {2: 300.0, 3: 50.0}


def test_guess_pending_first_answer_stands_and_verdict() -> None:
    with TestClient(app) as c:
        d = main._today()
        day, post = d.isoformat(), lambda url, **body: c.post(url, json=body, headers=H)
        guess = lambda: c.get("/today", headers=H).json()["guess"]
        assert guess() == {"on": False, "pending": False, "value": None, "verdict": None,
                           "stats": {"status": "learning", "n": 0, "need": 5}, "days": []}
        c.patch("/settings", json={"guess": "1"}, headers=H)
        assert guess()["on"] is True and guess()["pending"] is False  # no readiness score yet
        db.save_day(d, (80, 72, 60), {}, {})
        assert guess()["pending"] is True
        assert c.post("/guess", json={"date": day, "value": 60}).status_code == 401
        for bad in ({"date": (d - timedelta(1)).isoformat()}, {"date": None}, {"value": 101}, {"value": -1},
                    {"value": "60"}, {"value": 60.5}, {"value": True}):
            assert post("/guess", **{"date": day, "value": 60, **bad}).status_code == 422, bad
        assert post("/guess/verdict", date=day, verdict=1).status_code == 422  # no guess yet
        j = post("/guess", date=day, value=60).json()
        assert j["date"] == day and j["readiness"]["score"] == 72
        assert j["guess"] == {"on": True, "pending": False, "value": 60, "verdict": None,
                              "stats": {"status": "learning", "n": 1, "need": 4}, "days": [[60, 72]]}
        assert post("/guess", date=day, value=90).json()["guess"]["value"] == 60  # no second guess after the reveal
        assert post("/guess", date=day, value=None).json()["guess"]["value"] == 60
        assert db.days(d, d)[0]["felt"] == 60 and "felt" not in db.query("SELECT contributors FROM daily")[0][0]
        for bad in ({"date": (d - timedelta(1)).isoformat()}, {"verdict": 3}, {"verdict": -1}, {"verdict": "1"},
                    {"verdict": None}, {"verdict": True}):
            assert post("/guess/verdict", **{"date": day, "verdict": 1, **bad}).status_code == 422, bad
        assert post("/guess/verdict", date=day, verdict=2).json()["guess"]["verdict"] == 2
        assert c.get(f"/today?date={d - timedelta(1)}", headers=H).json()["guess"]["value"] is None
        assert post("/experiments", kind="alcohol", metric="felt").status_code == 200


def test_guess_skip_stores_null() -> None:
    with TestClient(app) as c:
        d = main._today()
        db.set_settings({"guess": "1"})
        db.save_day(d, (80, 72, 60), {}, {})
        post = lambda url, **body: c.post(url, json={"date": d.isoformat(), **body}, headers=H)
        g = post("/guess", value=None).json()["guess"]
        assert (g["pending"], g["value"], g["days"], g["stats"]["n"]) == (False, None, [], 0)
        assert [tuple(r) for r in db.query("SELECT date, value, verdict FROM guess")] == [(d.isoformat(), None, None)]
        assert post("/guess", value=70).json()["guess"]["value"] is None  # the skip is the day's answer
        assert post("/guess/verdict", verdict=1).status_code == 422  # nothing to judge
        assert db.days(d, d)[0]["felt"] is None


def test_guess_stats_and_days_in_today() -> None:
    with TestClient(app) as c:
        d = main._today()
        for i in range(20):  # 20 guessed mornings, each over the score: 14 pairs shown, all 20 counted
            db.save_day(d - timedelta(i), (None, 70, None), {}, {})
            db.add_guess(d - timedelta(i), 80 + i, i)
        db.add_guess(d - timedelta(25), 50, 0)  # no readiness that morning: not a pair
        g = c.get("/today", headers=H).json()["guess"]
        assert g["days"] == [[80 + i, 70] for i in range(13, -1, -1)]  # oldest first
        assert (g["stats"]["n"], g["stats"]["bias"], g["stats"]["same"]) == (20, 19.5, 5)


def test_guess_setting_round_trip() -> None:
    with TestClient(app) as c:
        assert c.get("/settings", headers=H).json()["guess"] == ""
        for bad in ({"guess": 1}, {"guess": True}, {"guess": "0"}, {"guess": None}):
            assert c.patch("/settings", json=bad, headers=H).status_code == 422, bad
        assert c.patch("/settings", json={"guess": "1"}, headers=H).json()["guess"] == "1"
        assert c.get("/settings", headers=H).json()["guess"] == "1" and c.get("/today", headers=H).json()["guess"]["on"] is True
        assert c.patch("/settings", json={"guess": ""}, headers=H).json()["guess"] == ""
        assert c.get("/today", headers=H).json()["guess"]["on"] is False


def test_today_drive_today_only() -> None:
    with TestClient(app) as c:
        d, now = main._today(), time.time()
        drive = lambda day="": c.get(f"/today{day}", headers=H).json()["drive"]
        assert drive() is None  # no stored session: no data is not no sleep
        night = {"start": now - 11 * 3600, "end": now - 3 * 3600, "total_min": 450.0}
        nap = {"start": now - 20 * 3600, "end": now - 19.5 * 3600, "total_min": 30.0}
        db.save_day(d, (None, None, None), {"session": night}, {})
        db.save_day(d - timedelta(1), (None, None, None), {"sleep_extra": {"naps": [nap]}}, {})
        load({"biometric_hr_bpm": {now - 7200: 60.0, now - 3600: 0.0}})  # 0 = off the finger
        j = drive()
        assert (j["wake"], j["sleep_min"], j["worn"]) == (night["end"], 480, False) and now <= j["as_of"]
        load({"biometric_hr_bpm": {now - 3600: 61.0}})
        assert drive()["worn"] is True
        assert drive(f"?date={d - timedelta(1)}") is None
