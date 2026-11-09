"""Journal insights: outcomes on days with vs without a journal kind, last 90 days. Pure (no I/O)."""
from datetime import date, datetime, timedelta
from statistics import mean, median

from . import samples

DAYS, MIN_N = 90, 3
NIGHT_KINDS = ("alcohol", "caffeine_late", "late_meal", "workout", "stress", "travel", "breathe")
NIGHT = ("sleep_score", "deep_min", "rem_min", "hrv", "rhr", "total_min", "awake_min", "bed_start")
MED = ("day_hr", "rhr", "hrv", "bed_start", "total_min", "sleep_score")
UNITS = {"sleep_score": "pts", "readiness_score": "pts", "deep_min": "min", "rem_min": "min", "total_min": "min", "awake_min": "min",
         "bed_start": "min", "hrv": "ms", "rhr": "bpm", "day_hr": "bpm", "strain": "pts"}
FLOORS = {"rhr": 1.5, "day_hr": 1.5, "hrv": 5.0, "awake_min": 10.0,  # same floors as scoring.FLOORS
          # minimum spreads so near-identical values (5 min grid bed times) are never "strong"
          "bed_start": 15.0, "total_min": 15.0, "deep_min": 5.0, "rem_min": 5.0, "sleep_score": 3.0, "readiness_score": 3.0,
          "strain": 5.0}


def effect(with_: list[float], without: list[float], floor: float = 0.0) -> dict | None:
    """mean(with) - mean(without); strong = |delta| > 0.5 x robust spread(without). None under 3 a side."""
    if len(with_) < MIN_N or len(without) < MIN_N:
        return None
    a, b, m = mean(with_), mean(without), median(without)
    spread = max(1.4826 * median(abs(v - m) for v in without), floor)
    d = a - b
    return {"delta": round(d, 1), "pct": round(100 * d / b) if b else None, "n_with": len(with_),
            "n_without": len(without), "strong": abs(d) > 0.5 * spread, "z": round(d / spread, 2) if spread else 0.0,
            "with": round(a, 1), "without": round(b, 1)}


def nights(rows: list[dict]) -> dict[date, dict]:
    """Per daily row (wake date): outcomes of the night ending that morning, plus that day's strain index."""
    out = {}
    for r in rows:
        s = r["c"].get("session")
        if not s:
            continue
        st = datetime.fromtimestamp(s["start"], samples.tz())
        out[r["date"]] = {"sleep_score": r["sleep_score"], "readiness_score": r.get("readiness_score"),
                          "rhr": r["inputs"].get("rhr"), "hrv": r["inputs"].get("hrv"),
                          **{k: s[k] for k in ("deep_min", "rem_min", "total_min", "awake_min")},
                          # minutes from midnight, 23:30 -> -30. shortcut: 5 min grid start, upgrade with C1 inputs.bed_start
                          "bed_start": st.hour * 60 + st.minute - (1440 if st.hour >= 12 else 0),
                          "strain": (r["c"].get("signals") or {}).get("index")}
    return out


def compare(w: list[dict], wo: list[dict], metrics: tuple[str, ...]) -> dict:
    """w / wo: outcome dicts for days with / without the kind."""
    n = min(len(w), len(wo))
    if n < MIN_N:
        return {"status": "learning", "need": MIN_N - n, "n_with": len(w), "n_without": len(wo)}
    effects = []
    for m in metrics:
        e = effect([o[m] for o in w if o.get(m) is not None], [o[m] for o in wo if o.get(m) is not None],
                   FLOORS.get(m, 0.0))
        if e:
            if m == "bed_start":
                e["pct"] = None  # a percent of clock time means nothing
            effects.append({"metric": m, "unit": UNITS[m], **e})
    top = sorted((e for e in effects if e["strong"]), key=lambda e: -abs(e["z"]))[:2]
    return {"status": "ok", "n_with": len(w), "n_without": len(wo), "top": top, "all": effects}


def _hhmm(m: float) -> str:
    return f"{int(m) // 60:02d}:{int(m) % 60:02d}"


def kinds(rows: list[dict], entries: list[dict], today: date, day_hr: dict[date, float], med_started: str,
          breaths: list[dict]) -> dict:
    """rows: db.days over the window; entries: db.journal; day_hr: mean awake HR per date; breaths: db.breaths."""
    nt = nights(rows)
    days = [today - timedelta(DAYS - i) for i in range(DAYS)]  # d in [today-90, today-1]; night d+1 <= today
    logged: dict[str, set[date]] = {}
    doses: dict[date, list[int]] = {}
    for e in entries:
        d = date.fromisoformat(e["date"])
        logged.setdefault(e["kind"], set()).add(d)
        if e["kind"] == "medication" and e["value"]:
            doses.setdefault(d, []).append(int(e["value"][:2]) * 60 + int(e["value"][3:]))

    out = {}
    for k in NIGHT_KINDS:
        on = logged.get(k, set())
        out[k] = compare([nt[d + timedelta(1)] for d in days if d in on and d + timedelta(1) in nt],
                         [nt[d + timedelta(1)] for d in days if d not in on and d + timedelta(1) in nt], NIGHT)

    sick = logged.get("sick", set())
    win = lambda d: [nt[x]["strain"] for x in (d - timedelta(1), d, d + timedelta(1))
                     if x in nt and nt[x]["strain"] is not None]
    near = {d + timedelta(i) for d in sick for i in (-1, 0, 1)}
    out["sick"] = compare([{"strain": mean(v)} for d in days if d in sick and (v := win(d))],
                          [nt[d] for d in days if d not in near and d in nt and nt[d]["strain"] is not None],
                          ("strain",))

    def med(d: date) -> dict | None:  # same day (day_hr) + that night (row d+1)
        o = {**nt.get(d + timedelta(1), {}), "day_hr": day_hr.get(d)}
        return o if any(o.get(m) is not None for m in MED) else None

    # no-dose days count only from medication_started on (before it is a different baseline)
    on = [(d, o) for d in days if (not med_started or d.isoformat() >= med_started) and (o := med(d))]
    out["medication"] = compare([o for d, o in on if d in doses], [o for d, o in on if d not in doses], MED)
    timed = [(min(doses[d]), o) for d, o in on if d in doses]
    split = median(t for t, _ in timed) if timed else None
    by_time = compare([o for t, o in timed if t > split], [o for t, o in timed if t <= split], ("bed_start", "total_min"))
    out["medication"]["by_time"] = {**by_time, "split": None if split is None else _hhmm(split)}  # with = after split
    drop = [b["stress_after"] - b["stress_before"] for b in breaths
            if b["stress_before"] is not None and b["stress_after"] is not None]
    out["breathe"]["stress"] = {"delta": round(mean(drop), 1), "n": len(drop)} if len(drop) >= MIN_N else None
    return out


def bp(entries: list[dict]) -> list[dict]:
    """BP journal entries ("SYS/DIA") as an ascending series."""
    pts = [{"date": e["date"], "ts": e["ts"], "sys": int(e["value"].split("/")[0]), "dia": int(e["value"].split("/")[1])}
           for e in entries if e["kind"] == "bp" and e["value"]]
    return sorted(pts, key=lambda p: (p["date"], p["ts"]))
