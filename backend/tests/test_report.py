import re
from datetime import date, datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app import db, report
from app.main import app

H = {"Authorization": "Bearer secret"}
MON = date(2026, 11, 16)
CALM_SIG = {"index": 10, "level": "calm", "flags": {}, "deltas": {}, "usual": {}, "streak": 0}
STRAIN_SIG = {"index": 40, "level": "elevated", "flags": {"rhr": 2.1, "hrv": 1.8}, "deltas": {"rhr": 6.0, "hrv": -12.0},
              "usual": {"rhr": 52.0, "hrv": 48.0}, "streak": 3}


def _ts(d: date, h: int, m: int) -> float:
    return datetime(d.year, d.month, d.day, h, m, tzinfo=timezone.utc).timestamp()


def _row(d: date, ready: int | None = 75, sleep: int = 80, act: int = 70, asleep: float = 450.0, sig: dict = CALM_SIG,
         **inputs: float) -> dict:
    return {"date": d, "sleep_score": sleep, "readiness_score": ready, "activity_score": act,
            "c": {"readiness": {"resting_hr": 60, "sleep": 80}, "signals": sig,
                  "session": {"start": 0, "end": 1, "deep_min": 80.0, "rem_min": 95.0},
                  "workouts": [{"kind": "walk", "minutes": 35, "start": _ts(d, 17, 10), "avg_bpm": 104}],
                  "stress": {"restored": 10, "relaxed": 12, "engaged": 4, "stressed": 2, "stressed_hours": 1.0}},
            "inputs": {"sleep_min": asleep, "steps": 8000.0, "bed_start": 660.0, "bed_end": 1150.0, "cardio_load": 20.0,
                       "rhr": 55.0, "hrv": 45.0, "awake": 20.0, "recovery": 0.5, "stress_load": 1.0} | inputs}


def _rows(d: date = MON, **today: object) -> list[dict]:
    return [_row(d - timedelta(i)) for i in range(13, 0, -1)] + [_row(d, **today)]


def _week(scored: int = 7, asleep: float = 450.0) -> dict:
    return {"scored_days": scored, "means": {"sleep": 80.0, "readiness": 75.0, "activity": 70.0},
            "best_night": {"date": "2026-11-12", "score": 88}, "worst_night": {"date": "2026-11-10", "score": 64},
            "sleep_avg_min": asleep, "steps_total": 63801.0}


def _ctx(**kw: object) -> dict:
    return {"calm": False, "guess_pending": False, "need_min": 480.0, "window": {"status": "learning"},
            "plan": {"in_bed_by_min": 645, "wake_min": 1140, "extra_min": 0, "sleep_min": 480.0},
            "debt": {"debt_min": 0, "nights": 7, "suggest_min": 0}, "target": None, "cardio_base": 20.0,
            "moments": [], "because": [], "explain": [], "closed": None, "tz": timezone.utc} | kw


def _cards(rows: list[dict] | None = None, d: date = MON, week: dict | None = None, **kw: object) -> dict[str, dict]:
    out = report.cards(d, rows or _rows(d), week or _week(), _week(), _ctx(**kw))
    for c in out:
        assert 1 <= len(c["facts"]) <= report.MAX_FACTS and len(c["advice"]) <= report.MAX_ADVICE and c["fallback"]
        assert "oura" not in (c["fallback"] + " ".join(c["facts"] + c["advice"])).lower()
    return {c["kind"]: c for c in out}


def _text(c: dict) -> str:
    return " | ".join(c["facts"] + c["advice"] + [c["fallback"]])


DEBT = {"debt_min": 315, "nights": 7, "suggest_min": 60}
LATE_COFFEE = [{"kind": "caffeine_late", "effect": {"metric": "bed_start", "delta": 40.0}}]
TARGET = {"lo": 22, "hi": 28, "band": "recovered"}


