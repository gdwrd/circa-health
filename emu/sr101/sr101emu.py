#!/usr/bin/env python3
"""Run command handlers of the SR101 sibling image 003A0029 V142 (Bluetrum AB202x, RV32IMC) in unicorn. No boot.

Usage: python emu/sr101/sr101emu.py <frame hex>     e.g. 0b   (prints the reply frames; nothing is sent anywhere)
"""
import os, struct, sys
from unicorn import (Uc, UcError, UC_ARCH_RISCV, UC_MODE_RISCV32, UC_ERR_EXCEPTION, UC_ERR_FETCH_UNMAPPED,
                     UC_HOOK_CODE, UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE)
from unicorn.riscv_const import UC_RISCV_REG_X0, UC_RISCV_REG_PC

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from fot_unpack import unpack  # noqa: E402

FLASH, CODE, TP, STOP, SCRATCH = 0x10000000, 0x2400, 0x84800, 0x1003FFF0, 0x1F000
RA, SP, T0, A0 = 1, 2, 5, 10
SREG = (8, 9) + tuple(range(18, 28))                        # s0..s11
SAVE = {0x8C: 0, 0x84: 1, 0x7A: 2, 0x6E: 3, 0x60: 4, 0x50: 5, 0x3E: 6, 0x2A: 7, 0x12: 8, -8: 9, -0x24: 10,
        -0x40: 11, -0x60: 12}                               # ROM entry (off tp) -> how many s-registers it pushes
QUEUE_INIT, RECEIVE, DISPATCH = 0x10007F22, 0x1000A81E, 0x10008144
SEND20, SEND3, SENDN, SEND20B, SEND1 = 0x1000801E, 0x10007FD4, 0x10007F90, 0x1000806A, 0x10008044
BATTERY_PCT, BATTERY_STATE, MAC = 0x18338, 0x1832B, 0x17A10
# temperature: ADC slots (ch 16, ch 11, busy flags) and the reference in mV; raw result in 0.1 C
ADC_TEMP, ADC_REF, ADC_BUSY, VREF_MV, TEMP_RAW, TEMP_TABLE = 0x6BC, 0x6AC, 0x6C4, 0x17E5E, 0x166E4, 0x1002D994
TEMP_READ, TEMP_TASK, TEMP_TASK_END = 0x1000BCE2, 0x100090FC, 0x10009114
MODE_PREPARE, MODE_ACTION = 0x10008A1E, 0x10009A78
M32 = 0xFFFFFFFF


def sx(v: int, bits: int) -> int:
    return v - (1 << bits) if v >> (bits - 1) else v


