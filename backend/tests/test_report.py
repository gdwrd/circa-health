import re
from datetime import date, datetime, timedelta, timezone

from fastapi.testclient import TestClient

from app import db, report
from app.main import app

H = {"Authorization": "Bearer secret"}
MON = date(2026, 11, 16)
CALM_SIG = {"index": 10, "level": "calm", "flags": {}, "deltas": {}, "usual": {}, "streak": 0}
STRAIN_SIG = {"index": 40, "level": "elevated", "flags": {"rhr": 2.1, "hrv": 1.8}, "deltas": {"rhr": 6.0, "hrv": -12.0},
              "usual": {"rhr": 52.0, "hrv": 48.0}, "streak": 3}


def _ts(d: date, h: int, m: int) -> float:
    return datetime(d.year, d.month, d.day, h, m, tzinfo=timezone.utc).timestamp()


def _row(d: date, ready: int | None = 75, sleep: int = 80, act: int = 70, asleep: float = 450.0, sig: dict = CALM_SIG,
         **inputs: float) -> dict:
    return {"date": d, "sleep_score": sleep, "readiness_score": ready, "activity_score": act,
            "c": {"readiness": {"resting_hr": 60, "sleep": 80}, "signals": sig,
                  "session": {"start": 0, "end": 1, "deep_min": 80.0, "rem_min": 95.0},
                  "workouts": [{"kind": "walk", "minutes": 35, "start": _ts(d, 17, 10), "avg_bpm": 104}],
                  "stress": {"restored": 10, "relaxed": 12, "engaged": 4, "stressed": 2, "stressed_hours": 1.0}},
            "inputs": {"sleep_min": asleep, "steps": 8000.0, "bed_start": 660.0, "bed_end": 1150.0, "cardio_load": 20.0,
                       "rhr": 55.0, "hrv": 45.0, "awake": 20.0, "recovery": 0.5, "stress_load": 1.0} | inputs}


def _rows(d: date = MON, **today: object) -> list[dict]:
    return [_row(d - timedelta(i)) for i in range(13, 0, -1)] + [_row(d, **today)]


def _week(scored: int = 7, asleep: float = 450.0) -> dict:
    return {"scored_days": scored, "means": {"sleep": 80.0, "readiness": 75.0, "activity": 70.0},
            "best_night": {"date": "2026-11-12", "score": 88}, "worst_night": {"date": "2026-11-10", "score": 64},
            "sleep_avg_min": asleep, "steps_total": 63801.0}


def _ctx(**kw: object) -> dict:
    return {"calm": False, "guess_pending": False, "need_min": 480.0, "window": {"status": "learning"},
            "plan": {"in_bed_by_min": 645, "wake_min": 1140, "extra_min": 0, "sleep_min": 480.0},
            "debt": {"debt_min": 0, "nights": 7, "suggest_min": 0}, "target": None, "cardio_base": 20.0,
            "moments": [], "because": [], "explain": [], "closed": None, "tz": timezone.utc, "given": []} | kw


def _cards(rows: list[dict] | None = None, d: date = MON, week: dict | None = None, **kw: object) -> dict[str, dict]:
    out = report.cards(d, rows or _rows(d), week or _week(), _week(), _ctx(**kw))
    for c in out:
        assert 1 <= len(c["facts"]) <= report.MAX_FACTS and len(c["advice"]) <= report.MAX_ADVICE and c["fallback"]
        assert isinstance(c["quiet"], bool)
        assert "oura" not in (c["fallback"] + " ".join(c["facts"] + c["advice"])).lower()
    return {c["kind"]: c for c in out}


def _text(c: dict) -> str:
    return " | ".join(c["facts"] + c["advice"] + [c["fallback"]])


DEBT = {"debt_min": 315, "nights": 7, "suggest_min": 60}
LATE_COFFEE = [{"kind": "caffeine_late", "effect": {"metric": "bed_start", "delta": 40.0}}]
TARGET = {"lo": 22, "hi": 28, "band": "recovered"}