def test_today_verdicts() -> None:
    assert _cards(_rows(ready=90))["today"]["verdict"] == "push"
    assert _cards(_rows(ready=75))["today"]["verdict"] == "steady"
    assert _cards(_rows(ready=60))["today"]["verdict"] == "easy"
    easy = {"debt_min": report.EASY_DEBT_MIN, "nights": 7, "suggest_min": 60}
    assert _cards(_rows(ready=75), debt=easy)["today"]["verdict"] == "easy"  # debt turns a middle day easy
    t = _cards(_rows(ready=93, asleep=504.0), debt=DEBT)["today"]  # the first real reply: top band, 5 h 15 min debt
    assert t["verdict"] == "steady"
    assert any(f.startswith("Sleep debt 5 h 15 min") for f in t["facts"]) and "debt" in " ".join(t["advice"])
    push = {"debt_min": report.PUSH_DEBT_MAX, "nights": 7, "suggest_min": 20}
    assert _cards(_rows(ready=90), debt=push)["today"]["verdict"] == "steady"
    rest = _cards(_rows(ready=90, sig=STRAIN_SIG))["today"]
    assert rest["verdict"] == "rest" and rest["fallback"].startswith("Rest today.")
    assert _cards(_rows(ready=None)).get("today") is None  # no scored night: no today card


def test_today_observations_and_advice() -> None:
    t = _cards(_rows(ready=93, rhr=51.0, hrv=52.0, asleep=504.0), target=TARGET,
               moments=[{"kind": "flat", "ts": _ts(MON - timedelta(1), 15, 10)}])["today"]
    assert t["verdict"] == "push"
    f = t["facts"]
    assert "Overall recovery well above your usual" in f and "Likely more energy than usual today" in f
    assert "Resting heart rate 51 bpm, 4 bpm below your usual: a sign of good recovery" in f
    assert "HRV 52 ms, 16 % above your usual: a sign your body is well rested" in f
    assert "Slept 8 h 24 min, 54 min above your usual: more time for your body to recover" in f
    assert "You noted feeling flat at 15:10 yesterday" in f
    assert "A walk at 17:10 for 35 min yesterday, average heart rate 104 bpm" in f
    assert f.index("Deep sleep 1 h 20 min, about the same as your usual") > f.index("Slept 8 h 24 min, 54 min above your usual: more time for your body to recover")
    assert t["advice"] == ["A good day for a harder workout: aim for a cardio load of 22 to 28"]
    assert t["fallback"] == "A good day to push. " + f[0] + ". " + f[1] + ". " + t["advice"][0] + "."
    late = _cards(_rows(bed_start=810.0))["today"]["facts"]
    assert "You fell asleep at 01:30, about 2 h 30 min later than usual" in late


def test_no_score_numbers() -> None:
    rows = [_row(MON - timedelta(i), ready=91, sleep=83, act=67) for i in range(13, 0, -1)] + \
           [_row(MON, ready=91, sleep=83, act=67, rhr=50.0, hrv=55.0, asleep=520.0, spo2=88.0)]
    for calm in (False, True):
        cs = _cards(rows, calm=calm, target=TARGET, debt=DEBT, because=LATE_COFFEE,
                    moments=[{"kind": "coffee", "ts": _ts(MON - timedelta(1), 16, 20)}],
                    window={"status": "ok", "start_min": 630, "end_min": 690})
        assert {"today", "yesterday", "night", "tonight", "week", "next_week"} <= set(cs)
        for c in cs.values():
            assert not re.search(r"\b(91|83|67)\b", _text(c)), (c["kind"], _text(c))
            assert "score" not in _text(c).lower(), (c["kind"], _text(c))


def test_tonight_advice_grounded() -> None:
    assert _cards()["tonight"]["verdict"] == "usual"
    plan = {"in_bed_by_min": 615, "wake_min": 1140, "extra_min": 30, "sleep_min": 510.0}
    t = _cards(plan=plan, debt={"debt_min": 95, "nights": 7, "suggest_min": 30})["tonight"]
    assert t["verdict"] == "earlier" and "Sleep debt 1 h 35 min over the last 7 nights: can leave you more tired than usual" in t["facts"]
    assert t["advice"][0] == f"Be in bed by {report._clock(plan['in_bed_by_min'])} to pay back some sleep debt" \
        and "22:15" in t["advice"][0]
    today = _cards(_rows(ready=93), plan=plan, debt=DEBT)["today"]
    assert any(a.startswith("Be in bed by 22:15") for a in today["advice"])


