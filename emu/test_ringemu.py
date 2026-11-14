"""End-to-end check: the real firmware boots, takes a start-heart-rate command and reports the fake pulse.

Run: python -m pytest -q -p no:faulthandler test_ringemu.py
(unicorn trips a harmless, handled access violation on Windows that faulthandler would print.)
"""
import collections, contextlib, io, struct

import pytest

import ringemu

needs_rom = pytest.mark.skipif(not (ringemu.ROM_FILE and ringemu.ROM_FILE.exists()),
                               reason="needs the ROM dump firmware/rom_rtl8762.bin (untracked; FW16b) and RINGEMU_NO_ROM unset")
model_kernel = pytest.mark.skipif(ringemu.KERNEL == "real", reason="drives the modelled scheduler's tasks directly")


@needs_rom
@pytest.mark.parametrize("psp", [0x23CF00, 0x23CEFC])         # 8-byte aligned, and one word off (frame padded, xPSR bit 9)
def test_exception_layer_masks_takes_and_returns(psp):
    # Thread on PSP: enable IRQ 5, pend it with interrupts off, record the count, set Z, cpsie, then beq uses Z.
    # Handler on MSP: count, record its sp, clobber r0, return.  Hand-assembled Thumb (no assembler installed).
    e = ringemu.Emu(quiet_mem=True, kernel="real")
    b, flag = 0x23D000, 0x23D080
    code = [0x2001, 0x2102, 0x2203, 0x2304, 0x4C0C, 0x2520, 0x6025, 0xB672, 0x4C0B, 0x6025, 0x4E0B, 0x6837, 0x6077,
            0x4280, 0xB662, 0xD000, 0xE7FE, 0x6837, 0x60B7, 0xE7FE,                     # thread; 0x20 bad, 0x26 ok
            0xB4C0, 0x4E06, 0x6837, 0x3701, 0x6037, 0x466F, 0x60F7, 0x2063, 0xBCC0, 0x4770]  # handler at 0x28
    e.uc.mem_write(b, struct.pack(f"<{len(code)}H3I", *code, ringemu.SCS + 0x100, ringemu.SCS + 0x200, flag))
    e.w32(0x200000 + 4 * (16 + 5), b + 0x28 | 1)
    e.uc.reg_write(ringemu.UC_ARM_REG_PSP, psp)
    e.uc.reg_write(ringemu.UC_ARM_REG_CONTROL, 2)
    e.uc.reg_write(ringemu.UC_ARM_REG_PC, b)
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(2)
    count, before, after, handler_sp = struct.unpack("<4I", e.uc.mem_read(flag, 16))
    assert (count, before, after) == (1, 0, 1)                      # not while masked; once, right at cpsie
    assert handler_sp == 0x203800 - 8                                # on MSP, below its two pushed registers
    assert e.r(ringemu.UC_ARM_REG_PC) == b + 0x26                    # beq saw Z again: flags came back
    assert [e.r(r) for r in ringemu.REGS[:4]] == [1, 2, 3, 4]        # r0 restored from the frame
    assert e.r(ringemu.UC_ARM_REG_SP) == psp and e.r(ringemu.UC_ARM_REG_IPSR) == 0 and not e.exc_active
    assert e.stats["exception 21"] == 1


