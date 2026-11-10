from datetime import date, timedelta

from fastapi.testclient import TestClient

from app import db, insights, main, notify, samples
from app.main import app
from conftest import load

H = {"Authorization": "Bearer secret"}
TODAY = date(2026, 11, 8)


def row(d: date, hrv: float = 50, deep: float = 80, strain: int | None = 10, bed_min: int = -30,
        total: float = 420) -> dict:
    start = (samples.midnight(d) + timedelta(minutes=bed_min)).timestamp()
    return {"date": d, "sleep_score": 80, "inputs": {"rhr": 55, "hrv": hrv},
            "c": {"session": {"start": start, "end": start + 8 * 3600, "total_min": total, "awake_min": 30,
                              "rem_min": 90, "deep_min": deep}, "signals": {"index": strain}}}


def entry(d: date, kind: str, value: str | None = None) -> dict:
    return {"date": d.isoformat(), "kind": kind, "value": value, "ts": 0}


def test_effect() -> None:
    e = insights.effect([10, 10, 10], [20, 21, 19, 20])
    assert e["delta"] == -10 and e["pct"] == -50 and e["strong"] and e["n_with"] == 3 and e["n_without"] == 4
    assert not insights.effect([19, 21, 20], [10, 20, 30])["strong"]  # delta 0 < 0.5 x 14.8
    assert not insights.effect([19, 19, 19], [20, 20, 20], floor=5.0)["strong"]
    assert insights.effect([1, 2], [1, 2, 3]) is None


def test_bed_start_floor_not_strong() -> None:
    e = insights.effect([-28.0] * 3, [-30.0] * 5, insights.FLOORS["bed_start"])
    assert e["delta"] == 2.0 and not e["strong"]


def test_night_kind_pairs_with_next_night_and_learning() -> None:
    alc = [TODAY - timedelta(i) for i in (3, 6, 9)]
    rows = [row(TODAY - timedelta(i), hrv=30 if TODAY - timedelta(i + 1) in alc else 50,
                bed_min=30 if TODAY - timedelta(i + 1) in alc else -30) for i in range(20)]
    k = insights.kinds(rows, [entry(d, "alcohol") for d in alc] + [entry(alc[0], "workout")], TODAY, {}, "", [])
    a = k["alcohol"]
    assert a["status"] == "ok" and a["n_with"] == 3 and a["n_without"] == 17  # 20 nights with a next row
    m = {e["metric"]: e for e in a["all"]}
    assert m["hrv"]["delta"] == -20 and m["hrv"]["pct"] == -40 and m["hrv"]["unit"] == "ms"
    assert m["bed_start"]["delta"] == 60 and m["bed_start"]["pct"] is None
    assert m["deep_min"]["delta"] == 0 and not m["deep_min"]["strong"]
    assert {e["metric"] for e in a["top"]} == {"hrv", "bed_start"}
    assert k["workout"] == {"status": "learning", "need": 2, "n_with": 1, "n_without": 19}
    assert k["caffeine_late"]["need"] == 3


def test_sick_strain_window() -> None:
    sick = [TODAY - timedelta(i) for i in (5, 10, 15)]
    near = {s + timedelta(j) for s in sick for j in (-1, 0, 1)}
    rows = [row(TODAY - timedelta(i), strain=60 if TODAY - timedelta(i) in near else 10) for i in range(30)]
    s = insights.kinds(rows, [entry(d, "sick") for d in sick], TODAY, {}, "", [])["sick"]
    e = s["all"][0]
    assert s["status"] == "ok" and e["metric"] == "strain" and e["with"] == 60 and e["without"] == 10 and e["strong"]


