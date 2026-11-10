import json
import os
import re
import sqlite3
import threading
from contextlib import closing
from datetime import date, datetime, timedelta
from pathlib import Path

from . import samples

# several medication doses and breathing sessions a day; other kinds once
INDEX = ("CREATE UNIQUE INDEX IF NOT EXISTS journal_day_kind ON journal(date, kind) "
         "WHERE kind NOT IN ('medication', 'breathe')")
SCHEMA = f"""
CREATE TABLE IF NOT EXISTS daily(date TEXT PRIMARY KEY, sleep_score REAL, readiness_score REAL,
  activity_score REAL, contributors TEXT, computed_at TEXT);
CREATE TABLE IF NOT EXISTS baselines(metric TEXT, date TEXT, value REAL, PRIMARY KEY(metric, date));
CREATE TABLE IF NOT EXISTS journal(id INTEGER PRIMARY KEY AUTOINCREMENT, date TEXT, kind TEXT, value TEXT,
  note TEXT, ts INTEGER);
{INDEX};
CREATE TABLE IF NOT EXISTS notifications(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, title TEXT,
  body TEXT, created_at TEXT, acked_at TEXT, extra TEXT);
CREATE TABLE IF NOT EXISTS samples(metric TEXT, ts INTEGER, value REAL, PRIMARY KEY(metric, ts)) WITHOUT ROWID;
CREATE TABLE IF NOT EXISTS settings(key TEXT PRIMARY KEY, value TEXT);
INSERT OR IGNORE INTO settings VALUES('step_goal','8000'),('sleep_need_min','450'),('medication_started',''),
  ('medication_time','08:00'),('medication_window_h','8'),('birth_year',''),('low_step_kind','workout'),('muted',''),
  ('wake_weekday',''),('wake_weekend',''),('calm',''),('guess','');
CREATE TABLE IF NOT EXISTS workout_kind(date TEXT, start INTEGER, kind TEXT, PRIMARY KEY(date, start));
CREATE TABLE IF NOT EXISTS workout_manual(date TEXT, start INTEGER, "end" INTEGER, kind TEXT, PRIMARY KEY(date, start));
CREATE TABLE IF NOT EXISTS sleep_override(date TEXT PRIMARY KEY, start INTEGER, "end" INTEGER);
CREATE TABLE IF NOT EXISTS breath_session(start INTEGER PRIMARY KEY, "end" INTEGER, rounds INTEGER,
  hr_start REAL, hr_end REAL, hr_min REAL, feel_before INTEGER, feel_after INTEGER, stress_before REAL,
  stress_after REAL);
CREATE TABLE IF NOT EXISTS experiment(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, metric TEXT, start TEXT,
  days INTEGER, plan TEXT, ended TEXT);
CREATE TABLE IF NOT EXISTS guess(date TEXT PRIMARY KEY, value INTEGER, verdict INTEGER, ts INTEGER);
CREATE TABLE IF NOT EXISTS note(key TEXT PRIMARY KEY, text TEXT, ts INTEGER);
"""


def connect() -> sqlite3.Connection:
    con = sqlite3.connect(os.environ.get("DB_PATH", "/data/ring.db"))
    con.row_factory = sqlite3.Row
    return con


BACKUP = re.compile(r"ring-(\d{4}-\d{2}-\d{2})\.db")
BACKUP_LOCK = threading.Lock()  # concurrent rescores share the .tmp file


def backup() -> None:
    """Dated copy of ring.db next to it; keeps 14 days, judged by the date in the name (other files are never touched)."""
    folder = Path(os.environ.get("DB_PATH", "/data/ring.db")).parent / "backups"
    today = datetime.now(samples.tz()).date()
    target = folder / f"ring-{today.isoformat()}.db"
    with BACKUP_LOCK:
        if not target.exists():
            folder.mkdir(exist_ok=True)
            tmp = target.with_suffix(".tmp")
            with closing(connect()) as src, closing(sqlite3.connect(tmp)) as dst:
                src.backup(dst)
            tmp.replace(target)
    for f in folder.iterdir():
        m = BACKUP.fullmatch(f.name)
        if m and date.fromisoformat(m[1]) < today - timedelta(14):
            f.unlink()


