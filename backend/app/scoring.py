"""Pure scoring functions (no I/O). Scores are approximations against personal 14-day baselines."""
from statistics import mean, median, pstdev

Contribs = dict[str, float | None]


def _lin(x: float, at0: float, at100: float) -> float:
    """Linear map: at0 -> 0, at100 -> 100, clamped (works in either direction)."""
    return max(0.0, min(100.0, 100.0 * (x - at0) / (at100 - at0)))


def _result(c: Contribs) -> dict:
    vals = [v for v in c.values() if v is not None]
    return {"score": round(mean(vals)) if vals else None, "contributors": c}


def _band(ratio: float) -> float:
    """100 at 80-120 % of target, linear to 0 at 0 % and 200 %."""
    return _lin(ratio, 0.0, 0.8) if ratio < 0.8 else _lin(ratio, 2.0, 1.2)


def baseline(values_14d: list[float | None]) -> float | None:
    """Trailing 14-day mean; None until at least 5 values exist."""
    vals = [v for v in values_14d[-14:] if v is not None]
    return mean(vals) if len(vals) >= 5 else None


def sleep_score(session: dict, need_min: float) -> dict:
    """session: total_min (asleep), awake_min, rem_min, deep_min, midpoint_h (local hour 0-24),
    regularity_sd (minutes, from regularity(); None while learning)."""
    asleep, awake = session["total_min"], session["awake_min"]
    h, sd = session["midpoint_h"], session.get("regularity_sd")
    outside = 0.0 if h <= 3 else min(h - 3, 24 - h)  # hours outside 00:00-03:00, wrapping at midnight
    eff = asleep / (asleep + awake) if asleep + awake else 0.0
    return _result({
        "total": _lin(asleep, 240, need_min),
        "efficiency": _lin(eff, 0.75, 0.95),
        "restfulness": _lin(awake, 60, 10),
        "rem": _lin(session["rem_min"], 0, 90),
        "deep": _lin(session["deep_min"], 0, 90),
        "timing": max(0.0, 100 - 33 * outside),
        "regularity": None if sd is None else _lin(sd, 120, 30),
    })


def regularity(midpoints_min: list[float]) -> float | None:
    """SD (minutes) of the last 7 nights' sleep midpoints; None under 4 nights."""
    m = midpoints_min[-7:]
    return pstdev(m) if len(m) >= 4 else None


def sleep_debt(asleep_7: list[float | None], need_min: float) -> dict | None:
    """asleep_7: sleep + naps per night (None = no night); surplus nights offset shortfalls. None under 3 nights."""
    v = [a for a in asleep_7 if a is not None]
    if len(v) < 3:
        return None
    debt = max(0.0, sum(need_min - a for a in v))
    return {"debt_min": round(debt), "nights": len(v),
            "suggest_min": 0 if debt < 30 else min(60, round(debt / 3 / 5) * 5)}