def test_because_advice_only_with_effect() -> None:
    assert not any("coffee" in a for c in _cards().values() for a in c["advice"])
    no_effect = [{"kind": "caffeine_late", "effect": None}]
    assert not any("coffee" in a for c in _cards(because=no_effect).values() for a in c["advice"])
    earlier = [{"kind": "caffeine_late", "effect": {"metric": "bed_start", "delta": -20.0}}]
    assert not any("coffee" in a for c in _cards(because=earlier).values() for a in c["advice"])
    t = _cards(because=LATE_COFFEE)["tonight"]
    assert "Skip coffee after 14:00: after late coffee you fall asleep about 40 min later" in t["advice"]


def test_yesterday_and_week_wording() -> None:
    y = _cards(_rows()[:-2] + [_row(MON - timedelta(1), steps=12000.0, cardio_load=35.0), _row(MON)],
               moments=[{"kind": "flat", "ts": _ts(MON - timedelta(1), 15, 10)}])["yesterday"]
    assert y["verdict"] is None and y["advice"] == []
    assert "Walked 12,000 steps, 4,000 steps above your usual: a more active day than usual" in y["facts"]
    assert "Yesterday was a heavier day than usual for your heart" in y["facts"]
    assert "A walk at 17:10 for 35 min, average heart rate 104 bpm" in y["facts"]
    assert "You noted feeling flat at 15:10" in y["facts"]
    w = _cards()["week"]
    assert "63,801 steps in total, 63,801 the week before" in w["facts"]
    assert "averaged" not in _text(w) and "Best night Thursday 12 November" in w["facts"] and w["advice"] == []


def test_next_week_focus() -> None:
    n = _cards(week=_week(asleep=420.0))["next_week"]
    assert n["verdict"] == "sleep" and n["advice"] == ["Aim for 8 h of sleep a night"]
    spread = [_row(MON - timedelta(i), bed_start=540.0 + 150 * (i % 2)) for i in range(13, 0, -1)] + [_row(MON)]
    assert _cards(spread)["next_week"]["verdict"] == "regular"
    assert _cards(_rows(), cardio_base=40.0)["next_week"]["verdict"] == "move"
    assert _cards()["next_week"]["verdict"] == "keep"
    thu = MON + timedelta(3)
    assert "next_week" not in _cards(_rows(thu), d=thu)
    assert "week" not in _cards(week=_week(scored=2)) and "next_week" not in _cards(week=_week(scored=2))


def test_calm_mode_has_no_digits() -> None:
    ep = {"first": MON - timedelta(5), "last": MON - timedelta(3), "nights": 3, "kinds": ["alcohol"]}
    because = [{"kind": "alcohol", "effect": {"metric": "bed_start", "delta": 25.0}}] + LATE_COFFEE
    plan = {"in_bed_by_min": 615, "wake_min": 1140, "extra_min": 30, "sleep_min": 510.0}
    for sig in (STRAIN_SIG, CALM_SIG):
        cs = _cards(_rows(ready=58, asleep=340.0, sig=sig, rhr=60.0, spo2=88.0, wind_down=700.0), calm=True, because=because,
                    plan=plan, closed=ep, moments=[{"kind": "coffee", "ts": _ts(MON - timedelta(1), 16, 20)}],
                    explain=["alcohol"], target=TARGET, debt=DEBT,
                    window={"status": "ok", "start_min": 630, "end_min": 690})
        assert set(cs) == {"today", "yesterday", "night", "tonight", "week", "next_week", "body"}
        assert cs["today"]["advice"] and cs["tonight"]["advice"]
        for c in cs.values():
            assert not re.search(r"\d", _text(c)), (c["kind"], _text(c))


def test_guess_first_withholds_today() -> None:
    cs = _cards(guess_pending=True)
    assert "today" not in cs and "night" in cs


