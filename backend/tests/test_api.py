from datetime import date, datetime, timedelta

import httpx
from fastapi.testclient import TestClient

from app import db, main, vm
from app.main import app

H = {"Authorization": "Bearer secret"}
STEP = {"5m": 300, "30m": 1800, "1h": 3600}


def test_ingest_rescores_and_today_has_sleep_score(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    d = date.today()
    ss = (vm.midnight(d) - timedelta(minutes=30)).timestamp()  # 23:30 yesterday
    es = (vm.midnight(d) + timedelta(hours=7)).timestamp()

    def fake(expr: str, start: datetime, end: datetime, step: str) -> list[dict]:
        out, t = [], start.timestamp()
        while t <= end.timestamp():
            hour = datetime.fromtimestamp(t).hour
            first = ss <= t < ss + 300
            v = {
                "biometric_sleep_end_ts": es if first else None,
                "biometric_sleep_total_min": 420 if first else None,
                "biometric_sleep_awake_min": 30 if first else None,
                "biometric_sleep_rem_min": 90 if first else None,
                "biometric_sleep_deep_min": 80 if first else None,
                "last_over_time(biometric_sleep_stage[3h])": 2 if first else None,
                "biometric_hr_bpm": 54 if ss <= t <= es else 70,
                "biometric_hrv_rmssd": 45,
                "biometric_stress": 80 if hour < 10 else 30,
                "biometric_steps": 500 if 8 <= hour <= 20 else None,
                "biometric_calories": 90 if 8 <= hour <= 20 else None,
                "biometric_spo2_pct": 97,
                "ring_battery_pct": 80,
            }[expr]
            if v is not None:
                out.append([t, str(v)])
            t += STEP[step]
        return [{"metric": {}, "values": out}] if out else []

    sent: list[bytes] = []
    monkeypatch.setattr(vm, "query_range", fake)
    monkeypatch.setattr(vm, "forward_import", lambda b: sent.append(b) or httpx.Response(204))
    with TestClient(app) as c:
        body = f"biometric_steps 5 {int(datetime.now().timestamp() * 1000)}\n"
        assert c.post("/api/v1/import/prometheus", content=body, headers=H).status_code == 204
        j = c.get("/today", headers=H).json()
        print(j)
        assert j["sleep"]["score"] is not None and j["status"] == "learning" and j["baseline_days"] == "1/14"
        assert j["activity"]["score"] is not None and j["vitals"]["hr"] == 70
        assert b"score_sleep" in sent[-1]
        s = c.get("/sleep", headers=H).json()
        assert s["session"]["total_min"] == 420 and s["stages"]
        t = c.get("/trends?metric=sleep&days=7", headers=H).json()
        assert t["points"][-1]["value"] == j["sleep"]["score"]
        assert c.get("/trends?metric=bogus", headers=H).status_code == 422
        assert db.days(d, d)


def test_hr_drops_nonpositive_and_clamps_hours(monkeypatch) -> None:
    seen: list[float] = []

    def fake(expr: str, start: datetime, end: datetime, step: str) -> list[dict]:
        seen.append((end - start).total_seconds() / 3600)
        t = start.timestamp()
        return [{"metric": {}, "values": [[t, "0"], [t + 300, "61"]]}]

    monkeypatch.setattr(vm, "query_range", fake)
    with TestClient(app) as c:
        p = c.get("/hr?hours=24", headers=H).json()["points"]
        assert [x["bpm"] for x in p] == [61.0]
        c.get("/hr?hours=999", headers=H)
        assert seen[-1] == 48
        assert c.get("/hr").status_code == 401


def test_tags() -> None:
    with TestClient(app) as c:
        assert c.post("/tags", json={"tag": "Alcohol"}).status_code == 401
        assert c.post("/tags", json={"tag": " "}, headers=H).status_code == 422
        assert c.post("/tags", json={"tag": "x" * 41}, headers=H).status_code == 422
        assert c.post("/tags", json={"tag": " Alcohol ", "note": "2 beers"}, headers=H).json() == {"status": "ok"}
        assert c.post("/tags", json={"tag": "Old", "ts": 1}, headers=H).status_code == 200
        t = c.get("/tags?days=1", headers=H).json()["tags"]
        assert [x["tag"] for x in t] == ["Alcohol"] and t[0]["note"] == "2 beers"


def test_night_without_end_ts(monkeypatch) -> None:
    """Sessions pushed by app v1.0 carry only *_min series; the night still resolves."""
    d = date.today()
    t0 = (vm.midnight(d) - timedelta(minutes=30)).timestamp()
    monkeypatch.setattr(vm, "series", lambda expr, s, e, step: {t0: 420.0} if expr == "biometric_sleep_total_min" else {})
    n = main._night(d)
    assert n is not None and n["end"] == t0 + 420 * 60 and n["total_min"] == 420
