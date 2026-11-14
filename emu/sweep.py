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


CREDITS, NO_BUF = 0x207660, 0x81EE9A   # real stack: its LE ACL credit count (u16); server_send_data's "no buffer" exit
RETRY = 0x209A31                        # the app's refusals in a row (20 drops its send ring)

def app_blocks(e):
    """Real kernel: every stretch the app task did not run, from writes to pxCurrentTCB.  Per stretch: (ms, what it
    waited on when it left the CPU: event-list container (a queue's waiting list), state-list container, frame pc, lr)."""
    st = {"tcb": None, "out": None, "why": None, "gaps": []}
    def sw(uc, access, addr, size, new, _):
        old = e.r32(0x201340)
        if st["tcb"] is None and e.cstr(new + 0x34) == "app": st["tcb"] = new
        if old == st["tcb"] and new != old:
            sp = e.r32(old)                             # PendSV saved r4-r11 below the exception frame
            st["out"], st["why"] = e.cyc, (e.r32(old + 0x18 + 0x10), e.r32(old + 4 + 0x10), e.r32(sp + 0x38), e.r32(sp + 0x34))
        if new == st["tcb"] and new != old and st["out"] is not None:
            st["gaps"].append(((e.cyc - st["out"]) * 1000 // ringemu.CPU_HZ, e.now) + st["why"])
            st["out"] = None
    e.uc.hook_add(ringemu.UC_HOOK_MEM_WRITE, sw, begin=0x201340, end=0x201343)
    return st


def queue_fill(e):
    """Every post to a real queue (item size > 0): queue -> most messages ever waiting at a post (of its length), and
    the posts that found it full (a full post from TIM5 loses that watchdog kick)."""
    st = {"peak": {}, "full": collections.Counter()}
    def post(uc, addr, size, _):
        q = e.r(ringemu.UC_ARM_REG_R0)
        waiting, length, item = struct.unpack("<3I", e.uc.mem_read(q + 0x38, 12))
        if not item: return
        st["peak"][q] = max(st["peak"].get(q, (0, length))[0], waiting), length
        if waiting >= length: st["full"][q] += 1
    for name in ("xQueueGenericSend", "xQueueGenericSendFromISR"):
        a = e.sym(name)
        e.uc.hook_add(ringemu.UC_HOOK_CODE, post, begin=a, end=a)
    return st


def run(seed, image, verbose=False, ring_heap=False, stack="model"):
    rnd = random.Random(seed)
    e = ringemu.Emu(quiet_mem=True, trace=True, image=image, kernel="real", stack=stack)
    e.flash_erase_ms = rnd.randint(20, 300)
    dirty = rnd.random() < 0.5
    if dirty: e.uc.mem_write(0x878000, bytes(0x1000))             # every log slot "different": the write erases
    sched_end = 77000
    # real stack: the log write lands anywhere in the 30 s stream (and up to 4 s past its stop)
    t0 = sched_end - (rnd.randint(0, 34500) if e.upper else 30500 + rnd.randint(-4000, 4000))
    on = t0 + rnd.randint(300, 3000)
    stalls, t = [], on
    while t < sched_end + 2000:
        if rnd.random() < 0.08:
            stalls.append((t, t + (rnd.randint(500, 5000) if e.upper else rnd.randint(1200, 2500))))
            t = stalls[-1][1]
        t += 500
    kicks = []                                          # the only kicker: the app task on message 0x19 (TIM5, 2 s)
    def kick():
        kicks.append(e.now)
        e.wdg_kick = e.now
    e.rom_WDG_Restart = kick
    if e.upper:         # the phone's side of the link; the stack's own credits, buffers and callbacks are real
        e.link_rate, e.link_stalls = rnd.randint(12, 17), stalls
        blocks, credits, refused, retry, notify = app_blocks(e), [ringemu.LE_ACL_BUFS], [0], [0], collections.Counter()
        queues = queue_fill(e)
        e.uc.hook_add(ringemu.UC_HOOK_CODE, lambda uc, a, s, _: notify.update([e.r(ringemu.UC_ARM_REG_R3)]), begin=0x81EEA6, end=0x81EEA6)
        e.uc.hook_add(ringemu.UC_HOOK_MEM_WRITE, lambda uc, acc, a, size, v, _: retry.__setitem__(0, max(retry[0], v & 0xFF)), begin=RETRY, end=RETRY)
        e.uc.hook_add(ringemu.UC_HOOK_CODE, lambda *a: refused.__setitem__(0, refused[0] + 1), begin=NO_BUF, end=NO_BUF)
        e.uc.hook_add(ringemu.UC_HOOK_MEM_WRITE, lambda uc, acc, a, size, v, _: credits.append(v & 0xFFFF), begin=CREDITS, end=CREDITS + 1)
    else:
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
        e.hw["fill"] = {"at": 2700 if e.upper else 1500, "interval": None, "fire": take}    # the ring read 528 while connected
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
    if e.upper:
        gaps = [g for g in blocks["gaps"] if g[1] > 3000]
        g = max(gaps, default=(0,) * 6)
        ready = max((x[0] for x in gaps if not x[2]), default=0)   # not waiting on any queue: preempted or in a delay
        info += (f"; link {e.link_rate}/s, longest stall {max((b - a for a, b in stalls), default=0)} ms, credits lowest "
                 f"{min(credits)}, no-credit refusals {notify[3]}, no-buffer refusals {refused[0]}, app retries {retry[0]}, app task longest off CPU "
                 f"{g[0]} ms (back at {g[1]}; event list {g[2]:#x}, state list {g[3]:#x}, pc {g[4]:#x}, lr {g[5]:#x}), "
                 f"longest off CPU not waiting on a queue {ready} ms; fullest queues "
                 + ", ".join(f"{q:#x} {w}/{n}" for q, (w, n) in sorted(queues["peak"].items(), key=lambda kv: kv[1][1] - kv[1][0])[:3]))
        if queues["full"]: bad.append("posts to a full queue: " + ", ".join(f"{q:#x} x{n}" for q, n in queues["full"].items()))
        if g[0] > 2100 or ready > 500: bad.append(f"app task off the CPU {g[0]} ms, {ready} ms not on a queue")
        if retry[0] >= 20: bad.append("the app dropped its send ring (20 refusals)")
    if verbose: print(out.getvalue())
    return info, bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--runs", type=int, default=20)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--only", type=int, help="run one seed and print its log")
    ap.add_argument("--ring-heap", action="store_true", help="take the data heap down to the ring's 528 free bytes first")
    ap.add_argument("--stack", default="model", choices=("model", "real"), help="real: the upper stack from firmware/flash_ring.bin")
    a = ap.parse_args()
    img = Path(tempfile.mkdtemp()) / "patched.bin"
    img.write_bytes(patch_fw.patch(ringemu.IMAGE.read_bytes()))
    seeds = [a.only] if a.only is not None else range(a.seed, a.seed + a.runs)
    found = 0
    for s in seeds:
        info, bad = run(s, img, verbose=a.only is not None, ring_heap=a.ring_heap, stack=a.stack)
        print(("FOUND " if bad else "ok    ") + info, flush=True)
        for b in bad: print("      ", b, flush=True)
        found += bool(bad)
    print(f"{found} of {len(seeds)} runs showed something")


if __name__ == "__main__":
    sys.exit(main())