def test_today_verdicts() -> None:
    assert _cards(_rows(ready=90))["today"]["verdict"] == "push"
    assert _cards(_rows(ready=75))["today"]["verdict"] == "steady"
    assert _cards(_rows(ready=60))["today"]["verdict"] == "easy"
    easy = {"debt_min": report.EASY_DEBT_MIN, "nights": 7, "suggest_min": 60}
    assert _cards(_rows(ready=75), debt=easy)["today"]["verdict"] == "easy"  # debt turns a middle day easy
    t = _cards(_rows(ready=93, asleep=504.0), debt=DEBT)["today"]  # the first real reply: top band, 5 h 15 min debt
    assert t["verdict"] == "steady"
    assert "debt" in " ".join(t["advice"])
    push = {"debt_min": report.PUSH_DEBT_MAX, "nights": 7, "suggest_min": 20}
    assert _cards(_rows(ready=90), debt=push)["today"]["verdict"] == "steady"
    rest = _cards(_rows(ready=90, sig=STRAIN_SIG))["today"]
    assert rest["verdict"] == "rest" and rest["fallback"].startswith("Rest today.")
    assert _cards(_rows(ready=None)).get("today") is None  # no scored night: no today card


def _busy(**today: float) -> list[dict]:
    """Yesterday: 14,000 steps and double cardio load; today: the given inputs."""
    return _rows()[:-2] + [_row(MON - timedelta(1), steps=14000.0, cardio_load=40.0), _row(MON, **today)]


def test_unusual_findings_carry_the_difference_only() -> None:
    cs = _cards(_busy(rhr=62.0, bed_start=760.0), target=TARGET)
    t, n, y = cs["today"], cs["night"], cs["yesterday"]
    assert not t["quiet"] and t["verdict"] == "steady"
    assert t["advice"] == ["Train at your usual effort: aim for a cardio load of 22 to 28"]
    # ranked by size in bands: steps 6000/400 = 15, fall-asleep 100/15 = 6.7, load 20/4 = 5, rhr 7/2.75 = 2.5
    assert t["facts"] == ["Walked 6,000 steps more than your usual yesterday: a more active day than usual",
                          "You fell asleep about 1 h 40 min later than usual"]
    assert n["facts"] == ["Resting heart rate 7 bpm higher than your usual: can mean your body is still recovering"]
    assert y["facts"] == ["Cardio load 100 % higher than your usual yesterday: a heavier day than usual for your heart"]
    for c in (t, n, y):
        assert not re.search(r"\b(62|14,000|01:40)\b", _text(c)), _text(c)  # the readings stay on the pages
    assert t["fallback"] == "A steady day, keep to your usual. " + t["facts"][0] + ". " + t["facts"][1] + ". " \
        + t["advice"][0] + "."


def test_inside_twice_the_band_is_not_a_finding() -> None:
    # rhr band = max(5 % of 55, 1.5) = 2.75 bpm: 5 bpm is outside the band but under 2 bands
    cs = _cards(_rows(rhr=60.0, bed_start=685.0))  # fall asleep 25 min later: under 30
    assert cs["today"]["quiet"] and cs["night"]["quiet"]
    assert "Resting heart rate 6 bpm higher than your usual" in " ".join(_cards(_rows(rhr=61.0))["today"]["facts"])


def test_streak_wording() -> None:
    rows = _rows()[:-2] + [_row(MON - timedelta(1), rhr=59.0), _row(MON, rhr=62.0)]
    assert "Resting heart rate 7 bpm higher than your usual, for the 2nd night in a row: can mean your body is still recovering" \
        in _cards(rows)["today"]["facts"]
    assert "Resting heart rate higher than your usual, for more than one night: can mean your body is still recovering" \
        in _cards(rows, calm=True)["today"]["facts"]
    lower = _rows()[:-2] + [_row(MON - timedelta(1), rhr=52.0), _row(MON, rhr=62.0)]  # the night before went the other way
    assert "Resting heart rate 7 bpm higher than your usual: can mean your body is still recovering" in _cards(lower)["today"]["facts"]
    assert [report._nth(n) for n in (2, 3, 11, 21)] == ["2nd", "3rd", "11th", "21st"]


