"""Report cards for the on-phone writer (PLAN12 AI1): verdicts, observations with their meaning attached and the allowed
advice, all decided here, never by the model. No score number ever appears: the app already shows the scores.
Pure: rows and helper results in (main.get_report gathers them), card dicts out."""
from collections.abc import Callable, Iterable
from datetime import date, datetime, timedelta, tzinfo
from statistics import mean, pstdev

from . import scoring
from .notify import GUESS, LOGGED, NOUNS, WORDS, _duration, _join, _nights

PUSH_DEBT_MAX = 60  # push needs readiness 85+ (scoring.band 2) and under 1 h of sleep debt
EASY_DEBT_MIN = 180  # 3 h of debt makes a day easy, unless readiness is in the top band (then steady)
SLEEP_SHORT_MIN = 30  # next week = sleep when the week's nights average this far under the need
SPREAD_MAX_MIN = 60  # next week = regular when bedtimes spread (SD) more than this
MOVE_RATIO = 0.7  # next week = move when the week's cardio load is under this share of the 14-day mean
LOAD_SAME = 0.2  # cardio load within 20 % of its usual is an ordinary day
SAME = 0.05  # within 5 % counts as "about the same"
MAX_FACTS = 12
MAX_ADVICE = 3

LEAD = {"push": "A good day to push.", "steady": "A steady day, keep to your usual.", "easy": "Take it easy today.",
        "rest": "Rest today.", "usual": "Your usual bedtime works tonight.", "earlier": "Go to bed a little earlier tonight.",
        "sleep": "Next week, put sleep first.", "regular": "Next week, keep a regular bedtime.",
        "move": "Next week, move a bit more.", "keep": "Next week, keep doing what you do.",
        "watch": "Your body may be under strain, so rest and hydrate.",
        "back": "Your body signals are back in your usual range."}
NOTED = {"tired": "feeling tired", "flat": "feeling flat", "wired": "feeling wired", "good": "feeling good",
         "coffee": "coffee", "alcohol": "alcohol", "meal": "a meal"}

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
}
# unit: (value, gap from the reference, calm word when higher, when lower)
UNITS: dict[str, tuple[Callable[[float], str], Callable[[float, float], str], str, str]] = {
    "bpm": (lambda v: f"{round(v)} bpm", lambda d, u: f"{round(abs(d))} bpm", "higher", "lower"),
    "ms": (lambda v: f"{round(v)} ms", lambda d, u: f"{round(abs(100 * d / u))} %", "higher", "lower"),
    "min": (_duration, lambda d, u: _duration(abs(d)), "longer", "shorter"),
    "%": (lambda v: f"{round(v)} %", lambda d, u: f"{round(abs(d))} %", "higher", "lower"),
    "steps": (lambda v: f"{round(v):,} steps", lambda d, u: f"{round(abs(d)):,} steps", "more", "less"),
}
GET: dict[str, Callable[[dict], float | None]] = {
    **{k: (lambda r, k=k: r["inputs"].get(k)) for k in ("rhr", "hrv", "sleep_min", "awake", "spo2", "steps")},
    "deep_min": lambda r: (r["c"].get("session") or {}).get("deep_min"),
    "rem_min": lambda r: (r["c"].get("session") or {}).get("rem_min"),
    "stress": lambda r: None if r["inputs"].get("stress_load") is None else 60 * r["inputs"]["stress_load"]}
# night kinds whose bad effect earns a "skip it" advice: (advice, calm advice)
AVOID = {"caffeine_late": ("Skip coffee after 14:00", "Skip coffee in the afternoon"),
         "alcohol": ("Skip alcohol tonight", "Skip alcohol tonight"),
         "late_meal": ("Finish eating before 21:00", "Eat earlier in the evening")}
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


