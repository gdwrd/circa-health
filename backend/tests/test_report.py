import re
from datetime import date, timedelta

from fastapi.testclient import TestClient

from app import db, report
from app.main import app

H = {"Authorization": "Bearer secret"}
MON = date(2026, 11, 16)
CALM_SIG = {"index": 10, "level": "calm", "flags": {}, "deltas": {}, "usual": {}, "streak": 0}
STRAIN_SIG = {"index": 40, "level": "elevated", "flags": {"rhr": 2.1, "hrv": 1.8}, "deltas": {"rhr": 6.0, "hrv": -12.0},
              "usual": {"rhr": 52.0, "hrv": 48.0}, "streak": 3}


def _row(d: date, ready: int | None = 75, sleep: int = 80, asleep: float = 450.0, sig: dict = CALM_SIG, **inputs: float) -> dict:
    return {"date": d, "sleep_score": sleep, "readiness_score": ready, "activity_score": 70,
            "c": {"readiness": {"resting_hr": 60, "sleep": 80}, "signals": sig,
                  "session": {"start": 0, "end": 1, "deep_min": 80.0, "rem_min": 95.0},
                  "workouts": [{"kind": "walk", "minutes": 35}]},
            "inputs": {"sleep_min": asleep, "steps": 8000.0, "bed_start": 660.0, "bed_end": 1150.0, "cardio_load": 20.0} | inputs}


def _rows(d: date = MON, **today: object) -> list[dict]:
    return [_row(d - timedelta(i)) for i in range(13, 0, -1)] + [_row(d, **today)]


def _week(scored: int = 7, asleep: float = 450.0) -> dict:
    return {"scored_days": scored, "means": {"sleep": 80.0, "readiness": 75.0, "activity": 70.0},
            "best_night": {"date": "2026-11-12", "score": 88}, "worst_night": {"date": "2026-11-10", "score": 64},
            "sleep_avg_min": asleep, "steps_total": 56000.0}


def _ctx(**kw: object) -> dict:
    return {"calm": False, "guess_pending": False, "need_min": 480.0, "window": {"status": "learning"},
            "plan": {"in_bed_by_min": 645, "wake_min": 1140, "extra_min": 0, "sleep_min": 480.0},
            "debt": {"debt_min": 0, "nights": 7, "suggest_min": 0}, "target": None, "cardio_base": 20.0,
            "moments": [], "because": [], "explain": [], "closed": None} | kw


def _cards(rows: list[dict] | None = None, d: date = MON, week: dict | None = None, **kw: object) -> dict[str, dict]:
    out = report.cards(d, rows or _rows(d), week or _week(), _week(), _ctx(**kw))
    for c in out:
        assert 1 <= len(c["facts"]) <= report.MAX_FACTS and c["fallback"]
        assert "oura" not in (c["fallback"] + " ".join(c["facts"])).lower()
    return {c["kind"]: c for c in out}


def test_today_verdicts() -> None:
    assert _cards(_rows(ready=90))["today"]["verdict"] == "push"
    assert _cards(_rows(ready=75))["today"]["verdict"] == "steady"
    assert _cards(_rows(ready=60))["today"]["verdict"] == "easy"
    debt = {"debt_min": report.EASY_DEBT_MIN, "nights": 7, "suggest_min": 60}
    assert _cards(_rows(ready=90), debt=debt)["today"]["verdict"] == "easy"  # debt beats a high readiness
    debt = {"debt_min": report.PUSH_DEBT_MAX, "nights": 7, "suggest_min": 20}
    assert _cards(_rows(ready=90), debt=debt)["today"]["verdict"] == "steady"
    rest = _cards(_rows(ready=90, sig=STRAIN_SIG))["today"]
    assert rest["verdict"] == "rest" and rest["fallback"].startswith("Rest today.")
    t = _cards(_rows(ready=58, asleep=340.0))["today"]
    assert t["facts"][:2] == ["Readiness 58, your usual is 75", "Slept 5 h 40 min, 1 h 50 min under your usual"]
    assert _cards(_rows(ready=None)).get("today") is None  # no scored night: no today card


def test_tonight_verdicts() -> None:
    assert _cards()["tonight"]["verdict"] == "usual"
    plan = {"in_bed_by_min": 615, "wake_min": 1140, "extra_min": 30, "sleep_min": 510.0}
    t = _cards(plan=plan, debt={"debt_min": 95, "nights": 7, "suggest_min": 30})["tonight"]
    assert t["verdict"] == "earlier" and t["facts"][0] == "In bed by 22:15 to be rested for 07:00"
    assert "Sleep debt 1 h 35 min over the last 7 nights" in t["facts"]


def test_next_week_focus() -> None:
    assert _cards(week=_week(asleep=420.0))["next_week"]["verdict"] == "sleep"
    spread = [_row(MON - timedelta(i), bed_start=540.0 + 150 * (i % 2)) for i in range(13, 0, -1)] + [_row(MON)]
    assert _cards(spread)["next_week"]["verdict"] == "regular"
    assert _cards(_rows(), cardio_base=40.0)["next_week"]["verdict"] == "move"
    assert _cards()["next_week"]["verdict"] == "keep"
    thu = MON + timedelta(3)
    assert "next_week" not in _cards(_rows(thu), d=thu)
    assert "week" not in _cards(week=_week(scored=2)) and "next_week" not in _cards(week=_week(scored=2))


def test_calm_mode_has_no_digits() -> None:
    ep = {"first": MON - timedelta(5), "last": MON - timedelta(3), "nights": 3, "kinds": ["alcohol"]}
    because = [{"kind": "alcohol", "effect": {"metric": "bed_start", "delta": 25.0}}]
    plan = {"in_bed_by_min": 615, "wake_min": 1140, "extra_min": 30, "sleep_min": 510.0}
    for sig in (STRAIN_SIG, CALM_SIG):
        cs = _cards(_rows(ready=58, asleep=340.0, sig=sig), calm=True, because=because, plan=plan, closed=ep,
                    moments=[{"kind": "coffee"}], explain=["alcohol"], target={"lo": 18, "hi": 26, "band": "easy"},
                    debt={"debt_min": 95, "nights": 7, "suggest_min": 30}, window={"status": "ok", "start_min": 630, "end_min": 690})
        assert set(cs) == {"today", "yesterday", "night", "tonight", "week", "next_week", "body"}
        for c in cs.values():
            text = " ".join(c["facts"]) + c["fallback"]
            assert not re.search(r"\d", text), (c["kind"], text)


def test_guess_first_withholds_today() -> None:
    cs = _cards(guess_pending=True)
    assert "today" not in cs and "night" in cs


def test_body_card() -> None:
    assert "body" not in _cards()  # quiet day
    b = _cards(_rows(sig=STRAIN_SIG), explain=["alcohol"])["body"]
    assert b["verdict"] == "watch" and b["facts"][:3] == [
        "Resting HR +6 bpm against your usual", "HRV −25 % against your usual", "Above calm for 3 nights"]
    assert "may" in b["fallback"] and "doctor" not in b["fallback"].lower()
    ep = {"first": MON - timedelta(4), "last": MON - timedelta(2), "nights": 3, "kinds": []}
    back = _cards(closed=ep)["body"]
    assert back["verdict"] == "back" and back["facts"] == ["Back in your usual range after 3 nights"]


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
    assert all(set(x) == {"kind", "verdict", "facts", "fallback"} for x in j["cards"])