KINDS = ("alcohol", "caffeine_late", "late_meal", "workout", "sick", "travel", "stress", "medication", "bp", "note", "breathe", "phone_in_bed")
TAG_ALIASES = {"caffeine": "caffeine_late"}


def init() -> None:
    with closing(connect()) as con, con:
        con.executescript(SCHEMA)
        if "extra" not in {r[1] for r in con.execute("PRAGMA table_info(notifications)")}:
            con.execute("ALTER TABLE notifications ADD COLUMN extra TEXT")
        _migrate_tags(con)
        if "breathe" not in con.execute("SELECT sql FROM sqlite_master WHERE name='journal_day_kind'").fetchone()[0]:
            con.execute("DROP INDEX IF EXISTS journal_day_kind")
            con.execute(INDEX)


def _migrate_tags(con: sqlite3.Connection) -> None:
    """Copy v2 tags into an empty journal; `tags` stays in place (never delete data)."""
    if not con.execute("PRAGMA table_info(tags)").fetchall() or con.execute("SELECT 1 FROM journal LIMIT 1").fetchone():
        return
    rows: dict[tuple[str, str], list] = {}
    for ts, tag, note in con.execute("SELECT ts, tag, note FROM tags ORDER BY ts"):
        d = datetime.fromtimestamp(ts, samples.tz()).date().isoformat()
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
    """daily rows in [start, end], ascending; `inputs` = raw per-day values kept in the contributors JSON;
    `felt` = that morning's readiness guess (read from `guess`, never stored in `daily`)."""
    out, g = [], guesses(start, end)
    for r in query("SELECT * FROM daily WHERE date BETWEEN ? AND ? ORDER BY date", (start.isoformat(), end.isoformat())):
        c = json.loads(r["contributors"] or "{}")
        scores = {k: None if r[k] is None else int(r[k]) for k in ("sleep_score", "readiness_score", "activity_score")}
        out.append({"date": date.fromisoformat(r["date"]), **scores, "c": c, "inputs": c.get("inputs", {}),
                    "felt": g.get(r["date"], {}).get("value")})
    return out


def guesses(start: date, end: date) -> dict[str, dict]:
    """{iso date: {value, verdict}}; value None = skipped ("Just show me"), verdict None = not answered."""
    return {r["date"]: {"value": r["value"], "verdict": r["verdict"]} for r in query(
        "SELECT date, value, verdict FROM guess WHERE date BETWEEN ? AND ?", (start.isoformat(), end.isoformat()))}


def add_guess(d: date, value: int | None, ts: int) -> None:
    """The first answer of a day stands: no second guess after the reveal."""
    with closing(connect()) as con, con:
        con.execute("INSERT OR IGNORE INTO guess(date, value, ts) VALUES(?,?,?)", (d.isoformat(), value, ts))


def set_verdict(d: date, verdict: int) -> bool:
    """False when d has no guess with a value (none yet, or skipped)."""
    with closing(connect()) as con, con:
        return con.execute("UPDATE guess SET verdict=? WHERE date=? AND value IS NOT NULL",
                           (verdict, d.isoformat())).rowcount > 0


def note(key: str) -> str | None:
    """The user's note to themselves for a Last time key ("signals" or kinds joined with "+")."""
    r = query("SELECT text FROM note WHERE key=?", (key,))
    return r[0][0] if r else None


def set_note(key: str, text: str, ts: int) -> None:
    """Empty text removes the note."""
    with closing(connect()) as con, con:
        if text:
            con.execute("INSERT OR REPLACE INTO note VALUES(?,?,?)", (key, text, ts))
        else:
            con.execute("DELETE FROM note WHERE key=?", (key,))


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


def delete_phone_in_bed(d: str) -> None:
    with closing(connect()) as con, con:
        con.execute("DELETE FROM journal WHERE date=? AND kind='phone_in_bed'", (d,))


def add_auto(d: str, kind: str) -> None:
    """Auto journal row; an entry already there for that day and kind (manual or auto) stays as it is.
    Only for kinds under the journal_day_kind unique index (not medication / breathe)."""
    with closing(connect()) as con, con:
        con.execute("INSERT OR IGNORE INTO journal(date, kind, value, note, ts) VALUES(?,?,NULL,'auto',?)",
                    (d, kind, int(datetime.now().timestamp())))


