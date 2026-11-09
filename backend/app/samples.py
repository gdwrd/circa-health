import math
import os
import re
import time
from contextlib import closing
from datetime import date, datetime
from zoneinfo import ZoneInfo

from . import db

LINE = re.compile(r"([A-Za-z_:][\w:]*)(?:\{[^}]*\})?\s+(\S+)(?:\s+(\d+))?\s*")


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


def parse(body: bytes) -> list[tuple[str, int, float]]:
    """Prometheus text lines to (metric, ts_ms, value); labels dropped (one device), bad lines skipped."""
    out: list[tuple[str, int, float]] = []
    now = int(time.time() * 1000)
    for line in body.decode(errors="replace").splitlines():
        m = LINE.fullmatch(line.strip())
        if not m:
            continue
        try:
            v = float(m[2])
        except ValueError:
            continue
        if math.isfinite(v):
            out.append((m[1], int(m[3]) if m[3] else now, v))
    return out


def put(rows: list[tuple[str, int, float]]) -> None:
    """One transaction; a re-sent sample keeps the larger value."""
    with closing(db.connect()) as con, con:
        con.executemany("INSERT INTO samples VALUES(?,?,?) ON CONFLICT(metric, ts) DO UPDATE "
                        "SET value = excluded.value WHERE excluded.value > value", rows)


def raw(metric: str, start: datetime, end: datetime) -> dict[float, float]:
    """Samples in [start, end] as {unix_s: value}, ascending."""
    with closing(db.connect()) as con:
        rows = con.execute("SELECT ts, value FROM samples WHERE metric=? AND ts BETWEEN ? AND ? ORDER BY ts",
                           (metric, round(start.timestamp() * 1000), round(end.timestamp() * 1000))).fetchall()
    return {ts / 1000: v for ts, v in rows}


def grid(metric: str, start: datetime, end: datetime, step_s: int, lookback_s: int) -> dict[float, float]:
    """Points on the epoch-aligned step grid in [start, end]; each is the last sample in (t - lookback, t], else absent."""
    t = math.ceil(start.timestamp() / step_s) * step_s
    e = end.timestamp()
    with closing(db.connect()) as con:
        rows = con.execute("SELECT ts, value FROM samples WHERE metric=? AND ts > ? AND ts <= ? ORDER BY ts",
                           (metric, round((t - lookback_s) * 1000), round(e * 1000))).fetchall()
    out: dict[float, float] = {}
    i, last = 0, None
    while t <= e:
        while i < len(rows) and rows[i][0] <= t * 1000:
            last = rows[i]
            i += 1
        if last and last[0] > (t - lookback_s) * 1000:
            out[float(t)] = last[1]
        t += step_s
    return out