def _cmp(key: str, x: float | None, ref: float | None, calm: bool, what: str = "your usual") -> str | None:
    """'Resting heart rate 52 bpm, 4 bpm below your usual: a sign of good recovery' (calm: no numbers)."""
    if x is None:
        return None
    noun, unit, _, up, down = BODY[key]
    val, gap, more, less = UNITS[unit]
    if ref is None:
        return None if calm else f"{noun} {val(x)}"
    g = _gap(key, x, ref)
    if not g:
        return f"{noun} about the same as {what}" if calm else f"{noun} {val(x)}, about the same as {what}"
    meaning = up if g > 0 else down
    s = f"{noun} {more if g > 0 else less} than {what}" if calm \
        else f"{noun} {val(x)}, {gap(x - ref, ref)} {'above' if g > 0 else 'below'} {what}"
    return s + (f": {meaning}" if meaning else "")


def _vs(key: str, row: dict, rows: list[dict], d: date, calm: bool) -> str | None:
    return _cmp(key, GET[key](row), _usual(rows, d, GET[key]), calm)


def _timing(noun: str, x: float | None, ref: float | None, calm: bool) -> str | None:
    """Clock times in minutes after noon: 'You fell asleep at 01:21, about 1 h 30 min later than usual'."""
    if x is None:
        return None
    if ref is None:
        return None if calm else f"{noun} at {_clock(x)}"
    d = x - ref
    if abs(d) < 15:
        return f"{noun} at about the same time as usual" if calm else f"{noun} at {_clock(x)}, about the same time as usual"
    w = "later" if d > 0 else "earlier"
    return f"{noun} {w} than usual" if calm else f"{noun} at {_clock(x)}, about {_duration(abs(d))} {w} than usual"


def _level(noun: str, x: float | None, ref: float | None) -> str | None:
    """A score's level in words only: 'Overall recovery well above your usual'."""
    if x is None or ref is None:
        return None
    d = x - ref
    if abs(d) < 3:
        return f"{noun} about the same as your usual"
    return f"{noun} {'well ' if abs(d) >= 10 else ''}{'above' if d > 0 else 'below'} your usual"


def _low_hr(row: dict, rows: list[dict], d: date, ctx: dict) -> str | None:
    """recovery = share of the night before HR nears its low; readiness scores an early low as better."""
    x, u = row["inputs"].get("recovery"), _usual(rows, d, lambda r: r["inputs"].get("recovery"))
    if x is None or u is None or abs(x - u) <= scoring.FLOORS["recovery"]:
        return None
    t = (row["c"].get("sleep_extra") or {}).get("hr_low_t")
    at = "" if ctx["calm"] or t is None else " " + _at(t, ctx["tz"], False)
    if x > u:
        return f"Heart rate reached its overnight low{at}, later in the night than usual: often means your body took longer to settle"
    return f"Heart rate reached its overnight low{at}, earlier in the night than usual: a sign your body settled quickly"


def _load(row: dict, rows: list[dict], d: date) -> str | None:
    x, u = row["inputs"].get("cardio_load"), _usual(rows, d, lambda r: r["inputs"].get("cardio_load"))
    if x is None or not u or abs(x - u) <= LOAD_SAME * u:
        return None
    return f"Yesterday was a {'heavier' if x > u else 'lighter'} day than usual for your heart"


def _debt(m: float, calm: bool) -> str | None:
    if m < 30:
        return None
    s = "Some sleep debt from the last week" if calm else f"Sleep debt {_duration(m)} over the last 7 nights"
    return s + (": can leave you more tired than usual" if m >= PUSH_DEBT_MAX else "")


def _workout(w: dict, tz: tzinfo, calm: bool, when: str = "") -> str:
    what = GUESS.get(w["kind"], "a workout").capitalize()
    if calm:
        return f"{what} {_at(w['start'], tz, True)}{when}"
    bpm = f", average heart rate {w['avg_bpm']} bpm" if w.get("avg_bpm") else ""
    return f"{what} {_at(w['start'], tz, False)} for {_duration(w['minutes'])}{when}{bpm}"


def _noted(m: dict, tz: tzinfo, calm: bool, when: str = "") -> str:
    return f"You noted {NOTED[m['kind']]} {_at(m['ts'], tz, calm)}{when}"


