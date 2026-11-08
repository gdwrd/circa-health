import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.scoring import (activity_score, baseline, readiness_score, resilience,
                         sleep_score, stress_zones)

GOOD = {"total_min": 480, "awake_min": 8, "rem_min": 100, "deep_min": 100, "midpoint_h": 2.0}
SHORT = {"total_min": 240, "awake_min": 20, "rem_min": 40, "deep_min": 30, "midpoint_h": 1.0}


def test_baseline_needs_five():
    assert baseline([1, 2, 3, 4]) is None
    assert baseline([1, None, 3, 4, 5, None]) is None
    assert baseline([1, 2, 3, 4, 5]) == 3


def test_sleep_good_night():
    r = sleep_score(GOOD, 450)
    assert r["score"] >= 95 and all(v == 100 for v in r["contributors"].values())


def test_sleep_short_night():
    r = sleep_score(SHORT, 450)
    assert r["contributors"]["total"] == 0 and r["score"] < 60 < sleep_score(GOOD, 450)["score"]


def test_sleep_timing_outside_window():
    assert sleep_score({**GOOD, "midpoint_h": 5.0}, 450)["contributors"]["timing"] == 100 - 66
    assert sleep_score({**GOOD, "midpoint_h": 23.0}, 450)["contributors"]["timing"] == 67


NIGHT = {"hr_p10": 52, "hrv_mean": 60, "sleep_score": 95, "recovery_frac": 0.3}
BASE = {"rhr": 55, "hrv": 55, "sleep_min": 460, "steps": 8200}


def test_readiness_good():
    r = readiness_score(NIGHT, BASE, 8000, 8000)
    assert r["score"] >= 95 and None not in r["contributors"].values()


def test_readiness_no_baseline():
    r = readiness_score(NIGHT, {"rhr": None, "hrv": None, "sleep_min": None, "steps": None}, 8000, 8000)
    c = r["contributors"]
    assert c["resting_hr"] is None and c["hrv_balance"] is None
    assert c["sleep_balance"] is None and c["activity_balance"] is None
    assert r["score"] is not None  # sleep, previous-day activity, recovery still count


def test_readiness_hr_above_baseline():
    r = readiness_score({**NIGHT, "hr_p10": 60}, BASE, 8000, 8000)
    assert r["contributors"]["resting_hr"] == 50


def test_activity():
    day = {"steps": 9000, "calories": 2200, "awake_hourly_steps": [300] * 10 + [10] * 2}
    assert activity_score(day, 8000, 2000, 6)["score"] == 100
    low = activity_score({"steps": 2000, "calories": 800, "awake_hourly_steps": [0] * 14}, 8000, None, None)
    assert low["contributors"]["calories"] is None and low["contributors"]["inactive_hours"] == 0
    assert low["contributors"]["steps"] == 25


def test_stress_zones():
    z = stress_zones([10, 25, 26, 50, 51, 75, 76, 99])
    assert (z["restored"], z["relaxed"], z["engaged"], z["stressed"]) == (2, 2, 2, 2)
    assert z["stressed_hours"] == 1.0


def test_resilience():
    assert resilience([1] * 4, [90] * 14) is None
    assert resilience([0.5] * 14, [92] * 14) == "exceptional"
    assert resilience([7] * 14, [50] * 14) == "limited"
