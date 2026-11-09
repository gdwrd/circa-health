import re
from datetime import date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app import db, notify, vm
from app.main import app

H = {"Authorization": "Bearer secret"}
D = date(2026, 11, 9)
CHECKIN = notify._checkin


@pytest.fixture(autouse=True)
def env(tmp_path, monkeypatch) -> dict:
    """Fresh DB per test; returns the {expr: {ts: value}} map served by the fake vm.series."""
    monkeypatch.setenv("DB_PATH", str(tmp_path / "ring.db"))
    monkeypatch.delenv("TZ", raising=False)
    db.init()
    series: dict = {}
    monkeypatch.setattr(vm, "series", lambda e, s, en, st: series.get(e, {}))
    monkeypatch.setattr(notify, "_checkin", lambda now, dates: None)  # 08-11 rule; on only in its own tests
    return series


def at(mp: pytest.MonkeyPatch, h: int, m: int = 0, day: date = D) -> datetime:
    now = datetime(day.year, day.month, day.day, h, m)
    mp.setattr(notify, "_now", lambda: now)
    return now


def seed(d: date, ready: int | None = None, contrib: dict | None = None) -> None:
    """Night 23:00 (d-1) to 07:00 (d)."""
    s, e = vm.midnight(d - timedelta(1)) + timedelta(hours=23), vm.midnight(d) + timedelta(hours=7)
    db.save_day(d, (None, ready, None), {"readiness": contrib, "session": {"start": s.timestamp(), "end": e.timestamp()}}, {})


def kinds() -> list[str]:
    return [n["kind"] for n in notify.pending()]


def test_readiness_once(monkeypatch) -> None:
    seed(D, 82, {"resting_hr": 90.0, "hrv_balance": 40.0, "sleep": None})
    at(monkeypatch, 8)
    notify.evaluate({D})
    notify.evaluate({D})
    (n,) = notify.pending()
    assert n["title"] == "Readiness 82" and "heart rate variability" in n["body"] and "Oura" not in n["body"]
    at(monkeypatch, 8, day=D + timedelta(1))
    notify.evaluate({D})  # no new night scored today
    assert kinds() == ["readiness"]


def test_readiness_appends_cardio_target(monkeypatch) -> None:
    seed(D, 70, {"resting_hr": 90.0})
    for i in range(1, 15):
        db.save_day(D - timedelta(i), (None, None, None), {"inputs": {"cardio_load": 10.0}}, {})
    db.set_settings({"birth_year": "1990"})
    at(monkeypatch, 8)
    notify.evaluate({D})
    (n,) = notify.pending()
    assert n["body"] == "Lowest factor: resting heart rate (90). Cardio target 8–11."


def test_bedtime_window_and_dedup(monkeypatch, env) -> None:
    env["biometric_steps"] = {1.0: 100.0}
    for i in range(14):
        seed(D - timedelta(i))
    at(monkeypatch, 22, 20)
    notify.evaluate(set())
    assert kinds() == []  # before 22:30
    at(monkeypatch, 22, 40)
    notify.evaluate(set())
    notify.evaluate(set())
    assert kinds() == ["bedtime"] and "23:00" in notify.pending()[0]["body"]


def test_bedtime_needs_five_nights(monkeypatch, env) -> None:
    env["biometric_steps"] = {1.0: 100.0}
    for i in range(4):
        seed(D - timedelta(i))
    at(monkeypatch, 22, 40)
    notify.evaluate(set())
    assert kinds() == []


def test_inactivity(monkeypatch, env) -> None:
    seed(D)
    env["biometric_steps"] = {1.0: 50.0}
    at(monkeypatch, 12, 30)
    notify.evaluate(set())
    assert kinds() == []
    env["biometric_steps"] = {}
    notify.evaluate(set())
    notify.evaluate(set())
    assert kinds() == ["inactivity"]


