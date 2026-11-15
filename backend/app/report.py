"""Report cards for the on-phone writer (PLAN12 AI1): verdicts and finished fact phrases, decided here, never by the model.
Pure: rows and helper results in (main.report gathers them), card dicts out."""
from collections.abc import Callable
from datetime import date, timedelta
from statistics import mean, pstdev

from . import scoring
from .notify import GUESS, LOGGED, NOUNS, WORDS, _duration, _join, _nights

PUSH_DEBT_MAX = 60  # push needs readiness 85+ (scoring.band 2) and under 1 h of sleep debt
EASY_DEBT_MIN = 180  # 3 h of debt makes any day easy
SLEEP_SHORT_MIN = 30  # next week = sleep when the week's nights average this far under the need
SPREAD_MAX_MIN = 60  # next week = regular when bedtimes spread (SD) more than this
MOVE_RATIO = 0.7  # next week = move when the week's cardio load is under this share of the 14-day mean
SAME = 0.05  # within 5 % counts as "about the same"
MAX_FACTS = 8

LEAD = {"push": "A good day to push.", "steady": "A steady day, keep to your usual.", "easy": "Take it easy today.",
        "rest": "Rest today.", "usual": "Your usual bedtime works tonight.", "earlier": "Go to bed a little earlier tonight.",
        "sleep": "Next week, put sleep first.", "regular": "Next week, keep a regular bedtime.",
        "move": "Next week, move a bit more.", "keep": "Next week, keep doing what you do.",
        "watch": "Your body may be under strain, so rest and hydrate.",
        "back": "Your body signals are back in your usual range."}
NOTED = {"tired": "feeling tired", "flat": "feeling flat", "wired": "feeling wired", "good": "feeling good",
         "coffee": "coffee", "alcohol": "alcohol", "meal": "a meal"}


def _than(x: float, ref: float, what: str, more: str = "higher", less: str = "lower") -> str:
    if abs(x - ref) <= SAME * abs(ref):
        return f"about the same as {what}"
    return f"{more if x > ref else less} than {what}"


def _vs(label: str, x: float | None, usual: float | None, calm: bool,
        fmt: Callable[[float], str] = lambda v: str(round(v))) -> str | None:
    """'Readiness 58, your usual is 74', or in calm mode 'Readiness lower than usual' (None without a usual)."""
    if x is None:
        return None
    if calm:
        return usual is not None and f"{label} {_than(x, usual, 'usual')}" or None
    return f"{label} {fmt(x)}" + (f", your usual is {fmt(usual)}" if usual is not None else "")


def _slept(x: float, usual: float | None, calm: bool) -> str:
    if calm:
        return "Slept " + (_than(x, usual, "usual", "more", "less") if usual else "through the night")
    s = f"Slept {_duration(x)}"
    if usual is None or abs(x - usual) < 15:
        return s + (", about your usual" if usual is not None else "")
    return s + f", {_duration(abs(x - usual))} {'over' if x > usual else 'under'} your usual"


def _clock(after_noon: float) -> str:
    m = round(720 + after_noon) % 1440
    return f"{m // 60:02d}:{m % 60:02d}"


def _card(kind: str, verdict: str | None, facts: list[str | None]) -> dict:
    facts = [f for f in facts if f][:MAX_FACTS]
    lead = [LEAD[verdict]] if verdict else []
    return {"kind": kind, "verdict": verdict, "facts": facts,
            "fallback": " ".join(lead + [f + "." for f in facts[:3 - len(lead)]])}


def _usual(rows: list[dict], before: date, get: Callable[[dict], float | None]) -> float | None:
    return scoring.baseline([get(r) for r in rows if r["date"] < before])


def _strained(sig: dict) -> bool:
    """notify._strain's rule: above calm for 2+ nights."""
    return sig.get("level") not in (None, "calm") and sig.get("streak", 0) >= 2


