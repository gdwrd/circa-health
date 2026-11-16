from datetime import date, timedelta

from fastapi.testclient import TestClient

from app import db, main, samples, scoring
from app.main import app
from conftest import load

H = {"Authorization": "Bearer secret"}


def test_scale_cancel_mean_and_latest() -> None:
    assert scoring.scale("mood_energy", {10: 2.0, 20: 4.0, 30: 9.0}) == 3.0  # a 9 (cancelled tap) is ignored
    assert scoring.scale("rate_focus", {30: 1.0, 10: 2.0, 20: 4.0}) == 1.0  # the latest rating
    assert scoring.scale("rate_focus", {30: 9.0, 10: 2.0, 20: 4.0}) is None  # a later 9 cleared it
    assert scoring.scale("rate_focus", {10: 9.0, 20: 3.0}) == 3.0  # rated again after a clear
    assert scoring.scale("rate_crash", {10: 9.0}) is None and scoring.scale("mood_valence", {}) is None


def test_mind_cleared_rating() -> None:
    d = date.today()
    m, y = samples.midnight(d).timestamp(), samples.midnight(d - timedelta(1)).timestamp()
    load({"rate_focus": {m + 3600: 4.0, y + 3600: 9.0, y + 7200: 2.0}, "rate_crash": {y + 3600: 3.0}})
    load({"rate_focus": {m + 3600: 9.0}, "rate_crash": {y + 5400: 9.0}})  # a 9 at the rating's second, and a later 9
    with TestClient(app) as c:
        j = c.get("/mind?days=2", headers=H).json()["days"]
        assert [(x["focus"], x["crash"]) for x in j] == [(2.0, None), (None, None)]


def test_mind_and_today_mood(monkeypatch) -> None:
    monkeypatch.setattr(main, "_vitals", lambda: {})
    d = date.today()
    m = samples.midnight(d).timestamp()
    y = samples.midnight(d - timedelta(1)).timestamp()
    load({"mood_energy": {m + 3600: 2.0, m + 7200: 4.0, m + 9000: 5.0},
          "mood_valence": {m + 3600: 3.0}, "rate_focus": {m + 3600: 1.0, m + 7200: 4.0},
          "moment_foggy": {m + 3600: 1.0, m + 7200: 1.0, m + 9000: 1.0},
          "moment_tired": {y + 3600: 1.0},  # an old feeling still reads on an earlier day
          "moment_happy": {m + 3600: 1.0},  # an unknown feeling is not a moment
          "phq9_total": {y + 600: 7.0}})
    load({"mood_energy": {m + 9000: 9.0}, "moment_foggy": {m + 9000: 2.0}})  # the 5 and one foggy tap are cancelled
    db.add_journal(d.isoformat(), "load_social", None, None, 1)
    db.set_settings({"medication_doses": f'[{{"from": "{d - timedelta(1)}", "mg": 30}}, {{"from": "{d}", "mg": 40}}]'})
    with TestClient(app) as c:
        assert c.post("/journal", json={"date": d.isoformat(), "kind": "load_masking"}, headers=H).status_code == 200
        j = c.get("/mind?days=3", headers=H).json()
        assert [x["date"] for x in j["days"]] == [(d - timedelta(i)).isoformat() for i in (2, 1, 0)]
        assert j["days"][0] == {"date": (d - timedelta(2)).isoformat(), "energy": None, "valence": None, "focus": None,
                                "appetite": None, "crash": None, "feelings": {}, "load": [], "mg": None}
        assert j["days"][1]["feelings"] == {"tired": 1}
        assert [x["mg"] for x in j["days"]] == [None, 30, 40]  # before the first step, then each step's dose
        t = j["days"][2]
        assert (t["energy"], t["valence"], t["focus"], t["feelings"], t["load"]) == \
            (3.0, 3.0, 4.0, {"foggy": 2}, ["load_masking", "load_social"])
        assert j["phq"] == [{"ts": y + 600, "total": 7.0}]
        assert c.get("/today", headers=H).json()["now"]["mood"] == {"energy": 4.0, "valence": 3.0}
        assert "happy" not in [x["kind"] for x in c.get("/today", headers=H).json()["moments"]]