class Emu:
    def __init__(self) -> None:
        app = unpack(open(os.path.join(HERE, "firmware", "003A0029_V142.bin"), "rb").read())["app"]
        self.uc = uc = Uc(UC_ARCH_RISCV, UC_MODE_RISCV32)
        uc.mem_map(FLASH, 0x40000)
        uc.mem_write(FLASH, app[CODE:])
        uc.mem_map(0x10000, 0x10000)                        # RAM
        uc.mem_map(0, 0x1000)                               # registers off x0, positive
        uc.mem_map(0xFFFFF000, 0x1000)                      # and negative offsets
        # what the reset code at 0x10000000 / 0x10013AB0 does before anything else: RAM code copy, tp, sp
        uc.mem_write(0x10C00, app[CODE + 0x200:CODE + 0x5E00])
        self.reg(4, TP)
        self.reg(SP, 0x10C00)
        self.regs: dict[int, int] = {}                      # peripheral register -> value a read returns
        self.reg_default = 0
        self.io: list[tuple[str, int, int]] = []            # ("r"/"w", address, value)
        self.clock = 0                                      # word 0 of the ROM clock; the firmware uses clock * 5 / 16 as ms
        self.replies: list[bytes] = []
        self.log: list[str] = []                            # printf format strings the firmware printed
        for lo in (0, 0xFFFFF000):
            uc.hook_add(UC_HOOK_MEM_READ, self._io_read, begin=lo, end=lo + 0xFFF)
            uc.hook_add(UC_HOOK_MEM_WRITE, self._io_write, begin=lo, end=lo + 0xFFF)
        for a in (SEND20, SEND3, SENDN, SEND20B, SEND1):
            uc.hook_add(UC_HOOK_CODE, self._sender, begin=a, end=a)
        self.call(QUEUE_INIT)

    # --- registers and memory
    def reg(self, n: int, v: int | None = None) -> int:
        if v is None:
            return self.uc.reg_read(UC_RISCV_REG_X0 + n)
        self.uc.reg_write(UC_RISCV_REG_X0 + n, v & M32)
        return v

    def rd(self, a: int, n: int = 4) -> int:
        return int.from_bytes(self.uc.mem_read(a, n), "little")

    def wr(self, a: int, v: int, n: int = 4) -> None:
        self.uc.mem_write(a, (v & ((1 << 8 * n) - 1)).to_bytes(n, "little"))

    def _io_read(self, uc, access, addr, size, value, user) -> None:
        v = self.regs.get(addr, self.reg_default)
        uc.mem_write(addr, v.to_bytes(4, "little")[:size])
        self.io.append(("r", addr, v))

    def _io_write(self, uc, access, addr, size, value, user) -> None:
        self.io.append(("w", addr, value & M32))

    # --- replies: taken at the sender's entry, the Bluetooth stack never runs
    def _sender(self, uc, addr, size, user) -> None:
        a0, a1, a2 = self.reg(A0), self.reg(A0 + 1), self.reg(A0 + 2)
        if addr in (SEND20, SEND20B):
            f = bytes(uc.mem_read(a0, 20))
        elif addr == SEND1:
            f = bytes(uc.mem_read(a0, 1))
        elif addr == SEND3:
            f = bytes((a0 & 0xFF, a1 & 0xFF, a2 & 0xFF))
        else:
            f = bytes((a0 & 0xFF,)) + bytes(uc.mem_read(a1, a2))
        self.replies.append(f.ljust(20, b"\0"))
        uc.reg_write(UC_RISCV_REG_PC, self.reg(RA))

    # --- ROM entries behind tp, identified from their call sites
    def _rom(self, pc: int) -> None:
        off, a0, a1, a2 = pc - TP, self.reg(A0), self.reg(A0 + 1), self.reg(A0 + 2)
        ret = self.reg(RA)
        if off in SAVE:                                     # prologue: push ra, s0..s(n-1); return through t0
            n, ret = SAVE[off], self.reg(T0)
            sp = self.reg(SP, self.reg(SP) - 4 * (n + 1))
            for i, r in enumerate((RA,) + SREG[:n]):
                self.wr(sp + 4 * (n - i), self.reg(r))
        elif 0x92 <= off <= 0xC2 and off % 4 == 2:          # epilogue: pop them and return
            n, sp = (0xC2 - off) // 4, self.reg(SP)
            for i, r in enumerate((RA,) + SREG[:n]):
                self.reg(r, self.rd(sp + 4 * (n - i)))
            self.reg(SP, sp + 4 * (n + 1))
            ret = self.reg(RA)
        elif off == -0x7C0:                                 # memset(dst, byte, n)
            self.uc.mem_write(a0, bytes([a1 & 0xFF]) * a2)
        elif off == -0x7BC:                                 # memcpy(dst, src, n)
            self.uc.mem_write(a0, bytes(self.uc.mem_read(a1, a2)))
        elif off == -0x67C:                                 # printf(fmt, ...)
            self.log.append(bytes(self.uc.mem_read(a0, 80)).split(b"\0")[0].decode("latin1"))
        elif off == -0x374:                                 # clock into a 12-byte struct; only word 0 is read
            self.uc.mem_write(a0, struct.pack("<III", self.clock, 0, 0))
        else:
            raise NotImplementedError(f"ROM entry tp{off:+#x} is not identified (ra {self.reg(RA):#x})")
        self.uc.reg_write(UC_RISCV_REG_PC, ret)

    # --- Bluetrum vendor instructions (README table), decoded from the code around them
    def _vendor(self, pc: int) -> None:
        w = self.rd(pc)
        op, rd, f3, rs1, rs2, f7 = w & 0x7F, w >> 7 & 31, w >> 12 & 7, w >> 15 & 31, w >> 20 & 31, w >> 25
        a, b = self.reg(rs1), self.reg(rs2)
        if op == 0x0B and f3 in (0, 1, 2, 4, 5):            # load (size and sign as the base lb..lhu), then rs1 += imm
            n = (1, 2, 4, 0, 1, 2)[f3]
            v = self.rd(a, n)
            self.reg(rs1, a + sx(w >> 20, 12))
            self.reg(rd, sx(v, 8 * n) if f3 < 2 else v)
        elif op == 0x2B and f3 < 3:                         # store, then rs1 += imm
            self.wr(a, b, 1 << f3)
            self.reg(rs1, a + sx(f7 << 5 | rd, 12))
        elif op == 0x23 and f3 in (4, 5, 6) and f7 == 0:    # store at rs1 + register (index in the rd field)
            self.wr(a + self.reg(rd) & M32, b, 1 << f3 - 4)
        elif op == 0x33 and f7 == 0x08 and f3 in (4, 5) and rs2 == 0:  # low 16 bits: 4 sign-extended, 5 zero-extended
            self.reg(rd, a & 0xFFFF if f3 == 5 else sx(a & 0xFFFF, 16))
        elif op == 0x33 and f7 == 0x02 and f3 == 4:         # signed minimum
            self.reg(rd, min(sx(a, 32), sx(b, 32)))
        elif op == 0x33 and f7 >> 5 == 3 and f3 in (3, 4):  # 3 clear / 4 set a field: (f7 & 31) bits wide at bit rs2
            m = ((1 << (f7 & 31)) - 1) << rs2 & M32
            self.reg(rd, a | m if f3 == 4 else a & ~m)
        else:
            raise NotImplementedError(f"vendor instruction {w:08x} at {pc:#x} is not known")
        self.uc.reg_write(UC_RISCV_REG_PC, pc + 4)

    def stub(self, addr: int) -> list[int]:
        """Never run the function at addr; return the list that gets its a0 on every call."""
        seen: list[int] = []

        def hook(uc, address, size, user) -> None:
            seen.append(self.reg(A0))
            uc.reg_write(UC_RISCV_REG_PC, self.reg(RA))
        self.uc.hook_add(UC_HOOK_CODE, hook, begin=addr, end=addr)
        return seen

    # --- running
    def call(self, addr: int, *args: int, until: int = STOP) -> int:
        """Call a firmware function and return a0; with until, run a stretch of code from addr to there."""
        for i, v in enumerate(args):
            self.reg(A0 + i, v)
        self.reg(RA, STOP)
        pc = addr
        while pc != until:
            try:
                self.uc.emu_start(pc, until, count=2_000_000)
                if self.uc.reg_read(UC_RISCV_REG_PC) != until:
                    raise RuntimeError(f"{addr:#x} did not return (pc {self.uc.reg_read(UC_RISCV_REG_PC):#x})")
            except UcError as e:
                pc = self.uc.reg_read(UC_RISCV_REG_PC)
                if e.errno == UC_ERR_FETCH_UNMAPPED and 0x80000 <= pc < 0x90000:
                    self._rom(pc)
                # shortcut: unicorn reports pc + 4 and every vendor form met is 4 bytes; a 2-byte one needs the block start
                elif e.errno == UC_ERR_EXCEPTION:
                    self._vendor(pc - 4)
                else:
                    raise RuntimeError(f"{e} at pc {pc:#x}, ra {self.reg(RA):#x}") from e
            pc = self.uc.reg_read(UC_RISCV_REG_PC)
        return self.reg(A0)

    def send(self, frame: bytes) -> list[bytes]:
        """Hand one frame to the firmware's receive path, run the dispatcher, return the replies."""
        self.replies = []
        self.uc.mem_write(SCRATCH, frame.ljust(20, b"\0")[:20])
        self.call(RECEIVE, SCRATCH)
        self.call(DISPATCH)
        return self.replies

    def temperature(self, mv: int) -> bytes:
        """One measurement with the sensor at mv millivolts: the ADC read, then the main task's own steps to the 38 frame."""
        self.wr(VREF_MV, 1000, 2)
        self.regs.update({ADC_REF: 1000, ADC_TEMP: mv})
        self.replies = []
        self.call(TEMP_READ)
        self.call(TEMP_TASK, until=TEMP_TASK_END)
        return self.replies[-1]


if __name__ == "__main__":
    for r in Emu().send(bytes.fromhex(sys.argv[1])):
        print(r.hex(" "))
