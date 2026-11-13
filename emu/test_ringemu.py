"""End-to-end check: the real firmware boots, takes a start-heart-rate command and reports the fake pulse.

Run: python -m pytest -q -p no:faulthandler test_ringemu.py
(unicorn trips a harmless, handled access violation on Windows that faulthandler would print.)
"""
import contextlib, io

import ringemu


def test_real_time_heart_rate_tracks_fake_pulse():
    ringemu.PULSE_BPM = 60
    e = ringemu.Emu(quiet_mem=True)
    e.command(3000, bytes([0x69, 0x01]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(30000)
    assert e.sched_started
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings


def test_patched_firmware_streams_every_accelerometer_sample():
    import patch_fw
    patched = ringemu.HERE / "firmware" / "RY02_3.00.33_fullaccel.bin"
    patched.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, trace=True, image=patched)
    e.hw["motion"] = {"at": 2500, "interval": None, "fire": lambda: e.raise_pin(22)}
    e.command(3000, bytes([0xA1, 0x04]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(6000)
    sent = b"".join(n[3:3 + n[2]] for n in e.notified if n[:2] == bytes([0xA1, 5]))
    fed = b"".join(bytes.fromhex(ev["rx"])[1:] for ev in e.trace if ev["kind"] == "bus" and ev["tx"] == "a7" and ev["t"] >= 3000)
    low6 = lambda b: bytes(v & 0xC0 if i % 2 == 0 else v for i, v in enumerate(b))     # FW12 counter bits out
    assert len(sent) >= 6 * 25 * 2 and low6(sent) == low6(fed)   # every sample the chip read after the command, in order
    assert all(sum(n[:15]) & 0xFF == n[15] for n in e.notified)


def test_bluetooth_update_installs_patched_firmware():
    import patch_fw
    e = ringemu.Emu(quiet_mem=True)                     # starts on stock firmware
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e.hw["motion"] = {"at": 11000, "interval": None, "fire": lambda: e.raise_pin(22)}
    e.command(11500, bytes([0xA1, 0x04]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(14000)
    assert e.resets == 2                                # first-boot config reset, then the reset that ends the update
    assert e.ota["state"] == 5
    assert sum(n[:2] == bytes([0xA1, 5]) for n in e.notified) > 20   # only the patched firmware sends these


def test_motion_only_stream_has_no_gaps_while_still():
    import patch_fw
    e = ringemu.Emu(quiet_mem=True, trace=True)         # starts on stock firmware, installs the patch over Bluetooth
    e.still = True                                      # and no motion interrupt is ever raised
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e.command(11500, bytes([0xA1, 0x07]))
    e.command(71500, bytes([0xA1, 0x08]))
    start = patch_fw.SEQ_MAX - 1000                     # FW12: about 40 s before the counter wraps
    e.hw["seq"] = {"at": 11000, "interval": None, "fire": lambda: e.uc.mem_write(patch_fw.SEQ, start.to_bytes(4, "little"))}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(80000)
    assert e.resets == 2 and e.ota["state"] == 5
    tx = [(ev["t"], bytes.fromhex(ev["data"])) for ev in e.trace if ev["kind"] == "ble_tx" and ev["data"][:2] == "a1"]
    prev, wraps = start, 0                              # FW12: seq runs on by each packet's samples, no gap, 0 skipped
    for t, n in tx:
        if n[1] != 5: continue
        seq = (n[3] & 63) | (n[5] & 63) << 6 | (n[7] & 63) << 12
        want = prev + n[2] // 6
        wraps += want > patch_fw.SEQ_MAX
        assert seq == (want - patch_fw.SEQ_MAX if want > patch_fw.SEQ_MAX else want), (t, prev, seq)
        prev = seq
    assert wraps == 1
    assert [n[1] for t, n in tx if n[1] != 5] == [7, 8]                     # both commands echoed, no a1 ff
    times = [t for t, n in tx if n[1] == 5]
    samples = sum(n[2] // 6 for t, n in tx if n[1] == 5)
    assert times[0] < 13000 and times[-1] < 72000 and 60 * 24 <= samples <= 61 * 26, (times[:3], times[-3:], samples)
    assert max(b - a for a, b in zip(times, times[1:])) <= 1000                # no gap while still
    assert not any(ev["kind"] == "bus" and ev["dev"] == ringemu.PPG and ev["t"] >= 11500 for ev in e.trace)
    stops = [ev["t"] for ev in e.trace if ev["text"].startswith("os_timer_stop(0x20ba28")]
    assert stops and 71500 < stops[-1] < 80000                                 # back to tap-wait after a1 08


def dropped_link(mode):
    """Patched over Bluetooth, still ring, a1 <mode> at 11.5 s, the phone leaves at 15 s -> (emu, timer stops)."""
    import patch_fw
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.still = True
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e.hw["motion"] = {"at": 11000, "interval": None, "fire": lambda: e.raise_pin(22)}
    e.command(11500, bytes([0xA1, mode]))
    e.hw["leave"] = {"at": 15000, "interval": None, "fire": e.ble_disconnect}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(26000)
    assert e.resets == 2 and e.ota["state"] == 5
    assert any(ev["kind"] == "ble_tx" and ev["data"][:4] == "a105" and ev["t"] < 15000 for ev in e.trace)
    return e, [ev["t"] for ev in e.trace if ev["text"].startswith("os_timer_stop(0x20ba28")]


def test_motion_only_stream_ends_when_the_phone_leaves():
    e, stops = dropped_link(0x07)
    assert e.uc.mem_read(0x2098B0, 1)[0] == 0 and e.uc.mem_read(0x20BA3B, 1)[0] == 0    # mode cleared, tap-wait
    assert stops and 15000 < stops[-1] < 25000, stops


def test_raw_stream_mode_survives_the_phone_leaving():
    e, stops = dropped_link(0x04)
    assert e.uc.mem_read(0x2098B0, 1)[0] == 4


def test_double_taps_in_a_real_recording():
    # testdata/taps.csv: 51 s recorded on the ring (firmware version 4) while trying double taps of every kind,
    # then shaking and moving.  Clear, separate double taps end at samples 122 and 242 (gentle) and 278, 308, 319,
    # 366, 397 and 435 (firm); a gentle one at 163 is missed.  Samples 546-720 are shaking and must count nothing.
    import csv, patch_fw
    rows = [r for r in csv.reader((ringemu.HERE / "testdata" / "taps.csv").open()) if r and r[0].isdigit()]
    e = ringemu.Emu(quiet_mem=True, trace=True)         # starts on stock firmware, installs the patch over Bluetooth
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e.recording = [(0, 0, 1000)]
    def start():
        e.recording, e.accel_k = [tuple(int(v) for v in r[2:5]) for r in rows], 0
    e.hw["rec"] = {"at": 12000, "interval": None, "fire": start}
    e.command(12000, bytes([0xA1, 0x07]))
    e.command(11000, bytes([0xA1, 0x09]))              # a1 09 only goes ring -> phone
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(12000 + len(rows) * 40)
    assert e.resets == 2 and e.ota["state"] == 5
    tx = [bytes.fromhex(ev["data"]) for ev in e.trace if ev["kind"] == "ble_tx" and ev["data"][:2] == "a1"]
    assert tx[0][1] == 0xFF and all(sum(n[:15]) & 0xFF == n[15] for n in tx)
    sent, taps = 0, []                                  # first sample of the batch each a1 09 was found in
    for n in tx:
        if n[1] == 5: sent += n[2] // 6
        if n[1] == 9: taps.append((sent, n[2]))
    assert [c for _, c in taps] == list(range(1, len(taps) + 1))
    at = [i for i, _ in taps]
    early = [i for i in at if i <= 440]
    assert len(early) == 8 and all(i <= t < i + 32 for i, t in zip(early, (122, 242, 278, 308, 319, 366, 397, 435))), at
    assert not [i for i in at if 540 <= i <= 720] and len(at) <= 22, at


def worn(image):
    """30 s on a moving finger with the phone connected -> (accelerometer interrupts, double-tap packets, resets)."""
    e = ringemu.Emu(quiet_mem=True, trace=True, image=image)
    e.command(2500, bytes([0x03]))
    e.wear(3000)
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(33000)
    return (sum(ev["kind"] == "irq" and ev.get("pin") == 22 for ev in e.trace),
            sum(n[:2] == bytes([0xA1, 9]) for n in e.notified), e.resets)


def test_wearing_the_ring_interrupts_stock_only_to_wake():
    interrupts, taps, resets = worn(ringemu.IMAGE)
    assert 0 < interrupts <= 10 and taps == 0 and resets == 1      # reset 1 is the first-boot config write


def test_wearing_the_patched_ring_sends_no_taps():
    # Firmware version 2 kept double clicks on the interrupt pin while sampling: about 8 interrupts a second here.
    interrupts, taps, resets = worn(ringemu.HERE / "firmware" / "RY02_3.00.33_fullaccel.bin")
    assert interrupts <= 10 and taps == 0 and resets == 1


def light(on_ms, off_ms=None, leave_ms=None):
    """Patched over Bluetooth, live heart rate from 11.5 s, a1 0a at on_ms -> (emu, a1 packets with times, queue reads)."""
    import patch_fw
    ringemu.PULSE_BPM = 60
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e.command(11500, bytes([0x69, 0x01]))
    if on_ms: e.command(on_ms, bytes([0xA1, 0x0A]))
    if off_ms: e.command(off_ms, bytes([0xA1, 0x0B]))
    if leave_ms: e.hw["leave"] = {"at": leave_ms, "interval": None, "fire": e.ble_disconnect}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(44000)
    assert e.resets == 2 and e.ota["state"] == 5
    tx = [(ev["t"], bytes.fromhex(ev["data"])) for ev in e.trace if ev["kind"] == "ble_tx" and ev["data"][:2] == "a1"]
    reads = [(ev["t"], bytes.fromhex(ev["rx"])) for ev in e.trace if ev["kind"] == "bus" and ev["dev"] == ringemu.PPG and ev["tx"] == "41"]
    return e, tx, reads


def test_light_samples_arrive_in_order():
    e, tx, reads = light(15000, 40000)
    assert [n[1] for t, n in tx if n[1] != 0x0C] == [0x0A, 0x0B]           # both echoed, no a1 ff
    light_tx = [(t, n) for t, n in tx if n[1] == 0x0C]
    assert all(3 <= n[2] <= 12 and n[2] % 3 == 0 and not any(n[3 + n[2]:15]) for t, n in light_tx)
    assert all(sum(n[:15]) & 0xFF == n[15] for t, n in tx)
    sent = b"".join(n[3:3 + n[2]] for t, n in light_tx)
    fed = b"".join(rx for t, rx in reads if 15000 < t < 40000)
    assert len(sent) >= 3 * 20 * 10 and sent == fed                       # every queued sample, in order, only while on
    assert light_tx[-1][0] < 40000 and any(t > 40000 for t, rx in reads)  # a1 0b stops them, the drain goes on
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings  # the stock algorithm saw the same samples


def test_light_mode_ends_when_the_phone_leaves():
    import patch_fw
    e, tx, reads = light(15000, leave_ms=20000)
    assert any(n[1] == 0x0C and t < 20000 for t, n in tx)
    assert any(t > 20500 for t, rx in reads), "no queue read after the phone left"
    assert e.uc.mem_read(patch_fw.LIGHT, 1)[0] == 0


def test_light_mode_off_keeps_stock_behaviour():
    e, tx, reads = light(None)
    assert reads and not any(n[1] == 0x0C for t, n in tx)
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings


def test_bluetooth_update_restores_stock_firmware(tmp_path):
    import patch_fw
    patched = tmp_path / "patched.bin"
    patched.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, image=patched)      # starts on the patched firmware
    e.ota_start(3000, ringemu.IMAGE.read_bytes())
    e.hw["motion"] = {"at": 11000, "interval": None, "fire": lambda: e.raise_pin(22)}
    e.command(11500, bytes([0xA1, 0x04]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(14000)
    assert e.resets == 2 and e.ota["state"] == 5
    after = [n for n in e.notified if n[0] == 0xA1]
    assert after and not any(n[1] == 5 for n in after), after   # the stream runs, but at the stock 1-a-second rate