def _signals(sig: dict, calm: bool) -> str | None:
    if sig.get("level") in (None, "calm"):
        return None
    streak = sig.get("streak", 0)
    return "Body signals above calm" + ("" if streak < 2 else " for more than one night" if calm
                                        else f" for {streak} nights") + (": your body may be under strain" if streak >= 2 else "")


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


def _card(kind: str, verdict: str | None, facts: Iterable[str | None | bool], advice: Iterable[str | None] = ()) -> dict:
    # what matches the usual goes last: the model should lead with what changed
    facts = sorted(dict.fromkeys(f for f in facts if f), key=lambda f: "about the same" in f)[:MAX_FACTS]
    advice = [a for a in advice if a][:MAX_ADVICE]
    lead = [LEAD[verdict]] if verdict else []
    return {"kind": kind, "verdict": verdict, "facts": facts, "advice": advice,
            "fallback": " ".join(lead + [s + "." for s in facts[:3 - len(lead)] + advice[:1]])}


def _strained(sig: dict) -> bool:
    """notify._strain's rule: above calm for 2+ nights."""
    return sig.get("level") not in (None, "calm") and sig.get("streak", 0) >= 2


def _today(d: date, row: dict, rows: list[dict], ctx: dict) -> dict | None:
    ready, calm, tz = row.get("readiness_score"), ctx["calm"], ctx["tz"]
    if ready is None or ctx["guess_pending"]:
        return None
    sig, debt = row["c"].get("signals") or {}, (ctx["debt"] or {}).get("debt_min", 0)
    band = scoring.band(ready)
    verdict = "rest" if _strained(sig) else "easy" if band == 0 or (debt >= EASY_DEBT_MIN and band < 2) \
        else "push" if band == 2 and debt < PUSH_DEBT_MAX else "steady"
    i, y = row["inputs"], _on(rows, d - timedelta(1))
    g = {k: _gap(k, GET[k](row), _usual(rows, d, GET[k])) for k in ("rhr", "hrv", "sleep_min")}
    good, bad = g["rhr"] < 0 or g["hrv"] > 0, g["rhr"] > 0 or g["hrv"] < 0
    likely = {"push": good and not bad and "Likely more energy than usual today",
              "steady": band == 2 and "Likely fine for a normal day, though the sleep debt may catch up with you later",
              "easy": (bad or g["sleep_min"] < 0 or debt >= EASY_DEBT_MIN) and "Likely to tire sooner than usual",
              "rest": "Likely to feel below your best today"}[verdict]
    c = {k: v for k, v in (row["c"].get("readiness") or {}).items() if v is not None}
    low = min(c, key=lambda k: c[k], default=None)
    return _card("today", verdict, [
        _signals(sig, calm),
        _level("Overall recovery", ready, _usual(rows, d, lambda r: r["readiness_score"])),
        likely,
        _vs("rhr", row, rows, d, calm), _vs("hrv", row, rows, d, calm), _vs("sleep_min", row, rows, d, calm),
        _timing("You fell asleep", i.get("bed_start"), _usual(rows, d, lambda r: r["inputs"].get("bed_start")), calm),
        _vs("deep_min", row, rows, d, calm), _vs("rem_min", row, rows, d, calm), _vs("awake", row, rows, d, calm),
        _low_hr(row, rows, d, ctx),
        _gap("spo2", i.get("spo2"), _usual(rows, d, GET["spo2"])) < 0 and _vs("spo2", row, rows, d, calm),
        _debt(debt, calm),
        _load(y, rows, d - timedelta(1)),
        _gap("stress", GET["stress"](y), _usual(rows, d - timedelta(1), GET["stress"]))
        and "Yesterday's t" + _vs("stress", y, rows, d - timedelta(1), calm)[1:],
        *[_workout(w, tz, calm, " yesterday") for w in y["c"].get("workouts") or []],
        *[_noted(m, tz, calm, " yesterday") for m in ctx["moments"] if m["kind"] in NOTED],
        low and f"The weakest part of your recovery: {WORDS.get(low, low)}",
        (row["c"].get("confidence") or {}).get("readiness") == "low"
        and "The ring had little data last night, so read this loosely"],
        [_train(verdict, ctx["target"], calm), debt >= 30 and _bed(ctx["plan"], debt, calm), *_avoid(ctx["because"], calm)])