def test_quiet_cards() -> None:
    cs = _cards(_rows(ready=93), target=TARGET)
    t = cs["today"]
    assert t["quiet"] and t["verdict"] == "push" and t["facts"] == ["Nothing unusual in your body's numbers today"]
    assert t["fallback"] == "A good day to push. Nothing unusual in your body's numbers today. " \
                            "A good day for a harder workout: aim for a cardio load of 22 to 28."
    assert cs["night"] == {"kind": "night", "verdict": None, "facts": ["A usual night for you"], "advice": [], "quiet": True,
                           "fallback": "A usual night for you."}
    assert cs["yesterday"]["quiet"] and cs["yesterday"]["fallback"] == "A usual day for you."
    assert cs["week"]["quiet"] and not cs["next_week"]["quiet"]
    no_session = _rows()[:-1] + [_row(MON) | {"c": {"signals": CALM_SIG}}]
    assert "night" not in _cards(no_session)
    off = _row(MON - timedelta(1))
    off["c"]["wear"] = {"awake_hours": 16, "worn_hours": 5}
    assert _cards(_rows()[:-2] + [off, _row(MON)])["yesterday"]["facts"] == [
        "A usual day for you", "The ring was off for much of the day, so movement may be undercounted"]


def test_max_findings_and_no_repeat() -> None:
    rows = _rows()[:-2] + [_row(MON - timedelta(1), steps=14000.0, cardio_load=40.0, stress_load=3.0),
                           _row(MON, rhr=62.0, hrv=30.0, asleep=330.0, awake=60.0, bed_start=760.0, bed_end=1300.0)]
    cs = _cards(rows)
    assert len(cs["today"]["facts"]) == len(cs["night"]["facts"]) == len(cs["yesterday"]["facts"]) == report.MAX_FINDINGS
    shown = cs["today"]["facts"] + cs["night"]["facts"] + cs["yesterday"]["facts"]
    assert len(shown) == len(set(shown))
    # all of the night's (or yesterday's) findings went to today: the card stays, quiet, without claiming a usual night
    only = _cards(_rows(rhr=62.0))
    assert only["today"]["facts"][0].startswith("Resting heart rate 7 bpm higher than")
    assert only["night"] == {"kind": "night", "verdict": None, "facts": ["Nothing else stood out last night"], "advice": [],
                             "quiet": True, "fallback": "Nothing else stood out last night."}
    day = _cards(_rows()[:-2] + [_row(MON - timedelta(1), steps=14000.0), _row(MON)])["yesterday"]
    assert day["quiet"] and day["facts"] == ["Nothing else stood out yesterday"]
    assert day["fallback"] == "Nothing else stood out yesterday."


PLANNED = "Be in bed by 22:45 to be rested for 07:00"
LATE_COFFEE_YDAY = [{"kind": "coffee", "ts": _ts(MON - timedelta(1), 16, 10)}]


def test_follow_up_bedtime() -> None:
    on_time = _cards(_rows(bed_start=650.0), given=[PLANNED])["today"]["facts"]  # 22:50: within 15 min
    assert on_time == ["You were in bed by 22:45 as planned"]
    late = _cards(_rows(bed_start=685.0, asleep=420.0), given=[PLANNED])["today"]["facts"]
    assert late == ["You fell asleep about 40 min after the 22:45 you planned, and you slept 30 min shorter than your usual"]
    calm = _cards(_rows(bed_start=685.0), given=[PLANNED], calm=True)["today"]["facts"]
    assert calm == ["You fell asleep later than you planned"]
    assert _cards(_rows(bed_start=None), given=[PLANNED])["today"]["quiet"]  # no data: no follow-up


