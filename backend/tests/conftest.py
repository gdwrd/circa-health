import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
os.environ["DB_PATH"] = os.path.join(tempfile.mkdtemp(), "ring.db")
os.environ["API_TOKEN"] = "secret"
os.environ["BACKFILL_DAYS"] = "0"

import pytest

from app import db, samples


@pytest.fixture(autouse=True)
def fresh_db(tmp_path, monkeypatch) -> None:
    monkeypatch.setenv("DB_PATH", str(tmp_path / "ring.db"))
    db.init()


def load(data: dict[str, dict[float, float]]) -> None:
    """Write {metric: {unix_s: value}} into the current ring.db."""
    samples.put([(m, round(t * 1000), v) for m, s in data.items() for t, v in s.items()])