def test_medication_dose_vs_none_and_dose_time() -> None:
    start = TODAY - timedelta(20)
    dose = {TODAY - timedelta(i): t for i, t in ((2, "08:00"), (4, "08:30"), (6, "09:00"),
                                                  (8, "11:00"), (10, "12:00"), (12, "12:30"))}
    late = {d for d, t in dose.items() if t >= "11:00"}
    rows = [row(TODAY - timedelta(i), bed_min=60 if TODAY - timedelta(i + 1) in late else 0) for i in range(30)]
    hr = {TODAY - timedelta(i): 80.0 if TODAY - timedelta(i) in dose else 70.0 for i in range(30)}
    entries = [entry(d, "medication", t) for d, t in dose.items()] + [entry(TODAY - timedelta(2), "medication", "20:00")]
    m = insights.kinds(rows, entries, TODAY, hr, start.isoformat(), [])["medication"]
    e = {x["metric"]: x for x in m["all"]}
    assert m["n_with"] == 6 and m["n_without"] == 14  # days before medication_started are excluded
    assert e["day_hr"]["delta"] == 10 and e["day_hr"]["unit"] == "bpm"
    bt = m["by_time"]
    assert bt["split"] == "10:00" and bt["n_with"] == 3 and bt["n_without"] == 3  # first dose of the day counts
    assert {x["metric"]: x["delta"] for x in bt["all"]}["bed_start"] == 60
    lone = insights.kinds(rows, entries[:2], TODAY, hr, start.isoformat(), [])["medication"]
    assert lone["status"] == "learning" and lone["by_time"]["status"] == "learning"


def test_routes(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "i.db"))
    with TestClient(app) as c:
        d = main._today()
        for i in range(10):
            r = row(d - timedelta(i))
            db.save_day(r["date"], (80, 70, 60), {**r["c"], "inputs": r["inputs"]}, {})
        for i in (2, 4, 6):
            db.add_journal((d - timedelta(i)).isoformat(), "alcohol", None, None, i)
        db.add_journal(d.isoformat(), "medication", "08:00", None, 1)
        db.add_journal((d - timedelta(1)).isoformat(), "bp", "128/82", None, 1)
        db.add_journal(d.isoformat(), "bp", "120/80", None, 2)
        assert c.get("/journal/insights").status_code == 401
        k = c.get("/journal/insights", headers=H).json()["kinds"]
        assert set(k) == set(insights.NIGHT_KINDS) | {"sick", "medication"}
        assert k["alcohol"]["status"] == "ok" and {"metric", "delta", "pct", "unit", "strong"} <= set(k["alcohol"]["all"][0])
        assert k["medication"]["status"] == "learning" and "by_time" in k["medication"]
        bp = c.get("/journal/bp?days=30", headers=H).json()["points"]
        assert [(p["sys"], p["dia"]) for p in bp] == [(128, 82), (120, 80)]
        assert len(c.get("/journal/bp?days=1", headers=H).json()["points"]) == 1


def test_breathe_stress_delta_needs_three() -> None:
    br = [{"stress_before": 60, "stress_after": a} for a in (40, 50, 45)] + [{"stress_before": 60, "stress_after": None}]
    assert insights.kinds([], [], TODAY, {}, "", br)["breathe"]["stress"] == {"delta": -15.0, "n": 3}
    assert insights.kinds([], [], TODAY, {}, "", br[1:])["breathe"]["stress"] is None


def test_plan_balanced() -> None:
    import random
    for days in (10, 14, 28):
        p = insights.plan(days, random.Random(1))
        assert len(p) == days and p.count("1") == p.count("0") == days // 2
    assert insights.plan(14, random.Random(1)) == insights.plan(14, random.Random(1)) != "10" * 7  # shuffled, by the rng


