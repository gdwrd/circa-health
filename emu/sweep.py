"""Seeded sweep of the light-stream restart scenario under the real kernel (needs the ROM dump).

Per run (seed = run number + --seed): the patched image boots; the clock is set to 06:04:00 with the HR log every
5 min, so a scheduled measurement starts at the 06:05 minute tick (~62 s) and ends ~15 s later with its flash log
write; 69 01 is timed so its stock stop (30.5 s) lands within +-4 s of that end, a1 0a 0.3..3 s after it; the link
takes 12..16 notifications a second with random stalls of 1.2..2.5 s; a flash erase holds interrupts off 20..300 ms;
half the runs start with the HR log sector dirty (so the log write erases); 30 s idle; then the phone asks for the
battery level, which must be answered.  Reported per run: anything other than the first-boot reset, watchdog fires,
hangs, faults, dropped queue posts, timer-pool growth, no answer.

  python sweep.py --runs 300            RINGEMU_KERNEL is forced to "real"
  python sweep.py --only 17             one seed, with its event log printed
"""
import argparse, collections, contextlib, io, os, random, struct, sys, tempfile, time
from pathlib import Path

import ringemu, cmdmap, patch_fw

SET_0604 = bytes.fromhex("01250120060400") + b"\x01"     # cmdmap.SET_TIME at 06:04:00


def run(seed, image, verbose=False, ring_heap=False):
    rnd = random.Random(seed)
    e = ringemu.Emu(quiet_mem=True, trace=True, image=image, kernel="real")
    e.flash_erase_ms = rnd.randint(20, 300)
    dirty = rnd.random() < 0.5
    if dirty: e.uc.mem_write(0x878000, bytes(0x1000))             # every log slot "different": the write erases
    sched_end = 77000
    t0 = sched_end - 30500 + rnd.randint(-4000, 4000)
    on = t0 + rnd.randint(300, 3000)
    stalls, t = [], on
    while t < sched_end + 2000:
        if rnd.random() < 0.08:
            stalls.append((t, t + rnd.randint(1200, 2500)))
            t = stalls[-1][1]
        t += 500
    rate = {w: rnd.randint(6, 8) for w in range(0, 200)}
    sent = collections.Counter()
    stock = e.rom_SystemCall_Stack
    def link():
        if e.arg(0) == 0x3108 and e.now > on:
            w = e.now // 500
            if any(a <= e.now < b for a, b in stalls) or sent[w] >= rate[w % 200]:
                e.uc.mem_write(e.arg(7), bytes(1))
                return 0
            sent[w] += 1
        return stock()
    e.rom_SystemCall_Stack = link
    kicks = []                                          # the only kicker: the app task on message 0x19 (TIM5, 2 s)
    def kick():
        kicks.append(e.now)
        e.wdg_kick = e.now
    e.rom_WDG_Restart = kick
    e.hw["credits"] = {"at": on, "interval": 500, "fire": lambda: e.spawn("ble:done", 0x82A5CE | 1), "keep": True}
    e.command(2000, SET_0604)
    e.command(2500, bytes.fromhex("16020105"))
    e.hw["rtc"] = {"at": 62000, "interval": 60000, "fire": cmdmap.minute_tick(e), "keep": True}
    e.command(t0, bytes([0x69, 0x01]))
    e.command(on, bytes([0xA1, 0x0A]))
    end = sched_end + 30000
    e.command(end, bytes([0x03]))
    timers = []
    e.hw["timers"] = {"at": 10000, "interval": 5000, "fire": lambda: timers.append(struct.unpack("<HH", e.uc.mem_read(0x201474, 4))), "keep": True}
    if ring_heap:       # the ring's idle data heap: 528 free (the upper stack's allocations are not emulated)
        take = lambda: e.spawn("fill", e.sym("pvPortMalloc"), (0, e.r32(ringemu.HEAP_WORDS) - 528 - 8))
        e.hw["fill"] = {"at": 1500, "interval": None, "fire": take}
    e.boot()
    out = io.StringIO()
    t = time.time()
    with contextlib.redirect_stdout(out): e.run(end + 3000)
    bad = [f"{ev['t']}ms {ev['text'][:110]}" for ev in e.trace
           if ev["kind"] in ("reset", "fault") and not ev["text"].startswith(("CHIP RESET #1 ", "flash erase"))
           or "queue" in ev["text"] and "full" in ev["text"]]
    live = [ev["t"] for ev in e.trace if ev["kind"] == "ble_tx" and ev["data"][:2] == "03" and ev["t"] >= end]
    if not live: bad.append("no answer to 03 at the end: command path dead")
    alive = [b - a for a, b in timers]
    if alive and alive[-1] > alive[0] + 2: bad.append(f"timer pool grows: live {alive[0]} -> {alive[-1]}")
    gap = max((b - a for a, b in zip(kicks, kicks[1:])), default=0)
    if gap > 3000: bad.append(f"watchdog kick gap {gap} ms (4 s resets)")
    light = sum(1 for ev in e.trace if ev["kind"] == "ble_tx" and ev["data"][:4] == "a10c")
    erases = sum(1 for ev in e.trace if ev["text"].startswith("flash erase"))
    free, low = struct.unpack("<I4xI", e.uc.mem_read(ringemu.HEAP_WORDS, 12))
    info = f"seed {seed}: heap free {free} lowest {low}, 69 01 at {t0}, a1 0a {on}, erase {e.flash_erase_ms} ms x{erases}, dirty {dirty}, stalls {len(stalls)}, kick gap {gap} ms, light {light}, resets {e.resets}, {time.time() - t:.1f} s"
    if verbose: print(out.getvalue())
    return info, bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", type=int, default=20)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--only", type=int, help="run one seed and print its log")
    ap.add_argument("--ring-heap", action="store_true", help="take the data heap down to the ring's 528 free bytes first")
    a = ap.parse_args()
    img = Path(tempfile.mkdtemp()) / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    seeds = [a.only] if a.only is not None else range(a.seed, a.seed + a.runs)
    found = 0
    for s in seeds:
        info, bad = run(s, img, verbose=a.only is not None, ring_heap=a.ring_heap)
        print(("FOUND " if bad else "ok    ") + info, flush=True)
        for b in bad: print("      ", b, flush=True)
        found += bool(bad)
    print(f"{found} of {len(seeds)} runs showed something")


if __name__ == "__main__":
    sys.exit(main())