def test_follow_up_coffee_alcohol_meal() -> None:
    kept = _cards(given=["Skip coffee after 14:00: after late coffee you fall asleep about 40 min later"],
                  moments=[{"kind": "coffee", "ts": _ts(MON - timedelta(1), 9, 0)}])["today"]["facts"]
    assert kept == ["No coffee after 14:00 yesterday, as planned"]
    broke = _cards(_rows(bed_start=720.0), given=["No more coffee today: it can push your sleep later"],
                   moments=LATE_COFFEE_YDAY)
    assert broke["today"]["facts"] == [
        "Coffee at 16:10 yesterday, after the cut-off, and you fell asleep about 1 h later than usual"]
    assert broke["night"]["facts"] == ["Nothing else stood out last night"]  # the fall-asleep shift is told once
    calm = _cards(given=["Skip coffee in the afternoon"], moments=LATE_COFFEE_YDAY, calm=True)["today"]["facts"]
    assert calm == ["Coffee in the afternoon yesterday, after the cut-off"]
    assert _cards(given=["Skip alcohol tonight"])["today"]["facts"] == ["No alcohol yesterday, as planned"]
    drink = [{"kind": "alcohol", "ts": _ts(MON - timedelta(1), 20, 30)}]
    assert _cards(given=["Skip alcohol tonight"], moments=drink)["today"]["facts"] == [
        "Alcohol at 20:30 yesterday, against the plan"]
    meal = [{"kind": "meal", "ts": _ts(MON - timedelta(1), 21, 40)}]
    assert _cards(given=["Finish eating before 21:00: x"], moments=meal)["today"]["facts"] == [
        "A meal at 21:40 yesterday, after the cut-off"]
    assert _cards(given=["Eat earlier in the evening"], calm=True)["today"]["facts"] == ["No late meal yesterday, as planned"]
    assert _cards(given=["Rest today.", "Go to bed at your planned time", "something else"])["today"]["quiet"]


def test_follow_up_light_day() -> None:
    light = "Keep exercise light, a walk rather than a hard workout"
    assert _cards(given=[light])["today"]["facts"] == ["You kept yesterday light, as planned"]
    heavy = _cards(_busy(), given=["Keep the rest of the day easy after a walk"])["today"]["facts"]
    assert heavy[0] == "Yesterday was a heavier day than planned"


def test_no_score_numbers() -> None:
    rows = [_row(MON - timedelta(i), ready=91, sleep=83, act=67) for i in range(13, 0, -1)] + \
           [_row(MON, ready=91, sleep=83, act=67, rhr=50.0, hrv=55.0, asleep=520.0, spo2=88.0)]
    for calm in (False, True):
        cs = _cards(rows, calm=calm, target=TARGET, debt=DEBT, because=LATE_COFFEE,
                    moments=[{"kind": "coffee", "ts": _ts(MON - timedelta(1), 16, 20)}],
                    window={"status": "ok", "start_min": 630, "end_min": 690})
        assert {"today", "yesterday", "night", "tonight", "week", "next_week"} <= set(cs)
        for c in cs.values():
            assert not re.search(r"\b(91|83|67)\b", _text(c)), (c["kind"], _text(c))
            assert "score" not in _text(c).lower(), (c["kind"], _text(c))


def test_tonight_advice_grounded() -> None:
    assert _cards()["tonight"]["verdict"] == "usual"
    assert _cards(window={"status": "learning"}, plan=None, debt=None).get("tonight") is None
    plan = {"in_bed_by_min": 615, "wake_min": 1140, "extra_min": 30, "sleep_min": 510.0}
    t = _cards(plan=plan, debt={"debt_min": 95, "nights": 7, "suggest_min": 30})["tonight"]
    assert t["verdict"] == "earlier" and t["quiet"] and t["facts"] == ["Go to bed a little earlier tonight"]
    assert t["fallback"] == "Go to bed a little earlier tonight. Be in bed by 22:15 to pay back some sleep debt."
    assert t["advice"][0] == f"Be in bed by {report._clock(plan['in_bed_by_min'])} to pay back some sleep debt" \
        and "22:15" in t["advice"][0]
    today = _cards(_rows(ready=93), plan=plan, debt=DEBT)["today"]
    assert any(a.startswith("Be in bed by 22:15") for a in today["advice"])


def test_because_advice_only_with_effect() -> None:
    assert not any("coffee" in a for c in _cards().values() for a in c["advice"])
    no_effect = [{"kind": "caffeine_late", "effect": None}]
    assert not any("coffee" in a for c in _cards(because=no_effect).values() for a in c["advice"])
    earlier = [{"kind": "caffeine_late", "effect": {"metric": "bed_start", "delta": -20.0}}]
    assert not any("coffee" in a for c in _cards(because=earlier).values() for a in c["advice"])
    t = _cards(because=LATE_COFFEE)["tonight"]
    assert "Skip coffee after 14:00: after late coffee you fall asleep about 40 min later" in t["advice"]