def test_body_card() -> None:
    assert "body" not in _cards()  # quiet day
    b = _cards(_rows(sig=STRAIN_SIG), explain=["alcohol"])["body"]
    assert b["verdict"] == "watch" and b["facts"][:3] == [
        "Resting HR +6 bpm against your usual", "HRV −25 % against your usual", "Above calm for 3 nights"]
    assert b["advice"] == ["Rest and drink enough"]
    assert "may" in b["fallback"] and "doctor" not in b["fallback"].lower()
    ep = {"first": MON - timedelta(4), "last": MON - timedelta(2), "nights": 3, "kinds": []}
    back = _cards(closed=ep)["body"]
    assert back["verdict"] == "back" and back["facts"] == ["Back in your usual range after 3 nights"] and back["advice"] == []


BED = _ts(MON, 22, 45)


def _upd(h: int, since_h: int = 12, given: tuple[str, ...] = (), calm: bool = False, bed: float | None = BED,
         **day: object) -> dict:
    day = {"moments": [], "workouts": [], "steps_today": 6000.0, "steps_since": 0.0, "stressed_min": 0.0,
           "hr_since": None, "hr_usual": None} | day
    u = report.update(_ts(MON, h, 0), _ts(MON, since_h, 0), day, list(given),
                      {"calm": calm, "tz": timezone.utc, "step_goal": 8000.0, "bed": bed})
    assert set(u) == {"at", "nothing_new", "evening", "facts", "advice", "fallback"}
    text = " ".join(u["facts"] + u["advice"]) + u["fallback"]
    assert not re.search(r"medic|dose|pill|score|oura", text.lower()), text
    return u


WALK = {"kind": "walk", "minutes": 40, "start": _ts(MON, 13, 0)}
COFFEE = [{"kind": "coffee", "ts": _ts(MON, 8, 0)}, {"kind": "coffee", "ts": _ts(MON, 10, 0)}]


def test_update_nothing_new() -> None:
    u = _upd(13)
    assert u["nothing_new"] and not u["evening"] and u["advice"] == [] and u["at"] == _ts(MON, 13, 0)
    assert "Walked 0 steps since 12:00: a still stretch" in u["facts"]
    assert not _upd(13, steps_since=900.0)["nothing_new"]


def test_update_effort_goes_easy() -> None:
    u = _upd(15, workouts=[WALK], steps_since=3000.0)
    assert not u["nothing_new"] and "A walk at 13:00 for 40 min" in u["facts"]
    assert u["advice"] == ["Keep the rest of the day easy after a walk"]


def test_update_coffee() -> None:
    assert _upd(11, moments=COFFEE)["advice"] == []  # two before the cut-off: fine
    assert "Coffee 2 times today" in _upd(11, moments=COFFEE)["facts"]
    late = _upd(15, moments=COFFEE)
    assert late["advice"] == ["No more coffee today: it can push your sleep later"]  # past 14:00
    third = COFFEE + [{"kind": "coffee", "ts": _ts(MON, 12, 30)}]
    assert _upd(13, moments=third)["advice"] == ["No more coffee today: it can push your sleep later"]


def test_update_stress_and_move() -> None:
    s = _upd(13, stressed_min=90.0)
    assert "Stressed for 1 h 30 min since 12:00: a demanding stretch" in s["facts"]
    assert s["advice"] == ["Take a short walk or do the breathing exercise"]
    assert _upd(15, steps_today=2000.0)["advice"] == ["Fit in a brisk walk while there is time before bed"]
    assert _upd(13, steps_today=2000.0)["advice"] == []  # not afternoon yet
    assert _upd(20, steps_today=2000.0)["advice"] == []  # under 3 h to bed: too late to move


def test_update_evening_wind_down_only() -> None:
    u = _upd(21, moments=COFFEE, stressed_min=90.0, steps_today=2000.0)
    assert u["evening"] and not u["nothing_new"]
    assert u["advice"] == ["Start winding down: dim the lights and put screens away", "Be in bed by 22:45"]
    assert "Your planned bedtime is 22:45, about 1 h 45 min from now" in u["facts"]
    assert not _upd(21, bed=None)["evening"]


