import json
import logging
from contextlib import closing
from datetime import date, datetime, timedelta
from statistics import mean, median

from . import cardio, db, insights, samples, scoring

log = logging.getLogger("ring")

WORDS = {"resting_hr": "resting heart rate", "hrv_balance": "heart rate variability", "sleep": "last night's sleep",
         "sleep_balance": "sleep balance", "previous_day_activity": "yesterday's activity",
         "activity_balance": "recent activity balance", "recovery_index": "overnight recovery"}


NOUNS = {"rhr": ("Resting HR", " bpm"), "hrv": ("HRV", " %"), "awake": ("awake time", " min"),
         "spo2": ("blood oxygen", " %"), "temp": ("temperature", " °C")}
LOGGED = {"alcohol": "alcohol", "workout": "a workout", "sick": "feeling unwell", "travel": "travel",
          "stress": "stress", "late_meal": "a late meal"}


def _now() -> datetime:
    return datetime.now(samples.tz())


def _add(kind: str, title: str, body: str, now: datetime, extra: dict[str, str] | None = None) -> None:
    with closing(db.connect()) as con, con:
        con.execute("INSERT INTO notifications(kind, title, body, created_at, extra) VALUES(?,?,?,?,?)",
                    (kind, title, body, now.isoformat(), json.dumps(extra) if extra else None))


def _fired(kind: str, now: datetime, since: datetime | None = None) -> bool:
    """Dedup: a kind fires at most once per local day (created_at starts with the local date), or once since `since`."""
    if since:
        return bool(db.query("SELECT 1 FROM notifications WHERE kind=? AND created_at>=?", (kind, since.isoformat())))
    return bool(db.query("SELECT 1 FROM notifications WHERE kind=? AND substr(created_at,1,10)=?",
                         (kind, now.date().isoformat())))


def cardio_base(d: date) -> tuple[float | None, int]:
    """14-day mean cardio load (None under 5 days) and how many of the 14 prior days have one."""
    v = [r["inputs"].get("cardio_load") for r in db.days(d - timedelta(14), d - timedelta(1))]
    return scoring.baseline(v), sum(x is not None for x in v)


def _word(readiness: int) -> str:
    return ("Take it easy", "Steady", "Ready")[scoring.band(readiness)]


def _readiness(now: datetime, dates: set[date]) -> None:
    d = now.date()
    if d not in dates or _fired("readiness", now):
        return
    row = next(iter(db.days(d, d)), None)
    if not row or row["readiness_score"] is None:
        return
    c = {k: v for k, v in (row["c"].get("readiness") or {}).items() if v is not None}
    low = min(c, key=lambda k: c[k], default=None)
    if db.settings()["guess"]:  # guess first: nothing that gives the score away
        _add("readiness", "Your scores are in", "Guess first, then see them.", now, {"open": "today"})
        return
    if db.settings()["calm"]:  # calm mode: words, no numbers
        body = f"Lowest factor: {WORDS.get(low, low)}." if low else "Based on last night's sleep."
        _add("readiness", _word(row["readiness_score"]), body, now)
        return
    body = f"Lowest factor: {WORDS.get(low, low)} ({round(c[low])})." if low else "Based on last night's sleep."
    t = cardio.target(row["readiness_score"], cardio_base(d)[0]) if db.settings()["birth_year"] else None
    if t:
        body += f" Cardio target {t['lo']}–{t['hi']}."
    _add("readiness", f"Readiness {row['readiness_score']}", body, now)


def _after_noon(ts: float) -> int:
    t = datetime.fromtimestamp(ts, samples.tz())
    return (t.hour * 60 + t.minute - 720) % 1440  # minutes after noon: no wrap at midnight


QUALITY = ("total", "efficiency", "restfulness", "rem", "deep")  # timing/regularity left out on purpose


def bedtime_window(d: date) -> dict:
    """scoring.bedtime_window over the last 60 days' scored nights (shared by /sleep, /today and _bedtime)."""
    nights = [(_after_noon(r["c"]["session"]["start"]), mean(r["c"]["sleep"][k] for k in QUALITY))
              for r in db.days(d - timedelta(59), d) if r["c"].get("session") and r["c"].get("sleep")]
    return scoring.bedtime_window(nights)