@needs_rom
def test_real_kernel_runs_the_measurement():
    # The ROM's FreeRTOS schedules the app: SysTick from the 32 kHz reference at 100 Hz, PendSV switches, TIM5 through
    # the ROM's Timer4/5 dispatcher every 2 s feeding the app's watchdog kick; the pulse comes out as with the model.
    ringemu.PULSE_BPM = 60
    e = ringemu.Emu(quiet_mem=True, kernel="real")
    e.command(3000, bytes([0x69, 0x01]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(30000)
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings
    assert 2700 <= e.stats["exception 15"] <= 3000 and e.stats["exception 14"] > 100 and e.stats["exception 24"] >= 14
    assert e.resets == 1 and e.sched_started                       # only the first-boot config write


@needs_rom
@pytest.mark.parametrize("erase_ms, restarts", [(300, False), (4500, True)])
def test_real_kernel_watchdog_ends_a_long_interrupts_off_window(erase_ms, restarts):
    # A flash erase holds interrupts off (ROM flash lock): the TIM5 kick waits.  4 s without a kick resets the chip
    # and the latch holds where it was.  A real 4 KB erase (tens to a few hundred ms) stays well inside.
    e = ringemu.Emu(quiet_mem=True, trace=True, kernel="real")
    e.hw["erase"] = {"at": 5000, "interval": None, "fire": lambda: setattr(e, "flash_erase_ms", erase_ms)}
    e.hw["dirty"] = {"at": 80000, "interval": None, "fire": lambda: e.uc.mem_write(0x8780E0, bytes(0x20))}    # slot rewrite: erase
    e.command(5500, bytes.fromhex("01250120060400") + b"\x01")
    e.command(5700, bytes.fromhex("16020105"))
    import cmdmap
    e.hw["rtc"] = {"at": 65500, "interval": 60000, "fire": cmdmap.minute_tick(e), "keep": True}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(100000)
    fired = [ev for ev in e.trace if ev["text"].startswith("WATCHDOG")]
    assert any(ev["text"].startswith("flash erase 0x878000") for ev in e.trace)
    assert bool(fired) == restarts and e.resets == 1 + restarts


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
    credits: notifications the link takes per 500 ms from on_ms (default: all), or a function of the time giving it.
    e.waiting: most packets ever waiting in the firmware's send ring."""
    import patch_fw
    ringemu.PULSE_BPM = 60
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.ota_start(3000, patch_fw.patch(ringemu.IMAGE.read_bytes()))
    stock_call, sent, e.waiting = e.rom_SystemCall_Stack, collections.Counter(), 0
    def slow_link():                                    # the stack refuses a notify once this interval's credits are used
        if credits and e.arg(0) == 0x3108 and e.now > on_ms and not e.services[e.arg(2) - 1]["uuid"].startswith(ringemu.OTA_UUID):
            if sent[e.now // 500] >= (credits(e.now) if callable(credits) else credits):
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
            (0x43F40, 12),                                                   # the last 12 bytes of the ROM's .data image
            (0x43F44, 9), (0x880000, 1), (0x215FFC, 8), (0x40000000, 4), (0xE000ED00, 4), (0xFFFFFFFC, 8), (0x824400, 0), (0x824400, 13)]
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
    assert got[3][2:7] == bytes([4]) + bytes(e.uc.mem_read(0, 4))         # the ROM dump, or the emulator's filler
    assert got[4][2:15] == bytes([12]) + bytes(e.uc.mem_read(0x43F40, 12))
    assert all(n[2] == 0xFF and not any(n[3:15]) for n in got[5:])          # refused: past ROM, flash and RAM, peripherals, wrap, len 0 and 13


def aon_reads(e, at_ms, asks):
    """Schedule a1 12 <reg> <count> requests 200 ms apart from at_ms."""
    for i, (reg, n) in enumerate(asks): e.command(at_ms + 200 * i, bytes([0xA1, 0x12]) + struct.pack("<HB", reg, n))


def test_aon_read_answers_the_registers_and_refuses_the_rest(tmp_path):
    import patch_fw
    patched = tmp_path / "patched.bin"
    patched.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, trace=True, image=patched)
    e.hw["set"] = {"at": 9000, "interval": None, "fire": lambda: e.aon.update({0x100: 0xBEEF, 0x1DE: 0x1234})}
    asks = [(0x1C0, 6), (0x1CC, 6), (0x14, 1), (0x100, 1), (0x1DE, 1),
            (0x1E0, 1), (0x1DA, 4), (0x15, 1), (0x14, 0), (0x14, 7), (0xFFFE, 1)]    # past 0x1DE, odd, count 0 and 7, wrap
    aon_reads(e, 10000, asks)
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(13000)
    got = [n for n in e.notified if n[:2] == bytes([0xA1, 0x12])]
    assert len(got) == len(asks) and all(sum(n[:15]) & 0xFF == n[15] for n in got)
    for n, (reg, k) in zip(got[:5], asks):
        assert n[2] == k and list(struct.unpack_from(f"<{k}H", n, 3)) == [e.aon.get(r, 0) for r in range(reg, reg + 2 * k, 2)]
    assert got[3][3:5] == bytes.fromhex("efbe") and got[4][3:5] == bytes.fromhex("3412")
    assert all(n[2] == 0xFF and not any(n[3:15]) for n in got[5:])
    assert e.resets == 1                                # only the first-boot config write
    pc = (e.aon[0x1C2] << 17) | (e.aon[0x1C0] << 1)
    assert pc == 0x12B08                                # its WDG_SystemReset spin, latched and kept through the boot


@model_kernel
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
    assert e.resets == 4 and e.ota["state"] == 5        # update, the fault (its spin ended by the watchdog), 08
    got = [n for n in e.notified if n[:2] == bytes([0xA1, 0x11])]
    assert [n[2] for n in got] == list(range(6)) * 4 and all(sum(n[:15]) & 0xFF == n[15] for n in got)
    reads = [b"".join(n[3:15] for n in got[i:i + 6]) for i in range(0, 24, 6)]
    first, fault, cleared, reset = [struct.unpack("<18I", d[:72]) for d in reads]
    assert first[0] == 1 and first[1] == e.hardfault_vec and first[3] == 0     # boot 1, the stock handler kept, no record
    hang = next(ev["t"] for ev in e.trace if ev["text"].startswith("HANG (HardFault: b ."))
    assert 13000 <= hang < next(ev["t"] for ev in e.trace if ev["text"].startswith("CHIP RESET #3")) <= hang + ringemu.WDG_TIMEOUT_MS
    w = fault[3:]
    assert fault[0] == 2 and w[:4] == (patch_fw.REC_MAGIC, 1, 1, 0x20F000)                # boot 2; a HardFault in boot 1
    assert w[4:8] == (0x11, 0x22, 0x33, 0x44) and w[10:12] == (0x10000000, 1 << 24) and w[12:14] == (0x215F00 - 32, 0xFFFFFFFD)
    assert "check ok" in ringprobe.decode(reads[1])
    assert cleared[0] == 2 and not any(cleared[3:]) and "no record" in ringprobe.decode(reads[2])
    wdg = next(ev["text"] for ev in e.trace if ev["text"].startswith("WDG_SystemReset(") and ev["t"] >= 22000)
    w = reset[3:]
    assert reset[0] == 3 and w[:3] == (patch_fw.REC_MAGIC, 2, 2)                          # a reset in boot 2
    assert [w[4], w[5]] == [int(x, 16) for x in wdg[16:-1].split(", ")[:2]] and "check ok" in ringprobe.decode(reads[3])
    if e.rom:   # reset reasons, word +12 of each read: the fault no longer goes through WDG_SystemReset(3, 5)
        assert [first[2], fault[2], reset[2]] == [0xD3, 1, 0xD3]           # end of the update, the watchdog, 08


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


@pytest.mark.skipif(ringemu.KERNEL == "real", reason="compares a stock and a patched run byte for byte; under the real kernel the patch's extra cycles move the samples")
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


def heap(e):
    """(data free, data lowest-ever free, data total) from the ROM's heap words."""
    w = struct.unpack("<6I", e.uc.mem_read(ringemu.HEAP_WORDS, 24))
    return w[0], w[2], w[4]


def leave_free(e, at_ms, free, buffer_free=None):
    """At at_ms take the data heap down to `free` bytes (and the buffer heap to `buffer_free`), standing in for what
    the ring's closed code holds."""
    def fill():
        e.call(e.sym("pvPortMalloc"), 0, heap(e)[0] - free - 8)
        if buffer_free is not None:
            e.call(e.sym("pvPortMalloc"), 1, 8)                     # the buffer heap starts on first use
            e.call(e.sym("pvPortMalloc"), 1, e.r32(ringemu.HEAP_WORDS + 4) - buffer_free - 8)
    e.hw["fill"] = {"at": at_ms, "interval": None, "fire": fill}


@needs_rom
def test_rom_heap_holds_through_the_light_restart_scenario():
    # The ring (FW16b, idle): data heap 528 free of 29,688, lowest ever 448.  Here: 69 01, a1 0a, a link taking 12-16
    # notifications a second with stalls of 1.2 to 2.5 s, the stock stop at 42 s, 30 s idle, from the ring's 528.
    stalls = ((20000, 21500), (27000, 29000), (35000, 36200), (40500, 43000))
    credits = lambda now: 0 if any(a <= now < b for a, b in stalls) else 6 + now // 500 % 3
    seen, lowest = [], {}
    orig_boot = ringemu.Emu.boot
    def boot(e):
        leave_free(e, 11400, 528)
        e.hw["heap"] = {"at": 11500, "interval": 250, "fire": lambda: seen.append(heap(e))}
        seen.clear()
        orig_boot(e)
    try:
        ringemu.Emu.boot = boot
        e, tx, reads = light(12000, credits=credits, ms=72000)
    finally:
        ringemu.Emu.boot = orig_boot
    assert seen[0][2] == 0x73F8                                            # same total as the ring
    assert sum(n[1] == 0x0C for t, n in tx) > 200                          # the stream ran
    assert all(f == 528 for f, low, tot in seen[-40:])                     # back to idle after the stop
    assert min(low for f, low, tot in seen) >= 528 - 48                    # the app holds at most two 24-byte blocks
    assert not any(ev["kind"] == "heap" or "HANG" in ev["text"] for ev in e.trace)


@needs_rom
def test_rom_heap_exhaustion_hangs_until_the_watchdog():
    # What the ROM does when an allocation fails: os_mem_alloc(data) tries the data heap, then the buffer heap; when
    # both are full, 0x11124 -> vAssertHandler, which the stock SystemInit turned on (0x200378 |= 0x26): interrupts
    # off, spin.  The chip goes silent; only a watchdog restarts it.
    e = ringemu.Emu(quiet_mem=True, trace=True)
    e.command(3000, bytes([0x69, 0x01]))
    leave_free(e, 2900, 0, 0)                           # the app's 12-byte blocks need 24
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(12000)
    fail = next(ev["t"] for ev in e.trace if ev["kind"] == "heap")
    hang = next(ev["t"] for ev in e.trace if ev["text"].startswith("HANG (ROM assert)"))
    reset = next(ev["t"] for ev in e.trace if ev["text"].startswith("CHIP RESET #2"))
    assert 3000 <= fail == hang and hang < reset <= hang + ringemu.WDG_TIMEOUT_MS     # 4 s after the last kick (real kernel: before the hang)
    assert not any(ev["kind"] == "ble_tx" and hang < ev["t"] < reset for ev in e.trace)    # silent while hung
    assert e.call(e.sym("reset_reason_get")) == 1       # no WDG_SystemReset: the default the ROM set at boot


@needs_rom
def test_rom_watchdog_latch_shows_the_hang_after_the_restart(tmp_path):
    # ca0004 on a ring that hangs (heap exhausted -> ROM assert spin, interrupts off) and is restarted by its watchdog: the
    # phone's a1 12 after the restart reads the hang's pc from the latch; the stock boot left it alone (its self-test runs
    # only while the latched lr is 0).
    import patch_fw, ringprobe
    patched = tmp_path / "patched.bin"
    patched.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, trace=True, image=patched)
    e.command(3000, bytes([0x69, 0x01]))
    leave_free(e, 2900, 0, 0)
    aon_reads(e, 10000, [(0x1C0, 6), (0x1CC, 6), (0x14, 1)])
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(11000)
    hang = next(ev["t"] for ev in e.trace if ev["text"].startswith("HANG (ROM assert)"))
    assert e.resets == 2 and hang < next(ev["t"] for ev in e.trace if ev["text"].startswith("CHIP RESET #2")) <= hang + ringemu.WDG_TIMEOUT_MS
    assert not any(ev["text"].startswith("WDG_Config(0x1, 0x0, 0x0") and ev["t"] > hang for ev in e.trace)   # no self-test
    got = [n for n in e.notified if n[:2] == bytes([0xA1, 0x12])]
    v = {}
    for n, reg in zip(got, (0x1C0, 0x1CC, 0x14)): v.update(zip(range(reg, reg + 2 * n[2], 2), struct.unpack_from(f"<{n[2]}H", n, 3)))
    text = ringprobe.decode_aon(v)
    assert "watchdog latch: pc 0x00008d38" in text      # the ROM's assert spin
    assert "AON 0x15 (reset-reason byte, re-armed to 1 at boot) 0x01" in text


