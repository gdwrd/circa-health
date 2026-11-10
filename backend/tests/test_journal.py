import sqlite3
from datetime import date, datetime, timedelta

from fastapi.testclient import TestClient

from app import db, main, samples
from app.main import app
from conftest import load

H = {"Authorization": "Bearer secret"}


def test_migration_copies_tags_and_keeps_them(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "old.db"))
    ts = int(datetime(2026, 11, 1, 22).timestamp())
    with sqlite3.connect(tmp_path / "old.db") as con:
        con.execute("CREATE TABLE tags(ts INTEGER, tag TEXT, note TEXT)")
        con.executemany("INSERT INTO tags VALUES(?,?,?)", [(ts, "Alcohol", "2 beers"), (ts + 1, "Caffeine", None),
                                                           (ts + 2, "Sauna", None), (ts + 3, "Late meal", None),
                                                           (ts + 4, "Party", "fun")])
    db.init()
    db.init()  # second start: journal not empty, no re-copy
    rows = {r["kind"]: r for r in db.journal("2026-01-01")}
    assert set(rows) == {"alcohol", "caffeine_late", "late_meal", "note"} and len(db.journal("2026-01-01")) == 4
    assert rows["alcohol"]["note"] == "2 beers" and rows["alcohol"]["date"] == "2026-11-01"
    assert rows["note"]["note"] == "Sauna; Party: fun"
    assert len(db.query("SELECT * FROM tags")) == 5
    assert db.settings()["medication_time"] == "08:00"


def test_journal_routes() -> None:
    today = datetime.now().date()
    with TestClient(app) as c:
        post = lambda **b: c.post("/journal", json={"date": today.isoformat(), **b}, headers=H)
        assert c.post("/journal", json={"date": today.isoformat(), "kind": "alcohol"}).status_code == 401
        assert post(kind="tag").status_code == 422
        assert c.post("/journal", json={"date": "nope", "kind": "alcohol"}, headers=H).status_code == 422
        tomorrow = (today + timedelta(1)).isoformat()
        assert c.post("/journal", json={"date": tomorrow, "kind": "alcohol"}, headers=H).status_code == 422
        assert post(kind="alcohol", value="x").status_code == 422
        assert post(kind="note").status_code == 422
        assert post(kind="note", note="x" * 201).status_code == 422
        for bad in ("24:00", "8:00", None):
            assert post(kind="medication", value=bad).status_code == 422
        for bad in ("69/80", "251/80", "120/39", "120/151", "120-80", 120):
            assert post(kind="bp", value=bad).status_code == 422
        assert post(kind="bp", value="70/40").status_code == 200
        assert post(kind="bp", value="250/150").status_code == 200  # replaces today's bp
        assert post(kind="medication", value="08:10").status_code == 200
        assert post(kind="medication", value="13:00").status_code == 200  # second dose kept
        a = post(kind="alcohol", note="1").json()["id"]
        post(kind="alcohol", note="2 beers")
        r = c.get("/journal?days=1", headers=H).json()
        assert "medication" in r["kinds"]
        kinds = [e["kind"] for e in r["entries"]]
        assert kinds.count("medication") == 2 and kinds.count("bp") == 1 and kinds.count("alcohol") == 1
        assert next(e for e in r["entries"] if e["kind"] == "bp")["value"] == "250/150"
        alc = next(e for e in r["entries"] if e["kind"] == "alcohol")
        assert alc["note"] == "2 beers" and alc["id"] != a
        assert c.delete(f"/journal/{alc['id']}", headers=H).json() == {"status": "ok"}
        assert "alcohol" not in [e["kind"] for e in c.get("/journal?days=0", headers=H).json()["entries"]]
        old = (today - timedelta(400)).isoformat()
        assert c.post("/journal", json={"date": old, "kind": "travel"}, headers=H).status_code == 200
        assert "travel" not in [e["kind"] for e in c.get("/journal?days=9999", headers=H).json()["entries"]]
        assert c.get("/tags", headers=H).status_code == 404


