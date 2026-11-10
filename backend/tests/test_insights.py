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


E0 = date(2026, 10, 1)  # the logged evening; its first night is E0 + 1


def sigs(first: date, *index: int | None) -> dict[date, dict]:
    """signals per wake date from `first` on (None = no index that night); rhr is flagged, hrv is not."""
    return {first + timedelta(i): {"index": v, "flags": {"rhr": 3.0}, "deltas": {"rhr": 6.0, "hrv": -1.0},
                                   "usual": {"rhr": 55.0, "hrv": 50.0}} for i, v in enumerate(index)}


def eps(logged: dict[date, set[str]], *index: int | None, today: date = E0 + timedelta(20)) -> list[dict]:
    return insights.episodes(logged, sigs(E0 + timedelta(1), *index), today)


def test_episode_closed_after_two_nights() -> None:
    assert eps({E0: {"alcohol"}}, 40, 30, 10, 60) == [
        {"key": "alcohol", "kinds": ["alcohol"], "first": E0, "last": E0, "nights": 2, "state": "closed",
         "deltas": {"rhr": 6.0}, "usual": {"rhr": 55.0}}]


def test_episode_zero_nights_when_first_night_is_calm() -> None:
    (e,) = eps({E0: {"alcohol"}}, 24, 60)
    assert (e["nights"], e["state"]) == (0, "closed")


def test_episode_unknown_first_night_is_left_out() -> None:
    assert eps({E0: {"alcohol"}}, None, 40, 10) == [] and eps({E0: {"alcohol"}}) == []


def test_episode_capped_at_seven_nights() -> None:
    (e,) = eps({E0: {"alcohol"}}, *[40] * 9)
    assert (e["nights"], e["state"]) == (7, "capped")


def test_episode_open_until_a_calm_night() -> None:
    (e,) = eps({E0: {"alcohol"}}, 40, 40, today=E0 + timedelta(2))
    assert (e["nights"], e["state"]) == (2, "open")
    (e,) = eps({E0: {"alcohol"}}, today=E0)  # logged this evening: no night yet
    assert (e["nights"], e["state"], e["deltas"]) == (0, "open", {})


def test_episode_run_of_days_is_one_episode() -> None:
    (e,) = eps({E0 - timedelta(1): {"sick"}, E0: {"sick"}}, 40, 10)
    assert (e["key"], e["first"], e["last"], e["nights"], e["state"]) == ("sick", E0 - timedelta(1), E0, 1, "closed")


def test_episode_two_kinds_give_a_plus_key_and_no_price() -> None:
    es = eps({E0 - timedelta(1): {"late_meal"}, E0: {"alcohol", "late_meal"}}, 40, 10)
    assert [(e["key"], e["kinds"], e["state"]) for e in es] == [("alcohol+late_meal", ["alcohol", "late_meal"], "closed")]
    assert insights.prices(es) == {}


def test_episode_event_during_recovery_cuts() -> None:
    a, b = eps({E0: {"alcohol"}, E0 + timedelta(2): {"stress"}}, 40, 40, 40, 10)
    assert (a["key"], a["nights"], a["state"]) == ("alcohol", 2, "cut")  # night 3 belongs to the stress evening
    assert (b["key"], b["nights"], b["state"]) == ("stress", 1, "closed")
    (e,) = eps({E0: {"alcohol"}}, 40, None, 10)  # a later night without an index
    assert (e["nights"], e["state"]) == (1, "cut")


def ep(kind: str, nights: int, state: str = "closed") -> dict:
    return {"key": kind, "kinds": kind.split("+"), "nights": nights, "state": state}


def test_prices_need_three_single_kind_finished_episodes() -> None:
    es = [ep("alcohol", 1), ep("alcohol", 2), ep("alcohol", 6, "cut"), ep("alcohol", 5, "open"), ep("alcohol+stress", 3)]
    assert insights.prices(es) == {"alcohol": {"status": "learning", "n": 2}}
    assert insights.prices(es + [ep("alcohol", 7, "capped")]) == {"alcohol": {"status": "ok", "nights": 2, "n": 3}}


def test_last_time_event_has_the_time_before_and_the_price() -> None:
    t = E0 + timedelta(10)
    es = eps({E0: {"alcohol"}, t: {"alcohol"}}, 40, 30, 10, today=t)
    assert insights.last_time(es, {}, t) == {
        "why": "event", "key": "alcohol", "kinds": ["alcohol"], "nights": 0, "last": t,
        "before": {"first": E0, "nights": 2}, "price": {"status": "learning", "n": 1}}


