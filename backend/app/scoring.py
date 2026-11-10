"""Pure scoring functions (no I/O). Scores are approximations against personal 14-day baselines."""
from statistics import mean, median, pstdev, quantiles

Contribs = dict[str, float | None]


def _lin(x: float, at0: float, at100: float) -> float:
    """Linear map: at0 -> 0, at100 -> 100, clamped (works in either direction)."""
    if at0 == at100:  # a step, not a division by zero (sleep need 240)
        return 100.0 if x >= at0 else 0.0
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


def confidence(coverage: float, present: int, total: int) -> str:
    """How much data backs a score: coverage = share of its hours with ring data, present/total = non-null contributors."""
    share = present / total if total else 0.0
    if coverage < 0.5 or share < 0.5:
        return "low"
    return "high" if coverage >= 0.8 and share >= 0.7 else "medium"


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


def _on_min(intervals: list[tuple[float, float]], lo: float, hi: float) -> float:
    return sum(max(min(off, hi) - max(on, lo), 0.0) for on, off in intervals) / 60


def clip_night(start: float, end: float, awake_min: float, intervals: list[tuple[float, float]],
               ring_awake: list[tuple[float, float]]) -> tuple[float, float, float, float]:
    """Screen-on (on, off) epoch seconds -> (start, end, awake_min, phone_min) of a night.

    Awake is a union: screen-on minutes the ring already marked awake (ring_awake intervals) add nothing.

    shortcut: screen-on means someone used this phone, not necessarily the user; upgrade with an unlock/usage signal.
    """
    phone = _on_min(intervals, start - 3600, end)
    s = max((off for on, off in intervals if off - on >= 60 and on < start + 5400 and off > start), default=start)
    e = next((on for on, off in intervals if off - on >= 180 and end - 3600 <= on < end), end)
    if s >= e:
        return start, end, awake_min, phone
    extra = _on_min(intervals, s, e) - sum(_on_min([(max(on, rs), min(off, ro))], s, e)
                                           for on, off in intervals for rs, ro in ring_awake if min(off, ro) > max(on, rs))
    cut = _on_min(ring_awake, start, end) - _on_min(ring_awake, s, e)  # ring-awake minutes the clip removed from the span
    return s, e, min(max(awake_min - cut, 0.0) + extra, (e - s) / 60), phone


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


def tonight(wake_min: float, need_min: float, suggest_min: int) -> dict:
    """Tonight's plan, all minutes after noon (07:00 = 1140): asleep-by = wake - need - catch-up share."""
    asleep = wake_min - need_min - suggest_min
    # shortcut: fixed 15-min sleep-onset allowance (ring reports onset, not time in bed); use a measured median once a bed-time source exists
    return {"asleep_by_min": asleep, "in_bed_by_min": asleep - 15, "sleep_min": need_min + suggest_min, "extra_min": suggest_min}


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


def social_jetlag(nights: list[tuple[int, float]]) -> dict | None:
    """nights: (weekday of the wake date, sleep midpoint in minutes after noon), last 28 days.
    Median of Sat/Sun wake dates minus median of the rest; None under 4 weekday or 2 weekend nights."""
    week, end = [m for wd, m in nights if wd < 5], [m for wd, m in nights if wd >= 5]
    if len(week) < 4 or len(end) < 2:
        return None
    return {"delta_min": round(median(end) - median(week)), "weekday_min": median(week), "weekend_min": median(end),
            "weekdays": len(week), "weekends": len(end)}


def wind_down(hr: list[tuple[float, float]], rhr: float) -> float | None:
    """hr: (minutes after noon, bpm) up to sleep start, ascending. Start of the last run of samples <= 1.2 x rhr
    that reaches sleep start (one higher sample allowed), from 18:00 on; None when there is none.

    shortcut: HR is logged every 5-15 min, so this is accurate to about 15 min; upgrade if the ring logs HR more often.
    """
    start, high = None, 0
    for t, v in reversed(hr):
        if t < 360:  # 18:00
            break
        if v <= 1.2 * rhr:
            start = t
        elif (high := high + 1) > 1:
            break
    return start


def suggest(evening_hr: float | None, base: tuple[float, float] | None, workout: bool) -> dict | None:
    """Journal kinds to offer (never logged) when the evening HR is >= 1.5 robust SDs above its usual and no
    workout explains it. base: robust() of the prior evenings; delta in bpm."""
    if evening_hr is None or base is None or workout or (evening_hr - base[0]) / base[1] < 1.5:
        return None
    return {"kinds": ["alcohol", "late_meal"], "delta": round(evening_hr - base[0])}


def what_if(history: list[tuple[float, float, int]], hard_from: float, need_min: float) -> dict:
    """history: (cardio load, sleep minutes that night, next-day readiness) over 90 days. Days like this, no
    regression: rest/train (load >= hard_from) x full/short night (>= need - 30). Each bucket is the next-day
    readiness {median, lo, hi, n} (lo/hi = quartiles), or None under 5 days."""
    out: dict = {}
    for day, hard in (("rest", False), ("train", True)):
        out[day] = {}
        for night, full in (("full", True), ("short", False)):
            v = [r for load, sleep, r in history if (load >= hard_from) == hard and (sleep >= need_min - 30) == full]
            q = len(v) >= 5 and [round(x) for x in quantiles(v, n=4, method="inclusive")]
            out[day][night] = {"median": q[1], "lo": q[0], "hi": q[2], "n": len(v)} if q else None
    return out


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
