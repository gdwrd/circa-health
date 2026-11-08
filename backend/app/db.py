import os
import sqlite3

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
