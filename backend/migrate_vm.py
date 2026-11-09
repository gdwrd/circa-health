"""One-off: copy every VictoriaMetrics sample into ring.db `samples`, then rescore the last 60 days on a copy.

Run next to the `app` package: `python migrate_vm.py` (env: VM_URL, DB_PATH, TZ). Safe to re-run (larger value wins).
Only `samples` is written to the live ring.db; the rescore happens on a temp copy and its diffs are printed.
"""
import json
import math
import os
import sqlite3
import tempfile
from collections.abc import Iterable
from contextlib import closing
from datetime import datetime, timedelta

import httpx

from app import db, main, samples

MATCH = '{__name__=~"biometric_.*|ring_.*"}'
BATCH = 10_000
DAYS = 60


def convert(line: str) -> tuple[str, list[tuple[str, int, float]]]:
    """One VM export line to (metric, [(metric, ts_ms, value)]); labels dropped, non-finite values skipped."""
    o = json.loads(line)
    name = o["metric"]["__name__"]
    return name, [(name, int(t), float(v)) for t, v in zip(o["timestamps"], o["values"]) if v is not None and math.isfinite(v)]


def ingest(lines: Iterable[str]) -> dict[str, list[int]]:
    """Upsert in BATCH-row transactions; returns {metric: [values seen, series seen]}."""
    stats: dict[str, list[int]] = {}
    buf: list[tuple[str, int, float]] = []
    for line in lines:
        if not line.strip():
            continue
        name, rows = convert(line)
        s = stats.setdefault(name, [0, 0])
        s[0] += len(rows)
        s[1] += 1
        buf += rows
        while len(buf) >= BATCH:
            samples.put(buf[:BATCH])
            buf = buf[BATCH:]
    if buf:
        samples.put(buf)
    return stats


def export_lines() -> Iterable[str]:
    url = os.environ.get("VM_URL", "http://victoriametrics:8428") + "/api/v1/export"
    with httpx.stream("GET", url, params={"match[]": MATCH}, timeout=None) as r:
        r.raise_for_status()
        yield from r.iter_lines()


def report(stats: dict[str, list[int]]) -> None:
    with closing(db.connect()) as con:
        have = {r[0]: r[1:] for r in con.execute("SELECT metric, COUNT(*), MIN(ts), MAX(ts) FROM samples GROUP BY metric")}
    for m in sorted(stats):
        vm, series = stats[m]
        n, lo, hi = have.get(m, (0, None, None))
        note = "ok" if vm == n else (f"{vm - n} timestamps shared by {series} merged series" if vm > n and series > 1
                                      else "MISSING ROWS" if vm > n else f"sqlite has {n - vm} rows VM lacks (pushed since V1)")
        span = f"{datetime.fromtimestamp(lo / 1000, samples.tz()):%F %T} .. {datetime.fromtimestamp(hi / 1000, samples.tz()):%F %T}" if n else "-"
        print(f"{m}: vm={vm} sqlite={n} series={series} {span} [{note}]")


def rescore_diff() -> None:
    live = os.environ.get("DB_PATH", "/data/ring.db")
    cols = ("sleep_score", "readiness_score", "activity_score")
    today = datetime.now(samples.tz()).date()
    dates = [today - timedelta(i) for i in range(DAYS - 1, -1, -1)]  # ascending: baselines read the prior days' new rows
    with tempfile.TemporaryDirectory(ignore_cleanup_errors=True) as tmp:
        copy = os.path.join(tmp, "ring.db")
        with closing(sqlite3.connect(live)) as src, closing(sqlite3.connect(copy)) as dst:
            src.backup(dst)
        os.environ["DB_PATH"] = copy
        try:
            for d in dates:
                try:
                    main._score_day(d)
                except Exception as e:
                    print(f"{d}: rescore failed: {e!r}")
            new = {r["date"]: r for r in db.query("SELECT * FROM daily")}
        finally:
            os.environ["DB_PATH"] = live
    old = {r["date"]: r for r in db.query("SELECT * FROM daily")}
    bad = 0
    for d in dates:
        o, n = old.get(d.isoformat()), new.get(d.isoformat())
        pairs = [(c, o and o[c], n and n[c]) for c in cols]
        if any((a is None) != (b is None) or (a is not None and abs(a - b) > 2) for _, a, b in pairs):
            bad += 1
            print(f"{d}: " + ", ".join(f"{c.split('_')[0]} {a} -> {b}" for c, a, b in pairs))
    print(f"rescore {dates[0]}..{dates[-1]}: {bad} day(s) differ by more than 2")


if __name__ == "__main__":
    db.init()
    report(ingest(export_lines()))
    rescore_diff()
