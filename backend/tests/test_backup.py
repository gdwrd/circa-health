import sqlite3
from datetime import datetime, timedelta

from app import db, samples


def test_backup(tmp_path, monkeypatch):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "ring.db"))
    db.init()
    folder = tmp_path / "backups"
    folder.mkdir()
    today = datetime.now(samples.tz()).date()
    old = folder / f"ring-{today - timedelta(15)}.db"
    keep = folder / f"ring-{today - timedelta(14)}.db"
    other = folder / "notes.txt"
    for f in (old, keep, other):
        f.write_text("x")

    db.backup()
    new = folder / f"ring-{today}.db"
    assert new.exists() and not old.exists() and keep.exists() and other.exists()
    assert sqlite3.connect(new).execute("PRAGMA integrity_check").fetchone()[0] == "ok"

    mtime = new.stat().st_mtime_ns
    db.backup()
    assert new.stat().st_mtime_ns == mtime