def _today(d: date, row: dict, rows: list[dict], ctx: dict) -> dict | None:
    ready, calm = row.get("readiness_score"), ctx["calm"]
    if ready is None or ctx["guess_pending"]:
        return None
    sig, debt = row["c"].get("signals") or {}, (ctx["debt"] or {}).get("debt_min", 0)
    band = scoring.band(ready)
    verdict = "rest" if _strained(sig) else "easy" if band == 0 or debt >= EASY_DEBT_MIN \
        else "push" if band == 2 and debt < PUSH_DEBT_MAX else "steady"
    c = {k: v for k, v in (row["c"].get("readiness") or {}).items() if v is not None}
    low = min(c, key=lambda k: c[k], default=None)
    asleep = row["inputs"].get("sleep_min")
    t = ctx["target"]
    return _card("today", verdict, [
        _vs("Readiness", ready, _usual(rows, d, lambda r: r["readiness_score"]), calm),
        asleep is not None and _slept(asleep, _usual(rows, d, lambda r: r["inputs"].get("sleep_min")), calm),
        low and f"Lowest factor: {WORDS.get(low, low)}" + ("" if calm else f" ({round(c[low])})"),
        debt >= 30 and ("Some sleep debt from the last week" if calm else f"Sleep debt {_duration(debt)} over the last 7 nights"),
        sig.get("level") not in (None, "calm") and "Body signals above calm" + (
            "" if sig.get("streak", 0) < 2 else " for more than one night" if calm else f" for {sig['streak']} nights"),
        t and not calm and f"Cardio target {t['lo']} to {t['hi']}"])


def _yesterday(d: date, rows: list[dict], ctx: dict) -> dict | None:
    y, calm = d - timedelta(1), ctx["calm"]
    row = next((r for r in rows if r["date"] == y), {"c": {}, "inputs": {}})
    facts = [_vs(name, row.get(k), _usual(rows, y, lambda r, k=k: r[k]), calm)
             for name, k in (("Sleep score", "sleep_score"), ("Readiness", "readiness_score"), ("Activity", "activity_score"))]
    facts.append(_vs("Steps", row["inputs"].get("steps"), _usual(rows, y, lambda r: r["inputs"].get("steps")), calm,
                     lambda v: f"{round(v):,}"))
    for w in row["c"].get("workouts") or []:
        what = GUESS.get(w["kind"], "a workout").capitalize()
        facts.append(what if calm else f"{what} of {_duration(w['minutes'])}")
    noted = list(dict.fromkeys(NOTED[m["kind"]] for m in ctx["moments"] if m["kind"] in NOTED))
    facts.append(noted and f"You noted {_join(noted)}")
    return _card("yesterday", None, facts) if any(facts) else None


def _night(d: date, row: dict, rows: list[dict], ctx: dict) -> dict | None:
    s, i, calm = row["c"].get("session"), row["inputs"], ctx["calm"]
    if not s or i.get("sleep_min") is None:
        return None
    facts = [_slept(i["sleep_min"], _usual(rows, d, lambda r: r["inputs"].get("sleep_min")), calm),
             _vs("Sleep score", row.get("sleep_score"), _usual(rows, d, lambda r: r["sleep_score"]), calm)]
    if not calm:
        facts.append(f"Deep sleep {_duration(s.get('deep_min', 0))}, REM {_duration(s.get('rem_min', 0))}")
        if i.get("bed_start") is not None and i.get("bed_end") is not None:
            facts.append(f"Asleep from {_clock(i['bed_start'])} to {_clock(i['bed_end'])}")
    for b in ctx["because"]:
        e = b["effect"]
        if e and b["kind"] in LOGGED:
            n, up = round(abs(e["delta"])), e["delta"] > 0
            if e["metric"] == "bed_start":
                what = f"you fall asleep {'' if calm else f'about {n} min '}{'later' if up else 'earlier'}"
            else:
                what = f"your sleep score is {'' if calm else f'about {n} points '}{'higher' if up else 'lower'}"
            facts.append(f"After {LOGGED[b['kind']]} {what}")
    return _card("night", None, facts)


def _tonight(ctx: dict) -> dict | None:
    p, b, w, calm = ctx["plan"], ctx["debt"], ctx["window"], ctx["calm"]
    extra = p["extra_min"] if p else (b or {}).get("suggest_min", 0)
    if not p and not b and w.get("status") != "ok":
        return None
    facts = []
    if p:
        facts.append("Aim for a full night before your wake time" if calm
                     else f"In bed by {_clock(p['in_bed_by_min'])} to be rested for {_clock(p['wake_min'])}")
    if extra > 0:
        facts.append("A little extra sleep tonight helps you catch up" if calm else f"{_duration(extra)} extra tonight to catch up")
    if b and b["debt_min"] >= 30:
        facts.append("Some sleep debt from the last week" if calm else f"Sleep debt {_duration(b['debt_min'])} over the last 7 nights")
    if w.get("status") == "ok":
        facts.append("Your best nights start at your usual bedtime" if calm
                     else f"Your best nights start between {_clock(w['start_min'])} and {_clock(w['end_min'])}")
    return _card("tonight", "earlier" if extra > 0 else "usual", facts)


