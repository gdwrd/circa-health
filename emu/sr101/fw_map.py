#!/usr/bin/env python3
"""Print the facts docs/SR101-FIRMWARE.md quotes from the sibling image 003A0029 V142 (needs capstone).

Usage: python emu/sr101/fw_map.py      (reads emu/sr101/firmware/003A0029_V142.bin, executes nothing from it)
Addresses: flash XIP address = 0x10000000 + (offset in app.bin - 0x2400).
"""
import collections, os, re, struct
from capstone import CS_ARCH_RISCV, CS_MODE_RISCV32, CS_MODE_RISCVC, Cs
from fot_unpack import unpack

HERE = os.path.dirname(os.path.abspath(__file__))
app = unpack(open(os.path.join(HERE, "firmware", "003A0029_V142.bin"), "rb").read())["app"]
BASE, CODE = 0x10000000, 0x2400
va = lambda off: BASE + off - CODE
off = lambda addr: addr - BASE + CODE

tag, hsize, dsize = struct.unpack_from("<4sII", app, 0x2000)
assert bytes(b & 0x7F for b in tag) == b"XCOD" and hsize == 0x400
print(f"code region XCOD: app.bin {CODE:#x}+{dsize:#x} -> {BASE:#x}..{BASE + dsize:#x}")
print("strings:", [s.decode() for s in re.findall(rb"Hello AB202X[^\0]*|CH_AB2020|SR08", app)])

md = Cs(CS_ARCH_RISCV, CS_MODE_RISCV32 | CS_MODE_RISCVC)
md.skipdata = True
ins = [(i.address, i.mnemonic, i.op_str) for i in md.disasm(app[CODE:CODE + dsize], BASE)]
print(f"instructions {len(ins)}, not decodable as RV32IMC (vendor opcodes or data) {sum(m == '.byte' for _, m, _ in ins)}")

# reset code: tp = ROM anchor; the hello string proves the base
a, m, o = ins[0]
assert (m, o) == ("auipc", "tp, 0xf0085") and ins[1][2] == "tp, tp, -0x800"
print(f"entry {a:#x}: tp = {(a + 0xF0085000 - 0x800) & 0xFFFFFFFF:#x} (ROM anchor)")
hello = va(app.find(b"Hello AB202X"))
assert any(m == "lui" and o == f"a0, {(hello + 0x800) >> 12:#x}" for _, m, o in ins)
tp = collections.Counter(o.split(", ")[2] for _, m, o in ins if m == "jalr" and ", tp, " in o)
ram = sum(1 for k in range(1, len(ins)) if ins[k][1] == "jalr" and ins[k - 1][1] == "auipc"
          and 0x10000 <= (ins[k - 1][0] + (int(ins[k - 1][2].split(", ")[1], 16) << 12)) & 0xFFFFFFFF < 0x17000)
sfr = {mm.group(1) for _, _, o in ins if (mm := re.search(r"(-?0x[0-9a-f]+)\(zero\)", o))}
print(f"ROM calls through tp: {sum(tp.values())} sites, {len(tp)} distinct entries; calls into RAM 0x10000..0x16fff:"
      f" {ram}; registers addressed off x0: {len(sfr)}")

# GATT database (BTstack att_db records: len u16, flags u16, handle u16, uuid (16 bytes if flags & 0x200), value)
p = app.rfind(bytes.fromhex("020001000028")) - 2
print(f"GATT database at {va(p):#x}:")
while (n := struct.unpack_from("<H", app, p)[0]):
    flags, handle = struct.unpack_from("<HH", app, p + 2)
    ul = 16 if flags & 0x200 else 2
    uuid, value = app[p + 6:p + 6 + ul][::-1].hex(), app[p + 6 + ul:p + n]
    if uuid == "2800":
        print(f"  {handle:#04x} service {value[::-1].hex()[-28:-24] if len(value) > 2 else value[::-1].hex()}")
    elif uuid not in ("2803", "2902", "2908"):
        print(f"  {handle:#04x}   char {uuid} flags {flags:#06x} value {value.hex()}")
    p += n

# command dispatcher: compare tree on s0 = frame byte 0
LO, HI = 0x10008194, 0x10008A1E
last, cases = {}, {}
for a, m, o in ins:
    if not LO <= a < HI:
        continue
    q = o.split(", ")
    if m == "c.li":
        last[q[0]] = int(q[1], 16)
    elif m == "addi" and len(q) == 3 and q[1] == "zero":
        last[q[0]] = int(q[2], 16)
    elif m in ("beq", "bne") and q[0] == "s0" and q[1] in last:
        cases[last[q[1]]] = a + int(q[2], 16) if m == "beq" else a + 4
print(f"dispatcher 0x10008144, {len(cases)} command bytes found in its compare tree:")
print("  " + " ".join(f"{c:02X}@{t & 0xFFFFF:05x}" for c, t in sorted(cases.items())))


def imm(lui_addr: int) -> int:
    """value built by lui at lui_addr and the addi after it"""
    k = next(i for i, x in enumerate(ins) if x[0] == lui_addr)
    return ((int(ins[k][2].split(", ")[1], 16) << 12) + int(ins[k + 1][2].split(", ")[-1], 16)) & 0xFFFFFFFF


k = next(i for i, x in enumerate(ins) if x[0] == 0x10008094)
print("0C reply 0x1000808c: ver/cid/did =", [int(ins[k + j][2].split(", ")[-1], 16) for j in (0, 2, 4)],
      f"crc32 seed {(0x4A59B000 - 0xAB):#x} at 0x10008116")
assert ins[next(i for i, x in enumerate(ins) if x[0] == 0x10008116)][2] == "a0, 0x4a59b"
print("0E keys at 0x100084ec..: " + ", ".join(f"{imm(a):08x}" for a in (0x100084EC, 0x100084FA, 0x10008518, 0x1000852E)))

# accelerometer: constant (device, register) pairs passed to the I2C helpers
pairs = collections.Counter()
for k in range(2, len(ins)):
    if ins[k][1] in ("jal", "c.jal") and ins[k - 1][2] == "a0, 0x18" and ins[k - 2][2].startswith("a1, "):
        pairs[int(ins[k - 2][2].split(", ")[-1], 16)] += 1
print("I2C device 0x18 registers:", " ".join(f"{r:02x}" for r in sorted(pairs)))

# temperature: ADC voltage table and the two fudge functions
table = struct.unpack_from("<33H", app, off(0x1002D994))
assert list(table) == sorted(table)
print(f"temperature table 0x1002d994: 33 x u16, {table[0]}..{table[-1]}, step -30 C + 5 C per entry,"
      f" mean {(table[-1] - table[0]) / 160:.2f} per C")
body = lambda s: (s - 2800) * 125 // 900 + 3575 if 0 <= s - 2800 <= 0x383 else s
at = {a: (m, o) for a, m, o in ins}
assert at[0x1000B6F2] == ("addi", "a0, zero, 0x7d") and at[0x1000B6FA] == ("addi", "a5, zero, 0x384")
print(f"body(surface) at 0x1000b6de: (s-2800)*125/900+3575; body(3100) = {body(3100)} (the ring sent 31.00 / 36.16)")
