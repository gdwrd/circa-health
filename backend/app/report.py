"""Report cards for the on-phone writer (PLAN12 AI1): verdicts, observations with their meaning attached and the allowed
advice, all decided here, never by the model. No score number ever appears: the app already shows the scores.
Pure: rows and helper results in (main.get_report gathers them), card dicts out."""
import re
from collections.abc import Callable, Iterable
from datetime import date, datetime, timedelta, tzinfo
from statistics import mean, pstdev

from . import scoring
from .notify import GUESS, LOGGED, NOUNS, _duration, _join, _nights

PUSH_DEBT_MAX = 60  # push needs readiness 85+ (scoring.band 2) and under 1 h of sleep debt
EASY_DEBT_MIN = 180  # 3 h of debt makes a day easy, unless readiness is in the top band (then steady)
SLEEP_SHORT_MIN = 30  # next week = sleep when the week's nights average this far under the need
SPREAD_MAX_MIN = 60  # next week = regular when bedtimes spread (SD) more than this
MOVE_RATIO = 0.7  # next week = move when the week's cardio load is under this share of the 14-day mean
LOAD_SAME = 0.2  # cardio load within 20 % of its usual is an ordinary day
SAME = 0.05  # within 5 % counts as "about the same"
MAX_FACTS = 12
COFFEE_CUTOFF_H = 14  # scoring.derived's caffeine_late hour
MAX_ADVICE = 3

LEAD = {"push": "A good day to push.", "steady": "A steady day, keep to your usual.", "easy": "Take it easy today.",
        "rest": "Rest today.", "usual": "Your usual bedtime works tonight.", "earlier": "Go to bed a little earlier tonight.",
        "sleep": "Next week, put sleep first.", "regular": "Next week, keep a regular bedtime.",
        "move": "Next week, move a bit more.", "keep": "Next week, keep doing what you do.",
        "watch": "Your body may be under strain, so rest and hydrate.",
        "back": "Your body signals are back in your usual range."}
NOTED = {"tired": "feeling tired", "flat": "feeling flat", "wired": "feeling wired", "good": "feeling good",
         "coffee": "coffee", "alcohol": "alcohol", "meal": "a meal", "snack": "a snack", "energy": "an energy drink",
         "nicotine": "nicotine", "nap": "a nap"}

# input: (noun, unit, "about the same" floor in that unit, meaning when higher, meaning when lower). A meaning follows
# the direction scoring.py gives the input (readiness, sleep score, strain DIRS, resilience); None where it gives none.
BODY: dict[str, tuple[str, str, float, str | None, str | None]] = {
    "rhr": ("Resting heart rate", "bpm", 1.5, "can mean your body is still recovering", "a sign of good recovery"),
    "hrv": ("HRV", "ms", 0.0, "a sign your body is well rested", "your body may still be working on something"),
    "sleep_min": ("Slept", "min", 15.0, "more time for your body to recover", "can leave you less rested"),
    "deep_min": ("Deep sleep", "min", 10.0, "a sign of restful sleep", "lighter sleep than usual"),
    "rem_min": ("REM sleep", "min", 10.0, "a sign of restful sleep", None),
    "awake": ("Awake in the night", "min", 10.0, "sleep was more broken than usual", "a sign of settled sleep"),
    "spo2": ("Lowest blood oxygen", "%", 1.0, None, None),
    "steps": ("Walked", "steps", 0.0, "a more active day than usual", "a quieter day than usual"),
    "stress": ("Time stressed", "min", 30.0, "a more demanding day than usual", "a calmer day than usual"),
    "cardio_load": ("Cardio load", "load", 0.0, "a heavier day than usual for your heart",
                    "a lighter day than usual for your heart"),
}
# unit: (gap from the reference, word when higher, when lower)
UNITS: dict[str, tuple[Callable[[float, float], str], str, str]] = {
    "bpm": (lambda d, u: f"{round(abs(d))} bpm", "higher", "lower"),
    "ms": (lambda d, u: f"{round(abs(100 * d / u))} %", "higher", "lower"),
    "min": (lambda d, u: _duration(abs(d)), "longer", "shorter"),
    "%": (lambda d, u: f"{round(abs(d))} %", "higher", "lower"),
    "steps": (lambda d, u: f"{round(abs(d)):,} steps", "more", "less"),
    "load": (lambda d, u: f"{round(abs(100 * d / u))} %", "higher", "lower"),
}
GET: dict[str, Callable[[dict], float | None]] = {
    **{k: (lambda r, k=k: r["inputs"].get(k)) for k in ("rhr", "hrv", "sleep_min", "awake", "spo2", "steps",
                                                         "bed_start", "bed_end", "cardio_load")},
    "deep_min": lambda r: (r["c"].get("session") or {}).get("deep_min"),
    "rem_min": lambda r: (r["c"].get("session") or {}).get("rem_min"),
    "stress": lambda r: None if r["inputs"].get("stress_load") is None else 60 * r["inputs"]["stress_load"]}
