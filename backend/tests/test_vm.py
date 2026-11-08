import os
from datetime import date, datetime

from fastapi.testclient import TestClient

from app import vm
from app.main import app


def ms(y: int, mo: int, d: int, h: int, mi: int) -> int:
    return int(datetime(y, mo, d, h, mi).timestamp() * 1000)  # host-local, matches TZ-unset parsing


def test_dates_in_body_spans_midnight(monkeypatch) -> None:
    monkeypatch.delenv("TZ", raising=False)
    body = (
        f'biometric_hr_bpm{{a="b"}} 60 {ms(2026, 1, 1, 23, 50)}\n'
        f"biometric_steps 5 {ms(2026, 1, 2, 0, 10)}\n"
        f"biometric_steps 7 {ms(2026, 1, 2, 9, 0)}\n"
    ).encode()
    assert vm.dates_in_body(body) == {date(2026, 1, 1), date(2026, 1, 2)}


def test_auth() -> None:
    c = TestClient(app)
    assert c.get("/health").status_code == 200
    assert c.get("/today").status_code == 401
    assert c.get("/today", headers={"Authorization": "Bearer wrong"}).status_code == 401
    r = c.get("/today", headers={"Authorization": f"Bearer {os.environ['API_TOKEN']}"})
    assert r.json() == {"status": "learning"}
