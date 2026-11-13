"""FW3: command map and flash map of the stock RY02 firmware, in the emulator only.

  python cmdmap.py          prints docs/FIRMWARE.md's tables as Markdown

1. Runs the command dispatcher at 0x8286DA alone for every first byte 0..255 to see which bytes it compares
   against (any byte whose path does not reach the default "unknown command" call).
2. Boots the stock image, connects the fake phone, sets the clock, saves a snapshot; then sends every command byte
   and every A1 sub-type, each from the restored snapshot, so a reset or power-off cannot disturb the next one.
3. From the same snapshot: a Bluetooth update with the stock image, then a simulated day of fake activity.
   Every flash erase / write / read and every ftl_save is logged throughout.
"""
import contextlib, copy, io, sys, time
from unicorn import UC_HOOK_CODE, UC_HOOK_MEM_READ
from unicorn.arm_const import UC_ARM_REG_LR, UC_ARM_REG_PC, UC_ARM_REG_R0, UC_ARM_REG_SP
import ringemu
from ringemu import APP_BASE, EXIT, OTA_TMP, OTA_TMP_SIZE

DISPATCH, BODY_END = 0x8286DA, 0x8289BE         # dispatcher function body
DEFAULT, IGNORE = 0x828804, 0x82872E            # "bl 0x827f66" (answers cmd|0x80, EE) and a silent return
SWITCH = (0x837EC8, 0x837EE2)                   # compiler switch-table helper the dispatcher calls
FLASH, FLASH_END, SECTOR = 0x800000, 0x880000, 0x1000
APP_END = APP_BASE + len(ringemu.IMAGE.read_bytes()) - ringemu.OTA_HDR
REGIONS = ((0x200000, 0x40000), (FLASH, FLASH_END - FLASH), (0x40000000, 0x100000), (0xE0000000, 0x100000))
SKIP = {"uc", "syms", "ram_syms", "periph"}
SET_TIME = bytes.fromhex("01250120060000") + b"\x01"   # 2025-01-20 06:00:00, BCD, language byte 1
DAY_H = 24
WAIT_MS = 3000                                  # how long each command's replies are collected


def dispatch_paths() -> dict[int, str]:
    """First byte -> where the dispatcher sends it: a handler address, 'ignored', 'inline' or 'default'."""
    e = ringemu.Emu(quiet_mem=True)
    buf, stack = e.alloc(16), e.alloc(0x400)
    out = {}
    for b in range(256):
        hit = []
        def hook(uc, a: int, size: int, _) -> None:
            if a == IGNORE: hit.append("ignored")
            if a == DEFAULT or not (DISPATCH <= a < BODY_END or SWITCH[0] <= a < SWITCH[1]):
                hit.append("default" if a == DEFAULT else f"{a:#x}")
                uc.emu_stop()
        h = e.uc.hook_add(UC_HOOK_CODE, hook, begin=APP_BASE, end=FLASH_END - 1)
        e.uc.mem_write(buf, bytes([b]) + bytes(15))
        for reg, v in ((UC_ARM_REG_R0, buf), (UC_ARM_REG_SP, stack + 0x400), (UC_ARM_REG_LR, EXIT | 1)):
            e.uc.reg_write(reg, v)
        with contextlib.redirect_stdout(io.StringIO()):
            e.uc.emu_start(DISPATCH | 1, EXIT, count=2000)
        e.uc.hook_del(h)
        out[b] = hit[-1] if hit else "inline"
    return out


