import os
import time
from datetime import date, datetime, timedelta

from fastapi.testclient import TestClient

from app import db, main, samples
from app.main import app
from conftest import load


def ms(y: int, mo: int, d: int, h: int, mi: int) -> int:
    return int(datetime(y, mo, d, h, mi).timestamp() * 1000)  # host-local, matches TZ-unset parsing


def test_dates_in_body_spans_midnight(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    body = (
        f'biometric_hr_bpm{{a="b"}} 60 {ms(2026, 1, 1, 23, 50)}\n'
        f"biometric_steps 5 {ms(2026, 1, 2, 0, 10)}\n"
        f"biometric_steps 7 {ms(2026, 1, 2, 9, 0)}\n"
    ).encode()
    assert samples.dates_in_body(body) == {date(2026, 1, 1), date(2026, 1, 2)}


def test_auth() -> None:
    c = TestClient(app)
    assert c.get("/health").status_code == 200
    assert c.get("/today").status_code == 401
    assert c.get("/today", headers={"Authorization": "Bearer wrong"}).status_code == 401
    r = c.get("/today", headers={"Authorization": f"Bearer {os.environ['API_TOKEN']}"})
    assert r.status_code == 200 and r.json()["status"] == "learning"


def test_parse_skips_bad_lines_and_drops_labels() -> None:
    body = (b'biometric_hr_bpm{device="colmi_r02",source="live"} 61 1000\n'
            b"ring_battery_pct 80 2000\n"
            b"biometric_steps NaN 3000\nbiometric_steps +Inf 3000\njunk\n\n{x} 1 2\nbiometric_steps abc 3000\n")
    assert samples.parse(body) == [("biometric_hr_bpm", 1000, 61.0), ("ring_battery_pct", 2000, 80.0)]
    ((m, ts, v),) = samples.parse(b"biometric_steps 5\n")  # no timestamp: now
    assert m == "biometric_steps" and v == 5.0 and abs(ts - time.time() * 1000) < 5000


def test_put_keeps_larger_value() -> None:
    samples.put([("a", 1_000_001_000, 5.0), ("a", 1_000_002_000, 7.0)])
    samples.put([("a", 1_000_001_000, 9.0), ("a", 1_000_002_000, 3.0), ("b", 1_000_001_000, 1.0)])
    t = datetime.fromtimestamp(1_000_000)
    assert samples.raw("a", t, t + timedelta(seconds=2)) == {1_000_001.0: 9.0, 1_000_002.0: 7.0}
    assert samples.raw("a", t + timedelta(seconds=1), t + timedelta(seconds=1)) == {1_000_001.0: 9.0}  # inclusive range
    assert samples.raw("b", t, t + timedelta(seconds=5)) == {1_000_001.0: 1.0}


def test_grid_no_carry_for_hourly_and_3h_for_stages() -> None:
    h = datetime(2026, 11, 3, 0, 0).timestamp() // 3600 * 3600  # an hour boundary
    load({"steps": {h + 3600 * 2: 10.0, h + 3600 * 5 + 120: 4.0}, "stage": {h + 600: 2.0, h + 7200: 0.0}, "lone": {h + 600: 2.0}})
    a, z = datetime.fromtimestamp(h), datetime.fromtimestamp(h + 3600 * 8 - 1)
    assert samples.grid("steps", a, z, 3600, 3540) == {h + 7200: 10.0, h + 3600 * 6: 4.0}  # hours 3-5, 7 stay empty
    st = samples.grid("stage", a, datetime.fromtimestamp(h + 4 * 3600), 300, 10800)
    assert st[h + 600] == 2.0 and st[h + 7200 - 300] == 2.0 and st[h + 7200] == 0.0 and h not in st
    assert samples.grid("lone", a, datetime.fromtimestamp(h + 20000), 300, 10800).get(h + 600 + 10800) is None  # 3 h gone
