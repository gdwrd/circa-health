import sqlite3
from datetime import datetime, timedelta
from pathlib import Path

from app import db, samples


def test_backup(tmp_path, monkeypatch):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "circa.db"))
    db.init()
    folder = tmp_path / "backups"
    folder.mkdir()
    today = datetime.now(samples.tz()).date()
    old = folder / f"ring-{today - timedelta(15)}.db"  # pre-Circa prefix: still pruned after 14 days
    keep = folder / f"circa-{today - timedelta(14)}.db"
    other = folder / "notes.txt"
    for f in (old, keep, other):
        f.write_text("x")

    db.backup()
    new = folder / f"circa-{today}.db"
    assert new.exists() and not old.exists() and keep.exists() and other.exists()
    assert sqlite3.connect(new).execute("PRAGMA integrity_check").fetchone()[0] == "ok"

    mtime = new.stat().st_mtime_ns
    db.backup()
    assert new.stat().st_mtime_ns == mtime


def _make_db(path: Path, row: int) -> None:
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE t(x INTEGER)")
    con.execute("INSERT INTO t VALUES(?)", (row,))
    con.commit()
    con.close()


def test_adopt_old_db(tmp_path, monkeypatch) -> None:
    folder = tmp_path / "adopt"
    folder.mkdir()
    _make_db(folder / "ring.db", 42)
    monkeypatch.setenv("DB_PATH", str(folder / "circa.db"))
    db.init()
    assert not (folder / "ring.db").exists()
    assert sqlite3.connect(folder / "circa.db").execute("SELECT x FROM t").fetchone()[0] == 42


def test_adopt_moves_journal(tmp_path, monkeypatch) -> None:
    folder = tmp_path / "adopt"
    folder.mkdir()
    _make_db(folder / "ring.db", 42)
    journal = folder / "ring.db-journal"
    journal.write_bytes(b"rollback-bytes")
    monkeypatch.setenv("DB_PATH", str(folder / "circa.db"))
    db._adopt_old_db()
    assert (folder / "circa.db").exists()
    moved = folder / "circa.db-journal"
    assert moved.exists() and moved.read_bytes() == b"rollback-bytes"
    assert not (folder / "ring.db").exists() and not journal.exists()


def test_adopt_keeps_both_when_new_exists(tmp_path, monkeypatch) -> None:
    folder = tmp_path / "adopt"
    folder.mkdir()
    _make_db(folder / "ring.db", 42)
    _make_db(folder / "circa.db", 7)
    monkeypatch.setenv("DB_PATH", str(folder / "circa.db"))
    db.init()
    assert (folder / "ring.db").exists()
    assert sqlite3.connect(folder / "circa.db").execute("SELECT x FROM t").fetchone()[0] == 7
