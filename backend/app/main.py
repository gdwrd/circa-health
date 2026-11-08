import logging
import os
import re
import secrets
import threading
import time
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta
from statistics import mean, median

from fastapi import APIRouter, BackgroundTasks, Body, Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import db, insights, notify, scoring, vm

log = logging.getLogger("ring")

bearer = HTTPBearer(auto_error=False)


def auth(cred: HTTPAuthorizationCredentials | None = Depends(bearer)) -> None:
    token = os.environ.get("API_TOKEN", "")
    if not token or cred is None or not secrets.compare_digest(cred.credentials, token):
        raise HTTPException(401, "unauthorized")


def _hourly(metric: str, d: date) -> dict[int, float]:
    end = vm.midnight(d + timedelta(1)) - timedelta(seconds=1)  # exclude next midnight's lookback
    return {datetime.fromtimestamp(t, vm.tz()).hour: v for t, v in vm.series(metric, vm.midnight(d), end, "1h").items()}


def _after_noon(ts: float) -> int:
    """Local minutes after noon (as notify._bedtime): no wrap at midnight."""
    t = datetime.fromtimestamp(ts, vm.tz())
    return (t.hour * 60 + t.minute - 720) % 1440


def _sessions(d: date) -> list[dict]:
    """Sessions ending on d, longest (main) first. shortcut: start is the 5 min query grid point, upgrade if timing matters."""
    lo, hi = vm.midnight(d) - timedelta(hours=12), vm.midnight(d + timedelta(1))
    ends = vm.series("biometric_sleep_end_ts", lo, hi, "5m")
    parts = {k: vm.series(f"biometric_sleep_{k}_min", lo, hi, "5m") for k in ("total", "awake", "rem", "deep")}
    for t, total in parts["total"].items():  # sessions pushed before app v1.1 have no end_ts
        ends.setdefault(t, t + (total + parts["awake"].get(t, 0.0)) * 60)
    out = []
    for end in set(ends.values()):
        if datetime.fromtimestamp(end, vm.tz()).date() != d:
            continue
        start = min(t for t, v in ends.items() if v == end)
        out.append({"start": start, "end": end, **{f"{k}_min": parts[k].get(start, 0.0) for k in parts}})
    return sorted(out, key=lambda s: -s["total_min"])