# night kinds whose bad effect earns a "skip it" advice: (advice, calm advice)
AVOID = {"caffeine_late": ("Skip coffee after 14:00", "Skip coffee in the afternoon"),
         "alcohol": ("Skip alcohol tonight", "Skip alcohol tonight"),
         "late_meal": ("Finish eating before 21:00", "Eat earlier in the evening")}
UNUSUAL = 2.0  # a finding needs a gap of at least this many "about the same" bands
MAX_FINDINGS = 2
CLOCK_SAME = 15  # clock times within 15 min count as "about the same"
LATE_MEAL_H = 21  # scoring.derived's late_meal hour
TIMES = {"bed_start": "You fell asleep", "bed_end": "You woke"}
NIGHT_KEYS = ("rhr", "hrv", "sleep_min", "deep_min", "rem_min", "awake", "spo2", "bed_start", "bed_end")
DAY_KEYS = ("steps", "stress", "cardio_load")
# advice line prefix (as _bed, AVOID, update() and _train write them) -> what to follow up on the next day
FOLLOW = {"Be in bed by ": "bed", "Skip coffee": "coffee", "No more coffee today": "coffee",  # older lines
          "No more caffeine today": "coffee", "Skip alcohol": "alcohol",
          "Finish eating before": "meal", "Eat earlier in the evening": "meal", "Keep exercise light": "load",
          "Keep the rest of the day easy": "load"}
# moment kind: (from this hour, none taken, calm none taken, taken)
LATE = {"coffee": (COFFEE_CUTOFF_H, f"coffee after {COFFEE_CUTOFF_H}:00", "coffee in the afternoon", "after the cut-off"),
        "alcohol": (0, "alcohol", "alcohol", "against the plan"),
        "meal": (LATE_MEAL_H, f"meal after {LATE_MEAL_H}:00", "late meal", "after the cut-off")}
NEXT = {"regular": "Go to bed within the same hour each night", "move": "Add a brisk walk or a workout on most days",
        "keep": "Keep your current routine"}


def _than(x: float, ref: float, what: str, more: str = "higher", less: str = "lower") -> str:
    if abs(x - ref) <= SAME * abs(ref):
        return f"about the same as {what}"
    return f"{more if x > ref else less} than {what}"


def _clock(after_noon: float) -> str:
    m = round(720 + after_noon) % 1440
    return f"{m // 60:02d}:{m % 60:02d}"


def _at(ts: float, tz: tzinfo, calm: bool) -> str:
    t = datetime.fromtimestamp(ts, tz)
    if not calm:
        return f"at {t:%H:%M}"
    return "in the morning" if t.hour < 12 else "in the afternoon" if t.hour < 17 else "in the evening" if t.hour < 22 \
        else "at night"


def _on(rows: list[dict], d: date) -> dict:
    return next((r for r in rows if r["date"] == d), None) or {"c": {}, "inputs": {}}


def _usual(rows: list[dict], before: date, get: Callable[[dict], float | None]) -> float | None:
    return scoring.baseline([get(r) for r in rows if r["date"] < before])


def _gap(key: str, x: float | None, ref: float | None) -> int:
    """+1 higher than ref, -1 lower, 0 about the same or unknown."""
    if x is None or ref is None or abs(x - ref) <= max(SAME * abs(ref), BODY[key][2]):
        return 0
    return 1 if x > ref else -1


def _dev(key: str, rows: list[dict], day: date) -> tuple[float, float, float] | None:
    """(reading - usual, the "about the same" band, usual) for one day, or None without both."""
    x, u = GET[key](_on(rows, day)), _usual(rows, day, GET[key])
    if x is None or u is None:
        return None
    band = CLOCK_SAME if key in TIMES else LOAD_SAME * abs(u) if key == "cardio_load" else max(SAME * abs(u), BODY[key][2])
    return (x - u, band, u) if band > 0 else None


def _nth(n: int) -> str:
    return f"{n}{'th' if 10 <= n % 100 <= 20 else {1: 'st', 2: 'nd', 3: 'rd'}.get(n % 10, 'th')}"