def debt(end: date) -> dict | None:
    """Sleep debt over the 7 nights ending at end (sleep + naps, as sleep balance)."""
    ins = [r["inputs"] for r in db.days(end - timedelta(6), end)]
    return scoring.sleep_debt([None if i.get("sleep_min") is None else i["sleep_min"] + (i.get("nap_min") or 0)
                               for i in ins], db.setting("sleep_need_min"))


def tonight(d: date) -> dict | None:
    """Plan for the night after evening d from the usual wake time (weekend = nights before Sat/Sun); None when unset."""
    s = db.settings()
    wake = s["wake_weekend" if (d + timedelta(1)).weekday() >= 5 else "wake_weekday"]
    if not wake:
        return None
    h, m = map(int, wake.split(":"))
    wake_min = (h * 60 + m - 720) % 1440
    b = debt(d)
    p = scoring.tonight(wake_min, float(s["sleep_need_min"]), b["suggest_min"] if b else 0)
    w = bedtime_window(d)
    t = p["in_bed_by_min"]
    p["wake_min"] = wake_min
    p["window"] = None if w["status"] != "ok" else "earlier" if t < w["start_min"] else "later" if t >= w["end_min"] else "in"
    return p


def _bedtime(now: datetime, dates: set[date]) -> None:
    d = now.date()
    noon = samples.midnight(d) + timedelta(hours=12)
    if now < noon:
        noon -= timedelta(1)
    p = tonight(noon.date())
    if p:
        target = noon + timedelta(minutes=p["in_bed_by_min"])
        at = lambda k: f"{noon + timedelta(minutes=p[k]):%H:%M}"
        if target - timedelta(minutes=30) <= now < target + timedelta(minutes=15):
            if not _fired("bedtime_soon", now, noon):  # per evening: windows can cross midnight
                _add("bedtime_soon", "Time for bed", f"Lights out by {at('asleep_by_min')} — {_duration(p['sleep_min'])} "
                     f"before your {at('wake_min')} alarm.", now)
        elif target - timedelta(minutes=60) <= now < target and not _fired("bedtime", now, noon):
            extra = f" +{round(p['extra_min'])} min tonight to catch up." if p["extra_min"] > 0 else ""
            _add("bedtime", "Wind down", f"In bed by {at('in_bed_by_min')} to be rested for {at('wake_min')}.{extra}", now)
        return
    if _fired("bedtime", now):
        return
    w = bedtime_window(d)
    if w["status"] == "ok":
        target = noon + timedelta(minutes=w["start_min"])
        title, body = "Wind down", f"Your best nights start around {target:%H:%M}."
    else:
        starts = [r["c"]["session"]["start"] for r in db.days(d - timedelta(13), d) if r["c"].get("session")]
        if len(starts) < 5:
            return
        target = noon + timedelta(minutes=median(_after_noon(s) for s in starts))
        title, body = "Bedtime soon", f"Your usual bedtime is {target:%H:%M}."
    if target - timedelta(minutes=30) <= now < target:
        _add("bedtime", title, body, now)


def _stillup(now: datetime, dates: set[date]) -> None:
    """In bed by + 20 min until 03:00, phone screen on within 20 min, no session yet; once per evening."""
    noon = samples.midnight(now.date()) + timedelta(hours=12)
    if now < noon:
        noon -= timedelta(1)
    p = tonight(noon.date())
    if not p or not noon + timedelta(minutes=p["in_bed_by_min"] + 20) <= now < noon + timedelta(hours=15):
        return
    scr = list(samples.raw("biometric_screen_on", now - timedelta(minutes=20), now).values())
    if not scr or scr[-1] != 1.0 or _fired("stillup", now, noon):
        return
    if any(r["c"].get("session") and r["c"]["session"]["start"] >= noon.timestamp()
           for r in db.days(noon.date(), noon.date() + timedelta(1))):
        return
    start = noon.date() - timedelta(90)
    ins = insights.kinds(db.days(start, noon.date()), db.journal(start.isoformat()), noon.date(), {},
                         db.settings()["medication_started"], db.breaths(start, noon.date()))["phone_in_bed"]
    later = next((e["delta"] for e in ins.get("all", []) if e["metric"] == "bed_start"), 0)
    body = (f"Nights with 20+ min of phone in bed: asleep {round(later)} min later." if round(later) > 0
            else "Put the phone down for a better night.")
    _add("stillup", "Still up", body, now)