def test_inactivity_skips_night_and_late_hours(monkeypatch) -> None:
    seed(D)
    at(monkeypatch, 8, 30)  # hours 06-08 sit inside the night session / before 08:00
    notify.evaluate(set())
    at(monkeypatch, 23, 30)  # hours 21-23 end after 22:00
    notify.evaluate(set())
    assert kinds() == []


def test_pending_and_ack_routes(monkeypatch, env) -> None:
    notify._add("checkin", "How was yesterday?", "Takes ten seconds.", at(monkeypatch, 12))
    with TestClient(app) as c:
        (n,) = c.get("/notifications/pending", headers=H).json()
        assert set(n) == {"id", "kind", "title", "body", "created_at", "extra"} and n["kind"] == "checkin"
        assert n["extra"] is None
        assert c.post(f"/notifications/{n['id']}/ack", headers=H).status_code == 200
        assert c.get("/notifications/pending", headers=H).json() == []


def seed_week(n: int, sleep: int = 80) -> None:
    for i in range(1, n + 1):  # D is a Monday; days D-1.. are the previous week
        db.save_day(D - timedelta(i), (sleep + i, 70, 60), {}, {})


def test_weekly_fires_monday_once(monkeypatch, env) -> None:
    env["biometric_steps"] = {1.0: 100.0}
    seed_week(7)
    at(monkeypatch, 9)
    notify.evaluate(set())
    notify.evaluate(set())
    (n,) = notify.pending()
    assert n["title"] == "Your week" and n["body"] == "Sleep 84 · Readiness 70 · Activity 60 avg. Best night: Mon."
    assert n["extra"] == {"open": "trends"}
    at(monkeypatch, 10, day=D + timedelta(1))  # Tuesday
    notify.evaluate(set())
    assert kinds() == ["weekly"]


def test_weekly_not_tuesday_or_outside_window(monkeypatch, env) -> None:
    env["biometric_steps"] = {1.0: 100.0}
    seed_week(7)
    at(monkeypatch, 9, day=D + timedelta(1))
    notify.evaluate(set())
    at(monkeypatch, 13)
    notify.evaluate(set())
    assert kinds() == []


def test_weekly_needs_three_days(monkeypatch, env) -> None:
    env["biometric_steps"] = {1.0: 100.0}
    seed_week(2)
    at(monkeypatch, 9)
    notify.evaluate(set())
    assert kinds() == []


def seed_strain(d: date, streak: int, level: str = "elevated") -> None:
    sig = {"index": 40, "level": level, "flags": {"rhr": 3.0, "hrv": 2.0, "recovery": 2.5}, "streak": streak,
           "deltas": {"rhr": 6.2, "hrv": -10.0, "recovery": 0.3}, "usual": {"rhr": 55.0, "hrv": 50.0, "recovery": 0.3}}
    db.save_day(d, (None, None, None), {"signals": sig}, {})


ILLNESS = re.compile(r"(sick|ill|illness|infection|flu|covid)", re.I)


def test_strain_needs_streak_two_and_not_calm(monkeypatch) -> None:
    seed_strain(D, 1)
    at(monkeypatch, 9)
    notify.evaluate(set())
    seed_strain(D, 3, "calm")
    notify.evaluate(set())
    assert kinds() == []
    seed_strain(D, 2)
    notify.evaluate(set())
    (n,) = notify.pending()
    assert n["title"] == "Your body may be under strain"
    assert n["body"] == ("Resting HR +6 bpm, HRV −20 % and overnight recovery later vs your usual for 2 nights. "
                         "Rest and hydrate; see a doctor if you feel unwell.")