def _findings(d: date, rows: list[dict], calm: bool) -> list[tuple[float, str, str]]:
    """(size, card, text), largest first: only gaps of UNUSUAL bands or more, as the difference, never the reading."""
    out = []
    for card, day, keys in (("night", d, NIGHT_KEYS), ("yesterday", d - timedelta(1), DAY_KEYS)):
        for key in keys:
            g = _dev(key, rows, day)
            if not g or abs(g[0]) < UNUSUAL * g[1] or key == "spo2" and g[0] > 0:
                continue
            dx, u, up, n = g[0], g[2], g[0] > 0, 1
            while (p := _dev(key, rows, day - timedelta(n))) and p[0] * dx > 0 and abs(p[0]) > p[1]:
                n += 1
            per = "night" if card == "night" else "day"
            streak = "" if n < 2 else f", for more than one {per}" if calm else f", for the {_nth(n)} {per} in a row"
            if key in TIMES:
                w = "later" if up else "earlier"
                s = f"{TIMES[key]} {w if calm else f'about {_duration(abs(dx))} {w}'} than usual{streak}"
            else:
                noun, unit, _, hi, lo = BODY[key]
                gap, more, less = UNITS[unit]
                s = f"{noun} {'' if calm else gap(dx, u) + ' '}{more if up else less} than your usual" \
                    + (" yesterday" if card == "yesterday" else "") + streak
                s += f": {m}" if (m := hi if up else lo) else ""
            out.append((abs(dx) / g[1], card, s))
    return sorted(out, key=lambda f: -f[0])


def _after(rows: list[dict], d: date, calm: bool) -> str:
    """', and you fell asleep about 40 min later than usual' when last night's fall-asleep time left the usual."""
    g = _dev("bed_start", rows, d)
    if not g or abs(g[0]) <= g[1]:
        return ""
    w = "later" if g[0] > 0 else "earlier"
    return f", and you fell asleep {w if calm else f'about {_duration(abs(g[0]))} {w}'} than usual"


def _follow(d: date, rows: list[dict], ctx: dict) -> list[str]:
    """Did yesterday's advice (ctx["given"], lines as this module wrote them) happen, and what came after."""
    calm, tz, out, seen = ctx["calm"], ctx["tz"], [], set()
    for line in ctx["given"]:
        kind = next((k for p, k in FOLLOW.items() if line.startswith(p)), None)
        if not kind or kind in seen:
            continue
        if kind == "bed":
            x, hhmm = _on(rows, d)["inputs"].get("bed_start"), line[13:18]
            if x is None or not re.fullmatch(r"\d\d:\d\d", hhmm):
                continue
            late = x - (int(hhmm[:2]) * 60 + int(hhmm[3:]) - 720) % 1440
            s = ("You were in bed in time, as planned" if calm else f"You were in bed by {hhmm} as planned") \
                if late <= CLOCK_SAME else "You fell asleep later than you planned" if calm \
                else f"You fell asleep about {_duration(late)} after the {hhmm} you planned"
            if (g := _dev("sleep_min", rows, d)) and _gap("sleep_min", g[0] + g[2], g[2]):
                w = "longer" if g[0] > 0 else "shorter"
                s += f", and you slept {w if calm else f'{_duration(abs(g[0]))} {w}'} than your usual"
        elif kind == "load":
            if not (g := _dev("cardio_load", rows, d - timedelta(1))):
                continue
            s = "Yesterday was a heavier day than planned" if g[0] > g[1] else "You kept yesterday light, as planned"
        else:
            h, none, calm_none, broke = LATE[kind]
            late = [m for m in ctx["moments"] if m["kind"] in (scoring.CAFFEINE if kind == "coffee" else (kind,))
                    and datetime.fromtimestamp(m["ts"], tz).hour >= h]
            s = (f"{NOTED[late[-1]['kind']].capitalize()} {_at(late[-1]['ts'], tz, calm)} yesterday, {broke}" if late
                 else f"No {calm_none if calm else none} yesterday, as planned") + _after(rows, d, calm)
        seen.add(kind)
        out.append(s)
    return out


def _workout(w: dict, tz: tzinfo, calm: bool, when: str = "") -> str:
    what = GUESS.get(w["kind"], "a workout").capitalize()
    if calm:
        return f"{what} {_at(w['start'], tz, True)}{when}"
    bpm = f", average heart rate {w['avg_bpm']} bpm" if w.get("avg_bpm") else ""
    return f"{what} {_at(w['start'], tz, False)} for {_duration(w['minutes'])}{when}{bpm}"


def _noted(m: dict, tz: tzinfo, calm: bool, when: str = "") -> str:
    return f"You noted {NOTED[m['kind']]} {_at(m['ts'], tz, calm)}{when}"


