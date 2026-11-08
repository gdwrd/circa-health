import json
import os
import sqlite3
from contextlib import closing
from datetime import date, datetime

SCHEMA = """
CREATE TABLE IF NOT EXISTS daily(date TEXT PRIMARY KEY, sleep_score REAL, readiness_score REAL,
  activity_score REAL, contributors TEXT, computed_at TEXT);
CREATE TABLE IF NOT EXISTS baselines(metric TEXT, date TEXT, value REAL, PRIMARY KEY(metric, date));
CREATE TABLE IF NOT EXISTS tags(ts INTEGER, tag TEXT, note TEXT);
CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT,
  body TEXT, created_at TEXT, acked_at TEXT);
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT);
INSERT OR IGNORE INTO settings VALUES('step_goal','8000'),('sleep_need_min','450');
"""


def connect() -> sqlite3.Connection:
    con = sqlite3.connect(os.environ.get("DB_PATH", "/data/ring.db"))
    con.row_factory = sqlite3.Row
    return con


def init() -> None:
    with connect() as con:
        con.executescript(SCHEMA)


def query(sql: str, args: tuple = ()) -> list[sqlite3.Row]:
    with closing(connect()) as con:
        return con.execute(sql, args).fetchall()


def setting(key: str) -> float:
    return float(query("SELECT value FROM settings WHERE key=?", (key,))[0][0])


def days(start: date, end: date) -> list[dict]:
    """daily rows in [start, end], ascending; `inputs` = raw per-day values kept in the contributors JSON."""
    out = []
    for r in query("SELECT * FROM daily WHERE date BETWEEN ? AND ? ORDER BY date", (start.isoformat(), end.isoformat())):
        c = json.loads(r["contributors"] or "{}")
        scores = {k: None if r[k] is None else int(r[k]) for k in ("sleep_score", "readiness_score", "activity_score")}
        out.append({"date": date.fromisoformat(r["date"]), **scores, "c": c, "inputs": c.get("inputs", {})})
    return out


def baselines(metric: str, start: date, end: date) -> dict[str, float]:
    rows = query("SELECT date, value FROM baselines WHERE metric=? AND date BETWEEN ? AND ?",
                 (metric, start.isoformat(), end.isoformat()))
    return {r["date"]: r["value"] for r in rows}


def save_day(d: date, scores: tuple[int | None, int | None, int | None], contributors: dict,
             base: dict[str, float]) -> None:
    with closing(connect()) as con, con:
        con.execute("INSERT OR REPLACE INTO daily VALUES(?,?,?,?,?,?)",
                    (d.isoformat(), *scores, json.dumps(contributors), datetime.now().isoformat()))
        con.executemany("INSERT OR REPLACE INTO baselines VALUES(?,?,?)", [(m, d.isoformat(), v) for m, v in base.items()])