def delete_auto(d: str, kind: str) -> None:
    """Removes only the row add_auto wrote; a manual entry stays."""
    # shortcut: a manual entry whose note is exactly 'auto' counts as auto, add an `auto` column if users type that
    with closing(connect()) as con, con:
        con.execute("DELETE FROM journal WHERE date=? AND kind=? AND note='auto'", (d, kind))


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


def manual_workouts(d: date) -> list[dict]:
    """Workouts the user entered by hand for local date d."""
    return [dict(r) for r in query('SELECT start, "end", kind FROM workout_manual WHERE date=? ORDER BY start',
                                   (d.isoformat(),))]


def add_manual_workout(d: date, start: int, end: int, kind: str) -> None:
    with closing(connect()) as con, con:
        con.execute("INSERT OR REPLACE INTO workout_manual VALUES(?,?,?,?)", (d.isoformat(), start, end, kind))


def set_manual_workout_kind(d: date, start: int, kind: str) -> bool:
    """Relabels the manual workout at (d, start), or removes it for kind "none"; False when there is none."""
    sql, args = ("DELETE FROM workout_manual WHERE date=? AND start=?", (d.isoformat(), start)) if kind == "none" else \
        ("UPDATE workout_manual SET kind=? WHERE date=? AND start=?", (kind, d.isoformat(), start))
    with closing(connect()) as con, con:
        return con.execute(sql, args).rowcount > 0


def sleep_override(d: date) -> tuple[int, int] | None:
    r = query('SELECT start, "end" FROM sleep_override WHERE date=?', (d.isoformat(),))
    return (r[0][0], r[0][1]) if r else None


def set_sleep_override(d: date, start: int, end: int) -> None:
    with closing(connect()) as con, con:
        con.execute("INSERT OR REPLACE INTO sleep_override VALUES(?,?,?)", (d.isoformat(), start, end))


def delete_sleep_override(d: date) -> None:
    with closing(connect()) as con, con:
        con.execute("DELETE FROM sleep_override WHERE date=?", (d.isoformat(),))


def add_breath(row: dict) -> None:
    """Session row + its journal row; a re-sent session (same start) replaces the row and keeps one journal entry."""
    t = datetime.fromtimestamp(row["start"], samples.tz())
    with closing(connect()) as con, con:
        con.execute("INSERT OR REPLACE INTO breath_session VALUES(:start, :end, :rounds, :hr_start, :hr_end, :hr_min, "
                    ":feel_before, :feel_after, :stress_before, :stress_after)", row)
        con.execute("INSERT INTO journal(date, kind, value, note, ts) SELECT ?, 'breathe', ?, NULL, ? "
                    "WHERE NOT EXISTS (SELECT 1 FROM journal WHERE kind='breathe' AND ts=?)",
                    (t.date().isoformat(), t.strftime("%H:%M"), row["start"], row["start"]))


def breaths(start: date, end: date) -> list[dict]:
    """Sessions starting on local dates [start, end], ascending."""
    lo, hi = samples.midnight(start).timestamp(), samples.midnight(end + timedelta(1)).timestamp()
    return [dict(r) for r in query("SELECT * FROM breath_session WHERE start >= ? AND start < ? ORDER BY start", (lo, hi))]


def set_breath_stress(start: int, before: float | None, after: float | None) -> None:
    with closing(connect()) as con, con:
        con.execute("UPDATE breath_session SET stress_before=?, stress_after=? WHERE start=?", (before, after, start))


def experiments() -> list[dict]:
    """Newest first; at most one row has ended NULL (the active one)."""
    return [dict(r) for r in query("SELECT * FROM experiment ORDER BY id DESC")]


def add_experiment(kind: str, metric: str, start: str, days: int, plan: str) -> None:
    with closing(connect()) as con, con:
        con.execute("UPDATE experiment SET ended=? WHERE ended IS NULL", (start,))  # a finished one still open
        con.execute("INSERT INTO experiment(kind, metric, start, days, plan) VALUES(?,?,?,?,?)",
                    (kind, metric, start, days, plan))


def end_experiment(id: int, ended: str) -> None:
    """Rows are ended, never deleted."""
    with closing(connect()) as con, con:
        con.execute("UPDATE experiment SET ended=? WHERE id=? AND ended IS NULL", (ended, id))