def _effect(e: dict, calm: bool) -> str:
    up, n = e["delta"] > 0, round(abs(e["delta"]))
    if e["metric"] == "bed_start":
        return f"you fall asleep {'' if calm else f'about {n} min '}{'later' if up else 'earlier'}"
    return f"you tend to sleep {'better' if up else 'worse'}"  # a sleep score effect: no score points in the text


def _avoid(because: list[dict], calm: bool) -> list[str]:
    """Only effects that exist for this user and go the bad way (later sleep or worse sleep)."""
    out = []
    for b in because:
        e = b["effect"]
        if e and b["kind"] in AVOID and (e["delta"] > 0 if e["metric"] == "bed_start" else e["delta"] < 0):
            out.append(f"{AVOID[b['kind']][calm]}: after {LOGGED[b['kind']]} {_effect(e, calm)}")
    return out


def _train(verdict: str, t: dict | None, calm: bool) -> str | None:
    w = {"push": "A good day for a harder workout", "steady": "Train at your usual effort",
         "easy": "Keep exercise light, a walk rather than a hard workout", "rest": None}[verdict]
    return w and (w if calm or not t else f"{w}: aim for a cardio load of {t['lo']} to {t['hi']}")


def _bed(p: dict | None, debt: float, calm: bool) -> str | None:
    why = " to pay back some sleep debt" if debt >= 30 else ""
    if p and not calm:
        return f"Be in bed by {_clock(p['in_bed_by_min'])}" + (why or f" to be rested for {_clock(p['wake_min'])}")
    if debt >= 30 or p and p["extra_min"] > 0:
        return "Go to bed a little earlier tonight" + why
    return p and "Go to bed in time for a full night's sleep"


def _card(kind: str, verdict: str | None, facts: Iterable[str | None | bool], advice: Iterable[str | None] = (),
          fallback: str | None = None) -> dict:
    """fallback given = a quiet card: the app shows it as is, without the model."""
    # what matches the usual goes last: the model should lead with what changed
    facts = sorted(dict.fromkeys(f for f in facts if f), key=lambda f: "about the same" in f)[:MAX_FACTS]
    advice = [a for a in advice if a][:MAX_ADVICE]
    lead = [LEAD[verdict]] if verdict else []
    return {"kind": kind, "verdict": verdict, "facts": facts, "advice": advice, "quiet": fallback is not None,
            "fallback": fallback or " ".join(lead + [s + "." for s in facts[:3 - len(lead)] + advice[:1]])}


def _strained(sig: dict) -> bool:
    """notify._strain's rule: above calm for 2+ nights."""
    return sig.get("level") not in (None, "calm") and sig.get("streak", 0) >= 2


def _today(d: date, row: dict, rows: list[dict], ctx: dict, found: list[str]) -> dict | None:
    ready, calm = row.get("readiness_score"), ctx["calm"]
    if ready is None or ctx["guess_pending"]:
        return None
    sig, debt = row["c"].get("signals") or {}, (ctx["debt"] or {}).get("debt_min", 0)
    band = scoring.band(ready)
    verdict = "rest" if _strained(sig) else "easy" if band == 0 or (debt >= EASY_DEBT_MIN and band < 2) \
        else "push" if band == 2 and debt < PUSH_DEBT_MAX else "steady"
    advice = [a for a in (_train(verdict, ctx["target"], calm), debt >= 30 and _bed(ctx["plan"], debt, calm),
                          *_avoid(ctx["because"], calm)) if a]
    low = (row["c"].get("confidence") or {}).get("readiness") == "low" \
        and "The ring had little data last night, so read this loosely"
    fol = _follow(d, rows, ctx)
    told = any("fell asleep" in f for f in fol)  # the follow-up already gave the fall-asleep shift
    slept = any("you slept" in f for f in fol)  # and the night's length
    facts = (fol + [t for t in found if not (told and t.startswith(TIMES["bed_start"]) or slept and t.startswith("Slept"))])[:MAX_FINDINGS]
    if facts:
        return _card("today", verdict, facts + [low], advice)
    none = "Nothing unusual in your body's numbers today"
    return _card("today", verdict, [none, low], advice, " ".join([LEAD[verdict], none + "."] + [a + "." for a in advice[:1]]))


def _quiet(kind: str, facts: list[str] | None, extra: str | None | bool, line: str) -> dict:
    """The card's own findings, or its one fixed line when there are none."""
    return _card(kind, None, facts[:MAX_FINDINGS] + [extra]) if facts else _card(kind, None, [line, extra], (), line + ".")


