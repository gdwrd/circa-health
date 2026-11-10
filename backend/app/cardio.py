"""Pure cardio functions (no I/O): HR zones, load, daily target, workouts, restorative time, fitness estimate.
Samples are 5-min `biometric_hr_bpm` values {unix_ts: bpm}; steps are {hour-start unix_ts: steps}."""
from collections.abc import Callable
from datetime import date
from statistics import mean

STEP = 300  # 5-min HR samples


def max_hr(birth_year: int, today: date) -> float:
    return 208 - 0.7 * (today.year - birth_year)


def zones(max_hr: float, rhr: float) -> list[float]:
    """Five lower bounds by heart-rate reserve."""
    return [rhr + f * (max_hr - rhr) for f in (0.5, 0.6, 0.7, 0.8, 0.9)]


def zone_of(bpm: float, bounds: list[float]) -> int:
    return sum(bpm >= b for b in bounds)


def _slots(hr: dict[float, float]) -> dict[float, float]:
    """Worn samples as one mean per 300 s slot, so an off-grid live reading shares its slot instead of adding one."""
    by: dict[float, list[float]] = {}
    for t, v in hr.items():
        if v > 0:
            by.setdefault(t // STEP * STEP, []).append(v)
    return {t: mean(v) for t, v in by.items()}


def load(hr: dict[float, float], bounds: list[float]) -> dict:
    z = [zone_of(v, bounds) for v in _slots(hr).values()]
    return {"load": round(sum(0.5 * x for x in z), 1), "minutes": [5 * z.count(i) for i in range(1, 6)]}


def target(readiness: int | None, mean_load_14d: float | None) -> dict | None:
    if readiness is None or mean_load_14d is None:
        return None
    base = max(mean_load_14d, 5)
    (lo, hi), band = ((1.1, 1.4), "recovered") if readiness >= 80 else ((0.8, 1.1), "steady") if readiness >= 60 \
        else ((0.3, 0.7), "easy")
    lo = round(lo * base)
    return {"lo": lo, "hi": max(round(hi * base), lo + 2), "band": band}


def _hour(t: float) -> int:
    # shortcut: hour start in UTC seconds, matches local hours only for whole-hour offsets, upgrade for e.g. India
    return int(t // 3600 * 3600)


def workouts(hr: dict[float, float], steps_h: dict[int, float], bounds: list[float],
             awake: Callable[[float], bool], low_step_kind: str = "workout") -> list[dict]:
    """Runs of zone >= 2 samples (one non-active sample inside a run is kept), >= 4 active samples, merged across
    gaps <= 10 min. kind = walk at >= 1000 steps/h over the covered hours, else low_step_kind."""
    runs: list[list[float]] = []
    for t in sorted(t for t, v in hr.items() if v > 0 and awake(t) and zone_of(v, bounds) >= 2):
        if runs and t - runs[-1][-1] <= 2 * STEP:
            runs[-1].append(t)
        else:
            runs.append([t])
    segs: list[list[float]] = []
    for r in (r for r in runs if len(r) >= 4):
        if segs and r[0] - (segs[-1][-1] + STEP) <= 600:
            segs[-1] += r
        else:
            segs.append(r)
    out = []
    for s in segs:
        start, end = int(s[0]), int(s[-1]) + STEP
        seg = {t: v for t, v in hr.items() if start <= t < end and v > 0}
        bpm = list(seg.values())
        hours = range(_hour(start), end, 3600)
        steps = mean(steps_h.get(h, 0.0) for h in hours)
        out.append({"start": start, "end": end, "minutes": (end - start) // 60, "avg_bpm": round(mean(bpm)),
                    "max_bpm": max(bpm), "load": load(seg, bounds)["load"],
                    "kind": "walk" if steps >= 1000 else low_step_kind, "confirmed": False})
    return out


def restorative(hr: dict[float, float], rhr: float | None, steps_h: dict[int, float],
                awake: Callable[[float], bool]) -> int | None:
    """Minutes in runs of >= 3 consecutive calm samples: awake, bpm <= 1.10 x rhr, hour steps < 100."""
    if rhr is None:
        return None
    hr = _slots({t: v for t, v in hr.items() if awake(t)})
    calm = sorted(t for t, v in hr.items() if v <= 1.1 * rhr and steps_h.get(_hour(t), 0.0) < 100)
    total, run, prev = 0, 0, None
    for t in calm + [None]:
        if t is not None and prev is not None and t - prev == STEP:
            run += 1
        else:
            total += run if run >= 3 else 0
            run = 1
        prev = t
    return 5 * total


def fitness(max_hr: float | None, rhr14: float | None) -> float | None:
    """Resting-HR fitness estimate (Uth et al.): a trend, not a measurement."""
    return round(15.3 * max_hr / rhr14, 1) if max_hr and rhr14 else None