class Lab:
    def __init__(self) -> None:
        self.e = e = ringemu.Emu(quiet_mem=True)
        self.phase, self.log, self.ftl = "boot", [], []
        for name, kind in (("flash_erase_locked", "erase"), ("flash_write_locked", "write"),
                           ("flash_auto_write_locked", "write"), ("flash_read_locked", "read"),
                           ("flash_split_read_locked", "read"), ("flash_auto_read_locked", "read")):
            setattr(e, "rom_" + name, self.wrap(getattr(e, "rom_" + name), kind))
        orig_save = e.rom_ftl_save
        def ftl_save() -> int:
            self.ftl.append((e.arg(1), e.arg(2), e.r(UC_ARM_REG_LR) & ~1, self.phase))
            return orig_save()
        e.rom_ftl_save = ftl_save
        def xip(uc, access: int, a: int, size: int, value: int, _) -> None:   # the CPU reading flash directly
            self.log.append(("read", a, size, e.r(UC_ARM_REG_PC), self.phase))
        e.uc.hook_add(UC_HOOK_MEM_READ, xip, begin=FLASH, end=APP_BASE - 1)
        e.uc.hook_add(UC_HOOK_MEM_READ, xip, begin=APP_END, end=FLASH_END - 1)

    def wrap(self, orig, kind: str):
        e = self.e
        def call() -> int:
            if kind == "erase": a, n = e.arg(1) & ~0xFFF, SECTOR
            elif "auto" in orig.__name__: a, n = e.arg(0), 4
            else: a, n = e.arg(0), e.arg(1)
            self.log.append((kind, a, n, e.r(UC_ARM_REG_LR) & ~1, self.phase))
            return orig()
        return call

    def run(self, ms: int) -> str:
        """Run ms of virtual time; returns the emulator's printed events."""
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.e.run(self.e.now + ms)
        return out.getvalue()

    def snap(self) -> tuple:
        e = self.e
        return ([bytes(e.uc.mem_read(b, n)) for b, n in REGIONS],
                copy.deepcopy({k: v for k, v in vars(e).items() if k not in SKIP and not k.startswith("rom_")}))

    def restore(self, s: tuple) -> None:
        e = self.e
        for (b, n), m in zip(REGIONS, s[0]): e.uc.mem_write(b, m)
        e.uc.ctl_remove_cache(APP_BASE, FLASH_END)     # an update may have replaced code
        vars(e).update(copy.deepcopy(s[1]))

    def probe(self, s: tuple, payload: bytes) -> tuple[list[bytes], str]:
        """From snapshot s, send one command; return the notifications it caused and what the emulator saw."""
        self.restore(s)
        self.phase = "cmd " + payload.hex().upper()
        n0 = len(self.e.notified)
        self.e.command(self.e.now + 1, payload)
        text = self.run(WAIT_MS)
        notes = [w for w, k in (("chip reset", "CHIP RESET"), ("crash", "CRASH"), ("stuck", "STUCK"),
                                ("all tasks idle", "idle:")) if k in text]
        return self.e.notified[n0:], ", ".join(notes)


def minute_tick(e: ringemu.Emu):
    """The firmware arms RTC COMP0 for +60 s and keeps its clock in that interrupt; the emulator raises no RTC
    interrupts, so fire it every 60 s: status bit 0x100 (COMP0, tested at 0x833B18), handler in vector slot 29."""
    def fire() -> None:
        e.w32(0x40000108, 0x100)
        e.irq("RTC", 29)
    return fire


def reply_cell(pkts: list[bytes]) -> str:
    if not pkts: return "no reply"
    more = f" (+{len(pkts) - 1} more)" if len(pkts) > 1 else ""
    return f"`{pkts[0].hex(' ')}`{more}"


def hexrange(lo: int, hi: int) -> str:
    return f"{lo:02X}" if lo == hi else f"{lo:02X}..{hi:02X}"