def _yesterday(d: date, rows: list[dict], facts: list[str] | None) -> dict:
    """facts None: all of yesterday's findings went to today (a different quiet line: not a usual day)."""
    w = _on(rows, d - timedelta(1))["c"].get("wear") or {}
    off = w.get("awake_hours") and w["worn_hours"] < w["awake_hours"] / 2 \
        and "The ring was off for much of the day, so movement may be undercounted"
    return _quiet("yesterday", facts, off, "Nothing else stood out yesterday" if facts is None else "A usual day for you")


def _night(row: dict, facts: list[str] | None) -> dict | None:
    """Always present with a session: the app waits for it. facts None as in _yesterday."""
    if not row["c"].get("session") or row["inputs"].get("sleep_min") is None:
        return None
    return _quiet("night", facts, None, "Nothing else stood out last night" if facts is None else "A usual night for you")


def _tonight(ctx: dict) -> dict | None:
    """Always quiet: its content is the plan the Today page shows."""
    p, b, w, calm = ctx["plan"], ctx["debt"], ctx["window"], ctx["calm"]
    extra, debt = p["extra_min"] if p else (b or {}).get("suggest_min", 0), (b or {}).get("debt_min", 0)
    if not p and not b and w.get("status") != "ok":
        return None
    verdict = "earlier" if extra > 0 else "usual"
    advice = [a for a in (_bed(p, debt, calm), *_avoid(ctx["because"], calm)) if a][:MAX_ADVICE]
    return _card("tonight", verdict, [LEAD[verdict][:-1]], advice, " ".join([LEAD[verdict]] + [a + "." for a in advice]))


def _spread(rows: list[dict], d: date) -> float | None:
    beds = [r["inputs"]["bed_start"] for r in rows if d - timedelta(7) <= r["date"] < d and r["inputs"].get("bed_start") is not None]
    return pstdev(beds) if len(beds) >= 3 else None


def _spread_fact(spread: float | None, calm: bool) -> str | None:
    if spread is None:
        return None
    if calm:
        return "Bedtimes varied a lot" if spread > SPREAD_MAX_MIN else "Bedtimes were regular"
    return f"Bedtimes varied by about {_duration(spread)}" if spread >= 15 else "Bedtimes were regular"


def _next_week(d: date, rows: list[dict], week: dict, ctx: dict) -> dict | None:
    if d.weekday() > 2 or week["scored_days"] < 3:
        return None
    calm, need = ctx["calm"], ctx["need_min"]
    wk = [r["inputs"] for r in rows if d - timedelta(7) <= r["date"] < d]
    asleep, spread = week["sleep_avg_min"], _spread(rows, d)
    loads, base = [i["cardio_load"] for i in wk if i.get("cardio_load") is not None], ctx["cardio_base"]
    load = mean(loads) if loads else None
    verdict = "sleep" if asleep is not None and asleep < need - SLEEP_SHORT_MIN \
        else "regular" if spread is not None and spread > SPREAD_MAX_MIN \
        else "move" if load is not None and base and load < MOVE_RATIO * base else "keep"
    tip = NEXT.get(verdict) or ("Aim for a full night's sleep every night" if calm else f"Aim for {_duration(need)} of sleep a night")
    return _card("next_week", verdict, [
        asleep is not None and ("Slept " + _than(asleep, need, "you need", "more", "less") if calm
                                else f"Slept {_duration(asleep)} a night, your need is {_duration(need)}"),
        _spread_fact(spread, calm),
        load is not None and base and ("Cardio load " + _than(load, base, "usual") if calm
                                       else f"Cardio load {round(load)} a day, your usual is {round(base)}")], [tip])


def _body(row: dict, ctx: dict) -> dict | None:
    sig, calm, e = row["c"].get("signals") or {}, ctx["calm"], ctx["closed"]
    if sig.get("level") not in (None, "calm"):
        facts = []
        for k in sig.get("flags", {}):
            if k == "recovery":
                facts.append("Overnight recovery later than usual")
                continue
            d = sig["deltas"][k]
            name, unit = NOUNS[k]
            if calm:
                facts.append(f"{name[0].upper() + name[1:]} {'higher' if d > 0 else 'lower'} than usual")
            else:
                pct = 100 * d / sig["usual"][k] if k == "hrv" else d  # as notify._strain: HRV as a share of the usual
                facts.append(f"{name[0].upper() + name[1:]} {round(pct):+d}{unit} against your usual".replace("-", "−"))
        streak = sig.get("streak", 0)
        facts.append("Above calm last night" if streak < 2 else "Above calm for more than one night" if calm
                     else f"Above calm for {streak} nights")
        if ctx["explain"]:
            facts.append(f"You logged {_join([LOGGED[k] for k in ctx['explain']])} yesterday, which can cause this")
        return _card("body", "watch", facts, ["Rest and drink enough"])  # the app adds the doctor line
    if e:
        when = f"{e['first']:%A}" if e["first"] == e["last"] else f"{e['first']:%A} to {e['last']:%A}"
        return _card("body", "back", [
            ("Back in your usual range" if calm else f"Back in your usual range after {_nights(e['nights'])}"),
            e.get("kinds") and f"After {_join([LOGGED[k] for k in e['kinds']])}, {when}"])
    return None