def test_last_time_closed_only_on_the_first_calm_morning() -> None:
    es = eps({E0: {"alcohol"}}, 40, 30, 10)
    lt = insights.last_time(es, {}, E0 + timedelta(3))
    assert (lt["why"], lt["nights"], lt["last"], lt["before"]) == ("closed", 2, E0, None)
    assert insights.last_time(es, {}, E0 + timedelta(4)) is None  # the day after: no card
    assert insights.last_time(eps({E0: {"alcohol"}}, 10), {}, E0 + timedelta(1)) is None  # 0 nights: price only


def test_last_time_signals_finds_the_run_before_and_none_when_calm() -> None:
    t = E0 + timedelta(9)
    sig = sigs(E0, 40, 40, 40, 10, 40, 10, 10, 10, 40, 40)  # run of 3, a single night, then today's run of 2
    sig[t] |= {"level": "elevated", "streak": 2}
    assert insights.last_time([], sig, t) == {"why": "signals", "key": "signals", "nights": 2,
                                              "before": {"first": E0, "nights": 3}}
    assert insights.last_time([], {t: sig[t]}, t)["before"] is None
    assert insights.last_time([], {t: sig[t] | {"streak": 1}}, t) is None
    assert insights.last_time([], {t: sig[t] | {"level": "calm"}}, t) is None
    assert insights.last_time([], {}, t) is None


def marked_rows(marked: set[date], n: int = 30) -> tuple[dict[date, dict], dict[date, dict]]:
    """n days to TODAY: hrv 30 on a marked morning, and 3 h stress / 2000 steps the day before it."""
    rows = [row(TODAY - timedelta(i), hrv=30 if TODAY - timedelta(i) in marked else 50) for i in range(n)]
    for r in rows:
        hard = r["date"] + timedelta(1) in marked
        r["inputs"] |= {"stress_load": 3.0 if hard else 1.0, "steps": 2000.0 if hard else 8000.0}
    return insights.nights(rows), {r["date"]: r["inputs"] for r in rows}


def test_before_a_run_counts_once_and_neighbours_stay_out() -> None:
    first = {TODAY - timedelta(i) for i in (3, 8, 13)}
    nt, day = marked_rows(first)
    b = insights.before(first | {TODAY - timedelta(2)}, nt, day)  # day 2 of a run: marked, hrv 50, not a new one
    assert (b["status"], b["n_with"], b["n_without"]) == ("ok", 3, 20)  # 30 days - 4 marked - 6 next to one
    e = {x["metric"]: x for x in b["all"]}
    assert (e["hrv"]["delta"], e["hrv"]["n_with"], e["hrv"]["strong"]) == (-20, 3, True)
    assert (e["stress_load"]["unit"], e["stress_load"]["delta"], e["stress_load"]["strong"]) == ("h", 2, True)
    assert (e["steps"]["unit"], e["steps"]["delta"], e["steps"]["n_without"]) == ("steps", -6000, 19)  # no day before the oldest
    assert [x["metric"] for x in b["top"]] == ["steps", "hrv"] and e["total_min"]["delta"] == 0


def test_before_learning_under_three_marked_days() -> None:
    two = {TODAY - timedelta(i) for i in (3, 8)}
    assert insights.before(two, *marked_rows(two)) == {"status": "learning", "need": 1, "n_with": 2, "n_without": 24,
                                                        "pattern": None}
    assert insights.before(set(), {}, {}) == {"status": "learning", "need": 3, "n_with": 0, "n_without": 0, "pattern": None}


def test_before_pattern_counts_both_sides() -> None:
    marked = {TODAY - timedelta(i) for i in (3, 8, 13, 18)}
    low = {TODAY - timedelta(i) for i in (3, 8, 13, 22, 25, 4)}  # 3 marked mornings, 2 unmarked ones, 1 next to a mark
    rows = [row(TODAY - timedelta(i), hrv=30 if TODAY - timedelta(i) in low else 50) for i in range(30)]
    b = insights.before(marked, insights.nights(rows), {r["date"]: r["inputs"] for r in rows})
    assert [x["metric"] for x in b["top"]] == ["hrv"]  # 35 with, 47.8 without: under 41.4 looks like it
    assert b["pattern"] == {"metric": "hrv", "hits": 3, "marked": 4, "alike": 5}
    calm = insights.before(marked, *marked_rows(set()))  # nothing differs: no top effect, no pattern
    assert calm["status"] == "ok" and calm["top"] == [] and calm["pattern"] is None
