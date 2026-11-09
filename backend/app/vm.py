import os
from datetime import date, datetime, timezone
from zoneinfo import ZoneInfo

import httpx


def _vm() -> str:
    return os.environ.get("VM_URL", "http://victoriametrics:8428")


def forward_import(body: bytes) -> httpx.Response:
    return httpx.post(f"{_vm()}/api/v1/import/prometheus", content=body, timeout=30)


def tz() -> ZoneInfo | None:
    return ZoneInfo(os.environ["TZ"]) if os.environ.get("TZ") else None  # unset: host local time


def midnight(d: date) -> datetime:
    return datetime(d.year, d.month, d.day, tzinfo=tz())


def dates_in_body(body: bytes) -> set[date]:
    tz_ = tz()
    out: set[date] = set()
    for line in body.decode(errors="replace").splitlines():
        try:
            ms = int(line.rsplit(None, 1)[-1])
        except ValueError:
            continue
        out.add(datetime.fromtimestamp(ms / 1000, tz_).date())
    return out


def query_range(expr: str, start: datetime, end: datetime, step: str, lookback: str | None = None) -> list[dict]:
    params = {"query": expr, "start": start.timestamp(), "end": end.timestamp(), "step": step}
    if lookback:
        params["max_lookback"] = lookback
    r = httpx.get(
        f"{_vm()}/api/v1/query_range",
        params=params,
        timeout=30,
    )
    r.raise_for_status()
    return r.json()["data"]["result"]


def series(expr: str, start: datetime, end: datetime, step: str, lookback: str | None = None) -> dict[float, float]:
    """First result series as {unix_ts: value}, ascending."""
    res = query_range(expr, start, end, step, lookback)
    return {float(t): float(v) for t, v in res[0]["values"]} if res else {}