LABEL = {"wake": "Woke", "tired": "Tired", "flat": "Flat", "wired": "Wired", "good": "Good", "coffee": "Coffee",
         "alcohol": "Alcohol", "meal": "Meal", "dose": "Dose", "snack": "Snack", "energy": "Energy drink",
         "nicotine": "Nicotine", "nap": "Nap"}
WIND_DOWN_MIN = 120  # inside 2 h of the planned bedtime: wind-down lines only
MOVE_ROOM_MIN = 180  # "move" needs at least 3 h before the planned bedtime
EFFORT_MIN = 30  # a walk, ride or workout this long today is effort: go easy after it
COFFEE_MAX = 3  # this many coffees, or any coffee once past the cut-off: no more coffee
STRESS_LONG_MIN = 60  # stressed this long since the last note: a walk or the breathing exercise
MOVE_SHARE = 0.4  # under this share of the step goal by the afternoon is little movement
AFTERNOON_H = 14
STILL_STEPS = 500  # under this many steps since the last note is no movement (scoring.still_hours' hour)


def timeline(session: dict | None, workouts: list[dict], moments: list[dict], doses: list[float],
             steps: dict[float, float], calm: bool) -> list[dict]:
    """The day's recorded events in time order: the night's end, workouts (the phone's moves included), moments, doses,
    active hours (steps: hour start epoch s -> steps; an hour a walk or run overlaps is left out, the workout says it)."""
    ev = [{"t": w["start"], "kind": w["kind"], "label": f"{w['kind'].capitalize()} · {_duration(w['minutes'])}"}
          for w in workouts]
    moving = [(w["start"], w["start"] + w["minutes"] * 60) for w in workouts if w["kind"] in ("walk", "run")]
    ev += [{"t": t, "kind": "steps", "label": "An active hour" if calm else f"{round(v):,} steps", "steps": round(v)}
           for t, v in steps.items() if v >= STILL_STEPS and not any(a < t + 3600 and b > t for a, b in moving)]
    ev += [{"t": m["ts"], "kind": m["kind"], "label": LABEL.get(m["kind"], m["kind"].capitalize())} for m in moments]
    ev += [{"t": t, "kind": "dose", "label": LABEL["dose"]} for t in doses]  # shown only, never advised on
    if session and "end" in session:
        ev.append({"t": session["end"], "kind": "wake", "label": LABEL["wake"]})
    return sorted(ev, key=lambda e: e["t"])


