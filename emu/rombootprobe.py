"""Runs the ROM's own reset handler (vector 0x4EFF) from firmware/rom_rtl8762.bin through the Bluetooth lower stack's
init to os_sched_start, and then, on request, the lower stack's HCI path (docs/FIRMWARE.md "Lower stack").

Register models, each read off the loop that polls it:
  0x40000338 bit 25   ready flag polled at 0xB6F8 (clock / flash delay block)
  0x40007004 bit 1    cache flush done, polled at 0x6B7A after setting bit 0
  0x40011014 0x60     log UART transmitter empty (UART_GetFlagState 0x11B6C); the text written to 0x40011024 is kept
  0x4000621C bit 0    random generator ready (get_true_random_number, 0x12C1C)
  0x40058F00          indirect 16-bit register port (index bits 21:16, bit 22 = write): register 0x11 bits 13:12 and
                      register 0 bit 15 clear = the power-ready wait at 0xBB86 / 0xC14C
  0x40051074          Bluetooth MAC indirect port: a command completes by flipping bit 15 (writes wait for clear at
                      0x22A70, reads for set at 0x22986)
  0x40051244          radio/modem indirect port (BB ops 0x200B8C +0x14 read 0x225E6, +0x18 write 0x226A2): index | 0x8000
                      with data in 0x40051246/48 writes, the index alone reads; completion flips bit 15 as above (both poll
                      it under os_lock, up to 1,000,000 times).  A register file: a read returns what was written, else 0
  0x40058328          bzdma init (0x14AB6, poll at 0x14EF0, no bound): start bits 0/1 read back as done in bits 8/9
  OTP->stack_en       0x2002DC bit 0, set at os_init as ringemu presets it and the ring boots: without it the ROM brings
                      up the HCI-over-UART controller (UART0 buffers; waits on the UART at 0x172B8)
Not loaded: flash images (blank flash plus the stock app at 0x824000), so the ROM patch and the app never run and the heap
keeps the ROM's default layout.  Past os_sched_start the probe has no exception layer: --hci calls low_stack_task's
dispatcher (0x3204E) by hand, as the task does after its semaphore, so no interrupt or timer runs there.

  python rombootprobe.py [instructions]      the boot, default 60,000,000
  python rombootprobe.py --hci               then the upper stack's HCI init sequence through hci_if
  python rombootprobe.py --spin              each unbounded or masked poll site with its hardware held busy
"""
import collections, re, struct, sys
from unicorn import Uc, UcError, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_BLOCK, UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_MEM_WRITE
from unicorn.arm_const import UC_ARM_REG_PRIMASK, UC_CPU_ARM_CORTEX_M0, UC_ARM_REG_PC, UC_ARM_REG_LR, UC_ARM_REG_SP, UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3
import ringemu

STUBS = {0x40000338: 1 << 25, 0x40007004: 2, 0x40011014: 0x60, 0x4000621C: 1}
OS_SCHED_START, OS_INIT, LOW_DISPATCH = 0x131C2, 0x1328C, 0x3204E
CRITICAL_NESTING = 0x2014C4         # vPortEnterCritical's nesting count (os_lock is the lower stack's only masking)
HCI_OPEN, HCI_WRITE, HCI_RX_DONE = 0x177A4, 0x17804, 0x178CC
RET, CB, BUF, SP = 0xFFFF0, 0x23F000, 0x23E000, 0x23D000       # free addresses: return trap, callback, packet, stack
# The upper stack's HCI commands before a link, as the emulator's stack mode sends them (opcode, parameters)
STACK_INIT = [(0x1009, ""), (0x1002, ""), (0x1005, ""), (0x1001, ""), (0x1003, ""), (0x0C01, "ffffffffffffff3d"),
              (0x2003, ""), (0x201C, ""), (0x2002, ""), (0x200F, ""), (0x202A, ""), (0x204B, ""),
              (0x2001, "ffffff8000000000"), (0x2024, "fb004808"), (0xFD82, "050901"),
              (0x2006, "400640060000000000000000000700"), (0x2009, "00" * 32), (0x2008, "03020106" + "00" * 28), (0x200A, "01")]