def latch(e):
    """The watchdog latch as the AON registers hold it: (pc, lr)."""
    return e.aon[0x1C2] << 17 | e.aon[0x1C0] << 1, e.aon[0x1C6] << 16 | e.aon[0x1C4]


CODE = 0x215E00                 # chip RAM (a1 10 reads it): a task's code that faults on its first instruction
LOAD = 0x6808                   # ldr r0, [r1]  with r1 = 0x30000000: nothing is mapped there
FRAME_MSP = 0x215C00            # a hand-made exception frame in chip RAM


def fault_case(case):
    """(task setup, expected latch lr) for a fault from the modelled scheduler; the frame sp - 32 = 0x215EE0."""
    import patch_fw
    task = {"pc": 0x10001234, "lr": 0x0824ABCD, "sp": 0x215F00, "regs": {}}
    sel, expect = case
    if expect in ("ldr", "before"):     # the faulting instruction's bytes (ldr, nop), or the two nops before it
        task["pc"] = CODE
    return sel, task, {"pc": 0x10001234, "lr": 0x0824ABCD, "sp": 0x215F00 - 32, "exc": 0xFFFFFFFD, "tcb": 0x20F000,
                       "r4": 0x44444444, "r11": 0xBBBBBBBB, "xpsr": 1 << 24, "dead": patch_fw.UNREADABLE | 0x1234,
                       "ldr": LOAD | 0xBF00 << 16, "before": 0xBF00BF00, "bad": patch_fw.BAD_FRAME, "zero": 0}.get(expect, expect)