def _yesterday(d: date, rows: list[dict], ctx: dict) -> dict | None:
    y, calm, tz = d - timedelta(1), ctx["calm"], ctx["tz"]
    row = _on(rows, y)
    i, z, w = row["inputs"], row["c"].get("stress"), row["c"].get("wear") or {}
    up, down = (z["engaged"] + z["stressed"], z["restored"] + z["relaxed"]) if z else (0, 0)
    return _card("yesterday", None, [
        _level("Overall activity", row.get("activity_score"), _usual(rows, y, lambda r: r["activity_score"])),
        _vs("steps", row, rows, y, calm), _load(row, rows, y),
        *[_workout(x, tz, calm) for x in row["c"].get("workouts") or []],
        _vs("stress", row, rows, y, calm),
        up + down and ("Stress stayed raised for most of the day" if up > down else "Mostly relaxed or restored through the day"),
        *[_noted(m, tz, calm) for m in ctx["moments"] if m["kind"] in NOTED],
        (i.get("nap_min") or 0) >= 20 and ("You took a nap" if calm else f"Napped {_duration(i['nap_min'])}"),
        (i.get("breath_min") or 0) >= 1 and ("You did a breathing exercise" if calm
                                            else f"Breathing exercises for {_duration(i['breath_min'])}"),
        w.get("awake_hours") and w["worn_hours"] < w["awake_hours"] / 2
        and "The ring was off for much of the day, so movement may be undercounted"])


def _night(d: date, row: dict, rows: list[dict], ctx: dict) -> dict | None:
    s, i, calm = row["c"].get("session"), row["inputs"], ctx["calm"]
    if not s or i.get("sleep_min") is None:
        return None
    pm = round(s.get("phone_min") or 0)
    return _card("night", None, [
        _vs("sleep_min", row, rows, d, calm),
        _level("Overall sleep quality", row.get("sleep_score"), _usual(rows, d, lambda r: r["sleep_score"])),
        _timing("You fell asleep", i.get("bed_start"), _usual(rows, d, lambda r: r["inputs"].get("bed_start")), calm),
        _timing("You woke", i.get("bed_end"), _usual(rows, d, lambda r: r["inputs"].get("bed_end")), calm),
        _vs("deep_min", row, rows, d, calm), _vs("rem_min", row, rows, d, calm), _vs("awake", row, rows, d, calm),
        _low_hr(row, rows, d, ctx),
        _gap("spo2", i.get("spo2"), _usual(rows, d, GET["spo2"])) < 0 and _vs("spo2", row, rows, d, calm),
        _timing("Your heart rate settled for the night", i.get("wind_down"),
                _usual(rows, d, lambda r: r["inputs"].get("wind_down")), calm),
        pm >= 1 and ("Phone in use in bed" if calm else f"Phone in use in bed for {_duration(pm)}"),
        *[f"After {LOGGED[b['kind']]} {_effect(b['effect'], calm)}" for b in ctx["because"]
          if b["effect"] and b["kind"] in LOGGED]])


def _tonight(ctx: dict) -> dict | None:
    p, b, w, calm = ctx["plan"], ctx["debt"], ctx["window"], ctx["calm"]
    extra, debt = p["extra_min"] if p else (b or {}).get("suggest_min", 0), (b or {}).get("debt_min", 0)
    if not p and not b and w.get("status") != "ok":
        return None
    return _card("tonight", "earlier" if extra > 0 else "usual", [
        p and ("Aim for a full night's sleep before your wake time" if calm else
               f"Your wake time is {_clock(p['wake_min'])}, and you need about {_duration(ctx['need_min'])} of sleep"),
        extra > 0 and ("A little extra sleep tonight helps you catch up" if calm
                       else f"{_duration(extra)} extra tonight helps you catch up"),
        _debt(debt, calm),
        w.get("status") == "ok" and ("Your best nights start at your usual bedtime" if calm
                                     else f"Your best nights start between {_clock(w['start_min'])} and {_clock(w['end_min'])}")],
        [_bed(p, debt, calm), *_avoid(ctx["because"], calm)])


