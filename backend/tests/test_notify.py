import re
from contextlib import closing
from datetime import date, datetime, timedelta

import pytest
from fastapi.testclient import TestClient

from app import db, notify, samples
from app.main import app
from conftest import load

H = {"Authorization": "Bearer secret"}
D = date(2026, 11, 9)
CHECKIN = notify._checkin


@pytest.fixture(autouse=True)
def env(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    monkeypatch.setattr(notify, "_checkin", lambda now, dates: None)  # 08-11 rule; on only in its own tests


def walk() -> None:
    """Steps every hour around D, so the inactivity rule stays quiet."""
    m = samples.midnight(D - timedelta(1)).timestamp()
    load({"biometric_steps": {m + 3600 * h: 100.0 for h in range(96)}})


def at(mp: pytest.MonkeyPatch, h: int, m: int = 0, day: date = D) -> datetime:
    now = datetime(day.year, day.month, day.day, h, m)
    mp.setattr(notify, "_now", lambda: now)
    return now


def seed(d: date, ready: int | None = None, contrib: dict | None = None) -> None:
    """Night 23:00 (d-1) to 07:00 (d)."""
    s, e = samples.midnight(d - timedelta(1)) + timedelta(hours=23), samples.midnight(d) + timedelta(hours=7)
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


def test_bedtime_window_and_dedup(monkeypatch) -> None:
    walk()
    for i in range(14):
        seed(D - timedelta(i))
    at(monkeypatch, 22, 20)
    notify.evaluate(set())
    assert kinds() == []  # before 22:30
    at(monkeypatch, 22, 40)
    notify.evaluate(set())
    notify.evaluate(set())
    assert kinds() == ["bedtime"] and "23:00" in notify.pending()[0]["body"]


def test_bedtime_needs_five_nights(monkeypatch) -> None:
    walk()
    for i in range(4):
        seed(D - timedelta(i))
    at(monkeypatch, 22, 40)
    notify.evaluate(set())
    assert kinds() == []


def test_inactivity(monkeypatch) -> None:
    seed(D)
    walk()
    at(monkeypatch, 12, 30)
    notify.evaluate(set())
    assert kinds() == []
    with closing(db.connect()) as con, con:
        con.execute("DELETE FROM samples")  # temp db
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


def test_pending_and_ack_routes(monkeypatch) -> None:
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


def test_weekly_fires_monday_once(monkeypatch) -> None:
    walk()
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


def test_weekly_not_tuesday_or_outside_window(monkeypatch) -> None:
    walk()
    seed_week(7)
    at(monkeypatch, 9, day=D + timedelta(1))
    notify.evaluate(set())
    at(monkeypatch, 13)
    notify.evaluate(set())
    assert kinds() == []


def test_weekly_needs_three_days(monkeypatch) -> None:
    walk()
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


def test_checkin_window_once_and_extra(monkeypatch) -> None:
    monkeypatch.setattr(notify, "_checkin", CHECKIN)
    walk()
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


def test_checkin_skipped_when_yesterday_logged(monkeypatch) -> None:
    monkeypatch.setattr(notify, "_checkin", CHECKIN)
    walk()
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


def test_morning_not_before_five(monkeypatch) -> None:
    """A stub the ring publishes mid-sleep (ended 01:47) must not pass as the night at 02:20."""
    seed_morning()
    row = db.days(D, D)[0]
    db.save_day(D, (50, 77, None), row["c"] | {"session": row["c"]["session"] | {"end": row["c"]["session"]["start"] + 3420}}, {})
    at(monkeypatch, 2, 20)
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


def test_bedtime_uses_best_window(monkeypatch) -> None:
    """14 scored nights: even days in bed 22:00 (quality 90), odd days 23:30 (60) → window 22:00-23:00."""
    walk()
    for i in range(14):
        d, q = D - timedelta(i), 90.0 if i % 2 == 0 else 60.0
        s = samples.midnight(d - timedelta(1)) + timedelta(hours=22, minutes=0 if i % 2 == 0 else 30 * 3)
        c = {k: q for k in ("total", "efficiency", "restfulness", "rem", "deep")} | {"timing": 0.0, "regularity": 0.0}
        db.save_day(d, (None, None, None), {"sleep": c, "session": {"start": s.timestamp(), "end": s.timestamp() + 8 * 3600}}, {})
    assert notify.bedtime_window(D)["start_min"] == 600
    at(monkeypatch, 21, 20)
    notify.evaluate(set())
    assert kinds() == []  # before 21:30
    at(monkeypatch, 21, 40)
    notify.evaluate(set())
    notify.evaluate(set())
    (n,) = notify.pending()
    assert (n["title"], n["body"]) == ("Wind down", "Your best nights start around 22:00.")


def seed_month(n: int, workouts: int = 0) -> None:
    """n scored days in October 2026, the first carrying `workouts` workouts."""
    for i in range(n):
        ws = [{"minutes": 30, "load": 2.0, "kind": "walk"}] * (workouts if i == 0 else 0)
        db.save_day(date(2026, 10, 1 + i), (78, 74, 69), {"workouts": ws}, {})


def test_monthly_fires_on_the_first_once(monkeypatch) -> None:
    seed_month(12, workouts=12)
    first = date(2026, 11, 1)
    at(monkeypatch, 9, day=first)
    notify.evaluate(set())
    notify.evaluate(set())
    (n,) = [x for x in notify.pending() if x["kind"] == "monthly"]
    assert n["title"] == "Your October" and n["body"] == "Sleep 78 · Readiness 74 · Activity 69 avg. 12 workouts."
    assert n["extra"] == {"open": "month"}
    at(monkeypatch, 9, day=first + timedelta(1))
    notify.evaluate(set())
    assert [k for k in kinds() if k == "monthly"] == ["monthly"]


def test_monthly_not_on_the_second_or_under_ten_days(monkeypatch) -> None:
    seed_month(12)
    at(monkeypatch, 9, day=date(2026, 11, 2))
    notify.evaluate(set())
    at(monkeypatch, 13, day=date(2026, 11, 1))
    notify.evaluate(set())
    assert "monthly" not in kinds()


def test_monthly_needs_ten_days(monkeypatch) -> None:
    seed_month(9)
    at(monkeypatch, 9, day=date(2026, 11, 1))
    notify.evaluate(set())
    assert "monthly" not in kinds()


def test_muted_kind_skipped_other_still_fires(monkeypatch) -> None:
    seed(D, 82, {"resting_hr": 90.0})
    db.set_settings({"muted": "readiness,weekly"})
    at(monkeypatch, 8)
    notify.evaluate({D})
    assert "readiness" not in kinds()
    db.set_settings({"muted": "bedtime"})
    notify.evaluate({D})
    assert "readiness" in kinds()


def test_bad_muted_422() -> None:
    with TestClient(app) as c:
        for bad in ("nope", "readiness,nope", "readiness, bedtime", 1, None):
            assert c.patch("/settings", json={"muted": bad}, headers=H).status_code == 422, bad
        assert c.patch("/settings", json={"muted": "monthly,checkin"}, headers=H).json()["muted"] == "monthly,checkin"
        assert c.patch("/settings", json={"muted": ""}, headers=H).json()["muted"] == ""


def bed() -> list[tuple[str, str, str]]:
    return [(n["kind"], n["title"], n["body"]) for n in notify.pending() if n["kind"].startswith("bedtime")]


def test_tonight_weekday_vs_weekend() -> None:
    db.set_settings({"wake_weekday": "06:00", "wake_weekend": "09:00"})
    fri, thu, sun = date(2026, 11, 13), date(2026, 11, 12), date(2026, 11, 15)
    assert notify.tonight(fri)["wake_min"] == 1260  # Friday evening → Saturday morning → weekend
    assert notify.tonight(thu)["wake_min"] == notify.tonight(sun)["wake_min"] == 1080
    p = notify.tonight(thu)
    assert (p["asleep_by_min"], p["in_bed_by_min"], p["extra_min"], p["window"]) == (630, 615, 0, None)  # no debt yet
    db.set_settings({"wake_weekday": ""})
    assert notify.tonight(thu) is None and notify.tonight(fri) is not None


def test_tonight_two_notifications_and_dedup(monkeypatch) -> None:
    """Wake 07:00, need 450, no debt: asleep-by 23:30, in bed 23:15 → wind down 22:15-22:45, bed 22:45-23:30."""
    db.set_settings({"wake_weekday": "07:00"})
    at(monkeypatch, 22, 10)
    notify.evaluate(set())
    assert bed() == []
    at(monkeypatch, 22, 20)
    notify.evaluate(set())
    at(monkeypatch, 22, 40)
    notify.evaluate(set())
    assert bed() == [("bedtime", "Wind down", "In bed by 23:15 to be rested for 07:00.")]
    at(monkeypatch, 22, 50)
    notify.evaluate(set())
    at(monkeypatch, 23, 25)
    notify.evaluate(set())
    assert bed()[1:] == [("bedtime_soon", "Time for bed", "Lights out by 23:30 — 7 h 30 min before your 07:00 alarm.")]
    at(monkeypatch, 23, 35)
    notify.evaluate(set())
    assert len(bed()) == 2


def test_tonight_catch_up_and_late_sync(monkeypatch) -> None:
    """3 nights of 410 min → debt 120, suggest 40: asleep-by 22:50, in bed 22:35."""
    for i in range(3):
        db.save_day(D - timedelta(i), (None, None, None), {"inputs": {"sleep_min": 410.0}}, {})
    db.set_settings({"wake_weekday": "07:00"})
    at(monkeypatch, 21, 40)
    notify.evaluate(set())
    assert bed() == [("bedtime", "Wind down", "In bed by 22:35 to be rested for 07:00. +40 min tonight to catch up.")]
    with closing(db.connect()) as con, con:
        con.execute("DELETE FROM notifications")
    at(monkeypatch, 22, 30)  # first sync of the evening lands inside the second window
    notify.evaluate(set())
    assert bed() == [("bedtime_soon", "Time for bed", "Lights out by 22:50 — 8 h 10 min before your 07:00 alarm.")]


def test_tonight_unset_keeps_old_rule(monkeypatch) -> None:
    walk()
    for i in range(14):
        seed(D - timedelta(i))
    db.set_settings({"wake_weekend": "07:00"})  # Monday evening uses the weekday time, which is unset
    at(monkeypatch, 22, 40)
    notify.evaluate(set())
    assert kinds() == ["bedtime"] and notify.pending()[0]["body"] == "Your usual bedtime is 23:00."


def test_wake_settings_patch() -> None:
    with TestClient(app) as c:
        assert c.get("/settings", headers=H).json()["wake_weekday"] == ""
        for bad in ("7:00", "24:00", 700, None):
            assert c.patch("/settings", json={"wake_weekday": bad}, headers=H).status_code == 422, bad
        r = c.patch("/settings", json={"wake_weekday": "07:00", "wake_weekend": "09:30"}, headers=H).json()
        assert (r["wake_weekday"], r["wake_weekend"]) == ("07:00", "09:30")
        assert c.patch("/settings", json={"wake_weekend": ""}, headers=H).json()["wake_weekend"] == ""


def test_tonight_across_midnight_fires_once(monkeypatch) -> None:
    """Wake 08:00, need 455: asleep-by 00:25, in bed 00:10 → "Time for bed" window 23:40-00:25 spans midnight."""
    db.set_settings({"wake_weekday": "08:00", "sleep_need_min": "455"})
    at(monkeypatch, 23, 45)
    notify.evaluate(set())
    at(monkeypatch, 0, 5, day=D + timedelta(1))
    notify.evaluate(set())
    assert bed() == [("bedtime_soon", "Time for bed", "Lights out by 00:25 — 7 h 35 min before your 08:00 alarm.")]


def screen(h: int, m: int, v: float = 1.0, day: date = D) -> None:
    load({"biometric_screen_on": {datetime(day.year, day.month, day.day, h, m).timestamp(): v}})


def up() -> list[tuple[str, str]]:
    return [(n["title"], n["body"]) for n in notify.pending() if n["kind"] == "stillup"]


def test_stillup_fires_once_fallback_body(monkeypatch) -> None:
    """Wake 07:00 → in bed by 23:15, window opens 23:35."""
    db.set_settings({"wake_weekday": "07:00"})
    screen(23, 25)
    at(monkeypatch, 23, 30)  # before in_bed_by + 20 min
    notify.evaluate(set())
    assert up() == []
    screen(23, 40)
    at(monkeypatch, 23, 45)
    notify.evaluate(set())
    at(monkeypatch, 23, 55)
    notify.evaluate(set())
    assert up() == [("Still up", "Put the phone down for a better night.")]


def test_stillup_window_ends_at_three(monkeypatch) -> None:
    db.set_settings({"wake_weekday": "07:00"})
    n = D + timedelta(1)
    screen(2, 50, day=n)
    at(monkeypatch, 3, 0, day=n)
    notify.evaluate(set())
    assert up() == []
    at(monkeypatch, 2, 55, day=n)
    notify.evaluate(set())
    assert len(up()) == 1


def test_stillup_needs_recent_screen_on(monkeypatch) -> None:
    db.set_settings({"wake_weekday": "07:00"})
    screen(23, 40, 0.0)
    at(monkeypatch, 23, 45)
    notify.evaluate(set())
    assert up() == []
    screen(23, 20, 1.0)  # older than 20 min at 23:45
    notify.evaluate(set())
    assert up() == []


def test_stillup_not_when_asleep(monkeypatch) -> None:
    db.set_settings({"wake_weekday": "07:00"})
    s = datetime(2026, 11, 9, 23, 30).timestamp()
    db.save_day(D + timedelta(1), (None, None, None), {"session": {"start": s, "end": s + 600}}, {})
    screen(23, 40)
    at(monkeypatch, 23, 45)
    notify.evaluate(set())
    assert up() == []


def test_stillup_insight_body_and_muted(monkeypatch) -> None:
    db.set_settings({"wake_weekday": "07:00"})
    for i in range(1, 8):  # evenings D-7..D-1; phone in bed on 3 of them → bed 00:30 vs 23:00
        e, w = D - timedelta(i), D - timedelta(i - 1)
        late = i <= 3
        if late:
            db.add_journal(e.isoformat(), "phone_in_bed", "30", None, 1)
        s = datetime(w.year, w.month, w.day, 0, 30) if late else datetime(e.year, e.month, e.day, 23, 0)
        db.save_day(w, (None, None, None), {"session": {"start": s.timestamp(), "end": s.timestamp() + 28800, "deep_min": 60,
                    "rem_min": 90, "total_min": 450, "awake_min": 20}}, {})
    screen(23, 40)
    at(monkeypatch, 23, 45)
    db.set_settings({"muted": "stillup"})
    notify.evaluate(set())
    assert up() == []
    db.set_settings({"muted": ""})
    notify.evaluate(set())
    assert up() == [("Still up", "Nights with 20+ min of phone in bed: asleep 90 min later.")]


def test_experiment_daily_line_done_and_muted(monkeypatch) -> None:
    walk()
    db.add_experiment("caffeine_late", "deep_min", (D - timedelta(4)).isoformat(), 10, "1010010110")
    for h, m in ((6, 59), (12, 0)):
        at(monkeypatch, h, m)
        notify.evaluate(set())
    db.set_settings({"muted": "experiment"})
    at(monkeypatch, 8)
    notify.evaluate(set())
    assert kinds() == []  # outside 07:00-12:00, then muted
    db.set_settings({"muted": ""})
    notify.evaluate(set())
    notify.evaluate(set())
    (n,) = notify.pending()
    assert (n["kind"], n["title"], n["body"], n["extra"]) == ("experiment", "Experiment day 5 of 10", "Today: no late coffee.", None)
    at(monkeypatch, 8, day=D + timedelta(1))
    notify.evaluate(set())
    assert notify.pending()[-1]["body"] == "Today: late coffee."
    at(monkeypatch, 8, day=D + timedelta(6))  # the day after the last night
    notify.evaluate(set())
    notify.evaluate(set())
    at(monkeypatch, 8, day=D + timedelta(7))
    notify.evaluate(set())
    assert kinds() == ["experiment"] * 3
    assert (notify.pending()[-1]["title"], notify.pending()[-1]["extra"]) == ("Your experiment is done", {"open": "today"})
    with TestClient(app) as c:
        assert c.patch("/settings", json={"muted": "experiment"}, headers=H).json()["muted"] == "experiment"


def test_experiment_ended_is_quiet(monkeypatch) -> None:
    db.add_experiment("workout", "hrv", D.isoformat(), 10, "1010101010")
    db.end_experiment(db.experiments()[0]["id"], D.isoformat())
    at(monkeypatch, 8)
    notify.evaluate(set())
    assert kinds() == []


def test_calm_readiness_band_word_no_number(monkeypatch) -> None:
    db.set_settings({"calm": "1", "birth_year": "1990"})
    for i in range(1, 15):  # a cardio baseline: outside calm mode the body would end with "Cardio target 8–11."
        db.save_day(D - timedelta(i), (None, None, None), {"inputs": {"cardio_load": 10.0}}, {})
    for i, (score, word) in enumerate(((85, "Ready"), (84, "Steady"), (70, "Steady"), (69, "Take it easy"))):
        d = D + timedelta(i)
        seed(d, score, {"resting_hr": 90.0, "hrv_balance": 40.0})
        at(monkeypatch, 8, day=d)
        notify.evaluate({d})
        n = notify.pending()[-1]
        assert (n["kind"], n["title"], n["body"]) == ("readiness", word, "Lowest factor: heart rate variability.")
    seed(d + timedelta(1), 90)  # no contributors
    at(monkeypatch, 8, day=d + timedelta(1))
    notify.evaluate({d + timedelta(1)})
    assert (notify.pending()[-1]["title"], notify.pending()[-1]["body"]) == ("Ready", "Based on last night's sleep.")


def test_calm_morning_band_word_no_score(monkeypatch) -> None:
    db.set_settings({"calm": "1"})
    for ready, title in ((85, "Ready"), (84, "Steady"), (70, "Steady"), (69, "Take it easy"), (None, "Last night")):
        seed_morning(78, ready)
        at(monkeypatch, 9)
        notify.evaluate(set())
        (n,) = notify.pending()
        assert (n["kind"], n["title"], n["body"]) == ("morning", title, "7 h 15 min asleep")
        assert n["extra"] == {"open": "sleep"}
        with closing(db.connect()) as con, con:
            con.execute("DELETE FROM notifications")  # test db: lets the once-a-day rule fire for the next score