@model_kernel
@pytest.mark.parametrize("case", [(0, "pc"), (1, "lr"), (8, "sp"), (9, "exc"), (10, "tcb"), (13, "r4"), (20, "r11"),
                                  (7, "xpsr"), (12, "dead"), (12, "ldr"), (11, "before"), (99, "pc"), (0, "bad"),
                                  (8, 0x23F000 - 32), (0, "msp"), (8, "msp"), (15, "zero")])
def test_fault_hook_leaves_the_selected_word_in_the_latch(tmp_path, case):
    # ca0006: a1 13 picks the word; a task faults (thread mode, PSP; or the handler entered on MSP with a hand-made frame);
    # the hook records, spins, the watchdog resets the chip 4 s later, and a1 12 after the boot reads pc = the spin and
    # lr = the word.  The selector echoes, reads back, and is 0 again after the restart.  One fault only: the hook never
    # faults itself, also for an unreadable pc (selector 12) or a frame outside the chip's RAM.  A word of 0 (r6 here)
    # spins at FAULT_SPIN + 2 with lr = EXC_RETURN: a latched lr of 0 would make the boot's self-test overwrite the latch.
    import patch_fw, ringprobe
    img = tmp_path / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    sel, task, expect = fault_case(case)
    e = ringemu.Emu(quiet_mem=True, trace=True, image=img)
    msp = expect == "msp"
    if msp: expect = 0x08246789 if sel == 0 else FRAME_MSP
    elif case[1] == 0x23F000 - 32 or case[1] == "bad": task["sp"] = 0x23F000      # emulator RAM, past the chip's 88 KB
    def crash():
        e.uc.mem_write(patch_fw.CURRENT_TCB, struct.pack("<I", 0x20F000))
        e.uc.mem_write(CODE - 4, struct.pack("<4H", 0xBF00, 0xBF00, LOAD, 0xBF00))        # nop, nop, ldr, nop
        if msp:                 # entry as for a fault in handler mode: frame on MSP, lr = 0xFFFFFFF1
            e.uc.mem_write(FRAME_MSP, struct.pack("<8I", 1, 2, 3, 4, 12, 0x0824ABCD, 0x08246789, 1 << 24))
            e.spawn("HardFault", e.r32(0x20000C))
            e.tasks[-1].ctx.update({ringemu.UC_ARM_REG_SP: FRAME_MSP, ringemu.UC_ARM_REG_LR: 0xFFFFFFF1})
            return
        e.spawn("crash", task["pc"], (0x11, 0x30000000, 0x33, 0x44))
        e.tasks[-1].ctx.update({ringemu.UC_ARM_REG_SP: task["sp"], ringemu.UC_ARM_REG_LR: task["lr"],
                                ringemu.UC_ARM_REG_R4: 0x44444444, ringemu.UC_ARM_REG_R11: 0xBBBBBBBB})
    e.command(10000, bytes([0xA1, 0x13, sel]))
    e.command(10200, bytes([0xA1, 0x13, 0xFF]))
    e.hw["crash"] = {"at": 11000, "interval": None, "fire": crash}
    e.command(19000, bytes([0xA1, 0x13, 0xFF]))
    aon_reads(e, 19200, [(0x1C0, 6), (0x1CC, 6), (0x14, 1)])
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(20000)
    hang = next(ev["t"] for ev in e.trace if ev["text"].startswith("HANG (HardFault: b ."))
    assert e.resets == 2 and hang + ringemu.WDG_TIMEOUT_MS == next(ev["t"] for ev in e.trace if ev["text"].startswith("CHIP RESET #2"))
    assert sum(ev["text"].startswith("CRASH") for ev in e.trace) == (0 if msp else 1)       # no second fault
    sels = [n[2] for n in e.notified if n[:2] == bytes([0xA1, 0x13])]
    assert sels == [sel, sel, 0]
    zero = expect == 0
    assert latch(e) == ((patch_fw.FAULT_SPIN + 2, 0xFFFFFFFD) if zero else (patch_fw.FAULT_SPIN, expect)), [hex(v) for v in latch(e)]
    rec = struct.unpack("<15I", e.uc.mem_read(patch_fw.REC, 60))      # the RAM record is still written (lost on the ring)
    assert rec[:2] == (patch_fw.REC_MAGIC, 1) and rec[13] == (0xFFFFFFF1 if msp else 0xFFFFFFFD)
    got = [n for n in e.notified if n[:2] == bytes([0xA1, 0x12])]
    v = {}
    for n, reg in zip(got, (0x1C0, 0x1CC, 0x14)): v.update(zip(range(reg, reg + 2 * n[2], 2), struct.unpack_from(f"<{n[2]}H", n, 3)))
    assert f"HardFault, word <selector name unknown after reset> = {expect:#010x}" in ringprobe.decode_aon(v)
    assert not any(ev["text"].startswith("WDG_Config(0x1, 0x0, 0x0") and ev["t"] > hang for ev in e.trace)   # no self-test
    assert ringprobe.FAULT_SPIN == patch_fw.FAULT_SPIN


