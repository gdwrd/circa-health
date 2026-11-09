import json
import os
import sqlite3
from contextlib import closing
from datetime import date, datetime

from . import vm

SCHEMA = """
CREATE TABLE IF NOT EXISTS daily(date TEXT PRIMARY KEY, sleep_score REAL, readiness_score REAL,
  activity_score REAL, contributors TEXT, computed_at TEXT);
CREATE TABLE IF NOT EXISTS baselines(metric TEXT, date TEXT, value REAL, PRIMARY KEY(metric, date));
CREATE TABLE IF NOT EXISTS journal(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, kind TEXT, value TEXT,
  note TEXT, ts INTEGER);
CREATE UNIQUE INDEX IF NOT EXISTS journal_day_kind ON journal(date, kind) WHERE kind != 'medication';
CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT,
  body TEXT, created_at TEXT, acked_at TEXT, extra TEXT);
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT);
INSERT OR IGNORE INTO settings VALUES('step_goal','8000'),('sleep_need_min','450'),('medication_started',''),
  ('medication_time','08:00'),('medication_window_h','8'),('birth_year',''),('low_step_kind','workout'),('muted','');
CREATE TABLE IF NOT EXISTS workout_kind(date TEXT, start INTEGER, kind TEXT, PRIMARY KEY(date, start));
CREATE TABLE IF NOT EXISTS sleep_override(date TEXT PRIMARY KEY, start INTEGER, "end" INTEGER);
"""


def connect() -> sqlite3.Connection:
    con = sqlite3.connect(os.environ.get("DB_PATH", "/data/ring.db"))
    con.row_factory = sqlite3.Row
    return con


KINDS = ("alcohol", "caffeine_late", "late_meal", "workout", "sick", "travel", "stress", "medication", "bp", "note")
TAG_ALIASES = {"caffeine": "caffeine_late"}


def init() -> None:
    with closing(connect()) as con, con:
        con.executescript(SCHEMA)
        if "extra" not in {r[1] for r in con.execute("PRAGMA table_info(notifications)")}:
            con.execute("ALTER TABLE notifications ADD COLUMN extra TEXT")
        _migrate_tags(con)


def _migrate_tags(con: sqlite3.Connection) -> None:
    """Copy v2 tags into an empty journal; `tags` stays in place (never delete data)."""
    if not con.execute("PRAGMA table_info(tags)").fetchall() or con.execute("SELECT 1 FROM journal LIMIT 1").fetchone():
        return
    rows: dict[tuple[str, str], list] = {}
    for ts, tag, note in con.execute("SELECT ts, tag, note FROM tags ORDER BY ts"):
        d = datetime.fromtimestamp(ts, vm.tz()).date().isoformat()
        k = (tag or "").strip().lower().replace(" ", "_").replace("-", "_")
        k = TAG_ALIASES.get(k, k)
        if k in KINDS and k not in ("medication", "bp", "note"):
            rows[(d, k)] = [d, k, None, note, ts]  # unique per (date, kind): latest wins
        else:
            text = f"{tag}: {note}" if note else tag
            prev = rows.get((d, "note"))
            rows[(d, "note")] = [d, "note", None, f"{prev[3]}; {text}" if prev else text, ts]
    con.executemany("INSERT INTO journal(date, kind, value, note, ts) VALUES(?,?,?,?,?)", list(rows.values()))


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


def settings() -> dict[str, str]:
    return {r["key"]: r["value"] for r in query("SELECT key, value FROM settings")}


def set_settings(values: dict[str, str]) -> None:
    with closing(connect()) as con, con:
        con.executemany("INSERT OR REPLACE INTO settings VALUES(?,?)", list(values.items()))


def add_journal(d: str, kind: str, value: str | None, note: str | None, ts: int) -> int:
    verb = "INSERT" if kind == "medication" else "INSERT OR REPLACE"  # several doses a day; other kinds once
    with closing(connect()) as con, con:
        return con.execute(f"{verb} INTO journal(date, kind, value, note, ts) VALUES(?,?,?,?,?)",
                           (d, kind, value, note, ts)).lastrowid


def journal(since: str) -> list[dict]:
    return [dict(r) for r in query("SELECT id, date, kind, value, note, ts FROM journal WHERE date>=? "
                                   "ORDER BY date DESC, ts DESC, id DESC", (since,))]


def delete_journal(id: int) -> None:
    with closing(connect()) as con, con:
        con.execute("DELETE FROM journal WHERE id=?", (id,))


def workout_kinds(d: date) -> dict[int, str]:
    return {r["start"]: r["kind"] for r in query("SELECT start, kind FROM workout_kind WHERE date=?", (d.isoformat(),))}


def set_workout_kind(d: date, start: int, kind: str) -> None:
    with closing(connect()) as con, con:
        con.execute("INSERT OR REPLACE INTO workout_kind VALUES(?,?,?)", (d.isoformat(), start, kind))


def sleep_override(d: date) -> tuple[int, int] | None:
    r = query('SELECT start, "end" FROM sleep_override WHERE date=?', (d.isoformat(),))
    return (r[0][0], r[0][1]) if r else None


def set_sleep_override(d: date, start: int, end: int) -> None:
    with closing(connect()) as con, con:
        con.execute("INSERT OR REPLACE INTO sleep_override VALUES(?,?,?)", (d.isoformat(), start, end))


def delete_sleep_override(d: date) -> None:
    with closing(connect()) as con, con:
        con.execute("DELETE FROM sleep_override WHERE date=?", (d.isoformat(),))
