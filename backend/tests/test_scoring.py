import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.scoring import (activity_score, band, baseline, bedtime_window, confidence, guess_stats, low_index, readiness_score, regularity,
                         resilience, robust, sleep_debt, sleep_score, social_jetlag, strain, stress_zones, suggest,
                         tonight, what_if, wind_down)

GOOD = {"total_min": 480, "awake_min": 8, "rem_min": 100, "deep_min": 100, "midpoint_h": 2.0, "regularity_sd": 20}
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


def test_robust_needs_seven_and_floors():
    assert robust([50, 51, 52, 53, 54, 55]) is None
    assert robust([50, None, 51, 52, 53, 54, 55, None]) is None
    m, spread = robust([50, 52, 54, 56, 58, 60, 100])
    assert m == 56 and abs(spread - 1.4826 * 4) < 1e-9  # MAD ignores the outlier
    assert robust([55] * 7, 1.5) == (55, 1.5)


BASES = {"rhr": (55, 2.0), "hrv": (50, 10.0), "awake": (20, 10.0), "recovery": (0.3, 0.1)}


def test_strain_calm_night():
    r = strain({"rhr": 55, "hrv": 52, "awake": 18, "recovery": 0.3}, BASES)
    assert r["index"] < 25 and r["level"] == "calm" and r["flags"] == {} and r["available"] == 4


def test_strain_two_flags():
    r = strain({"rhr": 61, "hrv": 30, "awake": 20, "recovery": 0.3}, BASES)
    assert r["flags"] == {"rhr": 3.0, "hrv": 2.0} and r["deltas"] == {"rhr": 6, "hrv": -20}
    assert r["index"] == round(100 * (1 + 2 / 3) / 4) == 42 and r["level"] == "elevated"
    assert strain({"rhr": 70, "hrv": 10}, BASES)["level"] == "high"


def test_strain_missing_inputs():
    r = strain({"rhr": 61, "hrv": None, "spo2": None, "temp": None}, BASES)
    assert r["available"] == 1 and r["index"] is None and r["level"] is None
    assert strain({"rhr": 61, "hrv": 50}, {**BASES, "rhr": None})["index"] is None


def test_regularity_curve_and_learning():
    assert regularity([900, 900, 900]) is None  # under 4 nights
    assert regularity([999, 0, 0, 0, 0, 0, 0, 0]) == 0  # only the last 7 nights count
    assert regularity([840, 960, 840, 960]) == 60
    c = lambda sd: sleep_score({**GOOD, "regularity_sd": sd}, 450)["contributors"]["regularity"]
    assert c(30) == 100 and c(20) == 100 and c(120) == 0 and c(200) == 0 and c(75) == 50
    assert c(None) is None and sleep_score({**GOOD, "regularity_sd": None}, 450)["score"] >= 95


def test_low_index_first_near_minimum():
    assert low_index([60, 58, 52, 50, 51]) == 2  # 52 <= 50 x 1.05


def test_sleep_debt_offsets_learning_and_cap():
    assert sleep_debt([450, 450, None], 450) is None  # under 3 nights
    assert sleep_debt([400, 420, 500, None], 450) == {"debt_min": 30, "nights": 3, "suggest_min": 10}  # 50+30-50
    assert sleep_debt([500, 500, 500], 450) == {"debt_min": 0, "nights": 3, "suggest_min": 0}  # surplus is not credit
    assert sleep_debt([445, 445, 445], 450)["suggest_min"] == 0  # debt 15 < 30
    assert sleep_debt([300] * 7, 450) == {"debt_min": 1050, "nights": 7, "suggest_min": 60}


def test_bedtime_window_learning_flat_ok():
    assert bedtime_window([(600, 80)] * 13) == {"status": "learning", "nights": 13}
    assert bedtime_window([(600 + 10 * i, 80) for i in range(14)]) == {"status": "flat", "nights": 14}
    # 22:00 x6 q90, 23:00 x5 q70, 00:00 x5 q60, 01:20 x2 q100 (too few nights to be a window)
    n = [(600, 90)] * 6 + [(660, 70)] * 5 + [(720, 60)] * 5 + [(800, 100)] * 2
    assert bedtime_window(n) == {"status": "ok", "start_min": 600, "end_min": 660, "score_in": 90,
                                 "score_out": 71, "nights": 18, "nights_in": 6}  # (350+300+200)/12
    assert bedtime_window([(600, 81)] * 7 + [(700, 79)] * 7)["status"] == "flat"  # 2 apart < 3


def test_tonight() -> None:
    assert tonight(1140, 450, 40) == {"asleep_by_min": 650, "in_bed_by_min": 635, "sleep_min": 490, "extra_min": 40}


