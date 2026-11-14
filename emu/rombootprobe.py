"""Runs the ROM's own reset handler (vector 0x4EFF) from firmware/rom_rtl8762.bin and reports how far it gets.

Register stubs, each read off the loop that polls it (docs/FIRMWARE.md "Real kernel, real boot"):
  0x40000338 bit 25   ready flag polled at 0xB6F8 (clock / flash delay block)
  0x40007004 bit 1    cache flush done, polled at 0x6B7A after setting bit 0
  0x40011014 0x60     log UART transmitter empty (UART_GetFlagState 0x11B6C); the text written to 0x40011024 is kept
  0x4000621C bit 0    random generator ready (get_true_random_number, 0x12C1C)
  0x40058F00          indirect 16-bit register port (index bits 21:16, bit 22 = write): register 0x11 bits 13:12 and
                      register 0 bit 15 clear = the power-ready wait at 0xBB86 / 0xC14C
  0x40051074          Bluetooth MAC indirect port: a command completes by flipping bit 15 (writes wait for clear at
                      0x22A70, reads for set at 0x22986)
It stops in the Bluetooth MAC / radio bring-up: every further step there is a radio handshake (0x40051xxx), and a
stub would be a guess at the radio.  No flash images are loaded (blank flash plus the stock app at 0x824000).

  python rombootprobe.py [instructions]      default 60,000,000
"""
import collections, re, struct, sys
from unicorn import Uc, UcError, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_BLOCK, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_MEM_WRITE
from unicorn.arm_const import UC_CPU_ARM_CORTEX_M0, UC_ARM_REG_PC, UC_ARM_REG_LR, UC_ARM_REG_SP
import ringemu

STUBS = {0x40000338: 1 << 25, 0x40007004: 2, 0x40011014: 0x60, 0x4000621C: 1}


def probe(count=60_000_000):
    rom = ringemu.ROM_FILE.read_bytes()
    syms = {int(v, 16) & ~1: n for n, v in re.findall(r"(\w+)\s*=\s*(0x[0-9a-fA-F]+)", (ringemu.SDK / "rom_symbol_gcc.axf").read_text())}
    uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
    uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_M0)
    for base, size in ((0, 0x100000), (0x200000, 0x40000), (0x280000, 0x10000), (0x800000, 0x80000), (0x40000000, 0x100000), (0xE0000000, 0x100000)):
        uc.mem_map(base, size)
    uc.mem_write(0, rom)
    uc.mem_write(0x800000, b"\xff" * 0x80000)
    uc.mem_write(ringemu.APP_BASE, ringemu.IMAGE.read_bytes()[ringemu.OTA_HDR:])
    seen, log, blocks, vreg, sel, mac = [], [], collections.Counter(), {0x11: 1 << 12}, [0], [0]
    def blk(uc, a, size, _):
        blocks[a] += 1
        if a in syms and syms[a] not in seen: seen.append(syms[a])
        if blocks[a] > 3_000_000: uc.emu_stop()                    # a wait that never ends
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
        if not w and a in STUBS: uc.mem_write(a, struct.pack("<I", struct.unpack("<I", uc.mem_read(a, 4))[0] | STUBS[a]))
    uc.hook_add(UC_HOOK_BLOCK, blk)
    uc.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, mmio, begin=0x40000000, end=0x400FFFFF)
    uc.reg_write(UC_ARM_REG_SP, struct.unpack("<I", rom[:4])[0])
    try: uc.emu_start(0x4EFF, 0xFFFFFFF0, count=count)
    except UcError as e: seen.append(f"fault {e}")
    text = "".join(c if 32 <= ord(c) < 127 or c == "\n" else "." for c in log)
    return seen, text, uc.reg_read(UC_ARM_REG_PC), uc.reg_read(UC_ARM_REG_LR)


if __name__ == "__main__":
    seen, text, pc, lr = probe(int(sys.argv[1]) if len(sys.argv) > 1 else 60_000_000)
    print("named ROM functions reached, in order:", ", ".join(seen))
    print("log UART:", text)
    print(f"stopped at pc={pc:#x} lr={lr:#x}")
