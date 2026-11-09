import sqlite3
from datetime import datetime, timedelta

from fastapi.testclient import TestClient

from app import db
from app.main import app

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
                     "low_step_kind": "workout", "muted": "", "wake_weekday": "", "wake_weekend": "", "calm": ""}
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
