from datetime import date, timedelta

from fastapi.testclient import TestClient

from app import db, insights, main, vm
from app.main import app

H = {"Authorization": "Bearer secret"}
TODAY = date(2026, 11, 8)


def row(d: date, hrv: float = 50, deep: float = 80, strain: int | None = 10, bed_min: int = -30,
        total: float = 420) -> dict:
    start = (vm.midnight(d) + timedelta(minutes=bed_min)).timestamp()
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
    monkeypatch.setattr(vm, "series", lambda *a: {})
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