def test_experiment_skips_non_compliant_days_and_verdict_is_compare() -> None:
    start, plan = TODAY - timedelta(10), "1010101010"
    night = lambda j: start + timedelta(j + 1)  # the night after day j
    nt = insights.nights([row(night(j), deep=60 if j % 2 == 0 else 80) for j in range(10) if j != 7])
    logged = {start + timedelta(j) for j in (0, 2, 3, 4, 6)}  # day 3: done on a skip day; day 8: not done on a do day
    e = insights.experiment(plan, start, nt, logged, TODAY, "deep_min")
    assert (e["status"], e["day"], e["days"], e["today"]) == ("done", 10, 10, None)
    assert e["kept"] == [0, 1, 2, 4, 5, 6, 9] and e["skipped"] == [3, 7, 8]  # day 7: no night
    assert e["result"] == insights.compare([nt[night(j)] for j in (0, 2, 4, 6)], [nt[night(j)] for j in (1, 5, 9)],
                                           ("deep_min",))
    assert e["result"]["status"] == "ok" and e["result"]["all"][0]["delta"] == -20
    r = insights.experiment(plan, start, nt, logged, start + timedelta(4), "deep_min")
    assert (r["status"], r["day"], r["today"], r["kept"], r["skipped"]) == ("running", 5, "on", [0, 1, 2], [3])
    assert r["result"] == {"status": "learning", "need": 2, "n_with": 2, "n_without": 1}
    assert insights.experiment(plan, start, nt, logged, start + timedelta(5), "deep_min")["today"] == "off"


def test_experiment_route_done_counts_journal_and_nights() -> None:
    with TestClient(app) as c:
        d = main._today()
        start = d - timedelta(10)
        db.add_experiment("alcohol", "deep_min", start.isoformat(), 10, "1010101010")
        for j in range(10):
            r = row(start + timedelta(j + 1), deep=60 if j % 2 == 0 else 80)
            db.save_day(r["date"], (80, 70, 60), {**r["c"], "inputs": r["inputs"]}, {})
        for j in (0, 2, 4, 6, 8, 9):  # day 9: alcohol on a skip day
            db.add_journal((start + timedelta(j)).isoformat(), "alcohol", None, None, j)
        db.add_journal(start.isoformat(), "workout", None, None, 1)  # another kind does not count
        a = c.get("/experiments", headers=H).json()["active"]
        assert (a["status"], a["today"], a["kept"], a["skipped"]) == ("done", None, list(range(9)), [9])
        assert a["result"]["status"] == "ok" and a["result"]["all"][0]["delta"] == -20
        # a finished one does not block the next; it is ended, not deleted
        j = c.post("/experiments", json={"kind": "workout", "metric": "hrv", "days": 10}, headers=H).json()
        assert j["active"]["kind"] == "workout" and [(p["kind"], p["ended"], p["status"]) for p in j["past"]] == [
            ("alcohol", d.isoformat(), "done")]


def test_lead_time_median_over_episodes_none_under_two() -> None:
    a, b, c = (TODAY - timedelta(i) for i in (40, 20, 5))
    strain = {a - timedelta(i): 30 for i in range(1, 11)} | {b - timedelta(i): 25 for i in (1, 2, 3)} | {
        b - timedelta(4): 24, c - timedelta(1): 40, c - timedelta(2): None, c - timedelta(3): 40}
    assert insights.lead_time({a, a + timedelta(1), b, c}, strain) == {"days": 3, "n": 3}  # 7 (cap), 3, 1
    assert insights.lead_time({b, c}, strain) == {"days": 2, "n": 2}
    assert insights.lead_time({a, a + timedelta(1)}, strain) is None  # one episode: day 2 of a run is not a new one
    assert insights.lead_time({b, c, TODAY}, strain) == {"days": 2, "n": 2}  # no index the day before: left out
    assert insights.lead_time({b, c, a + timedelta(5)}, {**strain, a + timedelta(4): 10})["days"] == 1  # 3, 1, 0


def test_wind_down_is_a_clock_metric_on_the_bed_start_scale() -> None:
    alc = [TODAY - timedelta(i) for i in (3, 6, 9)]
    rows = [row(TODAY - timedelta(i)) for i in range(20)]
    for r in rows:
        r["inputs"]["wind_down"] = 660 if r["date"] - timedelta(1) in alc else 600  # minutes after noon
    k = insights.kinds(rows, [entry(d, "alcohol") for d in alc], TODAY, {}, "", [])
    e = {x["metric"]: x for x in k["alcohol"]["all"]}["wind_down"]
    assert (e["unit"], e["delta"], e["pct"], e["with"], e["without"], e["strong"]) == ("min", 60, None, -60, -120, True)
    assert k["sick"]["lead"] is None and k["sick"]["status"] == "learning"