def _score_day(d: date) -> None:
    goal, need = db.setting("step_goal"), db.setting("sleep_need_min")
    prior = db.days(d - timedelta(14), d - timedelta(1))

    def val(i: dict, k: str) -> float | None:  # sleep balance counts naps
        return i["sleep_min"] + (i.get("nap_min") or 0) if k == "sleep_min" and i.get(k) is not None else i.get(k)

    base = {k: scoring.baseline([val(p["inputs"], k) for p in prior]) for k in ("rhr", "hrv", "sleep_min", "steps")}
    week = [p["inputs"] for p in prior if p["date"] >= d - timedelta(7)]
    now = datetime.now(vm.tz())
    ss = _sessions(d)
    n = ss[0] if ss else None
    naps = [{k: x[k] for k in ("start", "end", "total_min")} for x in ss[1:] if x["total_min"] >= 20]

    def awake(ts: float) -> bool:
        return ts <= now.timestamp() and not any(x["start"] <= ts < x["end"] for x in ss)

    inputs: dict = {"sleep_min": n and n["total_min"], "nap_min": sum(x["total_min"] for x in naps)}
    extra: dict = {"naps": naps, "regularity": None, "spo2": None, "hr_low_t": None}
    sleep = ready = None
    if n:
        s, e = datetime.fromtimestamp(n["start"], vm.tz()), datetime.fromtimestamp(n["end"], vm.tz())
        mid = datetime.fromtimestamp((n["start"] + n["end"]) / 2, vm.tz())
        week_n = [p["c"]["session"] for p in prior if p["date"] >= d - timedelta(6) and p["c"].get("session")] + [n]
        sd = scoring.regularity([_after_noon((x["start"] + x["end"]) / 2) for x in week_n])
        extra["regularity"] = {"sd_min": sd, "nights": len(week_n),
                               "bed_start_median": median(_after_noon(x["start"]) for x in week_n),
                               "bed_end_median": median(_after_noon(x["end"]) for x in week_n)}
        sleep = scoring.sleep_score({**n, "midpoint_h": mid.hour + mid.minute / 60, "regularity_sd": sd}, need)
        hrs = {t: v for t, v in vm.series("biometric_hr_bpm", s, e, "5m").items() if v > 0}
        hr = list(hrs.values())
        low = scoring.low_index(hr) if hr else None
        hrv = [v for v in vm.series("biometric_hrv_rmssd", s, e, "30m").values() if v > 0]
        # shortcut: 1 h step assumes periodic SpO2 is 30-60 min apart, upgrade if the ring samples faster
        sp = [v for v in vm.series("biometric_spo2_pct", s, e, "1h").values() if v > 0]
        extra["spo2"] = {"mean": mean(sp), "min": min(sp), "low_count": sum(v < 90 for v in sp), "n": len(sp)} if sp else None
        extra["hr_low_t"] = list(hrs)[low] if hr else None
        night = {"sleep_score": sleep["score"], "need_min": need,
                 "hr_p10": sorted(hr)[int(0.1 * (len(hr) - 1))] if hr else None,
                 "hrv_mean": mean(hrv) if hrv else None,
                 "recovery_frac": low / len(hr) if hr else None}
        inputs |= {"rhr": night["hr_p10"], "hrv": night["hrv_mean"], "awake": n["awake_min"],
                   "recovery": night["recovery_frac"], "spo2": min(sp) if sp else None,
                   "bed_start": _after_noon(n["start"]), "bed_end": _after_noon(n["end"])}
        y = _hourly("biometric_steps", d - timedelta(1))
        ready = scoring.readiness_score(night, base, sum(y.values()) if y else None, goal)

    end = vm.midnight(d + timedelta(1)) - timedelta(seconds=1)
    worn = {datetime.fromtimestamp(t, vm.tz()).hour for t, v in vm.series("biometric_hr_bpm", vm.midnight(d), end, "5m").items()
            if v > 0 and t <= now.timestamp()}
    awake_h = [h for h in range(24) if awake((vm.midnight(d) + timedelta(hours=h)).timestamp())]
    inputs["worn_hours"] = len(worn)
    wear = {"worn_hours": sum(h in worn for h in awake_h), "awake_hours": len(awake_h)}  # both over awake hours

    steps, cal = _hourly("biometric_steps", d), _hourly("biometric_calories", d)
    activity = None
    if steps:
        inputs |= {"steps": sum(steps.values()), "calories": sum(cal.values()) if cal else None}
        hours = [steps.get(h, 0.0) for h in awake_h if h in worn]  # ring off is not inactive
        goal_days = None
        if scoring.baseline([w.get("steps") for w in week]) is not None:
            goal_days = sum(1 for w in week if (w.get("steps") or 0) >= goal)
        activity = scoring.activity_score({"steps": inputs["steps"], "calories": inputs["calories"],
                                           "awake_hourly_steps": hours}, goal,
                                          scoring.baseline([w.get("calories") for w in week]), goal_days)
    st = [v for t, v in vm.series("biometric_stress", vm.midnight(d), end, "30m").items() if v > 0 and awake(t)]
    zones = scoring.stress_zones(st) if st else None
    inputs["stress_load"] = zones and zones["stressed_hours"]

    med = db.settings()["medication_started"]  # rhr/hrv baselines use only days on medication (new normal)
    window = {k: [p["inputs"].get(k) for p in prior if not med or k not in ("rhr", "hrv") or p["date"].isoformat() >= med]
              for k in scoring.DIRS}
    rb = {k: scoring.robust(v, scoring.FLOORS.get(k, 0.0)) for k, v in window.items()}
    sig = scoring.strain(inputs, rb)
    prev = next((p for p in prior if p["date"] == d - timedelta(1)), None)
    sig["streak"] = (((prev and prev["c"].get("signals")) or {}).get("streak", 0) + 1) if len(sig["flags"]) >= 2 else 0
    sig["nights"] = sum(1 for v in window["rhr"] if v is not None)
    med_short = bool(med) and med > (d - timedelta(14)).isoformat() and rb["rhr"] is None
    sig["reason"] = "medication" if med_short else "baseline" if sig["index"] is None else None

    scores = tuple(r and r["score"] for r in (sleep, ready, activity))
    contributors = {"sleep": sleep and sleep["contributors"], "readiness": ready and ready["contributors"],
                    "activity": activity and activity["contributors"], "inputs": inputs, "stress": zones, "session": n,
                    "signals": sig, "sleep_extra": extra, "wear": wear}
    kept = {k: v for k, v in base.items() if v is not None}
    db.save_day(d, scores, contributors, kept)
    vals = {"score_sleep": scores[0], "score_readiness": scores[1], "score_activity": scores[2],
            "stress_zone": inputs["stress_load"], **{f"baseline_{k}": v for k, v in kept.items()}}
    ts = int(vm.midnight(d).timestamp() * 1000)
    body = "".join(f"{k} {v} {ts}\n" for k, v in vals.items() if v is not None)
    if body:
        vm.forward_import(body.encode())


