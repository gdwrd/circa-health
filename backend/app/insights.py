"""Journal insights: outcomes on days with vs without a journal kind, last 90 days. Pure (no I/O)."""
import random
from datetime import date, datetime, timedelta
from statistics import mean, median

from . import samples

DAYS, MIN_N = 90, 3
NIGHT_KINDS = ("alcohol", "caffeine_late", "late_meal", "workout", "stress", "travel", "breathe", "phone_in_bed")
NIGHT = ("sleep_score", "deep_min", "rem_min", "hrv", "rhr", "total_min", "awake_min", "bed_start", "wind_down", "felt")
EXPERIMENT_KINDS = ("alcohol", "caffeine_late", "late_meal", "workout", "breathe", "phone_in_bed")
MED = ("day_hr", "rhr", "hrv", "bed_start", "total_min", "sleep_score")
BEFORE = ("total_min", "awake_min", "deep_min", "hrv", "rhr", "bed_start", "sleep_score", "felt", "stress_load", "steps")
UNITS = {"sleep_score": "pts", "readiness_score": "pts", "deep_min": "min", "rem_min": "min", "total_min": "min", "awake_min": "min",
         "bed_start": "min", "wind_down": "min", "hrv": "ms", "rhr": "bpm", "day_hr": "bpm", "strain": "pts", "felt": "pts",
         "stress_load": "h", "steps": "steps"}
FLOORS = {"rhr": 1.5, "day_hr": 1.5, "hrv": 5.0, "awake_min": 10.0,  # same floors as scoring.FLOORS
          # minimum spreads so near-identical values (5 min grid bed times) are never "strong"
          "bed_start": 15.0, "wind_down": 15.0, "total_min": 15.0, "deep_min": 5.0, "rem_min": 5.0, "sleep_score": 3.0, "readiness_score": 3.0,
          "strain": 5.0, "felt": 5.0, "stress_load": 0.5, "steps": 1000.0}


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
                          # inputs.wind_down is minutes after noon; - 720 puts it on the bed_start scale
                          "wind_down": None if (w := r["inputs"].get("wind_down")) is None else w - 720,
                          "strain": (r["c"].get("signals") or {}).get("index"), "felt": r.get("felt")}
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
            if m in ("bed_start", "wind_down"):
                e["pct"] = None  # a percent of clock time means nothing
            effects.append({"metric": m, "unit": UNITS[m], **e})
    top = sorted((e for e in effects if e["strong"]), key=lambda e: -abs(e["z"]))[:2]
    return {"status": "ok", "n_with": len(w), "n_without": len(wo), "top": top, "all": effects}


