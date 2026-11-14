"""PC probe for the ring over Bluetooth (bleak).  Debug commands need firmware ca0003 or later (patch_fw.py, FW16a).

  python ringprobe.py plan 20 0:03 1:a10f          timed plan: each hex command t s after subscribing, every reply logged
  python ringprobe.py peek 0x20000c 4              a1 10 reads, hex dump
  python ringprobe.py dump 0x0 0x40a00 rom.bin     same into a file, 12 bytes a request; run it again to resume
  python ringprobe.py crash [--clear]              a1 11: boot counter and crash record (--clear: a1 11 01, then read),
                                                   on ca0005 the watchdog experiment's counters (a 7th packet),
                                                   then on ca0004 the watchdog latch and AON 0x15 (a1 12); from ca0006 a
                                                   latch pc at the fault hook's spin = a HardFault, lr = the selected word
  python ringprobe.py aon 0x1c0 12                 a1 12: 16-bit AON registers 0x1c0, 0x1c2, .. (ca0004 or later)
  python ringprobe.py fault-select 12              a1 13: which word the next HardFault leaves in the latch's lr
                                                   (ca0006); 255 reads it back.  Back to 0 at every restart.

Ring address: --addr, else RING_ADDR, else 31:37:42:30:68:07.  RESP=1 writes with response, V2=1 also logs de5bf729.
"""
import argparse, asyncio, collections, os, struct, sys, time

WRITE, NOTIFY = "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
STREAM = {(0xA1, 0x0C): "light", (0xA1, 0x05): "motion"}
REC_MAGIC = 0x48535243
FRAME = {1: ("r0", "r1", "r2", "r3", "r12", "lr", "pc", "xpsr"), 2: ("mode", "reason", "r2", "r3", "r4", "r5", "r6", "r7")}
FAULT_SPIN = 0x83FD28           # patch_fw.FAULT_SPIN (ca0006): the fault hook's b ., latched as pc after a HardFault;
                                # + 2 = its spin for a word of 0 (lr = EXC_RETURN: a latched lr of 0 is lost at boot)
SELECTORS = ("stacked pc", "stacked lr", "stacked r0", "stacked r1", "stacked r2", "stacked r3", "stacked r12",
             "stacked xPSR", "frame address (sp at the fault)", "EXC_RETURN", "task (pxCurrentTCB)", "word at stacked pc - 4",
             "word at stacked pc (the faulting instruction)", "r4", "r5", "r6", "r7", "r8", "r9", "r10", "r11")


def pkt(payload: bytes) -> bytes:
    b = payload.ljust(15, b"\0")
    return b + bytes([sum(b) & 0xFF])


def peek_request(addr: int, n: int) -> bytes:
    return pkt(bytes([0xA1, 0x10]) + struct.pack("<IB", addr, n))


def decode(data: bytes) -> str:
    """a1 11 joined in seq order as text: 72 bytes (seq 0..5), 84 from ca0005 (seq 6: the watchdog experiment)."""
    lines = decode_record(data).split("\n")
    if len(data) >= 84:
        late, standin, longest, run = struct.unpack_from("<2I2H", data, 72)
        lines.append(f"watchdog experiment: late 0x19 ticks {late}  stand-in kicks {standin}  longest gap between app kicks "
                     f"{longest} ticks (~{2 * longest} s)  current run {run}")
    return "\n".join(lines)


def decode_record(data: bytes) -> str:
    boots, stock_hf, reason = struct.unpack_from("<3I", data)
    w = struct.unpack_from("<15I", data, 12)
    lines = [f"boots {boots}  stock HardFault handler {stock_hf:#010x}  reset_reason_get() {reason:#x}"]
    if w[0] != REC_MAGIC:
        return "\n".join(lines + ["no record"])
    check = 0
    for v in w[:14]: check ^= v
    kind = {1: "HardFault", 2: "WDG_SystemReset"}.get(w[1], f"kind {w[1]}")
    lines.append(f"record: {kind}  in boot {w[2]}  task (pxCurrentTCB) {w[3]:#010x}  check {'ok' if check == w[14] else 'BAD'}")
    lines.append("  " + "  ".join(f"{n} {v:#010x}" for n, v in zip(FRAME.get(w[1], [f"w{i}" for i in range(8)]), w[4:12])))
    lines.append(f"  sp {w[12]:#010x}  lr on entry {w[13]:#010x}" + ("  (frame not in RAM: zeros)" if w[1] == 1 and not any(w[4:12]) else ""))
    return "\n".join(lines)


