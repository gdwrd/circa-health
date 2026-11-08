import os
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

import httpx


def _vm() -> str:
    return os.environ.get("VM_URL", "http://victoriametrics:8428")


def forward_import(body: bytes) -> httpx.Response:
    return httpx.post(f"{_vm()}/api/v1/import/prometheus", content=body, timeout=30)


def dates_in_body(body: bytes) -> set[date]:
    tz = ZoneInfo(os.environ["TZ"]) if os.environ.get("TZ") else None  # unset: host local time
    out: set[date] = set()
    for line in body.decode(errors="replace").splitlines():
        try:
            ms = int(line.rsplit(None, 1)[-1])
        except ValueError:
            continue
        out.add(datetime.fromtimestamp(ms / 1000, tz).date())
    return out


def query_range(expr: str, start: datetime, end: datetime, step: str) -> list[dict]:
    r = httpx.get(
        f"{_vm()}/api/v1/query_range",
        params={"query": expr, "start": start.timestamp(), "end": end.timestamp(), "step": step},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["data"]["result"]
