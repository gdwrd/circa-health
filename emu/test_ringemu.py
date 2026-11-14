"""End-to-end check: the real firmware boots, takes a start-heart-rate command and reports the fake pulse.

Run: python -m pytest -q -p no:faulthandler test_ringemu.py
(unicorn trips a harmless, handled access violation on Windows that faulthandler would print.)
"""
import collections, contextlib, io, struct

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


def light(on_ms, off_ms=None, leave_ms=None, credits=None, cmds=(), ms=44000):
    """Patched over Bluetooth, live heart rate from 11.5 s, a1 0a at on_ms -> (emu, a1 packets with times, queue reads).
    credits: notifications the link takes per 500 ms from on_ms (default: all).  e.waiting: most packets ever waiting
    in the firmware's send ring."""
    import patch_fw
    ringemu.PULSE_BPM = 60
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    stock_call, sent, e.waiting = e.rom_SystemCall_Stack, collections.Counter(), 0
    def slow_link():                                    # the stack refuses a notify once this interval's credits are used
        if credits and e.arg(0) == 0x3108 and e.now > on_ms and not e.services[e.arg(2) - 1]["uuid"].startswith(ringemu.OTA_UUID):
            if sent[e.now // 500] >= credits:
                e.uc.mem_write(e.arg(7), bytes(1))
                return 0
            sent[e.now // 500] += 1
        return stock_call()
    e.rom_SystemCall_Stack = slow_link
    if credits:     # credits back each interval: the stack's "send data complete" makes the app callback 0x829970 call 0x82A5CE
        e.hw["credits"] = {"at": on_ms, "interval": 500, "fire": lambda: e.spawn("ble:done", 0x82A5CE | 1), "keep": True}
    def queued(uc, addr, size, _):                      # QUEUE_PACKET entry
        r, w = struct.unpack("<HH", uc.mem_read(patch_fw.TX_RING, 4))
        e.waiting = max(e.waiting, (w - r) % 128)
    e.uc.hook_add(ringemu.UC_HOOK_CODE, queued, begin=patch_fw.QUEUE_PACKET, end=patch_fw.QUEUE_PACKET)
    e.command(11500, bytes([0x69, 0x01]))
    if on_ms: e.command(on_ms, bytes([0xA1, 0x0A]))
    if off_ms: e.command(off_ms, bytes([0xA1, 0x0B]))
    if leave_ms: e.hw["leave"] = {"at": leave_ms, "interval": None, "fire": e.ble_disconnect}
    for t, cmd in cmds: e.command(t, cmd)
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(ms)
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


def test_light_samples_leave_room_on_a_slow_link():
    # circaA restarted the real ring about 30 s into a light recording (2026-11-13); not reproduced here.  What is: on a
    # link slower than the samples, the copy filled the stock send ring (128 slots, no full check) and overwrote unsent
    # packets, the stock 69 readings with them.
    e, tx, reads = light(15000, credits=2)              # 4 notifications a second; light alone makes about 10
    assert e.waiting < 127                              # the ring never wrapped onto unsent packets
    assert sum(n[1] == 0x0C for t, n in tx) > 40
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings


def test_light_copy_ends_with_the_measurement():
    # ca0001 on the real ring: the stock stop 30.5 s after 69 01 with the copy still on killed the ring ~8 s later.
    e, tx, reads = light(15000, cmds=[(46000, bytes([0x69, 0x01])), (48000, bytes([0xA1, 0x0A]))], ms=50000)
    light_tx = [t for t, n in tx if n[1] == 0x0C]
    assert any(t < 42000 for t in light_tx)
    assert any(46000 < t < 48000 for t, rx in reads)                      # measuring again, copy off: no a1 0c
    assert not [t for t in light_tx if 42000 <= t < 48000], light_tx[-5:]
    assert any(t > 48000 for t in light_tx)                              # a1 0a turns it back on
    assert [n[1] for t, n in tx if n[1] != 0x0C] == [0x0A, 0x0A]           # both echoed


def test_light_mode_off_keeps_stock_behaviour():
    e, tx, reads = light(None)
    assert reads and not any(n[1] == 0x0C for t, n in tx)
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings


def test_memory_read_answers_rom_ram_and_flash_and_refuses_the_rest():
    import patch_fw
    img = patch_fw.patch(ringemu.IMAGE.read_bytes())
    asks = [(0x824400, 12), (0x200040, 4), (0x20000C, 4), (0x0, 4),          # flash, RAM (vector slots 16 and 3), ROM
            (0x880000, 1), (0x215FFC, 8), (0x40000000, 4), (0xE000ED00, 4), (0xFFFFFFFC, 8), (0x824400, 0), (0x824400, 13)]
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.ota_start(3000, img)
    for i, (a, n) in enumerate(asks): e.command(12000 + 200 * i, bytes([0xA1, 0x10]) + struct.pack("<IB", a, n))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(16000)
    assert e.resets == 2 and e.ota["state"] == 5 and not any(ev["text"].startswith("CRASH") for ev in e.trace)
    got = [n for n in e.notified if n[:2] == bytes([0xA1, 0x10])]
    assert len(got) == len(asks) and all(sum(n[:15]) & 0xFF == n[15] for n in got)
    assert got[0][2:15] == bytes([12]) + img[0x450:0x45C]                     # the image's bytes
    assert got[1][2:7] == bytes([4]) + struct.pack("<I", 0x82B7C5)          # RAM: the app's handler in slot 16
    hook = struct.unpack("<I", got[2][3:7])[0]                               # slot 3 holds the fault hook (appended code)
    assert got[2][2] == 4 and hook == e.r32(0x20000C) and len(ringemu.IMAGE.read_bytes()) < hook - 0x824000 + 0x50 < len(img)
    assert got[3][2:7] == bytes([4]) + bytes.fromhex("70477047")           # the emulator's ROM filler
    assert all(n[2] == 0xFF and not any(n[3:15]) for n in got[4:])          # refused: past flash and RAM, peripherals, wrap, len 0 and 13


def test_crash_record_survives_the_restart():
    import patch_fw, ringprobe
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    def crash():                                        # a task jumps to where nothing is; its stack in the chip's RAM
        e.uc.mem_write(patch_fw.CURRENT_TCB, struct.pack("<I", 0x20F000))
        e.spawn("crash", 0x10000000, (0x11, 0x22, 0x33, 0x44))
        e.tasks[-1].ctx[ringemu.UC_ARM_REG_SP] = 0x215F00
    e.command(12000, bytes([0xA1, 0x11]))
    e.hw["crash"] = {"at": 13000, "interval": None, "fire": crash}
    e.command(20000, bytes([0xA1, 0x11]))
    e.command(21000, bytes([0xA1, 0x11, 0x01]))
    e.command(22000, bytes([0x08]))                     # stock: restarts the ring through WDG_SystemReset
    e.command(30000, bytes([0xA1, 0x11]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(32000)
    assert e.resets == 4 and e.ota["state"] == 5        # update, the fault, 08
    got = [n for n in e.notified if n[:2] == bytes([0xA1, 0x11])]
    assert [n[2] for n in got] == list(range(6)) * 4 and all(sum(n[:15]) & 0xFF == n[15] for n in got)
    reads = [b"".join(n[3:15] for n in got[i:i + 6]) for i in range(0, 24, 6)]
    first, fault, cleared, reset = [struct.unpack("<18I", d) for d in reads]
    assert first[0] == 1 and first[1] == ringemu.ROM_HARDFAULT | 1 and first[3] == 0     # boot 1, chains to the stock handler, no record
    w = fault[3:]
    assert fault[0] == 2 and w[:4] == (patch_fw.REC_MAGIC, 1, 1, 0x20F000)                # boot 2; a HardFault in boot 1
    assert w[4:8] == (0x11, 0x22, 0x33, 0x44) and w[10:12] == (0x10000000, 1 << 24) and w[12:14] == (0x215F00 - 32, 0xFFFFFFFD)
    assert "check ok" in ringprobe.decode(reads[1])
    assert cleared[0] == 2 and not any(cleared[3:]) and "no record" in ringprobe.decode(reads[2])
    wdg = next(ev["text"] for ev in e.trace if ev["text"].startswith("WDG_SystemReset(") and ev["t"] >= 22000)
    w = reset[3:]
    assert reset[0] == 3 and w[:3] == (patch_fw.REC_MAGIC, 2, 2)                          # a reset in boot 2
    assert [w[4], w[5]] == [int(x, 16) for x in wdg[16:-1].split(", ")[:2]] and "check ok" in ringprobe.decode(reads[3])


def on_and_off(image, reads, off_ms=605000, on_ms=750000):
    """Image installed over Bluetooth, clock set to 06:00 at 12 s, HR log every 5 min, minute ticks, a1 0f at reads;
    a calibrated ring (four offsets of 100) on the finger, taken off at off_ms, back on at on_ms.  The optical chip's
    reflection registers are faked (FW15a): 19500 on the finger, -200 off; its light queue is the stock fake."""
    import cmdmap
    e = ringemu.Emu(quiet_mem=True, trace=True)
    skin = {"on": True}
    def chan(base):
        if base == 0xC8: return 20000 if skin["on"] else 300
        if base == 0xC5: return 500
        if base == 0xBF and not e.uc.mem_read(0x20D5C9, 1)[0]: return chan(0xC8)   # once equal, which enables the reading
        return 400
    stock_ppg = e.ppg_read
    def ppg(st, v):
        reg = st["ptr"]
        if 0xBC <= reg <= 0xCA and not (st["tx"] and st["tx"][0] == 0x41):
            base = 0xBC + (reg - 0xBC) // 3 * 3
            return (chan(base) << 4) >> 8 * (reg - base) & (0x0F if reg - base == 2 else 0xFF)
        return stock_ppg(st, v)
    e.ppg_read = ppg
    cal = lambda uc, a, size, _: uc.mem_write(0x2086B8, bytes([100, 0] * 4))    # after 0x83286C copies the stored one
    e.uc.hook_add(ringemu.UC_HOOK_CODE, cal, begin=0x832870, end=0x832870)
    e.hw["off"] = {"at": off_ms, "interval": None, "fire": lambda: skin.update(on=False)}
    e.hw["on"] = {"at": on_ms, "interval": None, "fire": lambda: skin.update(on=True)}
    e.ota_start(3000, image)
    e.command(12000, cmdmap.SET_TIME)
    e.command(13000, bytes.fromhex("16020105"))
    for t in reads: e.command(t, bytes([0xA1, 0x0F]))
    e.hw["rtc"] = {"at": 60000, "interval": 60000, "fire": cmdmap.minute_tick(e), "keep": True}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(1010000)
    assert e.resets == 2 and e.ota["state"] == 5
    return e


def test_wear_state_and_when_it_changed():
    import patch_fw
    ringemu.PULSE_BPM = 60
    six = 790668000                                     # cmdmap.SET_TIME, 2025-01-20 06:00:00, in seconds since 2000
    reads = [30000, 350000, 355000, 640000, 645000, 1000000, 1005000]
    e = on_and_off(patch_fw.patch(ringemu.IMAGE.read_bytes()), reads)
    got = [bytes(n) for n in e.notified if n[:2] == bytes([0xA1, 0x0F])]
    assert len(got) == len(reads) and all(sum(n[:15]) & 0xFF == n[15] and not any(n[7:15]) for n in got)
    state = [(n[2], int.from_bytes(n[3:7], "little")) for n in got]
    assert state[0] == (0, 0)                           # no verdict yet: unknown, not "worn"
    assert state[1] == (1, six + 300)                   # first measurement (06:05) on the finger
    assert state[3][0] == 2 and 0 <= 605 - (state[3][1] - six) < 60        # off at 605 s, the minute it happened
    assert state[5][0] == 1 and 0 < state[5][1] - six - 750 < 60           # back on at 750 s, the next check found it
    assert state[1] == state[2] and state[3] == state[4] and state[5] == state[6]   # repeated reads, same answer
    stock = on_and_off(ringemu.IMAGE.read_bytes(), reads)
    assert [n for n in stock.notified if n[0] == 0xA1] == [bytes([0xA1, 0xFF]) + bytes(13) + b"\xa0"] * len(reads)
    log = lambda em: bytes(em.uc.mem_read(0x878000, 0x100))
    assert log(e) == log(stock) and e.uc.mem_read(0x8780E1, 1)[0] == 60     # the hook only observes
    assert [n for n in e.notified if n[0] != 0xA1] == [n for n in stock.notified if n[0] != 0xA1]


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