def _week(week: dict, prev: dict, calm: bool) -> dict | None:
    if week["scored_days"] < 3:
        return None
    facts = []
    for name, k in (("Sleep score", "sleep"), ("Readiness", "readiness"), ("Activity", "activity")):
        a, b = week["means"][k], prev["means"][k]
        if a is None:
            continue
        if calm:
            facts.append(b is not None and f"{name} {_than(a, b, 'the week before')}")
        else:
            facts.append(f"{name} averaged {round(a)}" + (f", {round(b)} the week before" if b is not None else ""))
    for word, n in (("Best", week["best_night"]), ("Worst", week["worst_night"])):
        if n and week["best_night"] != week["worst_night"]:
            facts.append(f"{word} night {date.fromisoformat(n['date']):%A}" + ("" if calm else f", sleep score {n['score']}"))
    if week["sleep_avg_min"] is not None:
        a, b = week["sleep_avg_min"], prev["sleep_avg_min"]
        facts.append(b and "Slept " + _than(a, b, "the week before", "more", "less") if calm
                     else f"Slept {_duration(a)} a night on average")
    if week["steps_total"] is not None:
        a, b = week["steps_total"], prev["steps_total"]
        facts.append(b and f"Steps {_than(a, b, 'the week before')}" if calm
                     else f"{round(a):,} steps" + (f", {round(b):,} the week before" if b else ""))
    return _card("week", None, facts)


def _next_week(d: date, rows: list[dict], week: dict, ctx: dict) -> dict | None:
    if d.weekday() > 2 or week["scored_days"] < 3:
        return None
    calm, need = ctx["calm"], ctx["need_min"]
    wk = [r["inputs"] for r in rows if d - timedelta(7) <= r["date"] < d]
    asleep, beds = week["sleep_avg_min"], [i["bed_start"] for i in wk if i.get("bed_start") is not None]
    spread = pstdev(beds) if len(beds) >= 3 else None
    loads, base = [i["cardio_load"] for i in wk if i.get("cardio_load") is not None], ctx["cardio_base"]
    load = mean(loads) if loads else None
    verdict = "sleep" if asleep is not None and asleep < need - SLEEP_SHORT_MIN \
        else "regular" if spread is not None and spread > SPREAD_MAX_MIN \
        else "move" if load is not None and base and load < MOVE_RATIO * base else "keep"
    return _card("next_week", verdict, [
        asleep is not None and ("Slept " + _than(asleep, need, "you need", "more", "less") if calm
                                else f"Slept {_duration(asleep)} a night, your need is {_duration(need)}"),
        spread is not None and (("Bedtimes varied a lot" if spread > SPREAD_MAX_MIN else "Bedtimes were regular") if calm
                                else f"Bedtimes varied by about {_duration(spread)}" if spread >= 15
                                else "Bedtimes were regular"),
        load is not None and base and ("Cardio load " + _than(load, base, "usual") if calm
                                       else f"Cardio load {round(load)} a day, your usual is {round(base)}")])


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
        return _card("body", "watch", facts)
    if e:
        when = f"{e['first']:%A}" if e["first"] == e["last"] else f"{e['first']:%A} to {e['last']:%A}"
        return _card("body", "back", [
            ("Back in your usual range" if calm else f"Back in your usual range after {_nights(e['nights'])}"),
            e.get("kinds") and f"After {_join([LOGGED[k] for k in e['kinds']])}, {when}"])
    return None


def cards(d: date, rows: list[dict], week: dict, prev: dict, ctx: dict) -> list[dict]:
    """rows: db.days(d - 13, d); week / prev: main._week ending d - 1 and d - 8; ctx: calm, guess_pending, need_min,
    plan (notify.tonight), window (notify.bedtime_window), debt, target (cardio.target), cardio_base, moments and
    because (of d - 1), explain (notify.explain), closed (episode closed in the last 3 days or None)."""
    row = next((r for r in rows if r["date"] == d), None) or {"c": {}, "inputs": {}}
    out = [_today(d, row, rows, ctx), _yesterday(d, rows, ctx), _night(d, row, rows, ctx), _tonight(ctx),
           _week(week, prev, ctx["calm"]), _next_week(d, rows, week, ctx), _body(row, ctx)]
    return [c for c in out if c and c["facts"]]