def update(now: float, since: float, day: dict, given: list[str], ctx: dict) -> dict:
    """What changed since the last note and the advice that fits now (PLAN13 DT2). day: moments and workouts of today
    (main's shapes), steps_today, steps_since, stressed_min (since), hr_since and hr_usual (mean bpm or None).
    ctx: calm, tz, step_goal, bed (planned bedtime, epoch s, or None). Medication is never read, so never advised on."""
    calm, tz = ctx["calm"], ctx["tz"]
    at = "since the last note" if calm else f"since {datetime.fromtimestamp(since, tz):%H:%M}"
    ws = [w for w in day["workouts"] if w["start"] >= since]
    ms = [m for m in day["moments"] if m["ts"] >= since and m["kind"] in NOTED]
    coffee = [m["ts"] for m in day["moments"] if m["kind"] in scoring.CAFFEINE]
    hour, bed = datetime.fromtimestamp(now, tz).hour, ctx["bed"]
    to_bed = None if bed is None else (bed - now) / 60
    evening = to_bed is not None and to_bed <= WIND_DOWN_MIN
    goal, stressed, hr, usual = ctx["step_goal"], day["stressed_min"], day["hr_since"], day["hr_usual"]
    dhr = 0 if hr is None or usual is None or abs(hr - usual) < 5 else hr - usual
    # only what no page shows: events since the last note, and stretches that left the usual (as the difference)
    news = [
        *[_workout(w, tz, calm) for w in ws], *[_noted(m, tz, calm) for m in ms],
        now - since >= 3600 and day["steps_since"] < STILL_STEPS and f"Little movement {at}: a still stretch",
        stressed >= 30 and (f"Stressed for a while {at}" if calm else f"Stressed for {_duration(stressed)} {at}")
        + ": a demanding stretch",
        dhr and f"Heart rate {'' if calm else f'{round(abs(dhr))} bpm '}{'higher' if dhr > 0 else 'lower'} than your usual {at}"
        + (": your body has been busier than usual" if dhr > 0 else ": a calm stretch")]
    # (line, fresh): a fresh line rests on something since the last note, so it may return even if already given
    rules: list[tuple[str, bool]] = []
    if evening:
        rules += [("Start winding down: dim the lights and put screens away", False),
                  ("Go to bed at your planned time" if calm else f"Be in bed by {datetime.fromtimestamp(bed, tz):%H:%M}", False)]
    else:
        effort = [w for w in day["workouts"] if w["minutes"] >= EFFORT_MIN]
        if effort:
            rules.append((f"Keep the rest of the day easy after {GUESS.get(effort[-1]['kind'], 'a workout')}",
                          effort[-1]["start"] >= since))
        if len(coffee) >= COFFEE_MAX or coffee and hour >= COFFEE_CUTOFF_H:
            rules.append(("No more caffeine today: it can push your sleep later", coffee[-1] >= since))
        if stressed >= STRESS_LONG_MIN:
            rules.append(("Take a short walk or do the breathing exercise", True))
        if not effort and hour >= AFTERNOON_H and day["steps_today"] < MOVE_SHARE * goal \
                and (to_bed is None or to_bed >= MOVE_ROOM_MIN):
            rules.append(("Fit in a brisk walk while there is time before bed", False))
    advice = [a for a, fresh in rules if fresh or a not in given]
    # the coffee count is the reason for the coffee advice, not news on its own
    c = _card("update", None, news + [len(coffee) >= 2 and ("Caffeine more than once today" if calm
                                                           else f"Caffeine {len(coffee)} times today")], advice)
    return {"at": int(now), "nothing_new": not (any(news) or advice), "evening": evening,
            **{k: c[k] for k in ("facts", "advice", "fallback")}}


def cards(d: date, rows: list[dict], ctx: dict) -> list[dict]:
    """rows: db.days(d - 13, d); ctx: calm, guess_pending,
    plan (notify.tonight), window (notify.bedtime_window), debt, target (cardio.target), moments and
    because (of d - 1), explain (notify.explain), closed (episode closed in the last 3 days or None), tz (for clock times),
    given (advice lines shown on d - 1, followed up in today)."""
    row, found = _on(rows, d), _findings(d, rows, ctx["calm"])
    today = _today(d, row, rows, ctx, [t for _, _, t in found])
    used = set(today["facts"]) if today else set()
    if any("fell asleep" in f for f in used):  # a follow-up already told the fall-asleep shift
        used |= {t for _, _, t in found if t.startswith(TIMES["bed_start"])}
    if any("you slept" in f for f in used):
        used |= {t for _, _, t in found if t.startswith("Slept")}

    def left(card: str) -> list[str] | None:
        """None when all of the card's findings went to today: not a usual day, just nothing else to say."""
        mine = [t for _, c, t in found if c == card]
        rest = [t for t in mine if t not in used]
        return rest if rest or not mine else None

    out = [today, _yesterday(d, rows, left("yesterday")), _night(row, left("night")), _tonight(ctx), _body(row, ctx)]
    return [c for c in out if c and c["facts"]]


MAX_WEEK_FACTS = 5


def _night_of(d: date) -> str:
    return f"the night into {d:%A}"  # a row's night ends on its date


def _week_sleep(start: date, rows: list[dict], before: list[dict], full: bool, ctx: dict) -> dict | None:
    calm, need = ctx["calm"], ctx["need_min"]
    nights = [(r["date"], x) for r in rows if (x := r["inputs"].get("sleep_min")) is not None]
    if not nights:
        return None
    a = mean(x for _, x in nights)
    b = [x for r in before if (x := r["inputs"].get("sleep_min")) is not None]
    b = mean(b) if full and len(b) >= 3 else None  # a week the ring was mostly off is no reference
    short, long = min(nights, key=lambda n: n[1]), max(nights, key=lambda n: n[1])
    under = sum(x < need for _, x in nights)
    vs_need = "Slept " + _than(a, need, "you need", "more", "less") if calm else \
        f"Slept {_duration(a)} a night on average, your need is {_duration(need)}"
    facts = [
        vs_need + (": can leave you less rested" if a < need - SLEEP_SHORT_MIN else ""),
        b is not None and ("Nights about as long as the week before" if abs(a - b) < 15 else
                           f"Slept {'' if calm else _duration(abs(a - b)) + ' '}{'more' if a > b else 'less'} a night "
                           "than the week before"),
        len(nights) >= 2 and (f"Shortest night was {_night_of(short[0])}, the longest {_night_of(long[0])}" if calm else
                              f"Shortest night was {_night_of(short[0])} ({_duration(short[1])}), "
                              f"the longest {_night_of(long[0])} ({_duration(long[1])})"),
        _spread_fact(_spread(rows, start + timedelta(7)), calm),
        ("No night under your need" if under == 0 else "Every night under your need" if under == len(nights)
         else "Some nights under your need") if calm else f"{under} of {len(nights)} nights under your need"]
    return _card("week_sleep", None, [f for f in facts if f][:MAX_WEEK_FACTS])