def decode_aon(v: dict) -> str:
    """AON registers 0x14 and 0x1C0..0x1D6 (16-bit values by register) as text."""
    word = lambda lo: v[lo + 2] << 16 | v[lo]
    lines = [f"AON 0x15 (reset-reason byte, re-armed to 1 at boot) {v[0x14] >> 8:#04x}"]
    for name, b in (("watchdog latch", 0x1C0), ("AON watchdog latch", 0x1CC)):
        lines.append(f"{name}: pc {word(b) << 1:#010x}  lr {word(b + 4):#010x}  psr {word(b + 8):#010x}")
    if word(0x1C0) << 1 in (FAULT_SPIN, FAULT_SPIN + 2):
        lr = word(0x1C4)
        lines.append(f"HardFault, word <selector name unknown after reset> = {lr:#010x}" if word(0x1C0) << 1 == FAULT_SPIN else
                     f"HardFault, word <selector name unknown after reset> = 0x00000000  (second spin; lr = EXC_RETURN {lr:#010x})")
        lines.append("  the selector was lost at the reset: it is the one last set with fault-select (0 = stacked pc if"
                     " none since that boot)" + ("; 0xbadf0000 = the frame was not in RAM" if lr == 0xBADF0000 else "")
                     + ("; 0xdead.... = selector 11/12 and the stacked pc not readable (its low 16 bits)" if lr >> 16 == 0xDEAD else ""))
    return "\n".join(lines)