def rescore(dates: set[date]) -> None:
    for d in sorted(dates):
        try:
            _score_day(d)
        except Exception:
            log.exception("rescore %s failed", d)
    try:
        notify.evaluate(dates)
    except Exception:
        log.exception("notify failed")


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
    # Score history pushed before the backend existed (or before a rule change); ingest only rescores pushed dates.
    days = int(os.environ.get("BACKFILL_DAYS", "14"))
    today = datetime.now(vm.tz()).date()
    if days > 0:
        threading.Thread(target=rescore, args=({today - timedelta(i) for i in range(days)},), daemon=True).start()
    yield


app = FastAPI(lifespan=lifespan)
api = APIRouter(dependencies=[Depends(auth)])


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@api.post("/api/v1/import/prometheus")
async def ingest(request: Request, tasks: BackgroundTasks) -> Response:
    body = await request.body()
    r = vm.forward_import(body)
    if r.is_success:
        tasks.add_task(rescore, vm.dates_in_body(body))
    return Response(r.content, r.status_code)


def _today() -> date:
    return datetime.now(vm.tz()).date()


def _vitals() -> dict:
    now, out = datetime.now(vm.tz()), {}
    for k, expr in (("hr", "biometric_hr_bpm"), ("hrv", "biometric_hrv_rmssd"),
                    ("spo2", "biometric_spo2_pct"), ("battery", "ring_battery_pct")):
        try:
            s = vm.series(expr, now - timedelta(hours=24), now, "5m")
        except Exception:
            log.exception("vitals %s failed", k)
            s = {}
        out[k] = list(s.values())[-1] if s else None
    return out


@api.get("/today")
def today() -> dict:
    d = _today()
    rows = db.days(d - timedelta(13), d)
    n = sum(1 for r in rows if r["inputs"].get("sleep_min") is not None)
    row = next((r for r in rows if r["date"] == d), None) or {"c": {}}
    sig = row["c"].get("signals") or {"index": None, "level": None, "flags": {}, "streak": 0, "reason": "baseline"}
    return {
        "status": "ok" if n >= 5 else "learning", "date": d.isoformat(), "baseline_days": f"{n}/14",
        **{k: {"score": row.get(f"{k}_score"), "contributors": row["c"].get(k)} for k in ("sleep", "readiness", "activity")},
        "stress": row["c"].get("stress"),
        "resilience": scoring.resilience([r["inputs"].get("stress_load") for r in rows],
                                         [r["readiness_score"] for r in rows]),
        "vitals": _vitals(),
        "wear": row["c"].get("wear"),
        "signals": {**sig, "status": "learning" if sig["reason"] else "ok", "explain": notify.explain(d)},
    }


@api.get("/sleep")
def sleep(day: str | None = Query(None, alias="date")) -> dict:
    try:
        d = date.fromisoformat(day) if day else _today()
    except ValueError:
        raise HTTPException(422, "bad date")
    row = next(iter(db.days(d, d)), {"c": {}})
    n = row["c"].get("session")
    stages: list[dict] = []
    hr: list[dict] = []
    if n:
        s, e = (datetime.fromtimestamp(n[k], vm.tz()) for k in ("start", "end"))
        stages = [{"t": t, "stage": int(v)} for t, v in vm.series("last_over_time(biometric_sleep_stage[3h])", s, e, "5m").items() if t < n["end"]]
        hr = [{"t": t, "bpm": v} for t, v in vm.series("biometric_hr_bpm", s, e, "5m").items() if v > 0]  # as _score_day
    return {"date": d.isoformat(), "status": "ok" if n else "learning", "score": row.get("sleep_score"),
            "contributors": row["c"].get("sleep"), "session": n, "stages": stages,
            "naps": (row["c"].get("sleep_extra") or {}).get("naps", []), "hr": hr,
            "hr_low": hr[scoring.low_index([p["bpm"] for p in hr])] if hr else None}


SCORES = {"sleep": "sleep_score", "readiness": "readiness_score", "activity": "activity_score"}
INPUTS = ("rhr", "hrv", "steps", "sleep_min", "stress_load", "bed_start")


@api.get("/trends")
def trends(metric: str, days: int = 30) -> dict:
    if metric not in SCORES and metric not in INPUTS and metric != "strain":
        raise HTTPException(422, "unknown metric")
    end = _today()
    start = end - timedelta(max(days, 1) - 1)
    base = db.baselines(metric, start, end) if metric in INPUTS else {}
    pts = [{"date": r["date"].isoformat(),
            "value": r[SCORES[metric]] if metric in SCORES else
            (r["c"].get("signals") or {}).get("index") if metric == "strain" else r["inputs"].get(metric),
            "baseline": base.get(r["date"].isoformat())} for r in db.days(start, end)]
    return {"metric": metric, "days": days, "points": pts}


@api.get("/hr")
def hr(hours: int = 24) -> dict:
    now = datetime.now(vm.tz())
    s = vm.series("biometric_hr_bpm", now - timedelta(hours=min(max(hours, 1), 48)), now, "5m")
    return {"points": [{"t": t, "bpm": v} for t, v in s.items() if v > 0]}


