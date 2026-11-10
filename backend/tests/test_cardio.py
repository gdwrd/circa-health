from datetime import date

from app import cardio

B = cardio.zones(190, 50)  # reserve 140: 120, 134, 148, 162, 176
T0 = 1_700_000_000 // 3600 * 3600  # an hour start


def awake(t: float) -> bool:
    return True


def hr(*runs: tuple[int, list[float]]) -> dict[float, float]:
    """(offset in 5-min samples, [bpm...]) runs from T0."""
    return {float(T0 + (o + i) * 300): v for o, vs in runs for i, v in enumerate(vs)}


def test_max_hr_zones_zone_of() -> None:
    assert cardio.max_hr(1990, date(2026, 1, 1)) == 208 - 0.7 * 36
    assert B == [120, 134, 148, 162, 176]
    assert [cardio.zone_of(v, B) for v in (119.9, 120, 133.9, 134, 148, 162, 176, 200)] == [0, 1, 1, 2, 3, 4, 5, 5]


def test_load() -> None:
    assert cardio.load(hr((0, [150.0] * 12)), B) == {"load": 18.0, "minutes": [0, 0, 60, 0, 0]}  # an hour in zone 3
    assert cardio.load(hr((0, [0.0, 100.0, 125.0, 180.0])), B) == {"load": 3.0, "minutes": [5, 0, 0, 0, 5]}


def test_off_grid_sample_shares_its_slot() -> None:
    """A live reading between two 5-min samples neither breaks a calm run nor adds 5 min of load."""
    assert cardio.restorative(hr((0, [55.0] * 4)) | {T0 + 450.0: 56.0}, 52, {}, awake) == 20
    assert cardio.load({T0 + 0.0: 150.0, T0 + 100.0: 150.0}, B) == {"load": 1.5, "minutes": [0, 0, 5, 0, 0]}


def test_target_bands_and_none() -> None:
    assert cardio.target(85, 10) == {"lo": 11, "hi": 14, "band": "recovered"}
    assert cardio.target(70, 10) == {"lo": 8, "hi": 11, "band": "steady"}
    assert cardio.target(40, 2) == {"lo": 2, "hi": 4, "band": "easy"}  # base floor 5, hi >= lo + 2
    assert cardio.target(None, 10) is None and cardio.target(80, None) is None


def test_workouts_dip_bursts_merge_and_kind() -> None:
    # 30 min with one dip inside -> one session
    (w,) = cardio.workouts(hr((0, [140.0, 140, 140, 100, 140, 140])), {}, B, awake)
    assert w["start"] == T0 and w["end"] == T0 + 1800 and w["minutes"] == 30 and w["kind"] == "workout"
    assert w["max_bpm"] == 140 and w["avg_bpm"] == round((5 * 140 + 100) / 6) and not w["confirmed"]
    # two 10-min bursts are ignored
    assert cardio.workouts(hr((0, [140.0, 140]), (4, [140.0, 140])), {}, B, awake) == []
    # two 20-min segments 10 min apart merge; 15 min apart do not
    assert len(cardio.workouts(hr((0, [140.0] * 4), (6, [140.0] * 4)), {}, B, awake)) == 1
    assert len(cardio.workouts(hr((0, [140.0] * 4), (7, [140.0] * 4)), {}, B, awake)) == 2
    long = hr((0, [140.0] * 6))
    assert cardio.workouts(long, {T0: 1500.0}, B, awake)[0]["kind"] == "walk"
    assert cardio.workouts(long, {T0: 200.0}, B, awake, "ride")[0]["kind"] == "ride"
    assert cardio.workouts(long, {}, B, lambda t: False) == []  # sleep samples excluded


def test_restorative() -> None:
    calm = hr((0, [55.0] * 4), (10, [55.0] * 2))  # 20 min counts, 10 min does not
    assert cardio.restorative(calm, 52, {}, awake) == 20
    assert cardio.restorative(calm, 52, {T0: 500.0}, awake) == 0  # step-heavy hour
    assert cardio.restorative(hr((0, [70.0] * 4)), 52, {}, awake) == 0  # above 1.10 x rhr
    assert cardio.restorative(calm, None, {}, awake) is None


def test_fitness() -> None:
    assert cardio.fitness(190, 50) == 58.1
    assert cardio.fitness(None, 50) is None and cardio.fitness(190, None) is None