def test_confidence_thresholds() -> None:
    assert confidence(0.8, 7, 10) == "high"  # both exactly at their floor
    assert confidence(0.79, 7, 7) == "medium" and confidence(1.0, 6, 10) == "medium"
    assert confidence(0.5, 2, 4) == "medium"  # 0.5 is not below 0.5
    assert confidence(0.49, 7, 7) == "low" and confidence(1.0, 3, 7) == "low"  # either one alone makes it low
    assert confidence(1.0, 5, 7) == "high" and confidence(1.0, 3, 4) == "high"
    assert confidence(1.0, 0, 0) == "low"  # no contributors at all


def test_social_jetlag_needs_four_weekday_and_two_weekend_nights() -> None:
    week, end = [(i, 900.0) for i in range(4)], [(5, 1000.0), (6, 1010.0)]  # (weekday, midpoint after noon)
    assert social_jetlag(week[:3] + end) is None and social_jetlag(week + end[:1]) is None
    assert social_jetlag(week + end) == {"delta_min": 105, "weekday_min": 900.0, "weekend_min": 1005.0,
                                         "weekdays": 4, "weekends": 2}
    assert social_jetlag([(0, 960.0)] * 4 + [(6, 900.0)] * 2)["delta_min"] == -60  # earlier weekends are negative


def test_wind_down_last_run_one_outlier_from_18() -> None:
    hr = [(300, 60), (420, 90), (480, 62), (495, 80), (510, 61), (525, 66)]  # rhr 55 -> settled at <= 66
    assert wind_down(hr, 55) == 480  # 495 is the one higher sample allowed; the second (420) ends the run
    assert wind_down(hr[:4], 55) == 480  # a higher last sample is that one outlier
    assert wind_down([(400, 60), (420, 90), (440, 90), (480, 60)], 55) == 480
    assert wind_down([(300, 60), (345, 60), (380, 60)], 55) == 380  # nothing before 18:00 (360) counts
    assert wind_down([], 55) is None and wind_down([(300, 60), (345, 60)], 55) is None
    assert wind_down([(400, 60), (480, 90), (500, 90)], 55) is None  # not settled before sleep


def test_suggest_needs_baseline_z_and_no_workout() -> None:
    base = robust([70.0] * 7, 3.0)  # (70, 3): the floor
    assert suggest(74.5, base, False) == {"kinds": ["alcohol", "late_meal"], "delta": 4}  # z = 1.5
    assert suggest(80.0, base, False)["delta"] == 10
    assert suggest(74.4, base, False) is None
    assert suggest(80.0, base, True) is None  # a workout explains it
    assert suggest(80.0, None, False) is None and suggest(None, base, False) is None


def test_what_if_buckets_quartiles_and_five_day_floor() -> None:
    """need 450 -> a full night is >= 420; a hard day is load >= 14."""
    rest_full = [(5.0, 420.0, r) for r in (70, 72, 74, 76, 78)]  # 420 = exactly need - 30: full
    train_short = [(14.0, 419.0, r) for r in (66, 50, 58, 62, 54)]  # 14 = exactly hard_from: train
    train_full = [(30.0, 480.0, 60)] * 4  # one day short of a bucket
    w = what_if(rest_full + train_short + train_full, 14, 450)
    assert w == {"rest": {"full": {"median": 74, "lo": 72, "hi": 76, "n": 5}, "short": None},
                 "train": {"full": None, "short": {"median": 58, "lo": 54, "hi": 62, "n": 5}}}
    assert what_if(train_full + [(30.0, 480.0, 90)], 14, 450)["train"]["full"] == {"median": 60, "lo": 60, "hi": 60, "n": 5}
    assert what_if([], 14, 450) == {"rest": {"full": None, "short": None}, "train": {"full": None, "short": None}}


def test_lin_equal_bounds_is_a_step():
    from app.scoring import _lin
    assert _lin(240, 240, 240) == 100 and _lin(239, 240, 240) == 0
    assert readiness_score({**NIGHT, "total_min": 300}, BASE, 8000, 8000)["score"] is not None


def test_band_edges() -> None:
    assert [band(s) for s in (100, 85, 84.9, 84, 70, 69.9, 0)] == [2, 2, 1, 1, 1, 0, 0]


def test_guess_stats_learning_under_five() -> None:
    assert guess_stats([]) == {"status": "learning", "n": 0, "need": 5}
    assert guess_stats([(80, 70, 1)] * 4) == {"status": "learning", "n": 4, "need": 1}
    assert guess_stats([(80, 70, 1)] * 5)["status"] == "ok"


def test_guess_stats_off_bias_split_you_ring() -> None:
    s = guess_stats([(90, 80, 2),      # bands 2 / 1, the day felt like 2: you
                     (60, 75, 1),      # bands 0 / 1, felt like 1: ring
                     (90, 60, 1),      # bands 2 / 0, felt like 1: neither
                     (72, 90, None),   # bands differ, no verdict: not a split
                     (75, 80, 0),      # same band: a verdict there is not a split
                     (50, 60, None)])  # same band
    assert s == {"status": "ok", "n": 6, "off": 14.7, "bias": -1.3, "same": 2, "split": 3, "you": 1, "ring": 1}