BUSY = 0x23D000                 # spare emulator RAM (cleared at a reset): test code, its flag at BUSY + 0x800


@needs_rom
@pytest.mark.parametrize("where, sel", [("task", 0), ("task", 1), ("irq", 0), ("irq", 8), ("irq", 9)])
def test_fault_hook_real_kernel_thread_psp_and_nested_msp(tmp_path, where, sel):
    # Real kernel: a priority-3 task (thread mode, PSP) or an interrupt handler (IRQ 5, handler mode: the frame on MSP)
    # loads from an unmapped address.  The hook spins in handler mode; nothing kicks; the watchdog resets the chip and
    # latches pc = the spin, lr = the selected word: the faulting pc, the task's lr (the busy loop's bl return), the MSP
    # frame address (as the record's sp), EXC_RETURN 0xFFFFFFF1 (nested).
    import patch_fw
    img = tmp_path / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, trace=True, image=img, kernel="real")
    code = patch_fw.asm(f"""
    loop:
        ldr   r0, =0x{BUSY + 0x800:x}
        ldr   r0, [r0]
        cmp   r0, #0
        bne   fault
        movs  r0, #10
        bl    0x{e.sym("vTaskDelay"):x}
        b     loop
    fault:
        ldr   r1, =0x30000000
        ldr   r0, [r1]
        b     loop
    """, BUSY)
    handler = patch_fw.asm("ldr r1, =0x30000000\nldr r0, [r1]\nbx lr", BUSY + 0x100)
    boot = e.boot
    def task_boot():
        boot()
        e.uc.mem_write(BUSY, code)
        e.uc.mem_write(BUSY + 0x100, handler)
        e.uc.mem_write(BUSY + 0x7E0, b"busy\0")
        assert e.call(e.sym("xTaskCreate"), BUSY | 1, BUSY + 0x7E0, 128, 0, 3, BUSY + 0x7F0) == 1
    def fire():
        if where == "task": return e.w32(BUSY + 0x800, 1)
        e.w32(0x200000 + 4 * (16 + 5), BUSY + 0x100 | 1)
        e.nv_en |= 1 << 5
        e.pend(16 + 5)
    e.boot = task_boot
    e.command(10000, bytes([0xA1, 0x13, sel]))
    e.hw["fault"] = {"at": 11000, "interval": None, "fire": fire}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(17000)
    fired = [ev["t"] for ev in e.trace if ev["text"].startswith("WATCHDOG")]
    assert len(fired) == 1 and 11000 < fired[0] <= 11000 + ringemu.WDG_TIMEOUT_MS and e.resets == 2
    assert any(ev["text"].startswith("HANG (HardFault: b .") for ev in e.trace)
    assert sum(ev["kind"] == "fault" for ev in e.trace) == 1             # the hook did not fault
    rec = struct.unpack("<15I", e.uc.mem_read(patch_fw.REC, 60))
    pc_at = BUSY + 0x102 if where == "irq" else BUSY + code.index(bytes.fromhex("0868"))
    assert rec[:2] == (patch_fw.REC_MAGIC, 1) and rec[10] == pc_at
    assert rec[13] == (0xFFFFFFF1 if where == "irq" else 0xFFFFFFFD)
    assert (0x203000 <= rec[12] < 0x203800) == (where == "irq")         # the frame on MSP (main stack) or a task stack
    expect = {0: pc_at, 1: rec[9], 8: rec[12], 9: rec[13]}[sel]
    assert latch(e) == (patch_fw.FAULT_SPIN, expect), [hex(v) for v in latch(e)]