def test_update_no_repeat() -> None:
    line = "No more coffee today: it can push your sleep later"
    assert _upd(16, since_h=15, moments=COFFEE, given=(line,))["advice"] == []  # same state: not again
    again = COFFEE + [{"kind": "coffee", "ts": _ts(MON, 15, 30)}]
    assert _upd(16, since_h=15, moments=again, given=(line,))["advice"] == [line]  # a new coffee: state changed
    stress = "Take a short walk or do the breathing exercise"
    assert _upd(16, since_h=15, stressed_min=60.0, given=(stress,))["advice"] == [stress]  # stressed again since


def test_update_heart_rate_and_calm() -> None:
    u = _upd(15, hr_since=82.0, hr_usual=74.0)
    assert "Heart rate 82 bpm since 12:00, 8 bpm above your usual: your body has been busier than usual" in u["facts"]
    c = _upd(21, calm=True, moments=COFFEE, workouts=[WALK], stressed_min=90.0, hr_since=82.0, hr_usual=74.0)
    assert c["advice"] and not re.search(r"\d", " ".join(c["facts"] + c["advice"]) + c["fallback"])


def test_timeline() -> None:
    ev = report.timeline({"start": _ts(MON, 0, 0) - 3600, "end": _ts(MON, 7, 5)}, [WALK],
                         [{"kind": "coffee", "ts": _ts(MON, 8, 0)}], [_ts(MON, 9, 0)])
    assert [(e["kind"], e["label"]) for e in ev] == [("wake", "Woke"), ("coffee", "Coffee"), ("dose", "Dose"),
                                                     ("walk", "Walk · 40 min")]


def test_timeline_and_update_endpoints() -> None:
    from conftest import load
    from app import samples
    now = datetime.now(samples.tz())
    d = now.date()
    db.save_day(d, (80, 75, 70), {"inputs": {}, "signals": CALM_SIG, "workouts": [], "session":
                                  {"start": now.timestamp() - 40000, "end": now.timestamp() - 10000}}, {})
    t = now.timestamp() - 60
    load({"moment_coffee": {t: 1.0}})
    db.add_journal(d.isoformat(), "medication", "00:00", None, int(t))
    with TestClient(app) as c:
        assert c.get("/timeline").status_code == 401
        j = c.get(f"/timeline?date={d.isoformat()}", headers=H).json()
        assert j["date"] == d.isoformat() and {"wake", "coffee", "dose"} <= {e["kind"] for e in j["events"]}
        assert [e["t"] for e in j["events"]] == sorted(e["t"] for e in j["events"])
        bad = [{"since": "x"}, {"since": int(t), "given": "x"}, {"since": int(t), "moves": [{"kind": "swim"}]},
               {"since": int(now.timestamp()) + 999}]
        assert all(c.post("/report/update", json=b, headers=H).status_code == 422 for b in bad)
        u = c.post("/report/update", headers=H, json={"since": int(t) - 600, "given": [], "moves": [
            {"start": int(t) - 300, "end": int(t) - 60, "kind": "ride"}]}).json()
        assert not u["nothing_new"] and any(f.startswith("You noted coffee") for f in u["facts"])
        assert any(f.startswith("A ride") for f in u["facts"])
        quiet = c.post("/report/update", headers=H, json={"since": int(now.timestamp()) - 1}).json()
    assert quiet["nothing_new"] or quiet["advice"]  # advice alone (time of day) still counts as new


def test_report_endpoint() -> None:
    d = date.today()
    for i in range(7):
        db.save_day(d - timedelta(i), (80, 75, 70), {"inputs": {"sleep_min": 450.0, "steps": 8000.0}, "signals": CALM_SIG}, {})
    with TestClient(app) as c:
        assert c.get("/report").status_code == 401
        assert c.get("/report?date=bad", headers=H).status_code == 422
        j = c.get(f"/report?date={d.isoformat()}", headers=H).json()
    kinds = [x["kind"] for x in j["cards"]]
    assert j["date"] == d.isoformat() and kinds[:2] == ["today", "yesterday"] and "body" not in kinds
    assert all(set(x) == {"kind", "verdict", "facts", "advice", "fallback"} for x in j["cards"])
