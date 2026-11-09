from datetime import date, timedelta

from fastapi.testclient import TestClient

from app import db, insights, main, samples, scoring
from app.main import app
from conftest import load
from test_insights import TODAY, entry, row

H = {"Authorization": "Bearer secret"}
M = 60.0
S, E = 0.0, 480 * M  # an 8 h night


def test_clip_night() -> None:
    assert scoring.clip_night(S, E, 20.0, [], []) == (S, E, 20.0, 0.0)
    # scroll 10 min after getting in bed, then sleep: onset = phone put down; 6 min of the wake-up scroll too
    s, e, a, p = scoring.clip_night(S, E, 20.0, [(-30 * M, 40 * M), (E - 6 * M, E + 10 * M)], [])
    assert (s, e) == (40 * M, E - 6 * M) and a == 20.0 and p == 76.0
    # woke at 3 h, scrolled 10 min, slept again: neither end moves, the scroll counts as awake
    s, e, a, p = scoring.clip_night(S, E, 20.0, [(180 * M, 190 * M)], [])
    assert (s, e, a, p) == (S, E, 30.0, 10.0)
    # the ring already marked 180-190 awake: the same scroll adds nothing; 10 min beyond it adds 10
    ra = [(180 * M, 190 * M)]
    assert scoring.clip_night(S, E, 20.0, [(180 * M, 190 * M)], ra)[2] == 20.0
    assert scoring.clip_night(S, E, 20.0, [(180 * M, 200 * M)], ra)[2] == 30.0
    # ring saw the first 15 min awake while scrolling; clipping them off the span also drops them from awake
    assert scoring.clip_night(S, E, 20.0, [(-10 * M, 15 * M)], [(S, 15 * M)])[2] == 5.0
    # short glances (< 1 min at start, < 3 min at the end) do not clip
    assert scoring.clip_night(S, E, 0.0, [(M, 1.5 * M), (E - 20 * M, E - 18 * M)], [])[:2] == (S, E)
    # span would vanish: unchanged, phone still reported
    assert scoring.clip_night(S, 60 * M, 5.0, [(0.0, 70 * M)], []) == (S, 60 * M, 5.0, 60.0)


def test_phone_in_bed_journal_upsert_delete_and_route() -> None:
    d = date.today()
    m = samples.midnight(d).timestamp()
    ss, es = m - 1800, m + 7 * 3600
    night = {"biometric_sleep_end_ts": {ss: es}, "biometric_sleep_total_min": {ss: 400.0},
             "biometric_sleep_awake_min": {ss: 20.0}, "biometric_sleep_stage": {ss: 2.0}}
    ev = (d - timedelta(1)).isoformat()
    load(night)
    with TestClient(app) as c:
        main._score_day(d)
        assert not [e for e in db.journal(ev) if e["kind"] == "phone_in_bed"]
        assert c.get(f"/sleep?date={d}", headers=H).json()["phone_min"] == 0
        load({"biometric_screen_on": {ss - 600: 1.0, ss + 1200: 0.0}})  # 30 min, ends 20 min after bed
        main._score_day(d)
        main._score_day(d)  # re-score replaces
        rows = [e for e in db.journal(ev) if e["kind"] == "phone_in_bed"]
        assert [r["value"] for r in rows] == ["30"]
        j = c.get(f"/sleep?date={d}", headers=H).json()
        assert j["phone_min"] == 30 and j["session"]["start"] == ss + 1200
        assert c.post("/journal", json={"date": ev, "kind": "phone_in_bed"}, headers=H).status_code == 422
        db.init()
        with db.connect() as con, con:
            con.execute("DELETE FROM samples WHERE metric='biometric_screen_on'")
        main._score_day(d)
        assert not [e for e in db.journal(ev) if e["kind"] == "phone_in_bed"]


def test_phone_in_bed_counts_from_20_min() -> None:
    def kinds(v: str) -> dict:
        rows = [row(TODAY - timedelta(i)) for i in range(20)]
        ents = [entry(TODAY - timedelta(i), "phone_in_bed", v) for i in (3, 6, 9)]
        return insights.kinds(rows, ents, TODAY, {}, "", [])["phone_in_bed"]
    assert kinds("19")["n_with"] == 0 and kinds("20")["n_with"] == 3