def plan(days: int, rng: random.Random) -> str:
    """Experiment assignments, one per day: "1" do it, "0" skip it; exactly half ones."""
    p = ["1", "0"] * (days // 2)
    rng.shuffle(p)
    return "".join(p)


def experiment(plan: str, start: date, nt: dict[date, dict], logged: set[date], today: date, metric: str) -> dict:
    """Day i is start + i; it counts only when the journal agrees with the plan and night d + 1 exists.
    kept / skipped: 0-based indexes of the days before today."""
    days, i = len(plan), (today - start).days
    past = range(min(i, days))
    kept = [j for j in past if ((d := start + timedelta(j)) in logged) == (plan[j] == "1") and d + timedelta(1) in nt]
    side = lambda bit: [nt[start + timedelta(j + 1)] for j in kept if plan[j] == bit]
    return {"status": "done" if i >= days else "running", "day": min(i + 1, days), "days": days,
            "today": None if i >= days else "on" if plan[i] == "1" else "off",
            "kept": kept, "skipped": [j for j in past if j not in kept], "result": compare(side("1"), side("0"), (metric,))}


def lead_time(sick: set[date], strain: dict[date, float | None]) -> dict | None:
    """Per sick episode (first day of a run): consecutive days before it with strain index >= 25, up to 7.
    An episode with no index the day before is left out (unknown, not zero). None under 2 episodes."""
    leads = []
    for d in sick:
        if d - timedelta(1) in sick or strain.get(d - timedelta(1)) is None:
            continue
        n = 0
        while n < 7 and (strain.get(d - timedelta(n + 1)) or 0) >= 25:
            n += 1
        leads.append(n)
    return {"days": median(leads), "n": len(leads)} if len(leads) >= 2 else None


def episodes(logged: dict[date, set[str]], sig: dict[date, dict], today: date) -> list[dict]:
    """Per run of consecutive logged days: the nights after its last day with strain index >= 25, up to 7.
    logged: scoring.EXPLAIN kinds per journal date; sig: contributors.signals per wake date. Oldest first.
    state: closed (a calm night ended it), capped (7 nights), open (still running), cut (a new event or a night
    without an index got in the way). An episode with no index on its first night is left out (unknown, not zero)."""
    out = []
    for first in sorted(d for d in logged if d - timedelta(1) not in logged):
        last = first
        while last + timedelta(1) in logged:
            last += timedelta(1)
        kinds = sorted(set().union(*(logged[first + timedelta(i)] for i in range((last - first).days + 1))))
        one = sig.get(last + timedelta(1)) or {}
        n, state = 0, None
        while state is None:
            d = last + timedelta(n + 1)
            i = (sig.get(d) or {}).get("index")
            if d > today:
                state = "open"
            elif i is None:
                state = "cut"
            elif i < 25:
                state = "closed"
            elif n and d - timedelta(1) in logged:  # that evening's event owns this night
                state = "cut"
            else:
                n += 1
                state = "capped" if n == 7 else None
        if state == "cut" and not n:  # no index on the first night
            continue
        flagged = lambda k: {m: v for m, v in (one.get(k) or {}).items() if m in (one.get("flags") or ())}
        out.append({"key": "+".join(kinds), "kinds": kinds, "first": first, "last": last, "nights": n, "state": state,
                    "deltas": flagged("deltas"), "usual": flagged("usual")})
    return out


def prices(eps: list[dict]) -> dict[str, dict]:
    """Per kind: the median nights of its single-kind closed or capped episodes; learning under MIN_N."""
    by: dict[str, list[int]] = {}
    for e in eps:
        if len(e["kinds"]) == 1 and e["state"] in ("closed", "capped"):
            by.setdefault(e["kinds"][0], []).append(e["nights"])
    return {k: {"status": "ok", "nights": median(v), "n": len(v)} if len(v) >= MIN_N else {"status": "learning", "n": len(v)}
            for k, v in by.items()}


def closed_today(eps: list[dict], today: date) -> dict | None:
    """The episode whose first calm night is today, with 1 night or more."""
    return next((e for e in reversed(eps) if e["state"] == "closed" and e["nights"]
                 and e["last"] + timedelta(e["nights"] + 1) == today), None)


def last_time(eps: list[dict], sig: dict[date, dict], today: date) -> dict | None:
    """What to show today, first match wins: an open episode ("event"), one that closed today ("closed"), or
    today's 2+ night streak with no event ("signals"; `before` is None when no earlier run exists)."""
    opened = next((e for e in reversed(eps) if e["state"] == "open"), None)
    e = opened or closed_today(eps, today)
    if e:
        before = next(({"first": b["first"], "nights": b["nights"]} for b in reversed(eps)
                       if b["first"] < e["first"] and b["key"] == e["key"] and b["state"] in ("closed", "capped")), None)
        return {"why": "event" if opened else "closed", "key": e["key"], "kinds": e["kinds"], "nights": e["nights"],
                "last": e["last"], "before": before, "price": prices(eps).get(e["key"])}  # a mixed key has no price
    s = sig.get(today) or {}
    if s.get("level") not in ("elevated", "high") or (s.get("streak") or 0) < 2:
        return None
    run, before = [], None
    for d in sorted(x for x in sig if x <= today - timedelta(s["streak"])):
        hot = (sig[d].get("index") or 0) >= 25
        run = run + [d] if hot and run and d - run[-1] == timedelta(1) else [d] if hot else []
        if len(run) >= 2:
            before = {"first": run[0], "nights": len(run)}
    return {"why": "signals", "key": "signals", "nights": s["streak"], "before": before}


def before(marked: set[date], nt: dict[date, dict], day: dict[date, dict]) -> dict:
    """What came before a marked day: the night that ended that morning plus the day before's stress_load and steps.
    marked: local dates with a mark; nt: nights(); day: inputs per date. With = the first day of each run of marked
    days, without = unmarked days not next to a marked one; only days with a daily row count.
    pattern (None without a top effect): for the top metric a day "looks like it" when its value is past the midpoint
    of the two means on the marked side; hits / marked = with-days that did / that have a value, alike = with and
    without days that did (neighbours and later days of a run are in neither)."""
    def o(d: date) -> dict:
        y = day.get(d - timedelta(1), {})
        return {**nt.get(d, {}), "stress_load": y.get("stress_load"), "steps": y.get("steps")}

    days = sorted(set(nt) | set(day))
    w = [o(d) for d in days if d in marked and d - timedelta(1) not in marked]
    wo = [o(d) for d in days if not {d - timedelta(1), d, d + timedelta(1)} & marked]
    out = compare(w, wo, BEFORE)
    pattern = None
    if out.get("top"):
        e = out["top"][0]
        m, mid = e["metric"], (e["with"] + e["without"]) / 2
        like = lambda x: x.get(m) is not None and (x[m] - mid) * e["delta"] > 0
        pattern = {"metric": m, "hits": sum(map(like, w)), "marked": e["n_with"], "alike": sum(map(like, w + wo))}
    return {**out, "pattern": pattern}


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
        if e["kind"] == "phone_in_bed" and int(e["value"] or 0) < 20:  # a quick check is not phone in bed
            continue
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
    out["sick"]["lead"] = lead_time(sick, {d: o["strain"] for d, o in nt.items()})

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