class Ring:
    """One connection; every notification goes to self.rx (a1 05 / a1 0c streams are only counted)."""

    def __init__(self, addr: str):
        self.addr, self.client, self.rx, self.t0 = addr, None, asyncio.Queue(), time.monotonic()
        self.gone, self.counts, self.samples, self.last = asyncio.Event(), collections.Counter(), collections.Counter(), {}
        self.echo = False                   # plan mode: print packets as they arrive instead of queueing them

    def now(self) -> float:
        return time.monotonic() - self.t0

    def on(self, _, d) -> None:
        d = bytes(d)
        k = STREAM.get((d[0], d[1]))
        if not k: return print(f"{self.now():7.2f} <- {d.hex()}") if self.echo else self.rx.put_nowait(d)
        sec = int(self.now())
        self.counts[(k, sec)] += 1
        self.last[k] = self.now()
        if k == "light": self.samples[sec] += d[2] // 3

    async def connect(self) -> None:
        from bleak import BleakClient, BleakScanner
        dev = await BleakScanner.find_device_by_address(self.addr, timeout=20)
        if not dev: raise ConnectionError("ring not found in scan")
        self.gone.clear()
        self.client = BleakClient(dev, disconnected_callback=lambda c: (print(f"{self.now():7.2f} DISCONNECTED"), self.gone.set()))
        await self.client.connect()
        await self.client.start_notify(NOTIFY, self.on)
        if os.environ.get("V2"):
            await self.client.start_notify("de5bf729-d711-4e47-af26-65e3012a5dc7", lambda _, d: print(f"{self.now():7.2f} <-V2 {bytes(d).hex()}"))
        self.t0 = time.monotonic()

    async def close(self) -> None:
        if self.client: await self.client.disconnect()

    async def send(self, data: bytes) -> None:
        await self.client.write_gatt_char(WRITE, data, response=bool(os.environ.get("RESP")))

    async def ask(self, data: bytes, sub: int, count: int = 1, timeout: float = 4, least: int = 0) -> list:
        """Send data, return the next count a1 <sub> packets (other packets are printed and skipped); at least `least`
        of them is enough once the timeout runs out."""
        while not self.rx.empty(): self.rx.get_nowait()
        await self.send(data)
        got, end = [], time.monotonic() + timeout
        while len(got) < count:
            try: d = await asyncio.wait_for(self.rx.get(), max(0.01, end - time.monotonic()))
            except asyncio.TimeoutError:
                if least and len(got) >= least: break
                raise
            if d[:2] == bytes([0xA1, sub]) and sum(d[:15]) & 0xFF == d[15]: got.append(d)
            else: print(f"  (other packet {d.hex()})")
        return got

    async def peek(self, addr: int, n: int) -> bytes:
        d = (await self.ask(peek_request(addr, n), 0x10))[0]
        if d[2] == 0xFF: raise ValueError(f"ring refused {n} bytes at {addr:#x} (not a readable range)")
        if d[2] != n: raise IOError(f"asked {n} bytes, got {d[2]}")
        return d[3:3 + n]

    async def aon(self, reg: int, count: int) -> dict | None:
        """{register: 16-bit value} via a1 12, 6 a request; None if the firmware does not answer a1 12 (before ca0004)."""
        out = {}
        for r in range(reg, reg + 2 * count, 12):
            n = min(6, (reg + 2 * count - r) // 2)
            try: d = (await self.ask(pkt(bytes([0xA1, 0x12]) + struct.pack("<HB", r, n)), 0x12))[0]
            except asyncio.TimeoutError: return None
            if d[2] == 0xFF: raise ValueError(f"ring refused {n} AON registers at {r:#x}")
            out.update(zip(range(r, r + 2 * n, 2), struct.unpack_from(f"<{n}H", d, 3)))
        return out


async def read(ring: Ring, addr: int, length: int, sink, done: int = 0, tries: int = 5) -> None:
    """Read [addr + done, addr + length) in 12-byte requests into sink; reconnects and retries on errors."""
    while done < length:
        n = min(12, length - done)
        for attempt in range(tries):
            try:
                if ring.gone.is_set(): await ring.connect()
                sink(await ring.peek(addr + done, n))
                break
            except ValueError: raise
            except Exception as e:
                print(f"  {addr + done:#x}: {type(e).__name__} {e}, retry {attempt + 1}/{tries}")
                if attempt == tries - 1: raise
                await asyncio.sleep(1)
        done += n
        if done % 0x300 < 12 or done == length: print(f"  {done}/{length} bytes ({100 * done // length}%)", flush=True)


async def plan(ring: Ring, dur: float, steps: list) -> None:
    ring.echo = True
    for t, h in sorted((float(a.split(":")[0]), a.split(":")[1]) for a in steps):
        try: await asyncio.wait_for(ring.gone.wait(), max(0, t - ring.now()))
        except asyncio.TimeoutError: pass
        if ring.gone.is_set(): break
        print(f"{ring.now():7.2f} -> {h}")
        try: await ring.send(pkt(bytes.fromhex(h))); print(f"{ring.now():7.2f}    written")
        except Exception as e: print(f"{ring.now():7.2f} WRITE FAILED {e}"); break
    try: await asyncio.wait_for(ring.gone.wait(), max(0, dur - ring.now()))
    except asyncio.TimeoutError: pass
    for k in ("light", "motion"):
        secs = sorted(s for (kk, s) in ring.counts if kk == k)
        if secs:
            print(f"{k}: {sum(ring.counts[(k, s)] for s in secs)} packets, first {secs[0]} s, last {ring.last[k]:.2f} s"
                  + (f", {sum(ring.samples.values())} samples" if k == "light" else ""))
            print("  per 5 s:", [sum(ring.counts[(k, s)] for s in range(a, a + 5)) for a in range(secs[0], secs[-1] + 1, 5)])
    print(f"{ring.now():7.2f} end, link {'LOST' if ring.gone.is_set() else 'up'}")


async def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--addr", dest="ring", default=os.environ.get("RING_ADDR", "31:37:42:30:68:07"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan"); p.add_argument("seconds", type=float); p.add_argument("steps", nargs="*", metavar="t:hex")
    p = sub.add_parser("peek"); p.add_argument("addr", type=lambda s: int(s, 0)); p.add_argument("len", type=lambda s: int(s, 0))
    p = sub.add_parser("dump"); p.add_argument("addr", type=lambda s: int(s, 0)); p.add_argument("len", type=lambda s: int(s, 0)); p.add_argument("file")
    p = sub.add_parser("crash"); p.add_argument("--clear", action="store_true")
    p = sub.add_parser("aon"); p.add_argument("reg", type=lambda s: int(s, 0)); p.add_argument("count", type=lambda s: int(s, 0))
    p = sub.add_parser("fault-select"); p.add_argument("n", type=lambda s: int(s, 0), choices=range(256), metavar="n")
    a = ap.parse_args()
    ring = Ring(a.ring)
    await ring.connect()
    print(f"connected {time.strftime('%H:%M:%S')}")
    try:
        if a.cmd == "plan": await plan(ring, a.seconds, a.steps)
        elif a.cmd == "peek":
            data = bytearray()
            await read(ring, a.addr, a.len, data.extend)
            for i in range(0, len(data), 16): print(f"{a.addr + i:08x}  {data[i:i + 16].hex(' ')}")
        elif a.cmd == "dump":
            done = os.path.getsize(a.file) if os.path.exists(a.file) else 0
            if done: print(f"resuming at {done} bytes")
            with open(a.file, "ab") as f:
                await read(ring, a.addr, a.len, lambda b: (f.write(b), f.flush()), done)
        elif a.cmd == "aon":
            v = await ring.aon(a.reg, a.count)
            print("a1 12 not supported (firmware before ca0004)" if v is None else "\n".join(f"{r:#05x}  {x:#06x}" for r, x in v.items()))
        elif a.cmd == "fault-select":
            n = (await ring.ask(pkt(bytes([0xA1, 0x13, a.n])), 0x13))[0][2]
            print(f"fault selector {n}: {SELECTORS[n] if n < len(SELECTORS) else 'unknown = stacked pc'}"
                  " (lost at the next restart: note it, crash cannot tell)")
        else:
            got = sorted(await ring.ask(pkt(bytes([0xA1, 0x11, 1 if a.clear else 0])), 0x11, 7, least=6), key=lambda d: d[2])
            assert [d[2] for d in got] == list(range(len(got))), [d[2] for d in got]     # 6 packets before ca0005, 7 from it
            print(decode(b"".join(d[3:15] for d in got)))
            v = await ring.aon(0x1C0, 12)
            print("a1 12 not supported (firmware before ca0004): no watchdog latch" if v is None else
                  decode_aon(v | (await ring.aon(0x14, 1))))
    finally:
        await ring.close()


if __name__ == "__main__":
    asyncio.run(main())
