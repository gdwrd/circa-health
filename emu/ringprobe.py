"""PC probe for the ring over Bluetooth (bleak).  Debug commands need firmware ca0003 or later (patch_fw.py, FW16a).

  python ringprobe.py plan 20 0:03 1:a10f          timed plan: each hex command t s after subscribing, every reply logged
  python ringprobe.py peek 0x20000c 4              a1 10 reads, hex dump
  python ringprobe.py dump 0x0 0x40a00 rom.bin     same into a file, 12 bytes a request; run it again to resume
  python ringprobe.py crash [--clear]              a1 11: boot counter and crash record (--clear: a1 11 01, then read)

Ring address: --addr, else RING_ADDR, else 31:37:42:30:68:07.  RESP=1 writes with response, V2=1 also logs de5bf729.
"""
import argparse, asyncio, collections, os, struct, sys, time

WRITE, NOTIFY = "6e400002-b5a3-f393-e0a9-e50e24dcca9e", "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
STREAM = {(0xA1, 0x0C): "light", (0xA1, 0x05): "motion"}
REC_MAGIC = 0x48535243
FRAME = {1: ("r0", "r1", "r2", "r3", "r12", "lr", "pc", "xpsr"), 2: ("mode", "reason", "r2", "r3", "r4", "r5", "r6", "r7")}


def pkt(payload: bytes) -> bytes:
    b = payload.ljust(15, b"\0")
    return b + bytes([sum(b) & 0xFF])


def peek_request(addr: int, n: int) -> bytes:
    return pkt(bytes([0xA1, 0x10]) + struct.pack("<IB", addr, n))


def decode(data: bytes) -> str:
    """The 72 bytes of a1 11 (seq 0..5 joined) as text."""
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

    async def ask(self, data: bytes, sub: int, count: int = 1, timeout: float = 4) -> list:
        """Send data, return the next count a1 <sub> packets (other packets are printed and skipped)."""
        while not self.rx.empty(): self.rx.get_nowait()
        await self.send(data)
        got, end = [], time.monotonic() + timeout
        while len(got) < count:
            d = await asyncio.wait_for(self.rx.get(), max(0.01, end - time.monotonic()))
            if d[:2] == bytes([0xA1, sub]) and sum(d[:15]) & 0xFF == d[15]: got.append(d)
            else: print(f"  (other packet {d.hex()})")
        return got

    async def peek(self, addr: int, n: int) -> bytes:
        d = (await self.ask(peek_request(addr, n), 0x10))[0]
        if d[2] == 0xFF: raise ValueError(f"ring refused {n} bytes at {addr:#x} (not a readable range)")
        if d[2] != n: raise IOError(f"asked {n} bytes, got {d[2]}")
        return d[3:3 + n]


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
    ap.add_argument("--addr", default=os.environ.get("RING_ADDR", "31:37:42:30:68:07"))
    sub = ap.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("plan"); p.add_argument("seconds", type=float); p.add_argument("steps", nargs="*", metavar="t:hex")
    p = sub.add_parser("peek"); p.add_argument("addr", type=lambda s: int(s, 0)); p.add_argument("len", type=lambda s: int(s, 0))
    p = sub.add_parser("dump"); p.add_argument("addr", type=lambda s: int(s, 0)); p.add_argument("len", type=lambda s: int(s, 0)); p.add_argument("file")
    p = sub.add_parser("crash"); p.add_argument("--clear", action="store_true")
    a = ap.parse_args()
    ring = Ring(a.addr)
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
        else:
            got = sorted(await ring.ask(pkt(bytes([0xA1, 0x11, 1 if a.clear else 0])), 0x11, 6), key=lambda d: d[2])
            assert [d[2] for d in got] == list(range(6)), [d[2] for d in got]
            print(decode(b"".join(d[3:15] for d in got)))
    finally:
        await ring.close()


if __name__ == "__main__":
    asyncio.run(main())