def _week_activity(rows: list[dict], before: list[dict], full: bool, base: float | None, calm: bool) -> dict | None:
    days = [(r["date"], x) for r in rows if (x := r["inputs"].get("steps")) is not None]
    ws = [w for r in rows for w in r["c"].get("workouts") or []]
    loads = [x for r in rows if (x := r["inputs"].get("cardio_load")) is not None]
    if not days and not ws and not loads:
        return None
    total = sum(x for _, x in days)
    prev = [x for r in before if (x := r["inputs"].get("steps")) is not None]
    steps = days and (f"Walked {round(total):,} steps this week" if not calm else "")
    if days and full and prev and (p := sum(prev)):
        if abs(total - p) <= SAME * p:
            steps = "Walked about as much as the week before" if calm else f"{steps}, about as much as the week before"
        else:
            w = "more" if total > p else "less"
            steps = (f"Walked {w} than the week before" if calm
                     else f"{steps}, {round(100 * abs(total - p) / p)} % {w} than the week before") \
                + (": a more active week" if total > p else ": a quieter week")
    hi, lo = max(days, key=lambda n: n[1], default=None), min(days, key=lambda n: n[1], default=None)
    kinds = _join(sorted({w["kind"] for w in ws})) if ws else ""
    load = mean(loads) if loads else None
    facts = [
        steps,
        len(days) >= 2 and (f"Most active day {hi[0]:%A}, least active {lo[0]:%A}" if calm else
                            f"Most active day {hi[0]:%A} ({round(hi[1]):,} steps), least active {lo[0]:%A} "
                            f"({round(lo[1]):,} steps)"),
        "No workouts this week" if not ws else f"Workouts this week: {kinds}" if calm else
        f"{len(ws)} workout{'s' if len(ws) > 1 else ''}, {_duration(sum(w['minutes'] for w in ws))} in all: {kinds}",
        load is not None and base and (("Cardio load " + _than(load, base, "usual")) if calm else
                                       f"Cardio load {round(load)} a day, your usual is {round(base)}")
        + (": a lighter week for your heart" if load < MOVE_RATIO * base
           else ": a heavier week for your heart" if load > (1 + LOAD_SAME) * base else "")]
    return _card("week_activity", None, [f for f in facts if f][:MAX_WEEK_FACTS])


def week_cards(start: date, rows: list[dict], ctx: dict) -> dict:
    """The finished week start (a Monday) to start + 6. rows: db.days(start - 14, start + 6); ctx: calm, need_min.
    LW2 / LW3 append their days keys and cards here."""
    end = start + timedelta(6)
    wk = [r for r in rows if start <= r["date"] <= end]
    before = [r for r in rows if start - timedelta(7) <= r["date"] < start]

    def scored(rs: list[dict]) -> int:
        return sum(any(r.get(k) is not None for k in ("sleep_score", "readiness_score", "activity_score")) for r in rs)

    days = []
    for i in range(7):
        r = _on(rows, start + timedelta(i))
        days.append({"date": (start + timedelta(i)).isoformat(), "sleep_min": r["inputs"].get("sleep_min"),
                     "need_min": ctx["need_min"], "bed_min": r["inputs"].get("bed_start"), "steps": r["inputs"].get("steps"),
                     "load": r["inputs"].get("cardio_load"),
                     "workout_min": sum(w["minutes"] for w in r["c"].get("workouts") or [])})
    out = []
    if scored(wk) >= 3:
        full = scored(before) >= 3
        base = scoring.baseline([r["inputs"].get("cardio_load") for r in rows if r["date"] < start])  # notify.cardio_base(start)
        out = [_week_sleep(start, wk, before, full, ctx), _week_activity(wk, before, full, base, ctx["calm"])]
    return {"start": start.isoformat(), "end": end.isoformat(), "days": days, "cards": [c for c in out if c]}