HHMM = re.compile(r"([01]\d|2[0-3]):[0-5]\d")


def _num(v: object, lo: float, hi: float) -> bool:
    return isinstance(v, (int, float)) and not isinstance(v, bool) and lo <= v <= hi


def _bp_ok(v: object) -> bool:
    m = re.fullmatch(r"(\d{2,3})/(\d{2,3})", v) if isinstance(v, str) else None
    return bool(m) and 70 <= int(m[1]) <= 250 and 40 <= int(m[2]) <= 150


@api.get("/journal")
def list_journal(days: int = 30) -> dict:
    since = _today() - timedelta(min(max(days, 1), 365) - 1)
    return {"entries": db.journal(since.isoformat()), "kinds": list(db.KINDS)}


@api.post("/journal")
def add_journal(body: dict = Body(...)) -> dict:
    kind, value, note = body.get("kind"), body.get("value"), body.get("note")
    try:
        d = date.fromisoformat(body.get("date"))
    except (TypeError, ValueError):
        raise HTTPException(422, "bad date")
    if d > _today() or kind not in db.KINDS:
        raise HTTPException(422, "bad date or kind")
    if note is not None and (not isinstance(note, str) or len(note) > 200):
        raise HTTPException(422, "bad note")
    note = note.strip() or None if note else None
    ok = {"medication": isinstance(value, str) and HHMM.fullmatch(value), "bp": _bp_ok(value),
          "note": value is None and note}.get(kind, value is None)
    if not ok:
        raise HTTPException(422, "bad value")
    return {"status": "ok", "id": db.add_journal(d.isoformat(), kind, value, note, int(time.time()))}


def _day_hr(rows: list[dict], start: date, end: date) -> dict[date, float]:
    """Mean awake HR per day; one range query. shortcut: 30 min samples, upgrade if day_hr needs finer detail."""
    sess = [(r["c"]["session"]["start"], r["c"]["session"]["end"]) for r in rows if r["c"].get("session")]
    try:
        s = vm.series("biometric_hr_bpm", vm.midnight(start), vm.midnight(end + timedelta(1)), "30m")
    except Exception:
        log.exception("day_hr failed")
        return {}
    by: dict[date, list[float]] = {}
    for t, v in s.items():
        if v > 0 and not any(a <= t < b for a, b in sess):
            by.setdefault(datetime.fromtimestamp(t, vm.tz()).date(), []).append(v)
    return {d: mean(v) for d, v in by.items()}


@api.get("/journal/insights")
def journal_insights() -> dict:
    d = _today()
    start = d - timedelta(insights.DAYS)
    rows, entries = db.days(start, d), db.journal(start.isoformat())
    hr = _day_hr(rows, start, d) if any(e["kind"] == "medication" for e in entries) else {}
    return {"kinds": insights.kinds(rows, entries, d, hr, db.settings()["medication_started"])}


@api.get("/journal/bp")
def journal_bp(days: int = 30) -> dict:
    since = _today() - timedelta(min(max(days, 1), 365) - 1)
    return {"points": insights.bp(db.journal(since.isoformat()))}


@api.delete("/journal/{id}")
def delete_journal(id: int) -> dict:
    db.delete_journal(id)
    return {"status": "ok"}


def _settings_out() -> dict:
    s = db.settings()
    return {"step_goal": float(s["step_goal"]), "sleep_need_min": float(s["sleep_need_min"]),
            "medication_started": s["medication_started"], "medication_time": s["medication_time"],
            "medication_window_h": float(s["medication_window_h"])}


def _started_ok(v: object) -> bool:
    if v == "":
        return True
    try:
        return date.fromisoformat(v) <= _today()
    except (TypeError, ValueError):
        return False


SETTINGS = {"step_goal": lambda v: _num(v, 1000, 100000), "sleep_need_min": lambda v: _num(v, 240, 720),
            "medication_started": _started_ok, "medication_time": lambda v: isinstance(v, str) and HHMM.fullmatch(v),
            "medication_window_h": lambda v: _num(v, 1, 24)}


@api.get("/settings")
def get_settings() -> dict:
    return _settings_out()


@api.patch("/settings")
def patch_settings(body: dict = Body(...)) -> dict:
    if not body or any(k not in SETTINGS or not SETTINGS[k](v) for k, v in body.items()):
        raise HTTPException(422, "bad settings")
    db.set_settings({k: str(v) for k, v in body.items()})
    return _settings_out()


@api.get("/notifications/pending")
def pending() -> list[dict]:
    return notify.pending()


@api.post("/notifications/{id}/ack")
def ack(id: int) -> dict:
    notify.ack(id)
    return {"status": "ok"}


app.include_router(api)