def test_settings() -> None:
    with TestClient(app) as c:
        s = c.get("/settings", headers=H).json()
        assert s == {"step_goal": 8000.0, "sleep_need_min": 450.0, "medication_started": "",
                     "medication_time": "08:00", "medication_window_h": 8.0, "birth_year": "",
                     "low_step_kind": "workout", "muted": "", "wake_weekday": "", "wake_weekend": "", "calm": "", "guess": "",
                     "mark_1": "", "mark_1_since": "", "mark_2": "", "mark_2_since": "", "mark_3": "", "mark_3_since": ""}
        future = (datetime.now().date() + timedelta(1)).isoformat()
        for bad in ({}, {"x": 1}, {"step_goal": "9000"}, {"step_goal": True}, {"sleep_need_min": 100},
                    {"medication_started": future}, {"medication_started": "2026-13-01"},
                    {"medication_time": "25:00"}, {"medication_window_h": 0}, {"medication_window_h": 25}):
            assert c.patch("/settings", json=bad, headers=H).status_code == 422, bad
        r = c.patch("/settings", json={"medication_started": "2026-10-18", "medication_time": "07:30",
                                       "medication_window_h": 6, "step_goal": 9000}, headers=H).json()
        assert r["medication_started"] == "2026-10-18" and r["medication_window_h"] == 6.0 and r["step_goal"] == 9000
        assert db.setting("step_goal") == 9000
        assert c.patch("/settings", json={"medication_started": ""}, headers=H).json()["medication_started"] == ""
        c.patch("/settings", json={"step_goal": 8000}, headers=H)


def test_add_auto_never_replaces_and_delete_auto_leaves_manual() -> None:
    db.add_journal("2026-11-01", "workout", None, "gym", 5)
    db.add_auto("2026-11-01", "workout")  # ignored: the manual entry is there
    db.add_auto("2026-11-02", "workout")
    db.add_auto("2026-11-02", "workout")  # ignored: one auto row a day
    rows = {e["date"]: e for e in db.journal("2026-01-01")}
    assert len(db.journal("2026-01-01")) == 2
    assert (rows["2026-11-01"]["note"], rows["2026-11-01"]["ts"]) == ("gym", 5)
    assert (rows["2026-11-02"]["note"], rows["2026-11-02"]["value"]) == ("auto", None)
    db.delete_auto("2026-11-01", "workout")
    db.delete_auto("2026-11-02", "workout")
    assert [(e["date"], e["note"]) for e in db.journal("2026-01-01")] == [("2026-11-01", "gym")]


def test_auto_workout_row_follows_rescore_and_spares_manual(monkeypatch) -> None:
    """Yesterday, no night: 54 bpm all day, 10:00-10:30 at 150 bpm = one detected 30 min span."""
    monkeypatch.delenv("TZ", raising=False)
    d = date.today() - timedelta(1)
    m = samples.midnight(d).timestamp()
    load({"biometric_hr_bpm": {m + i * 300: 150.0 if 120 <= i < 126 else 54.0 for i in range(288)}})
    for i in range(1, 15):
        db.save_day(d - timedelta(i), (None, None, None), {"inputs": {"rhr": 50.0}}, {})
    rows = lambda: [(e["id"], e["date"], e["kind"], e["value"], e["note"]) for e in db.journal("2000-01-01")]
    main._score_day(d)  # no birth year: no zones, no span
    assert rows() == []
    db.set_settings({"birth_year": "1990"})
    main._score_day(d)
    (auto,) = rows()
    assert auto[1:] == (d.isoformat(), "workout", None, "auto")
    main._score_day(d)
    assert rows() == [auto]  # same row, not re-written
    with TestClient(app) as c:
        assert c.get("/journal?days=2", headers=H).json()["entries"][0]["note"] == "auto"
        c.post("/journal", json={"date": d.isoformat(), "kind": "workout", "note": "gym"}, headers=H)  # the user logs it
    (manual,) = rows()
    assert manual[4] == "gym"
    main._score_day(d)
    assert rows() == [manual]  # a span is still found: the manual entry is not replaced
    db.set_settings({"birth_year": ""})
    main._score_day(d)
    assert rows() == [manual]  # no span any more: the manual entry stays
    db.delete_journal(manual[0])
    db.set_settings({"birth_year": "1990"})
    main._score_day(d)
    assert rows()[0][4] == "auto"
    db.set_settings({"birth_year": ""})
    main._score_day(d)
    assert rows() == []  # the auto row goes when a rescore finds no span


