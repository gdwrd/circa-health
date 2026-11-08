import logging
from contextlib import closing
from datetime import date, datetime, timedelta
from statistics import median

from . import db, vm

log = logging.getLogger("ring")

WORDS = {"resting_hr": "resting heart rate", "hrv_balance": "heart rate variability", "sleep": "last night's sleep",
         "sleep_balance": "sleep balance", "previous_day_activity": "yesterday's activity",
         "activity_balance": "recent activity balance", "recovery_index": "overnight recovery"}


def _now() -> datetime:
    return datetime.now(vm.tz())


def _add(kind: str, title: str, body: str, now: datetime) -> None:
    with closing(db.connect()) as con, con:
        con.execute("INSERT INTO notifications(kind, title, body, created_at) VALUES(?,?,?,?)",
                    (kind, title, body, now.isoformat()))


def _fired(kind: str, now: datetime) -> bool:
    """Dedup: a kind fires at most once per local day (created_at starts with the local date)."""
    return bool(db.query("SELECT 1 FROM notifications WHERE kind=? AND substr(created_at,1,10)=?",
                         (kind, now.date().isoformat())))


def _readiness(now: datetime, dates: set[date]) -> None:
    d = now.date()
    if d not in dates or _fired("readiness", now):
        return
    row = next(iter(db.days(d, d)), None)
    if not row or row["readiness_score"] is None:
        return
    c = {k: v for k, v in (row["c"].get("readiness") or {}).items() if v is not None}
    low = min(c, key=lambda k: c[k], default=None)
    body = f"Lowest factor: {WORDS.get(low, low)} ({round(c[low])})." if low else "Based on last night's sleep."
    _add("readiness", f"Readiness {row['readiness_score']}", body, now)


def _bedtime(now: datetime, dates: set[date]) -> None:
    d = now.date()
    starts = [datetime.fromtimestamp(r["c"]["session"]["start"], vm.tz())
              for r in db.days(d - timedelta(13), d) if r["c"].get("session")]
    if len(starts) < 5 or _fired("bedtime", now):
        return
    med = median((s.hour * 60 + s.minute - 720) % 1440 for s in starts)  # minutes after noon: no wrap at midnight
    noon = vm.midnight(d) + timedelta(hours=12)
    if now < noon:
        noon -= timedelta(1)
    target = noon + timedelta(minutes=med)
    if target - timedelta(minutes=30) <= now < target:
        _add("bedtime", "Bedtime soon", f"Your usual bedtime is {target:%H:%M}.", now)


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
    if sum(vm.series("biometric_steps", start, end - timedelta(seconds=1), "1h").values()) == 0:
        _add("inactivity", "Time to move", "No steps in the last 2 hours.", now)


def _battery(now: datetime, dates: set[date]) -> None:
    """Re-fires only if the last battery notification was acked and the level rose above 15 since."""
    last = db.query("SELECT created_at, acked_at FROM notifications WHERE kind='battery' ORDER BY id DESC LIMIT 1")
    since = datetime.fromisoformat(last[0]["created_at"]) if last else now - timedelta(hours=24)
    s = vm.series("ring_battery_pct", since, now, "5m")
    if not s or s[max(s)] > 15:
        return
    if last and not (last[0]["acked_at"] and max(s.values()) > 15):
        return
    chg = vm.series("ring_charging", now - timedelta(hours=1), now, "5m")
    if chg and chg[max(chg)] == 1:
        return
    _add("battery", f"Ring battery {round(s[max(s)])}%", "Charge the ring soon.", now)


def _weekly(now: datetime, dates: set[date]) -> None:
    """Monday 08-12 local: averages of the previous 7 days, once (no weekly row in the last 6 days)."""
    if now.weekday() or not 8 <= now.hour < 12:
        return
    last = db.query("SELECT created_at FROM notifications WHERE kind='weekly' ORDER BY id DESC LIMIT 1")
    if last and now - datetime.fromisoformat(last[0]["created_at"]) < timedelta(days=6):
        return
    d = now.date()
    rows = [r for r in db.days(d - timedelta(7), d - timedelta(1))
            if any(r[k] is not None for k in ("sleep_score", "readiness_score", "activity_score"))]
    if len(rows) < 3:
        return
    parts = []
    for name, k in (("Sleep", "sleep_score"), ("Readiness", "readiness_score"), ("Activity", "activity_score")):
        v = [r[k] for r in rows if r[k] is not None]
        if v:
            parts.append(f"{name} {round(sum(v) / len(v))}")
    body = " · ".join(parts) + " avg."
    sleeps = [r for r in rows if r["sleep_score"] is not None]
    if sleeps:
        body += f" Best night: {max(sleeps, key=lambda r: r['sleep_score'])['date']:%a}."
    _add("weekly", "Your week", body, now)


def evaluate(dates: set[date]) -> None:
    now = _now()
    for rule in (_readiness, _bedtime, _inactivity, _battery, _weekly):
        try:
            rule(now, dates)
        except Exception:
            log.exception("notify %s failed", rule.__name__)


def pending() -> list[dict]:
    rows = db.query("SELECT id, kind, title, body, created_at FROM notifications WHERE acked_at IS NULL ORDER BY id")
    return [dict(r) for r in rows]


def ack(id: int) -> None:
    with closing(db.connect()) as con, con:
        con.execute("UPDATE notifications SET acked_at=? WHERE id=? AND acked_at IS NULL", (_now().isoformat(), id))