def test_patched_normal_run_kicks_as_stock(tmp_path):
    # ca0006, 60 s with two live measurements: no restart, no fault, one watchdog kick per TIM5 tick (the app task's, as
    # stock: FW16f's handler kicks are gone); a1 11 answers 6 packets; the fault selector reads 0.
    import patch_fw
    img = tmp_path / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    ringemu.PULSE_BPM = 60
    e = ringemu.Emu(quiet_mem=True, trace=True, image=img)
    e.command(3000, bytes([0x69, 0x01]))
    e.command(40000, bytes([0x69, 0x01]))
    e.command(60000, bytes([0xA1, 0x11]))
    e.command(60200, bytes([0xA1, 0x13, 0xFF]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(61000)
    readings = [n[3] for n in e.notified if n[0] == 0x69 and n[3]]
    assert readings and all(abs(r - 60) <= 2 for r in readings), readings
    assert e.resets == 1 and not any(ev["text"].startswith(("HANG", "CRASH", "FAULT")) for ev in e.trace)
    assert 28 <= e.stats["WDG_Restart"] <= 31
    assert [n[2] for n in e.notified if n[:2] == bytes([0xA1, 0x11])] == list(range(6))
    assert [n[2] for n in e.notified if n[:2] == bytes([0xA1, 0x13])] == [0]


@needs_rom
@pytest.mark.parametrize("patched", [True, False])
def test_starved_app_task_restarts_patched_as_stock(tmp_path, patched):
    # The app task held off the CPU for 10 s by a priority-3 busy task (real kernel): no kick for 4 s, the watchdog
    # restarts the ring at about 24 s, stock and ca0006 alike (ca0005's stand-in kicks carried it through; removed).
    import patch_fw
    img = tmp_path / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()) if patched else ringemu.IMAGE.read_bytes())
    e = ringemu.Emu(quiet_mem=True, trace=True, image=img, kernel="real")
    code = patch_fw.asm(f"""
    loop:
        ldr   r0, =0x{BUSY + 0x800:x}
        ldr   r0, [r0]
        cmp   r0, #0
        bne   spin
        movs  r0, #10
        bl    0x{e.sym("vTaskDelay"):x}
        b     loop
    spin:
    """ + "    nop\n" * 480 + "    b     loop\n", BUSY)       # long blocks: the emulator's cost is per block
    boot = e.boot
    def busy_boot():
        boot()
        e.uc.mem_write(BUSY, code)
        e.uc.mem_write(BUSY + 0x7E0, b"busy\0")
        assert e.call(e.sym("xTaskCreate"), BUSY | 1, BUSY + 0x7E0, 128, 0, 3, BUSY + 0x7F0) == 1
    e.boot = busy_boot
    e.hw["busy"] = {"at": 20000, "interval": None, "fire": lambda: e.w32(BUSY + 0x800, 1)}
    e.hw["free"] = {"at": 30000, "interval": None, "fire": lambda: e.w32(BUSY + 0x800, 0)}
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(26000)
    fired = [ev["t"] for ev in e.trace if ev["text"].startswith("WATCHDOG")]
    assert fired and 23000 <= fired[0] <= 24100 and e.resets == 2
    assert BUSY <= latch(e)[0] < BUSY + 0x400                      # the latch: where the CPU was kept busy