def _spread(rows: list[dict], d: date) -> float | None:
    beds = [r["inputs"]["bed_start"] for r in rows if d - timedelta(7) <= r["date"] < d and r["inputs"].get("bed_start") is not None]
    return pstdev(beds) if len(beds) >= 3 else None


def _spread_fact(spread: float | None, calm: bool) -> str | None:
    if spread is None:
        return None
    if calm:
        return "Bedtimes varied a lot" if spread > SPREAD_MAX_MIN else "Bedtimes were regular"
    return f"Bedtimes varied by about {_duration(spread)}" if spread >= 15 else "Bedtimes were regular"


def _week(d: date, rows: list[dict], week: dict, prev: dict, calm: bool) -> dict | None:
    if week["scored_days"] < 3:
        return None
    this = [r for r in rows if d - timedelta(7) <= r["date"] < d]
    before = [r for r in rows if r["date"] < d - timedelta(7)]

    def avg(rs: list[dict], k: str) -> float | None:
        v = [x for r in rs if (x := GET[k](r)) is not None]
        return mean(v) if len(v) >= 3 else None

    a, b = week["sleep_avg_min"], prev["sleep_avg_min"]
    sa, sb = week["steps_total"], prev["steps_total"]
    ws = [w for r in this for w in r["c"].get("workouts") or []]
    nights = []
    for word, n in (("Best", week["best_night"]), ("Worst", week["worst_night"])):
        if n and week["best_night"] != week["worst_night"]:
            t = date.fromisoformat(n["date"])
            nights.append(f"{word} night {t:%A}" + ("" if calm else f" {t.day} {t:%B}"))
    lvl = scoring.resilience([r["inputs"].get("stress_load") for r in rows], [r["readiness_score"] for r in rows])
    return _card("week", None, [
        a is not None and (b and "Slept " + _than(a, b, "the week before", "more", "less") if calm
                           else f"Slept {_duration(a)} a night on average" + (
                               f", {_duration(abs(a - b))} {'more' if a > b else 'less'} than the week before"
                               if b and abs(a - b) >= 15 else "")),
        _spread_fact(_spread(rows, d), calm),
        _cmp("rhr", avg(this, "rhr"), avg(before, "rhr"), calm, "the week before"),
        _cmp("hrv", avg(this, "hrv"), avg(before, "hrv"), calm, "the week before"),
        sa is not None and (sb and "Walked " + _than(sa, sb, "the week before", "more", "less") if calm
                            else f"{round(sa):,} steps in total" + (f", {round(sb):,} the week before" if sb else "")),
        ws and ("Worked out this week" if calm else
                f"{len(ws)} workout{'s' if len(ws) > 1 else ''}, {_duration(sum(w['minutes'] for w in ws))} in all"),
        *nights,
        lvl and f"Stress resilience over the last two weeks: {lvl}"])


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
         "alcohol": "Alcohol", "meal": "Meal", "dose": "Dose"}
WIND_DOWN_MIN = 120  # inside 2 h of the planned bedtime: wind-down lines only
MOVE_ROOM_MIN = 180  # "move" needs at least 3 h before the planned bedtime
EFFORT_MIN = 30  # a walk, ride or workout this long today is effort: go easy after it
COFFEE_MAX = 3  # this many coffees, or any coffee once past the cut-off: no more coffee
COFFEE_CUTOFF_H = 14  # scoring.derived's caffeine_late hour
STRESS_LONG_MIN = 60  # stressed this long since the last note: a walk or the breathing exercise
MOVE_SHARE = 0.4  # under this share of the step goal by the afternoon is little movement
AFTERNOON_H = 14
STILL_STEPS = 500  # under this many steps since the last note is no movement (scoring.still_hours' hour)