def test_yesterday_and_week_wording() -> None:
    y = _cards(_rows()[:-2] + [_row(MON - timedelta(1), steps=12000.0, cardio_load=35.0), _row(MON)], guess_pending=True,
               moments=[{"kind": "flat", "ts": _ts(MON - timedelta(1), 15, 10)}])["yesterday"]
    assert y["verdict"] is None and y["advice"] == [] and not y["quiet"]
    assert y["facts"] == ["Walked 4,000 steps more than your usual yesterday: a more active day than usual",
                          "Cardio load 75 % higher than your usual yesterday: a heavier day than usual for your heart"]



def test_week_differences_only() -> None:
    w = _cards()["week"]
    assert w == {"kind": "week", "verdict": None, "facts": ["Nothing stood out this week"], "advice": [], "quiet": True,
                 "fallback": "Nothing stood out this week."}
    one = _cards(week=_week(asleep=400.0))["week"]
    assert not one["quiet"] and one["facts"] == ["Slept 50 min less a night than the week before"]
    assert _cards(week=_week(asleep=440.0))["week"]["quiet"]  # under 15 min: not a difference
    hr = [_row(MON - timedelta(i), rhr=60.0 if i <= 7 else 55.0) for i in range(13, 0, -1)] + [_row(MON)]
    steps = _week() | {"steps_total": 50000.0}
    many = _cards(hr, week=steps | {"sleep_avg_min": 400.0})["week"]
    assert many["facts"] == ["Slept 50 min less a night than the week before",
                             "Resting heart rate 5 bpm higher than the week before: can mean your body is still recovering",
                             "Walked 22 % less than the week before"]
    assert not re.search(r"63,801|50,000|7 h|60|workout|Best|resilience", _text(many)), _text(many)
    calm = _cards(hr, week=steps, calm=True)["week"]
    assert calm["facts"] == ["Resting heart rate higher than the week before: can mean your body is still recovering",
                             "Walked less than the week before"]
    assert not re.search(r"\d", _text(calm)) and not re.search(r"\d", _text(_cards(calm=True)["week"]))


def test_next_week_focus() -> None:
    n = _cards(week=_week(asleep=420.0))["next_week"]
    assert n["verdict"] == "sleep" and n["advice"] == ["Aim for 8 h of sleep a night"]
    spread = [_row(MON - timedelta(i), bed_start=540.0 + 150 * (i % 2)) for i in range(13, 0, -1)] + [_row(MON)]
    assert _cards(spread)["next_week"]["verdict"] == "regular"
    assert _cards(_rows(), cardio_base=40.0)["next_week"]["verdict"] == "move"
    assert _cards()["next_week"]["verdict"] == "keep"
    thu = MON + timedelta(3)
    assert "next_week" not in _cards(_rows(thu), d=thu)
    assert "week" not in _cards(week=_week(scored=2)) and "next_week" not in _cards(week=_week(scored=2))


def test_calm_mode_has_no_digits() -> None:
    ep = {"first": MON - timedelta(5), "last": MON - timedelta(3), "nights": 3, "kinds": ["alcohol"]}
    because = [{"kind": "alcohol", "effect": {"metric": "bed_start", "delta": 25.0}}] + LATE_COFFEE
    plan = {"in_bed_by_min": 615, "wake_min": 1140, "extra_min": 30, "sleep_min": 510.0}
    for sig in (STRAIN_SIG, CALM_SIG):
        cs = _cards(_rows(ready=58, asleep=340.0, sig=sig, rhr=60.0, spo2=88.0, wind_down=700.0), calm=True, because=because,
                    plan=plan, closed=ep, moments=[{"kind": "coffee", "ts": _ts(MON - timedelta(1), 16, 20)}],
                    explain=["alcohol"], target=TARGET, debt=DEBT,
                    given=[PLANNED, "Skip coffee after 14:00", "Keep exercise light"],
                    window={"status": "ok", "start_min": 630, "end_min": 690})
        assert set(cs) == {"today", "yesterday", "night", "tonight", "week", "next_week", "body"}
        assert cs["today"]["advice"] and cs["tonight"]["advice"] and not cs["today"]["quiet"]
        for c in cs.values():
            assert not re.search(r"\d", _text(c)), (c["kind"], _text(c))


def test_guess_first_withholds_today() -> None:
    cs = _cards(guess_pending=True)
    assert "today" not in cs and "night" in cs