def _inactivity(now: datetime, dates: set[date]) -> None:
    # shortcut: steps are hourly, so this fires up to 1 h late (accepted in PLAN)
    end = now.replace(minute=0, second=0, microsecond=0)
    start = end - timedelta(hours=2)
    if not 8 <= start.hour <= 20 or _fired("inactivity", now):
        return
    for r in db.days(now.date() - timedelta(1), now.date()):
        s = r["c"].get("session")
        if s and s["start"] < end.timestamp() and s["end"] > start.timestamp():
            return
    steps = samples.grid("biometric_steps", start, end - timedelta(seconds=1), 3600, 3540)
    if sum(steps.values()):  # the ring sends no row for an hour without steps
        return
    if any(v > 0 for v in samples.raw("biometric_hr_bpm", start, end).values()):  # hr 0 = not worn
        _add("inactivity", "Time to move", "No steps in the last 2 hours.", now)


def _scored(start: date, end: date) -> list[dict]:
    return [r for r in db.days(start, end) if any(r[k] is not None for k in ("sleep_score", "readiness_score", "activity_score"))]


def _avg(rows: list[dict]) -> str:
    parts = []
    for name, k in (("Sleep", "sleep_score"), ("Readiness", "readiness_score"), ("Activity", "activity_score")):
        v = [r[k] for r in rows if r[k] is not None]
        if v:
            parts.append(f"{name} {round(sum(v) / len(v))}")
    return " · ".join(parts) + " avg."


def _weekly(now: datetime, dates: set[date]) -> None:
    """Monday 08-12 local: averages of the previous 7 days, once (no weekly row in the last 6 days)."""
    if now.weekday() or not 8 <= now.hour < 12:
        return
    last = db.query("SELECT created_at FROM notifications WHERE kind='weekly' ORDER BY id DESC LIMIT 1")
    if last and now - datetime.fromisoformat(last[0]["created_at"]) < timedelta(days=6):
        return
    d = now.date()
    rows = _scored(d - timedelta(7), d - timedelta(1))
    if len(rows) < 3:
        return
    body = _avg(rows)
    sleeps = [r for r in rows if r["sleep_score"] is not None]
    if sleeps:
        body += f" Best night: {max(sleeps, key=lambda r: r['sleep_score'])['date']:%a}."
    _add("weekly", "Your week", body, now, {"open": "trends"})


def _monthly(now: datetime, dates: set[date]) -> None:
    """Day 1, 08-12 local: averages of the previous month, once (no monthly row in the last 20 days), >= 10 scored days."""
    if now.day != 1 or not 8 <= now.hour < 12:
        return
    last = db.query("SELECT created_at FROM notifications WHERE kind='monthly' ORDER BY id DESC LIMIT 1")
    if last and now - datetime.fromisoformat(last[0]["created_at"]) < timedelta(days=20):
        return
    end = now.date() - timedelta(1)
    rows = _scored(end.replace(day=1), end)
    if len(rows) < 10:
        return
    n = sum(len(r["c"].get("workouts") or []) for r in db.days(end.replace(day=1), end))
    body = _avg(rows) + (f" {n} workout{'s' if n > 1 else ''}." if n else "")
    _add("monthly", f"Your {end:%B}", body, now, {"open": "month"})


def _checkin(now: datetime, dates: set[date]) -> None:
    """08-11 local, once per day, only while yesterday has no manual journal row; opens the check-in sheet on yesterday."""
    y = (now.date() - timedelta(1)).isoformat()
    if not 8 <= now.hour < 11 or _fired("checkin", now) or db.query(
            "SELECT 1 FROM journal WHERE date=? AND kind != 'phone_in_bed' AND note IS NOT 'auto'", (y,)):  # _score_day's rows
        return
    _add("checkin", "How was yesterday?", "Takes ten seconds: alcohol, caffeine, workout, medication.", now,
         {"open": "journal", "date": y})


def _duration(minutes: float) -> str:
    h, m = divmod(round(minutes), 60)
    return " ".join(p for p in (f"{h} h" if h else "", f"{m} min" if m or not h else "") if p)