def test_evening_hr_suggest_today_and_never_logged(monkeypatch) -> None:
    """Night 23:00-06:00 ending yesterday; 80 bpm in the 3 h before it against a usual 70."""
    monkeypatch.delenv("TZ", raising=False)
    monkeypatch.setattr(main, "_vitals", lambda: {})
    d = date.today() - timedelta(1)
    t0 = samples.midnight(d).timestamp() - 3600
    load({"biometric_sleep_total_min": {t0: 420.0},
          "biometric_hr_bpm": {t0 - 10800 + i * 300: 80.0 for i in range(37)} | {t0 - 10800 - 300: 200.0, t0 - 600: 0.0}})

    def prior(evening: float | None, workouts: list[dict]) -> None:
        for i in range(1, 15):
            db.save_day(d - timedelta(i), (None, None, None),
                        {"inputs": {"evening_hr": evening}, "workouts": workouts if i == 1 else []}, {})

    with TestClient(app) as c:
        today = lambda: c.get(f"/today?date={d}", headers=H).json()
        assert today()["suggest"] is None and "evening_hr" not in today()["inputs"]  # day not scored yet
        main._score_day(d)  # no baseline yet
        assert today()["suggest"] is None and today()["inputs"]["evening_hr"] == 80  # before 20:00, off-finger, at 23:00: out
        prior(70.0, [])
        main._score_day(d)
        j = today()
        assert j["suggest"] == {"kinds": ["alcohol", "late_meal"], "delta": 10} and j["inputs"]["evening_hr"] == 80
        assert db.days(d, d)[0]["c"]["suggest"] == j["suggest"]
        assert c.get("/journal?days=30", headers=H).json()["entries"] == []  # offered, never logged
        prior(77.0, [])  # z = 3 / 3 = 1.0
        main._score_day(d)
        assert today()["suggest"] is None
        prior(70.0, [{"start": t0 - 5400, "end": t0 - 3600}])  # a workout ended 22:00
        main._score_day(d)
        assert today()["suggest"] is None and today()["inputs"]["evening_hr"] == 80
        prior(70.0, [{"start": t0 - 18000, "end": t0 - 10800}])  # ended 20:00, before the 3 h
        main._score_day(d)
        assert today()["suggest"]["delta"] == 10
    assert db.journal("2000-01-01") == []


def test_spo2_min_and_low_count_from_min_samples_when_present(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    d = date.today() - timedelta(1)
    t0 = samples.midnight(d).timestamp() - 3600
    load({"biometric_sleep_total_min": {t0: 420.0}, "biometric_spo2_pct": {t0 + 300: 95.0, t0 + 3600: 93.0}})
    main._score_day(d)
    spo2 = lambda: db.days(d, d)[0]["c"]["sleep_extra"]["spo2"]
    assert spo2() == {"mean": 94.0, "min": 93.0, "low_count": 0, "n": 2}  # no minimum samples: from the hourly means
    load({"biometric_spo2_min_pct": {t0 + 300: 91.0, t0 + 3600: 88.0, t0 + 7200: 0.0, t0 + 30 * 3600: 70.0}})
    main._score_day(d)
    assert spo2() == {"mean": 94.0, "min": 88.0, "low_count": 1, "n": 2}  # 0 = no reading; 70 is outside the night
    assert db.days(d, d)[0]["c"]["inputs"]["spo2"] == 93  # the strain input keeps its meaning
    with TestClient(app) as c:
        assert c.get(f"/sleep?date={d}", headers=H).json()["sleep_extra"]["spo2"] == spo2()