def test_strain_once_per_three_days_and_explain(monkeypatch) -> None:
    seed_strain(D, 2)
    db.add_journal((D - timedelta(1)).isoformat(), "sick", None, None, 1)
    db.add_journal((D - timedelta(1)).isoformat(), "alcohol", None, None, 2)
    at(monkeypatch, 9)
    notify.evaluate(set())
    for k in (1, 2):
        seed_strain(D + timedelta(k), 2 + k)
        at(monkeypatch, 9, day=D + timedelta(k))
        notify.evaluate(set())
    assert kinds() == ["strain"]
    body = notify.pending()[0]["body"]
    assert body.endswith(" You logged alcohol and feeling unwell yesterday, which can cause this.")
    assert not ILLNESS.search(body) and not ILLNESS.search(notify.pending()[0]["title"])
    seed_strain(D + timedelta(3), 5)
    at(monkeypatch, 9, day=D + timedelta(3))
    notify.evaluate(set())
    assert kinds() == ["strain", "strain"]


def test_checkin_window_once_and_extra(monkeypatch, env) -> None:
    monkeypatch.setattr(notify, "_checkin", CHECKIN)
    env["biometric_steps"] = {1.0: 100.0}
    for h in (7, 11):
        at(monkeypatch, h, 30 if h == 7 else 0)
        notify.evaluate(set())
    assert kinds() == []  # outside 08:00-11:00
    at(monkeypatch, 8)
    notify.evaluate(set())
    at(monkeypatch, 10, 59)
    notify.evaluate(set())
    assert kinds() == ["checkin"]
    with TestClient(app) as c:
        (n,) = c.get("/notifications/pending", headers=H).json()
    assert n["title"] == "How was yesterday?"
    assert n["body"] == "Takes ten seconds: alcohol, caffeine, workout, medication."
    assert n["extra"] == {"open": "journal", "date": "2026-11-08"}


def test_checkin_skipped_when_yesterday_logged(monkeypatch, env) -> None:
    monkeypatch.setattr(notify, "_checkin", CHECKIN)
    env["biometric_steps"] = {1.0: 100.0}
    db.add_journal(D.isoformat(), "workout", None, None, 1)  # today's entry does not count
    db.add_journal((D - timedelta(1)).isoformat(), "note", None, "fine", 2)
    at(monkeypatch, 9)
    notify.evaluate(set())
    assert kinds() == []


def seed_morning(score: int | None = 78, ready: int | None = 71) -> None:
    """Night 23:00 (D-1) to 07:00 (D), 7 h 15 min asleep."""
    seed(D, ready)
    row = db.days(D, D)[0]
    db.save_day(D, (score, ready, None), row["c"] | {"inputs": {"sleep_min": 435}}, {})


def test_morning_fires_once_with_extra(monkeypatch) -> None:
    seed_morning()
    at(monkeypatch, 7, 29)
    notify.evaluate(set())
    assert kinds() == []  # before end + 30 min
    at(monkeypatch, 7, 30)
    notify.evaluate(set())
    notify.evaluate(set())
    (n,) = notify.pending()
    assert n["kind"] == "morning" and n["title"] == "Last night · Sleep 78"
    assert n["body"] == "7 h 15 min asleep · Readiness 71" and n["extra"] == {"open": "sleep"}


def test_morning_not_after_two_pm(monkeypatch) -> None:
    seed_morning()
    at(monkeypatch, 14)
    notify.evaluate(set())
    assert "morning" not in kinds()


def test_morning_null_scores(monkeypatch) -> None:
    seed_morning(None, None)
    at(monkeypatch, 9)
    notify.evaluate(set())
    (n,) = notify.pending()
    assert (n["title"], n["body"]) == ("Last night", "7 h 15 min asleep")


def test_morning_needs_a_session(monkeypatch) -> None:
    db.save_day(D, (78, 71, None), {"inputs": {"sleep_min": 435}}, {})
    at(monkeypatch, 9)
    notify.evaluate(set())
    assert kinds() == []


def test_morning_waits_for_scored_night(monkeypatch) -> None:
    seed(D, 71)  # session but no inputs yet
    at(monkeypatch, 9)
    notify.evaluate(set())
    assert "morning" not in kinds()
    seed_morning()
    notify.evaluate(set())
    assert "morning" in kinds()