def _morning(now: datetime, dates: set[date]) -> None:
    """Once per day, 30 min after today's night ended, 05:00-14:00 local; tap opens the Sleep tab."""
    row = next(iter(db.days(now.date(), now.date())), None)
    n = row and row["c"].get("session")
    asleep = row and row["inputs"].get("sleep_min")
    # before 05:00 the "night" is a stub the ring published mid-sleep (a stir closes a segment)
    if not n or asleep is None or now.timestamp() < n["end"] + 1800 or not 5 <= now.hour < 14 or _fired("morning", now):
        return  # no sleep_min: night not scored yet, the next sync after the rescore fires it
    score = row["sleep_score"]
    body = f"{_duration(asleep)} asleep"
    if db.settings()["guess"]:  # guess first: no score, no band word
        _add("morning", "Last night", body, now, {"open": "today"})
        return
    if db.settings()["calm"]:  # calm mode: the readiness word, no scores
        ready = row["readiness_score"]
        _add("morning", "Last night" if ready is None else _word(ready), body, now, {"open": "sleep"})
        return
    if row["readiness_score"] is not None:
        body += f" · Readiness {row['readiness_score']}"
    _add("morning", "Last night" + ("" if score is None else f" · Sleep {score}"), body, now, {"open": "sleep"})


def explain(d: date) -> list[str]:
    """Journal kinds logged the day before d that commonly raise the strain inputs."""
    y = (d - timedelta(1)).isoformat()
    kinds = {r["kind"] for r in db.journal(y) if r["date"] == y}
    return [k for k in scoring.EXPLAIN if k in kinds]


def _join(parts: list[str]) -> str:
    return parts[0] if len(parts) == 1 else ", ".join(parts[:-1]) + " and " + parts[-1]


def _strain(now: datetime, dates: set[date]) -> None:
    """Level above calm for 2+ nights, at most once per 3 days. Copy says "may", never names an illness."""
    d = now.date()
    row = next(iter(db.days(d, d)), None)
    sig = row and row["c"].get("signals")
    if not sig or sig["level"] in (None, "calm") or sig["streak"] < 2:
        return
    last = db.query("SELECT created_at FROM notifications WHERE kind='strain' ORDER BY id DESC LIMIT 1")
    if last and now - datetime.fromisoformat(last[0]["created_at"]) < timedelta(days=3):
        return
    parts = []
    for k in sig["flags"]:
        if k == "recovery":
            parts.append("overnight recovery later")
            continue
        delta = 100 * sig["deltas"][k] / sig["usual"][k] if k == "hrv" else sig["deltas"][k]
        name, unit = NOUNS[k]
        parts.append(f"{name} {round(delta):+d}{unit}".replace("-", "−"))
    body = f"{_join(parts)} vs your usual for {sig['streak']} nights. Rest and hydrate; see a doctor if you feel unwell."
    logged = explain(d)
    if logged:
        body += f" You logged {_join([LOGGED[k] for k in logged])} yesterday, which can cause this."
    start = d - timedelta(insights.DAYS)  # same call as /journal/insights, so the number matches the app
    lead = insights.kinds(db.days(start, d), db.journal(start.isoformat()), d, {}, db.settings()["medication_started"],
                          db.breaths(start, d))["sick"]["lead"]
    if lead and (n := round(lead["days"])):
        body += f" Last times this came about {n} day{'s' if n > 1 else ''} before you logged feeling sick."
    _add("strain", "Your body may be under strain", body, now)


EXPERIMENT = {"alcohol": ("alcohol", "no alcohol"), "caffeine_late": ("late coffee", "no late coffee"),  # (on, off)
              "late_meal": ("a late meal", "no late meal"), "workout": ("a workout", "no workout"),
              "breathe": ("a breathing session", "no breathing session"),
              "phone_in_bed": ("phone in bed", "no phone in bed")}


def _experiment(now: datetime, dates: set[date]) -> None:
    """07-12 local, once per day: today's assignment while running; "done" on the day after the last night."""
    e = next((r for r in db.experiments() if r["ended"] is None), None)
    if not e or not 7 <= now.hour < 12 or _fired("experiment", now):
        return
    i = (now.date() - date.fromisoformat(e["start"])).days
    if i == e["days"]:
        _add("experiment", "Your experiment is done", "Open Today to see what it showed.", now, {"open": "today"})
    elif 0 <= i < e["days"]:
        _add("experiment", f"Experiment day {i + 1} of {e['days']}",
             f"Today: {EXPERIMENT[e['kind']][e['plan'][i] == '0']}.", now)


GUESS = {"walk": "a walk", "ride": "a ride", "workout": "a workout"}