# Poll sites: (name, function, args, register held busy, the bit that never changes)
SPINS = [("RF port write 0x226A2", 0x226A2, (0x10, 0), 0x40051244, 0x8000, "bit 15 stays set"),
         ("RF port read 0x225E6", 0x225E6, (0x10,), 0x40051244, 0, "bit 15 never sets"),
         ("bzdma command 0x152FE (TX path)", 0x152FE, (0, 0), 0x40058460, 0x400, "busy bit 10 stays set"),
         ("bzdma init 0x14AB6 (cold init)", 0x14AB6, (0,), 0x40058328, 0, "done bits 8/9 never set"),
         ("BT MAC wake 0x3EB9C (DLPS)", 0x3EB9C, (), 0x400500BE, 0, "status bit 14 never sets"),
         ("BB ISR 0x35C", 0x35C, (), 0x400500BE, 0x0001, "unhandled status bit 0 stays set"),
         ("BB write 0x225CC while the MAC sleeps", 0x225CC, (0xDA, 0x100), 0x400500BE, 0, "wake status bit 14 never sets")]


class Boot:
    pass


def boot(count=60_000_000, until=OS_SCHED_START):
    """Run the reset handler until `until` (or a wait that never ends).  Returns a Boot with uc, seen, text, pc, lr,
    allocs [(ram type, size, caller)] from os_mem_alloc_intern, tasks [(name, entry, stack words, priority)]."""
    rom = ringemu.ROM_FILE.read_bytes()
    syms = {int(v, 16) & ~1: n for n, v in re.findall(r"(\w+)\s*=\s*(0x[0-9a-fA-F]+)", (ringemu.SDK / "rom_symbol_gcc.axf").read_text())}
    b = Boot()
    b.uc = uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
    uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_M0)
    for base, size in ((0, 0x100000), (0x200000, 0x40000), (0x280000, 0x10000), (0x800000, 0x80000), (0x40000000, 0x100000), (0xE0000000, 0x100000)):
        uc.mem_map(base, size)
    uc.mem_write(0, rom)
    uc.mem_write(0x800000, b"\xff" * 0x80000)
    uc.mem_write(ringemu.APP_BASE, ringemu.IMAGE.read_bytes()[ringemu.OTA_HDR:])
    b.seen, b.allocs, b.tasks, b.held, b.masked = [], [], [], {}, [0, 0]   # masked: [primask, instructions run masked]
    log, vreg, sel, mac, rf, port = [], {0x11: 1 << 12}, [0], [0], {}, [0]
    b.blocks = blocks = collections.Counter()
    b.asleep = False
    def blk(uc, a, size, _):
        blocks[a] += 1
        if b.masked[0]: b.masked[1] += size // 2
        if a in syms and syms[a] not in b.seen: b.seen.append(syms[a])
        if a == 0x12C30: b.allocs.append((uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R1), uc.reg_read(UC_ARM_REG_LR) - 1))
        if a == 0xF51A:     # xTaskCreate(entry, name, stack words, param, priority, handle)
            nm = bytes(uc.mem_read(uc.reg_read(UC_ARM_REG_R1), 16)).split(b"\0")[0].decode()
            b.tasks.append((nm, uc.reg_read(UC_ARM_REG_R0), uc.reg_read(UC_ARM_REG_R2), struct.unpack("<I", uc.mem_read(uc.reg_read(UC_ARM_REG_SP), 4))[0]))
        if a == OS_INIT: uc.mem_write(0x2002DC, bytes([uc.mem_read(0x2002DC, 1)[0] | 1]))     # OTP->stack_en
        if blocks[a] > 3_000_000: uc.emu_stop()                    # a wait that never ends (counted per call)
        if a == 0x3EB9C: b.seen.append("wake wait 0x3EB9C")
    def primask(uc, a, size, _):    # the ROM's only interrupt masking: cpsid/cpsie and msr primask (vPortEnter/ExitCritical)
        op = struct.unpack("<H", uc.mem_read(a, 2))[0]
        b.masked[0] = 1 if op == 0xB672 else 0 if op == 0xB662 else uc.reg_read(UC_ARM_REG_R0 + (op & 0xF)) & 1
    for a in MASK_SITES: uc.hook_add(UC_HOOK_CODE, primask, begin=a, end=a)
    def inactive(uc, a, size, _):   # --spin: btmac_pm_check_inactive answers "asleep", as between connection events in DLPS
        if b.asleep: uc.reg_write(UC_ARM_REG_R0, 1); uc.reg_write(UC_ARM_REG_PC, uc.reg_read(UC_ARM_REG_LR))
    uc.hook_add(UC_HOOK_CODE, inactive, begin=0x3DEFC, end=0x3DEFC)
    def mmio(uc, acc, a, size, v, _):
        w = acc == UC_MEM_WRITE
        if a == 0x40011024 and w: log.append(chr(v & 0xFF))
        if a == 0x40058F00:
            if w:
                sel[0] = v >> 16 & 0x3F
                if v & 1 << 22: vreg[sel[0]] = v & 0xFFFF
            else: uc.mem_write(a, struct.pack("<I", sel[0] << 16 | vreg.get(sel[0], 0) & ~(0x8000 if sel[0] == 0 else 0)))
        if a == 0x40051074:
            if w: mac[0] = v & 0xFFFF
            else: uc.mem_write(a, struct.pack("<H", mac[0] ^ 0x8000))
        if a == 0x40051244:
            if w:
                i = v & 0x3FF
                if v & 0x8000:
                    lo, hi = struct.unpack("<HH", uc.mem_read(0x40051246, 4))
                    rf[i] = hi << 16 | lo
                else: uc.mem_write(0x40051246, struct.pack("<HH", rf.get(i, 0) & 0xFFFF, rf.get(i, 0) >> 16))
                port[0] = (v ^ 0x8000) & 0xFFFF
            else: uc.mem_write(a, struct.pack("<H", port[0]))
        if a == 0x40058328 and not w:
            x = struct.unpack("<I", uc.mem_read(a, 4))[0]
            uc.mem_write(a, struct.pack("<I", x | (x & 3) << 8))
        if not w and a in STUBS: uc.mem_write(a, struct.pack("<I", struct.unpack("<I", uc.mem_read(a, 4))[0] | STUBS[a]))
        if not w and a in b.held: uc.mem_write(a, struct.pack("<I", b.held[a]))     # --spin: hardware that never answers
    uc.hook_add(UC_HOOK_BLOCK, blk)
    uc.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, mmio, begin=0x40000000, end=0x400FFFFF)
    uc.reg_write(UC_ARM_REG_SP, struct.unpack("<I", rom[:4])[0])
    try: uc.emu_start(0x4EFF, until, count=count)
    except UcError as e: b.seen.append(f"fault {e}")
    b.text = "".join(c if 32 <= ord(c) < 127 or c == "\n" else "." for c in log)
    b.pc, b.lr = uc.reg_read(UC_ARM_REG_PC), uc.reg_read(UC_ARM_REG_LR)
    return b