def bedtime_window(nights: list[tuple[float, float]]) -> dict:
    """nights: (bed_start minutes after noon, quality). Best 60-min span (30-min grid, >= 5 nights) vs the rest."""
    if len(nights) < 14:
        return {"status": "learning", "nights": len(nights)}
    beds = [b for b, _ in nights]
    best = None
    for start in range(int(min(beds)) // 30 * 30, int(max(beds)) + 1, 30):
        q = [x for b, x in nights if start <= b < start + 60]
        if len(q) >= 5 and (best is None or mean(q) > best[1]):
            best = (start, mean(q), len(q))
    out = best and [x for b, x in nights if not best[0] <= b < best[0] + 60]
    if not out or best[1] - mean(out) < 3:  # no candidate with 5 nights counts as flat too
        return {"status": "flat", "nights": len(nights)}
    return {"status": "ok", "start_min": best[0], "end_min": best[0] + 60, "score_in": round(best[1]),
            "score_out": round(mean(out)), "nights": len(nights), "nights_in": best[2]}


def low_index(hr: list[float]) -> int:
    """Index of the first sample within 5 % of the minimum (night HR near its low)."""
    return next(i for i, v in enumerate(hr) if v <= min(hr) * 1.05)


def readiness_score(night: dict, baselines: dict, yesterday_steps: float | None, goal: float) -> dict:
    """night: hr_p10, hrv_mean, sleep_score, recovery_frac (0-1 of night until HR near its minimum),
    need_min (default 450). baselines: rhr, hrv, sleep_min, steps (14-day means, None while learning)."""
    def rel(key: str, bkey: str, fn) -> float | None:
        v, b = night.get(key), baselines.get(bkey)
        return None if v is None or not b else fn(v, b)

    need = night.get("need_min", 450)
    rec = night.get("recovery_frac")
    sleep_min, steps = baselines.get("sleep_min"), baselines.get("steps")
    return _result({
        "resting_hr": rel("hr_p10", "rhr", lambda v, b: max(0.0, 100 - 10 * max(0.0, v - b))),
        "hrv_balance": rel("hrv_mean", "hrv", lambda v, b: _lin(v / b, 0.6, 1.0)),
        "sleep": night.get("sleep_score"),
        # shortcut: sleep balance reuses the sleep-total curve (240 min -> 0, need -> 100); PLAN gives no curve
        "sleep_balance": None if sleep_min is None else _lin(sleep_min, 240, need),
        "previous_day_activity": None if yesterday_steps is None else _band(yesterday_steps / goal),
        "activity_balance": None if steps is None else _band(steps / goal),
        "recovery_index": None if rec is None else _lin(rec, 1.0, 0.5),
    })


def activity_score(day: dict, goal: float, cal_7d_mean: float | None, goal_days_7d: int | None) -> dict:
    """day: steps, calories, awake_hourly_steps (list of step counts per awake hour)."""
    hours = day.get("awake_hourly_steps")
    inactive = None if hours is None else sum(1 for s in hours if s < 50)
    cal = day.get("calories")
    return _result({
        "steps": _lin(day["steps"], 0, goal),
        "inactive_hours": None if inactive is None else _lin(inactive, 12, 5),
        # shortcut: calories curve (0 at 50 % of the 7-day mean) is my choice; PLAN only says "vs 7-day mean"
        "calories": None if cal is None or not cal_7d_mean else _lin(cal / cal_7d_mean, 0.5, 1.0),
        "goal_days": None if goal_days_7d is None else _lin(goal_days_7d, 0, 5),
    })


def stress_zones(samples: list[float]) -> dict:
    """samples: awake-hours ring stress values (0-100), 30 min apart. Returns zone counts and stressed hours."""
    zones = {"restored": 0, "relaxed": 0, "engaged": 0, "stressed": 0}
    for s in samples:
        zones["restored" if s <= 25 else "relaxed" if s <= 50 else "engaged" if s <= 75 else "stressed"] += 1
    return {**zones, "stressed_hours": zones["stressed"] * 0.5}


_LEVELS = ["limited", "adequate", "solid", "strong", "exceptional"]


def resilience(stress_loads_14d: list[float | None], readiness_14d: list[float | None]) -> str | None:
    """Level from mean daily stress load (h) vs mean readiness; None until 5 days of each."""
    load, ready = baseline(stress_loads_14d), baseline(readiness_14d)
    if load is None or ready is None:
        return None
    r = sum(ready >= t for t in (60, 70, 80, 88))  # 0-4, higher readiness is better
    s = sum(load <= t for t in (6, 4, 2, 1))       # 0-4, lower stress load is better
    return _LEVELS[(r + s) // 2]


# Strain inputs: +1 = higher is worse, -1 = lower is worse. temp is reserved (R09), always null for now.
DIRS = {"rhr": 1, "hrv": -1, "awake": 1, "recovery": 1, "spo2": -1, "temp": 1}
FLOORS = {"rhr": 1.5, "hrv": 5.0, "awake": 10.0, "recovery": 0.10, "spo2": 1.0}
EXPLAIN = ("alcohol", "workout", "sick", "travel", "stress", "late_meal")


def robust(values_14d: list[float | None], floor: float = 0.0) -> tuple[float, float] | None:
    """Trailing 14-day (median, max(1.4826 x MAD, floor)); None until at least 7 values exist."""
    vals = [v for v in values_14d[-14:] if v is not None]
    if len(vals) < 7:
        return None
    m = median(vals)
    return m, max(1.4826 * median(abs(v - m) for v in vals), floor)


def strain(night: dict, base: dict) -> dict:
    """night: {input: value}; base: {input: robust() result}. Positive z = worse; index needs 2 inputs."""
    zs, deltas, usual = {}, {}, {}
    for k, sign in DIRS.items():
        x, b = night.get(k), base.get(k)
        if x is None or b is None:
            continue
        zs[k] = sign * (x - b[0]) / b[1]
        deltas[k], usual[k] = x - b[0], b[0]
    flagged = [k for k, z in zs.items() if z >= 1.5]
    index = round(100 * mean(max(0.0, min(1.0, z / 3)) for z in zs.values())) if len(zs) >= 2 else None
    level = None if index is None else "calm" if index < 25 else "elevated" if index <= 50 else "high"
    return {"index": index, "level": level, "flags": {k: round(zs[k], 2) for k in flagged},
            "deltas": {k: deltas[k] for k in flagged}, "usual": {k: usual[k] for k in flagged}, "available": len(zs)}