needs_flash = pytest.mark.skipif(not (ringemu.FLASH_FILE and ringemu.FLASH_FILE.exists() and ringemu.ROM_FILE and ringemu.ROM_FILE.exists()),
                                 reason="needs the ROM dump and the ring's flash dump firmware/flash_ring.bin (both untracked)")


@needs_flash
def test_real_upper_stack_answers_over_hci():
    # The upper stack from the ring's flash runs as UpperStackTask (priority 5 from the ROM config) and brings the
    # controller up over the ROM's hci_if; the phone connects, finds the services over ATT, and the 03 answer comes back
    # as a GATT notification through the stack's queues, credits and ACL.
    e = ringemu.Emu(quiet_mem=True, trace=True, kernel="real", stack="real")
    sent, write = [], e.rom_hci_if_write
    e.rom_hci_if_write = lambda: sent.append(bytes(e.uc.mem_read(e.arg(0), e.arg(1)))) or write()
    e.command(3000, bytes([0x03]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(5000)
    ops = [struct.unpack_from("<H", p, 1)[0] for p in sent if p[0] == 1]
    assert ops[:3] == [0x1009, 0x1002, 0x1005] and 0x2002 in ops and ops.index(0x200A) < ops.index(0x2016)   # advertising, then the link
    assert {"6e40fff0", ringemu.OTA_UUID} <= {s["uuid"][:8] for s in e.services}
    assert e.notified[-1][:2] == bytes([0x03, 0x00]) and e.resets == 1
    tcb = e.r32(0x207564)
    assert e.cstr(tcb + 0x34) == "UpperStac" and e.r32(tcb + 0x2C) == 5
    assert struct.unpack("<H", e.uc.mem_read(0x207660, 2))[0] == ringemu.LE_ACL_BUFS      # every ACL credit back


@needs_flash
def test_real_upper_stack_holds_a_stalled_light_stream(tmp_path):
    # Light stream at 12 notifications a second, then 4.5 s with no traffic.  The controller's 10 buffers fill, the stack's
    # 10 notification credits (0x207784) run out and server_send_data refuses (exit 3, not a missing buffer); the app
    # retries (os_delay 20) without reaching its 20-refusal drop and stays clear of the 4 s watchdog; the samples that
    # arrive come in order once the link is back (the patch's light copy skips samples rather than fill the send ring).
    import patch_fw, sweep
    img = tmp_path / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, trace=True, image=img, kernel="real", stack="real")
    e.link_rate, e.link_stalls = 12, ((15000, 19500),)
    blocks, credits, retry = sweep.app_blocks(e), [], [0]
    e.uc.hook_add(ringemu.UC_HOOK_MEM_WRITE, lambda uc, a, ad, s, v, _: credits.append((e.now, v & 0xFFFF)), begin=sweep.CREDITS, end=sweep.CREDITS + 1)
    e.uc.hook_add(ringemu.UC_HOOK_MEM_WRITE, lambda uc, a, ad, s, v, _: retry.__setitem__(0, max(retry[0], v & 0xFF)), begin=sweep.RETRY, end=sweep.RETRY)
    e.uc.hook_add(ringemu.UC_HOOK_CODE, lambda *a: pytest.fail("server_send_data refused"), begin=sweep.NO_BUF, end=sweep.NO_BUF)
    e.command(3000, bytes([0x69, 0x01]))
    e.command(4000, bytes([0xA1, 0x0A]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(36000)
    light = [(ev["t"], bytes.fromhex(ev["data"])) for ev in e.trace if ev["kind"] == "ble_tx" and ev["data"][:4] == "a10c"]
    reads = b"".join(bytes.fromhex(ev["rx"]) for ev in e.trace if ev["kind"] == "bus" and ev["dev"] == ringemu.PPG and ev["tx"] == "41" and ev["t"] > 4000)
    sent = b"".join(n[3:3 + n[2]] for t, n in light)
    it = iter(reads[i:i + 3] for i in range(0, len(reads), 3))
    assert len(sent) > 1500 and all(any(s == r for r in it) for s in (sent[i:i + 3] for i in range(0, len(sent), 3)))   # in order
    assert not any(15000 <= t < 19500 for t, n in light) and light[-1][0] > 19500
    assert min(v for t, v in credits if t > 4000) == 0 and credits[-1][1] == ringemu.LE_ACL_BUFS
    assert 0 < retry[0] < 20
    assert max(g[0] for g in blocks["gaps"] if g[1] > 3000) < 2100 and e.resets == 1


@needs_rom
def test_rom_lower_stack_answers_hci():
    # The ROM's reset handler runs the Bluetooth lower stack's whole init (three more register models, rombootprobe.py);
    # then the upper stack's HCI init goes through the real hci_if and low_stack_task's dispatcher, and the ROM's own HCI
    # layer gives the answers the emulator's controller gives in stack mode (ringemu.ROM_HCI; LE buffers 251 x 10).
    import rombootprobe
    b = rombootprobe.boot()
    assert b.pc == rombootprobe.OS_SCHED_START and ("low_stack_task", 0x1460B, 0xC00 // 4, 6) in b.tasks
    assert sum((s + 15) & ~7 for t, s, at in b.allocs if t == 1 and at >= 0x14000) == 10184     # its buffer-heap blocks
    got = dict(rombootprobe.hci(b))
    for op, evs in got.items():     # one Command Complete each, status 0
        assert len(evs) == 1 and evs[0][:2] == b"\x04\x0e" and struct.unpack_from("<HB", evs[0], 4) == (op, 0), hex(op)
    assert {op: got[op][0][7:] for op in ringemu.ROM_HCI} == ringemu.ROM_HCI


@needs_rom
def test_rom_lower_stack_spins_with_interrupts_masked():
    # Read off the code, run here with the hardware held busy: the bzdma command write on the packet TX path (0x152FE)
    # waits for its busy bit under os_lock with no bound; the radio port (0x226A2) gives up after 1,000,000 polls, all
    # masked (about 0.6 s at 40 MHz); the baseband ISR loops while an unmasked status bit it has no handler for stays
    # set; a baseband write while the MAC sleeps only asks for the wake-up and does not wait.
    import rombootprobe as rb
    b = rb.boot()
    back, masked, primask = rb.spin(b, 0x152FE, (0, 0), 0x40058460, 0x400)
    assert not back and primask and masked > 10_000_000
    back, masked, primask = rb.spin(b, 0x226A2, (0x10, 0), 0x40051244, 0x8000)
    assert back and 20_000_000 < masked < 30_000_000 and not primask
    assert not rb.spin(b, 0x35C, (), 0x400500BE, 1)[0]
    assert rb.spin(b, 0x225CC, (0xDA, 0x100), 0x400500BE, 0, asleep=True)[0]


@needs_flash
@pytest.mark.parametrize("hold_ms, resets", [(600, 1), (4500, 2)])
def test_lower_stack_masked_hold_at_a_connection_event(tmp_path, hold_ms, resets):
    # What the lower stack's code can do (docs/FIRMWARE.md "Lower stack"): mask interrupts at a connection event, about
    # 0.6 s per radio-port timeout, without a bound in a bzdma or ISR spin.  One timeout is survived; a hold past the
    # watchdog resets the patched image too (sweep.py --hold).
    import patch_fw
    img = tmp_path / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    e = ringemu.Emu(quiet_mem=True, trace=True, image=img, kernel="real", stack="real")
    done, stock = [], e.conn_event
    def conn_event():
        if not done and e.now >= 8000:
            done.append(e.now)
            e.busy(hold_ms)
        return stock()
    e.conn_event = conn_event
    e.command(3000, bytes([0x03]))
    e.boot()
    with contextlib.redirect_stdout(io.StringIO()):
        e.run(16000)
    assert done and e.resets == resets
    assert any("WATCHDOG" in ev["text"] for ev in e.trace) == (resets == 2)


@needs_flash
def test_rom_patch_ram_part_is_encrypted_in_flash():
    # The ROM loads the patch's RAM part through its AES engine (ROM 0x5082; key select 1: a key derived from the efuse
    # page at flash 0x800095 / 0x8000A7, which reads as erased once the ring runs).  The dump holds the ciphertext, so
    # the hooks that point into it (heap, timers, queues, reset) cannot run here.
    import math
    f = ringemu.FLASH_FILE.read_bytes()
    _, _, flags, image_id, _, _ = struct.unpack_from("<BBHHHI", f, 0x3000)
    exe, load, n = struct.unpack_from("<3I", f, 0x301C)
    assert (image_id, flags & 2, flags >> 4 & 7, exe, load & 0xFFFFFF, n) == (0x2792, 2, 1, 0x203800, 0x809404, 0x3510)
    ent = lambda b: -sum(c / len(b) * math.log2(c / len(b)) for c in collections.Counter(b).values())
    assert ent(f[0x9404:0x9404 + n]) > 7.9 and ent(f[0x3400:0x9404]) < 7.2       # ciphertext; the flash part is plain code
    assert f[0x95:0xB8] == b"\xff" * 0x23


@needs_flash
@pytest.mark.skipif(not ringemu.PATCH_RAM.exists(), reason="needs firmware/patch_ram.bin, read off a running ring: "
                    "python ringprobe.py dump 0x203800 0x3c00 firmware/patch_ram.bin")
def test_rom_patch_hooks_are_installed():
    # With the decrypted RAM part the ROM's patch entry (0x203801) runs as the ROM runs it and fills the hook pointers.
    e = ringemu.Emu(quiet_mem=True, kernel="real", stack="real")
    hooks = {0x20173C: 0x205C35, 0x201644: 0x205C01, 0x201574: 0x20503D, 0x2015FC: 0x205BD9}    # pvPortMalloc, os_timer_create, WDG_SystemReset, queue create
    assert {p: e.r32(p) for p in hooks} == hooks