def _mask_sites():
    rom = ringemu.ROM_FILE.read_bytes() if ringemu.ROM_FILE and ringemu.ROM_FILE.exists() else b""
    h = lambda a: struct.unpack_from("<H", rom, a)[0]
    return [a for a in range(0x100, len(rom) - 4, 2) if h(a) in (0xB672, 0xB662) or h(a) & 0xFFF0 == 0xF380 and h(a + 2) == 0x8810]
MASK_SITES = _mask_sites()


def call(b, fn, *args, count=20_000_000):
    """Call a ROM function on the probe's own stack; returns (r0, returned)."""
    for i, a in enumerate(args): b.uc.reg_write(UC_ARM_REG_R0 + i, a)
    b.uc.reg_write(UC_ARM_REG_LR, RET | 1)
    b.uc.reg_write(UC_ARM_REG_SP, SP)
    b.blocks.clear()
    b.uc.emu_start(fn | 1, RET, count=count)
    return b.uc.reg_read(UC_ARM_REG_R0), b.uc.reg_read(UC_ARM_REG_PC) == RET


def hci(b, cmds=STACK_INIT):
    """Open hci_if with a recording callback, then per command: hci_if write, low_stack_task's dispatcher, and the
    received buffers released as the upper stack does.  Returns [(opcode, [H4 packets the lower stack gave back])]."""
    got = []
    b.uc.mem_write(CB, b"\x70\x47")     # bx lr; the hook records hci_if_callback(evt, status, buf, len)
    def cb(uc, a, s, _):
        evt, st, buf, n = (uc.reg_read(UC_ARM_REG_R0 + i) for i in range(4))
        if evt == 2: got.append((buf, bytes(uc.mem_read(buf, n))))
    b.uc.hook_add(UC_HOOK_CODE, cb, begin=CB, end=CB)
    call(b, HCI_OPEN, CB | 1)
    call(b, LOW_DISPATCH)
    out = []
    for op, p in cmds:
        pkt = bytes([1]) + struct.pack("<HB", op, len(p) // 2) + bytes.fromhex(p)
        b.uc.mem_write(BUF, pkt)
        got.clear()
        call(b, HCI_WRITE, BUF, len(pkt))
        call(b, LOW_DISPATCH)
        out.append((op, [d for _, d in got]))
        for buf, _ in list(got):
            call(b, HCI_RX_DONE, buf)
            call(b, LOW_DISPATCH)
    return out


def spin(b, fn, args, reg, value, budget=40_000_000, asleep=False):
    """Run fn with the register `reg` reading `value` for good.  Returns (returned, instructions run with interrupts
    masked, primask at the end).  The ISRs run as plain calls (on the chip they are in handler mode)."""
    if reg: b.held[reg] = value
    b.masked[:], b.asleep = [0, 0], asleep
    nesting = bytes(b.uc.mem_read(CRITICAL_NESTING, 4))    # a spin that never returns leaves it raised: restore it
    b.uc.reg_write(UC_ARM_REG_PRIMASK, 0)                   # called as thread code with interrupts on (the boot leaves them off)
    try: _, back = call(b, fn, *args, count=budget)
    finally: b.held.clear(); b.asleep = False
    primask = b.masked[0]
    b.uc.mem_write(CRITICAL_NESTING, nesting)
    b.masked[0] = 0
    return back, b.masked[1], primask


def probe(count=60_000_000):
    b = boot(count)
    return b.seen, b.text, b.pc, b.lr


if __name__ == "__main__":
    n = next((int(x) for x in sys.argv[1:] if x.isdigit()), 60_000_000)
    b = boot(n)
    print("named ROM functions reached, in order:", ", ".join(b.seen))
    print("log UART:", b.text)
    print(f"stopped at pc={b.pc:#x} lr={b.lr:#x}" + (" (os_sched_start: lower stack initialised)" if b.pc == OS_SCHED_START else ""))
    print("tasks:", ", ".join(f"{t[0]} prio {t[3]} stack {t[2] * 4} B" for t in b.tasks))
    tot = collections.Counter()
    for t, size, at in b.allocs: tot[t, at >= 0x14000] += (size + 15) & ~7
    print(f"os_mem_alloc: lower stack (callers >= 0x14000) data {tot[0, True]} B, buffer {tot[1, True]} B; other ROM data {tot[0, False]} B")
    if "--hci" in sys.argv:
        for op, evs in hci(b): print(f"  HCI {op:#06x} -> {' '.join(e.hex() for e in evs) or 'no answer'}")
    if "--spin" in sys.argv:
        for name, fn, args, reg, val, why in SPINS:
            back, m, pm = spin(b, fn, args, reg, val, asleep="sleeps" in name)
            print(f"  {name}: {why}: {'returns' if back else 'never returns'}, {m:,} instructions with interrupts masked")
