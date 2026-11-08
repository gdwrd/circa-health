from datetime import date, datetime, timedelta

import httpx
from fastapi.testclient import TestClient

from app import db, vm
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
                "biometric_sleep_stage": 2 if first else None,
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