def test_body_card() -> None:
    assert "body" not in _cards()  # quiet day
    b = _cards(_rows(sig=STRAIN_SIG), explain=["alcohol"])["body"]
    assert b["verdict"] == "watch" and b["facts"][:3] == [
        "Resting HR +6 bpm against your usual", "HRV −25 % against your usual", "Above calm for 3 nights"]
    assert b["advice"] == ["Rest and drink enough"]
    assert "may" in b["fallback"] and "doctor" not in b["fallback"].lower()
    ep = {"first": MON - timedelta(4), "last": MON - timedelta(2), "nights": 3, "kinds": []}
    back = _cards(closed=ep)["body"]
    assert back["verdict"] == "back" and back["facts"] == ["Back in your usual range after 3 nights"] and back["advice"] == []


BED = _ts(MON, 22, 45)


def _upd(h: int, since_h: int = 12, given: tuple[str, ...] = (), calm: bool = False, bed: float | None = BED,
         **day: object) -> dict:
    day = {"moments": [], "workouts": [], "steps_today": 6000.0, "steps_since": 0.0, "stressed_min": 0.0,
           "hr_since": None, "hr_usual": None} | day
    u = report.update(_ts(MON, h, 0), _ts(MON, since_h, 0), day, list(given),
                      {"calm": calm, "tz": timezone.utc, "step_goal": 8000.0, "bed": bed})
    assert set(u) == {"at", "nothing_new", "evening", "facts", "advice", "fallback"}
    text = " ".join(u["facts"] + u["advice"]) + u["fallback"]
    assert not re.search(r"medic|dose|pill|score|oura", text.lower()), text
    return u


WALK = {"kind": "walk", "minutes": 40, "start": _ts(MON, 13, 0)}
COFFEE = [{"kind": "coffee", "ts": _ts(MON, 8, 0)}, {"kind": "coffee", "ts": _ts(MON, 10, 0)}]


def test_update_nothing_new() -> None:
    u = _upd(13, steps_since=900.0, hr_since=76.0, hr_usual=74.0)  # an ordinary stretch: nothing a page lacks
    assert u["nothing_new"] and not u["evening"] and u["advice"] == [] and u["facts"] == [] and u["at"] == _ts(MON, 13, 0)
    still = _upd(13)
    assert not still["nothing_new"] and still["facts"] == ["Little movement since 12:00: a still stretch"]
    assert _upd(13, since_h=13)["nothing_new"]  # no time passed: not a still stretch
    two = _upd(11, moments=COFFEE, steps_since=900.0)  # the count alone, no advice: nothing new
    assert two["nothing_new"] and two["facts"] == ["Caffeine 2 times today"]


def test_update_drops_what_pages_show() -> None:
    u = _upd(21, steps_since=900.0, steps_today=7000.0, hr_since=76.0, hr_usual=74.0)
    assert u["facts"] == [] and not re.search(r"steps|goal|bedtime|22:45|7,000|900|76|74", " ".join(u["facts"]))
    assert u["advice"] and not u["nothing_new"]
    assert u["fallback"] == "Start winding down: dim the lights and put screens away."  # advice alone: still a sentence


def test_update_effort_goes_easy() -> None:
    u = _upd(15, workouts=[WALK], steps_since=3000.0)
    assert not u["nothing_new"] and "A walk at 13:00 for 40 min" in u["facts"]
    assert u["advice"] == ["Keep the rest of the day easy after a walk"]


def test_update_coffee() -> None:
    assert _upd(11, moments=COFFEE)["advice"] == []  # two before the cut-off: fine
    assert "Caffeine 2 times today" in _upd(11, moments=COFFEE)["facts"]
    late = _upd(15, moments=COFFEE)
    assert late["advice"] == ["No more caffeine today: it can push your sleep later"]  # past 14:00
    third = COFFEE + [{"kind": "coffee", "ts": _ts(MON, 12, 30)}]
    assert _upd(13, moments=third)["advice"] == ["No more caffeine today: it can push your sleep later"]