def timeline(session: dict | None, workouts: list[dict], moments: list[dict], doses: list[float]) -> list[dict]:
    """The day's recorded events in time order: the night's end, workouts (the phone's moves included), moments, doses."""
    ev = [{"t": w["start"], "kind": w["kind"], "label": f"{w['kind'].capitalize()} · {_duration(w['minutes'])}"}
          for w in workouts]
    ev += [{"t": m["ts"], "kind": m["kind"], "label": LABEL[m["kind"]]} for m in moments]
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
    coffee = [m["ts"] for m in day["moments"] if m["kind"] == "coffee"]
    hour, bed = datetime.fromtimestamp(now, tz).hour, ctx["bed"]
    to_bed = None if bed is None else (bed - now) / 60
    evening = to_bed is not None and to_bed <= WIND_DOWN_MIN
    steps, goal, stressed, hr, usual = day["steps_since"], ctx["step_goal"], day["stressed_min"], day["hr_since"], day["hr_usual"]
    facts = [
        *[_workout(w, tz, calm) for w in ws], *[_noted(m, tz, calm) for m in ms],
        len(coffee) >= 2 and ("Coffee more than once today" if calm else f"Coffee {len(coffee)} times today"),
        ("You moved " if steps >= STILL_STEPS else "Little movement ") + at if calm
        else f"Walked {round(steps):,} steps {at}" + ("" if steps >= STILL_STEPS else ": a still stretch"),
        not calm and f"{round(day['steps_today']):,} steps today, your goal is {round(goal):,}",
        stressed >= 30 and (f"Stressed for a while {at}" if calm else f"Stressed for {_duration(stressed)} {at}")
        + ": a demanding stretch",
        hr is not None and usual is not None and (
            f"Heart rate {at} about the same as your usual" if abs(hr - usual) < 5 else
            f"Heart rate {at} {'higher' if hr > usual else 'lower'} than your usual" if calm else
            f"Heart rate {round(hr)} bpm {at}, {round(abs(hr - usual))} bpm {'above' if hr > usual else 'below'} your usual")
        + ("" if abs(hr - usual) < 5 else ": your body has been busier than usual" if hr > usual else ": a calm stretch"),
        to_bed is not None and (("Bedtime is close" if evening else "Some hours left before bedtime") if calm else
                                f"Your planned bedtime is {datetime.fromtimestamp(bed, tz):%H:%M}" + (
                                    f", about {_duration(to_bed)} from now" if to_bed > 0 else ", already past"))]
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
            rules.append(("No more coffee today: it can push your sleep later", coffee[-1] >= since))
        if stressed >= STRESS_LONG_MIN:
            rules.append(("Take a short walk or do the breathing exercise", True))
        if not effort and hour >= AFTERNOON_H and day["steps_today"] < MOVE_SHARE * goal \
                and (to_bed is None or to_bed >= MOVE_ROOM_MIN):
            rules.append(("Fit in a brisk walk while there is time before bed", False))
    advice = [a for a, fresh in rules if fresh or a not in given]
    c = _card("update", None, facts, advice)
    new = ws or ms or steps >= STILL_STEPS or stressed >= 30
    return {"at": int(now), "nothing_new": not (new or advice), "evening": evening,
            **{k: c[k] for k in ("facts", "advice", "fallback")}}


def cards(d: date, rows: list[dict], week: dict, prev: dict, ctx: dict) -> list[dict]:
    """rows: db.days(d - 13, d); week / prev: main._week ending d - 1 and d - 8; ctx: calm, guess_pending, need_min,
    plan (notify.tonight), window (notify.bedtime_window), debt, target (cardio.target), cardio_base, moments and
    because (of d - 1), explain (notify.explain), closed (episode closed in the last 3 days or None), tz (for clock times)."""
    row = _on(rows, d)
    out = [_today(d, row, rows, ctx), _yesterday(d, rows, ctx), _night(d, row, rows, ctx), _tonight(ctx),
           _week(d, rows, week, prev, ctx["calm"]), _next_week(d, rows, week, ctx), _body(row, ctx)]
    return [c for c in out if c and c["facts"]]