def main() -> None:
    t0 = time.time()
    paths = dispatch_paths()
    lab = Lab()
    e = lab.e
    e.boot()
    e.command(2000, SET_TIME)
    lab.run(3000)
    base = lab.snap()

    cmd_rows, default_odd = [], []
    for b in range(256):
        pkts, note = lab.probe(base, bytes([b]))
        if paths[b] == "default":
            if pkts[:1] != [bytes([b | 0x80, 0xEE]) + bytes(13) + bytes([(b | 0x80) + 0xEE & 0xFF])] or len(pkts) > 1 or note:
                default_odd.append(b)       # replied unlike the default handler: list it on its own row
            else: continue
        cmd_rows.append((f"{b:02X}", paths[b], reply_cell(pkts), note))
    n_default = sum(p == "default" for p in paths.values()) - len(default_odd)

    a1 = [lab.probe(base, bytes([0xA1, s])) for s in range(256)]
    a1_rows, s = [], 0
    while s < 256:                                  # collapse neighbouring sub-types with the same answer
        cell = (reply_cell(a1[s][0]), a1[s][1])
        end = s
        while end + 1 < 256 and (reply_cell(a1[end + 1][0]), a1[end + 1][1]) == cell: end += 1
        a1_rows.append((hexrange(s, end), *cell))
        s = end + 1

    lab.restore(base)
    lab.phase = "update"
    e.ota_start(e.now + 1, ringemu.IMAGE.read_bytes())
    lab.run(12000)
    update_ok = e.ota and e.ota["state"] == 5

    lab.restore(base)
    lab.phase = "day"
    start = e.now
    e.command(start + 1000, bytes.fromhex("16020105"))     # heart-rate log on, every 5 minutes
    for m in range(10, DAY_H * 60, 10):                    # accelerometer interrupt every 10 minutes
        e.hw[f"move{m}"] = {"at": start + m * 60000, "interval": None, "fire": lambda: e.raise_pin(22)}
    e.hw["rtc"] = {"at": start + 60000, "interval": 60000, "fire": minute_tick(e)}
    day_text = lab.run(DAY_H * 3600_000)
    hours = (e.now - start) / 3600_000

    # ---- command tables
    print(f"### Command bytes ({len(cmd_rows)} rows; {n_default} other bytes take the default path)\n")
    print("| Byte | Dispatcher path | Reply (first packet) | Emulator note |\n|---|---|---|---|")
    for r in cmd_rows: print("| " + " | ".join(r) + " |")
    print(f"| the other {n_default} | default (`0x827F66`) | `<byte with bit 7 set> ee 00 .. <checksum>`, checked for each | |")
    print(f"\n### `A1` sub-types (all 256 sent)\n")
    print("| Sub-type | Reply (first packet) | Emulator note |\n|---|---|---|")
    for r in a1_rows: print("| " + " | ".join(r) + " |")

    # ---- flash
    ops = {}
    for kind, a, n, who, ph in lab.log:
        for sec in range(a & ~(SECTOR - 1), a + max(n, 1), SECTOR):
            k = ops.setdefault(sec, {}).setdefault(kind, (set(), set()))
            k[0].add(ph); k[1].add(who)
    def phases(ph: set[str]) -> str:
        cmds = sorted(p[4:] for p in ph if p.startswith("cmd "))
        return ", ".join(sorted(p for p in ph if not p.startswith("cmd ")) + (["cmd " + " ".join(cmds)] if cmds else []))
    def user(sec: int) -> str:
        seen = ops.get(sec)
        if seen:
            return "; ".join(f"{k}: {phases(ph)}; from {' '.join(f'{w:#x}' for w in sorted(who))}"
                             for k, (ph, who) in sorted(seen.items()))
        if APP_BASE <= sec < APP_END: return "stock app image (code)"
        if OTA_TMP <= sec < OTA_TMP + OTA_TMP_SIZE: return "update staging bank (end is a guess); not touched"
        return f"unknown (not written in {hours:.0f} simulated hours)"
    rows, free = [], []
    for sec in range(FLASH, FLASH_END, SECTOR):
        u = user(sec)
        if rows and rows[-1][2] == u: rows[-1][1] = sec + SECTOR
        else: rows.append([sec, sec + SECTOR, u])
        blank = bytes(e.uc.mem_read(sec, SECTOR)) == b"\xff" * SECTOR
        if u.startswith("unknown") and blank:
            if free and free[-1][1] == sec: free[-1][1] = sec + SECTOR
            else: free.append([sec, sec + SECTOR])
    print(f"\n### Flash regions (4 KB sectors, `0x800000`..`0x880000`)\n")
    print("| Start | End | Size | User (operation and phase, emulator) |\n|---|---|---|---|")
    for lo, hi, u in rows: print(f"| `{lo:#x}` | `{hi:#x}` | {(hi - lo) // 1024} KB | {u} |")
    print(f"\n### `ftl_save` calls\n")
    print("| Offset | Bytes | Caller (return address) | Phases |\n|---|---|---|---|")
    saves = {}
    for off, n, who, ph in lab.ftl: saves.setdefault((off, n, who), set()).add(ph)
    for (off, n, who), ph in sorted(saves.items()):
        print(f"| `{off:#x}` | {n} | `{who:#x}` | {phases(ph)} |")
    print(f"\n### Never read, written or erased, and blank (apparently free)\n")
    for lo, hi in free: print(f"- `{lo:#x}`..`{hi:#x}`: {(hi - lo) // 1024} KB")
    day_w = [(a, n) for kind, a, n, _, ph in lab.log if ph == "day" and kind == "write"]
    print(f"\nSimulated day: {hours:.1f} h, {len(day_w)} flash writes, {sum(n for _, n in day_w)} bytes, "
          f"{sum(1 for k, *_, ph in lab.log if ph == 'day' and k == 'erase')} erases; update with the stock image completed: {bool(update_ok)}; "
          f"day phase notes: {', '.join(k for k in ('CHIP RESET', 'CRASH', 'STUCK') if k in day_text) or 'none'}; "
          f"wall time {time.time() - t0:.0f} s.")

    assert all(r[2] for r in cmd_rows) and all(r[1] for r in a1_rows)
    assert all(u for _, _, u in rows)


if __name__ == "__main__":
    sys.exit(main())
