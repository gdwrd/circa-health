import json

import migrate_vm
from app import db


def line(name: str, ts: list[int], vals: list) -> str:
    return json.dumps({"metric": {"__name__": name, "device": "r02"}, "values": vals, "timestamps": ts})


def test_convert_drops_labels_and_non_finite() -> None:
    name, rows = migrate_vm.convert(line("biometric_hr_bpm", [1000, 2000, 3000], [60, None, 62.5]))
    assert name == "biometric_hr_bpm"
    assert rows == [("biometric_hr_bpm", 1000, 60.0), ("biometric_hr_bpm", 3000, 62.5)]


def test_ingest_idempotent_larger_wins() -> None:
    a = line("biometric_steps", [1000, 2000], [5, 9])
    b = line("biometric_steps", [2000, 3000], [7, 1])  # second series: 2000 clashes, larger (9) stays
    assert migrate_vm.ingest([a, b]) == {"biometric_steps": [4, 2]}
    first = db.query("SELECT ts, value FROM samples ORDER BY ts")
    assert [tuple(r) for r in first] == [(1000, 5.0), (2000, 9.0), (3000, 1.0)]
    migrate_vm.ingest([b, a, ""])
    assert db.query("SELECT ts, value FROM samples ORDER BY ts") == first
