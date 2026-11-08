import logging
import os
import secrets
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta
from statistics import mean

from fastapi import APIRouter, BackgroundTasks, Depends, FastAPI, HTTPException, Query, Request, Response
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from . import db, notify, scoring, vm

log = logging.getLogger("ring")

bearer = HTTPBearer(auto_error=False)


def auth(cred: HTTPAuthorizationCredentials | None = Depends(bearer)) -> None:
    token = os.environ.get("API_TOKEN", "")
    if not token or cred is None or not secrets.compare_digest(cred.credentials, token):
        raise HTTPException(401, "unauthorized")


def _hourly(metric: str, d: date) -> dict[int, float]:
    end = vm.midnight(d + timedelta(1)) - timedelta(seconds=1)  # exclude next midnight's lookback
    return {datetime.fromtimestamp(t, vm.tz()).hour: v for t, v in vm.series(metric, vm.midnight(d), end, "1h").items()}


def _night(d: date) -> dict | None:
    """Longest session ending on d. shortcut: start is the 5 min query grid point, upgrade if timing matters."""
    lo, hi = vm.midnight(d) - timedelta(hours=12), vm.midnight(d + timedelta(1))
    ends = vm.series("biometric_sleep_end_ts", lo, hi, "5m")
    parts = {k: vm.series(f"biometric_sleep_{k}_min", lo, hi, "5m") for k in ("total", "awake", "rem", "deep")}
    best = None
    for end in set(ends.values()):
        if datetime.fromtimestamp(end, vm.tz()).date() != d:
            continue
        start = min(t for t, v in ends.items() if v == end)
        s = {"start": start, "end": end, **{f"{k}_min": parts[k].get(start, 0.0) for k in parts}}
        if best is None or s["total_min"] > best["total_min"]:
            best = s
    return best


def _score_day(d: date) -> None:
    goal, need = db.setting("step_goal"), db.setting("sleep_need_min")
    prior = db.days(d - timedelta(14), d - timedelta(1))
    base = {k: scoring.baseline([p["inputs"].get(k) for p in prior]) for k in ("rhr", "hrv", "sleep_min", "steps")}
    week = [p["inputs"] for p in prior if p["date"] >= d - timedelta(7)]
    now = datetime.now(vm.tz())
    n = _night(d)

    def awake(ts: float) -> bool:
        return ts <= now.timestamp() and not (n and n["start"] <= ts < n["end"])

    inputs: dict = {"sleep_min": n and n["total_min"]}
    sleep = ready = None
    if n:
        s, e = datetime.fromtimestamp(n["start"], vm.tz()), datetime.fromtimestamp(n["end"], vm.tz())
        mid = datetime.fromtimestamp((n["start"] + n["end"]) / 2, vm.tz())
        sleep = scoring.sleep_score({**n, "midpoint_h": mid.hour + mid.minute / 60}, need)
        hr = [v for v in vm.series("biometric_hr_bpm", s, e, "5m").values() if v > 0]
        hrv = [v for v in vm.series("biometric_hrv_rmssd", s, e, "30m").values() if v > 0]
        night = {"sleep_score": sleep["score"], "need_min": need,
                 "hr_p10": sorted(hr)[int(0.1 * (len(hr) - 1))] if hr else None,
                 "hrv_mean": mean(hrv) if hrv else None,
                 "recovery_frac": next(i for i, v in enumerate(hr) if v <= min(hr) * 1.05) / len(hr) if hr else None}
        inputs |= {"rhr": night["hr_p10"], "hrv": night["hrv_mean"]}
        y = _hourly("biometric_steps", d - timedelta(1))
        ready = scoring.readiness_score(night, base, sum(y.values()) if y else None, goal)

    steps, cal = _hourly("biometric_steps", d), _hourly("biometric_calories", d)
    activity = None
    if steps:
        inputs |= {"steps": sum(steps.values()), "calories": sum(cal.values()) if cal else None}
        hours = [steps.get(h, 0.0) for h in range(24) if awake((vm.midnight(d) + timedelta(hours=h)).timestamp())]
        goal_days = None
        if scoring.baseline([w.get("steps") for w in week]) is not None:
            goal_days = sum(1 for w in week if (w.get("steps") or 0) >= goal)
        activity = scoring.activity_score({"steps": inputs["steps"], "calories": inputs["calories"],
                                           "awake_hourly_steps": hours}, goal,
                                          scoring.baseline([w.get("calories") for w in week]), goal_days)
    end = vm.midnight(d + timedelta(1)) - timedelta(seconds=1)
    st = [v for t, v in vm.series("biometric_stress", vm.midnight(d), end, "30m").items() if v > 0 and awake(t)]
    zones = scoring.stress_zones(st) if st else None
    inputs["stress_load"] = zones and zones["stressed_hours"]

    scores = tuple(r and r["score"] for r in (sleep, ready, activity))
    contributors = {"sleep": sleep and sleep["contributors"], "readiness": ready and ready["contributors"],
                    "activity": activity and activity["contributors"], "inputs": inputs, "stress": zones, "session": n}
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


@asynccontextmanager
async def lifespan(_: FastAPI):
    db.init()
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
    return {
        "status": "ok" if n >= 5 else "learning", "date": d.isoformat(), "baseline_days": f"{n}/14",
        **{k: {"score": row.get(f"{k}_score"), "contributors": row["c"].get(k)} for k in ("sleep", "readiness", "activity")},
        "stress": row["c"].get("stress"),
        "resilience": scoring.resilience([r["inputs"].get("stress_load") for r in rows],
                                         [r["readiness_score"] for r in rows]),
        "vitals": _vitals(),
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
    if n:
        s, e = (datetime.fromtimestamp(n[k], vm.tz()) for k in ("start", "end"))
        stages = [{"t": t, "stage": int(v)} for t, v in vm.series("biometric_sleep_stage", s, e, "5m").items()]
    return {"date": d.isoformat(), "status": "ok" if n else "learning", "score": row.get("sleep_score"),
            "contributors": row["c"].get("sleep"), "session": n, "stages": stages}


SCORES = {"sleep": "sleep_score", "readiness": "readiness_score", "activity": "activity_score"}
INPUTS = ("rhr", "hrv", "steps", "sleep_min")


@api.get("/trends")
def trends(metric: str, days: int = 30) -> dict:
    if metric not in SCORES and metric not in INPUTS:
        raise HTTPException(422, "unknown metric")
    end = _today()
    start = end - timedelta(max(days, 1) - 1)
    base = db.baselines(metric, start, end) if metric in INPUTS else {}
    pts = [{"date": r["date"].isoformat(),
            "value": r[SCORES[metric]] if metric in SCORES else r["inputs"].get(metric),
            "baseline": base.get(r["date"].isoformat())} for r in db.days(start, end)]
    return {"metric": metric, "days": days, "points": pts}


@api.get("/notifications/pending")
def pending() -> list[dict]:
    return notify.pending()


@api.post("/notifications/{id}/ack")
def ack(id: int) -> dict:
    notify.ack(id)
    return {"status": "ok"}


app.include_router(api)