def test_update_energy_is_caffeine_others_advise_nothing() -> None:
    energy = [{"kind": "coffee", "ts": _ts(MON, 8, 0)}, {"kind": "energy", "ts": _ts(MON, 10, 0)}]
    assert "Caffeine 2 times today" in _upd(11, moments=energy)["facts"]
    assert _upd(15, moments=energy[1:])["advice"] == ["No more caffeine today: it can push your sleep later"]
    third = energy + [{"kind": "energy", "ts": _ts(MON, 12, 30)}]
    assert _upd(13, moments=third)["advice"] == ["No more caffeine today: it can push your sleep later"]
    others = [{"kind": k, "ts": _ts(MON, 12, 30 + i)} for i, k in enumerate(("snack", "nicotine", "nap"))]
    u = _upd(15, moments=others, steps_since=900.0)
    assert u["advice"] == [] and u["facts"] == [
        "You noted a snack at 12:30", "You noted nicotine at 12:31", "You noted a nap at 12:32"]


def test_follow_up_energy_counts_as_coffee() -> None:
    late = [{"kind": "energy", "ts": _ts(MON - timedelta(1), 16, 10)}]
    assert _cards(given=["No more caffeine today: it can push your sleep later"], moments=late)["today"]["facts"] == [
        "An energy drink at 16:10 yesterday, after the cut-off"]


def test_update_stress_and_move() -> None:
    s = _upd(13, stressed_min=90.0)
    assert s["facts"] == ["Little movement since 12:00: a still stretch",
                          "Stressed for 1 h 30 min since 12:00: a demanding stretch"]
    assert s["advice"] == ["Take a short walk or do the breathing exercise"]
    assert _upd(15, steps_today=2000.0)["advice"] == ["Fit in a brisk walk while there is time before bed"]
    assert _upd(13, steps_today=2000.0)["advice"] == []  # not afternoon yet
    assert _upd(20, steps_today=2000.0)["advice"] == []  # under 3 h to bed: too late to move


def test_update_evening_wind_down_only() -> None:
    u = _upd(21, moments=COFFEE, stressed_min=90.0, steps_today=2000.0)
    assert u["evening"] and not u["nothing_new"]
    assert u["advice"] == ["Start winding down: dim the lights and put screens away", "Be in bed by 22:45"]
    assert not any("bedtime" in f for f in u["facts"])
    assert not _upd(21, bed=None)["evening"]


def test_update_no_repeat() -> None:
    line = "No more caffeine today: it can push your sleep later"
    assert _upd(16, since_h=15, moments=COFFEE, given=(line,))["advice"] == []  # same state: not again
    again = COFFEE + [{"kind": "coffee", "ts": _ts(MON, 15, 30)}]
    assert _upd(16, since_h=15, moments=again, given=(line,))["advice"] == [line]  # a new coffee: state changed
    stress = "Take a short walk or do the breathing exercise"
    assert _upd(16, since_h=15, stressed_min=60.0, given=(stress,))["advice"] == [stress]  # stressed again since


def test_update_heart_rate_and_calm() -> None:
    u = _upd(15, steps_since=900.0, hr_since=82.0, hr_usual=74.0)
    assert u["facts"] == ["Heart rate 8 bpm higher than your usual since 12:00: your body has been busier than usual"]
    assert _upd(15, steps_since=900.0, hr_since=66.0, hr_usual=74.0)["facts"] == ["Heart rate 8 bpm lower than your usual since 12:00: a calm stretch"]
    assert not re.search(r"(82|74)", _text(u))
    c = _upd(21, calm=True, moments=COFFEE, workouts=[WALK], stressed_min=90.0, hr_since=82.0, hr_usual=74.0)
    assert c["advice"] and not re.search(r"\d", " ".join(c["facts"] + c["advice"]) + c["fallback"])
    assert "Heart rate higher than your usual since the last note: your body has been busier than usual" in c["facts"]
    q = _upd(13, calm=True, steps_today=500.0)
    assert q["facts"] == ["Little movement since the last note: a still stretch"] and not re.search(r"\d", _text(q))


def test_timeline() -> None:
    ev = report.timeline({"start": _ts(MON, 0, 0) - 3600, "end": _ts(MON, 7, 5)}, [WALK],
                         [{"kind": "coffee", "ts": _ts(MON, 8, 0)}], [_ts(MON, 9, 0)], {}, False)
    assert [(e["kind"], e["label"]) for e in ev] == [("wake", "Woke"), ("coffee", "Coffee"), ("dose", "Dose"),
                                                     ("walk", "Walk · 40 min")]
    new = [{"kind": k, "ts": _ts(MON, 14, i)} for i, k in enumerate(("snack", "energy", "nicotine", "nap"))]
    assert [e["label"] for e in report.timeline(None, [], new, [], {}, False)] == [
        "Snack", "Energy drink", "Nicotine", "Nap"]