def test_k1_routes_and_strain_sentence() -> None:
    with TestClient(app) as c:
        d = main._today()
        assert c.get("/sleep", headers=H).json()["jetlag"] is None
        assert c.get("/today", headers=H).json()["tonight"]["wind_down_usual"] is None
        sick = [d - timedelta(i) for i in (4, 12)]
        for i in range(21):  # three weeks: 15 weekday + 6 weekend wake dates, weekends an hour later
            x = d - timedelta(i)
            r, late = row(x, strain=40 if any(0 < (s - x).days <= 2 for s in sick) else 10), 60 if x.weekday() >= 5 else 0
            if x == d:
                r["c"]["signals"] = {"index": 40, "level": "elevated", "streak": 2, "flags": {"recovery": 2.0},
                                     "reason": None}
            inputs = {**r["inputs"], "bed_start": 690 + late, "bed_end": 1170 + late, "wind_down": 600 + late}
            db.save_day(x, (80, 70, 60), {**r["c"], "inputs": inputs}, {})
        for s in sick:
            db.add_journal(s.isoformat(), "sick", None, None, 1)
        assert c.get("/sleep", headers=H).json()["jetlag"] == {"delta_min": 60, "weekday_min": 930.0, "weekend_min": 990.0,
                                                               "weekdays": 15, "weekends": 6}
        t = c.get("/today", headers=H).json()
        assert t["tonight"]["wind_down_usual"] == 600 and t["inputs"]["wind_down"] in (600, 660)
        assert c.get("/journal/insights", headers=H).json()["kinds"]["sick"]["lead"] == {"days": 2, "n": 2}
        notify._strain(notify._now(), set())
        assert notify.pending()[-1]["body"].endswith(" Last times this came about 2 days before you logged feeling sick.")


def test_score_day_stores_wind_down() -> None:
    d = main._today() - timedelta(1)
    t = lambda h, m=0: (samples.midnight(d) + timedelta(hours=h, minutes=m)).timestamp()
    load({"biometric_sleep_total_min": {t(-1): 420}, "biometric_sleep_end_ts": {t(-1): t(6, 30)},  # asleep 23:00
          "biometric_hr_bpm": {t(-4): 90, t(-3): 90, t(-3, 30): 60, t(-2): 62, t(-2, 30): 61, **{t(h): 52 for h in range(6)}}})
    main._score_day(d)
    assert db.days(d, d)[0]["inputs"]["wind_down"] == 570  # 21:30: night p10 52 -> settled at <= 62.4


def test_felt_is_a_night_metric_with_three_mornings_a_side() -> None:
    alc = [TODAY - timedelta(i) for i in (3, 6, 9)]
    rows = [row(TODAY - timedelta(i)) for i in range(10)]
    for i, r in enumerate(rows):  # the morning after alcohol was guessed at 50, three other mornings at 80
        r["felt"] = 50 if r["date"] - timedelta(1) in alc else 80 if i in (0, 1, 4) else None
    assert insights.nights(rows)[TODAY]["felt"] == 80 and insights.nights([row(TODAY)])[TODAY]["felt"] is None
    journal = [entry(d, "alcohol") for d in alc]
    e = {x["metric"]: x for x in insights.kinds(rows, journal, TODAY, {}, "", [])["alcohol"]["all"]}["felt"]
    assert (e["unit"], e["delta"], e["n_with"], e["n_without"], e["strong"]) == ("pts", -30, 3, 3, True)
    rows[0]["felt"] = None  # two guessed mornings without: no effect yet
    assert "felt" not in {x["metric"] for x in insights.kinds(rows, journal, TODAY, {}, "", [])["alcohol"]["all"]}