def _workout(now: datetime, dates: set[date]) -> None:
    """One question per unlabelled workout of today or yesterday that ended 10+ min ago (a later sync may still extend
    it). The app's buttons confirm the guess or dismiss it through PATCH /workouts; a tap opens the day to change it."""
    d = now.date()
    asked = [int(json.loads(r["extra"])["start"]) for r in db.query(
        "SELECT extra FROM notifications WHERE kind='workout' AND created_at>=?", ((now - timedelta(days=2)).isoformat(),))]
    for row in db.days(d - timedelta(1), d):
        for w in row["c"].get("workouts") or []:
            if w["confirmed"] or w["end"] > now.timestamp() - 600 or any(w["start"] - 600 <= s <= w["end"] for s in asked):
                continue
            a, b = (f"{datetime.fromtimestamp(t, samples.tz()):%H:%M}" for t in (w["start"], w["end"]))
            _add("workout", "Was this a workout?", f"{'' if row['date'] == d else 'Yesterday '}{a}–{b}, {w['minutes']} min. "
                 f"Looks like {GUESS[w['kind']]}. Tap to change it.", now,
                 {"open": "workout", "date": row["date"].isoformat(), "start": str(w["start"]), "kind": w["kind"]})


def episodes(d: date) -> tuple[list[dict], dict[date, dict]]:
    """insights.episodes over the 90 days ending at d, plus the signals per wake date they were built from."""
    start = d - timedelta(insights.DAYS)
    logged: dict[date, set[str]] = {}
    for e in db.journal(start.isoformat()):
        if e["kind"] in scoring.EXPLAIN:
            logged.setdefault(date.fromisoformat(e["date"]), set()).add(e["kind"])
    sig = {r["date"]: r["c"]["signals"] for r in db.days(start, d) if r["c"].get("signals")}
    return insights.episodes(logged, sig, d), sig


def _nights(n: float) -> str:
    return f"{n:g} night{'' if n == 1 else 's'}"


def _receipt(now: datetime, dates: set[date]) -> None:
    """Once on the day an episode closes with 1 night or more; with guess on, only after today's guess."""
    d = now.date()
    index = {r["date"]: (r["c"].get("signals") or {}).get("index") for r in db.days(d - timedelta(1), d)}
    if index.get(d) is None or index[d] >= 25 or (index.get(d - timedelta(1)) or 0) < 25 or _fired("receipt", now):
        return
    if db.settings()["guess"] and d.isoformat() not in db.guesses(d, d):
        return
    eps = episodes(d)[0]
    e = insights.closed_today(eps, d)
    if not e:
        return
    a, b = e["first"], e["last"]
    when = f" on {b:%A}" if a == b else f", {a.day}{'' if a.month == b.month else f' {a:%b}'} to {b.day} {b:%b}"
    body = f"After {_join([LOGGED[k] for k in e['kinds']])}{when}: {_nights(e['nights'])}."
    price = insights.prices(eps).get(e["key"])
    if price and price["status"] == "ok":
        body += f" Your usual: {_nights(price['nights'])}."
    _add("receipt", "Back in your usual range", body, now, {"open": "today"})


KINDS = ("readiness", "bedtime", "bedtime_soon", "inactivity", "strain", "checkin", "weekly", "morning", "monthly", "stillup",
         "experiment", "workout", "receipt")


def evaluate(dates: set[date]) -> None:
    now = _now()
    muted = set(db.settings()["muted"].split(","))
    for rule, kind in ((_readiness, "readiness"), (_bedtime, "bedtime"), (_inactivity, "inactivity"),
                       (_weekly, "weekly"), (_strain, "strain"), (_checkin, "checkin"), (_morning, "morning"),
                       (_monthly, "monthly"), (_stillup, "stillup"), (_experiment, "experiment"),
                       (_workout, "workout"), (_receipt, "receipt")):
        if kind in muted:
            continue
        try:
            rule(now, dates)
        except Exception:
            log.exception("notify %s failed", rule.__name__)


def pending() -> list[dict]:
    rows = db.query("SELECT id, kind, title, body, created_at, extra FROM notifications WHERE acked_at IS NULL "
                    "ORDER BY id")
    return [dict(r) | {"extra": json.loads(r["extra"]) if r["extra"] else None} for r in rows]


def ack(id: int) -> None:
    with closing(db.connect()) as con, con:
        con.execute("UPDATE notifications SET acked_at=? WHERE id=? AND acked_at IS NULL", (_now().isoformat(), id))