def test_timeline_active_hours() -> None:
    # 10:00 active, 11:00 under 500, 13:00 overlapped by the 13:00-13:40 walk
    steps = {_ts(MON, 10, 0): 2866.0, _ts(MON, 11, 0): 499.0, _ts(MON, 13, 0): 3000.0}
    ev = report.timeline(None, [WALK], [], [], steps, False)
    assert [e for e in ev if e["kind"] == "steps"] == [
        {"t": _ts(MON, 10, 0), "kind": "steps", "label": "2,866 steps", "steps": 2866}]
    ride = {"kind": "ride", "minutes": 40, "start": _ts(MON, 13, 0)}  # a ride does not hide the hour's steps
    assert len([e for e in report.timeline(None, [ride], [], [], steps, False) if e["kind"] == "steps"]) == 2
    calm = [e for e in report.timeline(None, [], [], [], steps, True) if e["kind"] == "steps"]
    assert [e["label"] for e in calm] == ["An active hour", "An active hour"]


def test_timeline_and_update_endpoints() -> None:
    from conftest import load
    from app import samples
    now = datetime.now(samples.tz())
    d = now.date()
    db.save_day(d, (80, 75, 70), {"inputs": {}, "signals": CALM_SIG, "workouts": [], "session":
                                  {"start": now.timestamp() - 40000, "end": now.timestamp() - 10000}}, {})
    t = now.timestamp() - 60
    load({"moment_coffee": {t: 1.0}, "biometric_steps": {samples.midnight(d).timestamp(): 600.0}})
    db.add_journal(d.isoformat(), "medication", "00:00", None, int(t))
    with TestClient(app) as c:
        assert c.get("/timeline").status_code == 401
        j = c.get(f"/timeline?date={d.isoformat()}", headers=H).json()
        assert j["date"] == d.isoformat() and {"wake", "coffee", "dose", "steps"} <= {e["kind"] for e in j["events"]}
        assert [e["t"] for e in j["events"]] == sorted(e["t"] for e in j["events"])
        bad = [{"since": "x"}, {"since": int(t), "given": "x"}, {"since": int(t), "moves": [{"kind": "swim"}]},
               {"since": int(now.timestamp()) + 999}]
        assert all(c.post("/report/update", json=b, headers=H).status_code == 422 for b in bad)
        u = c.post("/report/update", headers=H, json={"since": int(t) - 600, "given": [], "moves": [
            {"start": int(t) - 300, "end": int(t) - 60, "kind": "ride"}]}).json()
        assert not u["nothing_new"] and any(f.startswith("You noted coffee") for f in u["facts"])
        assert any(f.startswith("A ride") for f in u["facts"])
        quiet = c.post("/report/update", headers=H, json={"since": int(now.timestamp()) - 1}).json()
    assert quiet["nothing_new"] or quiet["advice"]  # advice alone (time of day) still counts as new


def test_report_endpoint() -> None:
    d = date.today()
    for i in range(7):
        db.save_day(d - timedelta(i), (80, 75, 70), {"inputs": {"sleep_min": 450.0, "steps": 8000.0}, "signals": CALM_SIG}, {})
    with TestClient(app) as c:
        assert c.get("/report").status_code == 401
        assert c.get("/report?date=bad", headers=H).status_code == 422
        j = c.get(f"/report?date={d.isoformat()}", headers=H).json()
        assert c.get("/report", params={"given": ["x" * 301]}, headers=H).status_code == 422
        g = c.get("/report", params={"date": d.isoformat(), "given": ["Skip alcohol tonight", "Rest today."]}, headers=H).json()
    kinds = [x["kind"] for x in j["cards"]]
    assert j["date"] == d.isoformat() and kinds[:2] == ["today", "yesterday"] and "body" not in kinds
    assert all(set(x) == {"kind", "verdict", "facts", "advice", "fallback", "quiet"} for x in j["cards"])
    assert j["cards"][0]["quiet"] and g["cards"][0]["facts"] == ["No alcohol yesterday, as planned"]
