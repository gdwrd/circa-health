"""Runs the Colmi RY02 app image (Realtek RTL8762E) without the chip's ROM.

The real ROM never executes: every ROM function the app calls is replaced by a
Python stand-in looked up by name in the SDK symbol table, and every peripheral
register access is logged.  Sensors, the phone and interrupts are faked.

  python ringemu.py --ms 5000                      boot only
  python ringemu.py --ms 9000 --pin 3000:22        accelerometer interrupt at 3 s: motion sampling starts
  python ringemu.py --ms 34000 --cmd 3000:6901     phone starts real-time heart rate at 3 s
  python ringemu.py --ms 6000 --pin 3000:13        charger interrupt at 3 s
  python ringemu.py --ms 16000 --ota 3000:firmware/RY02_3.00.33_fullaccel.bin --pin 11000:22 --cmd 11500:a104
                                                   phone installs the patched firmware, then streams motion
  python ringemu.py --image FILE ...               boot another update file directly
Add --quiet-mem to hide raw register lines, --trace trace.js to record the run for viewer.html.
"""
import argparse, bisect, collections, hashlib, json, math, re, struct, sys
from pathlib import Path
from unicorn import Uc, UcError, UC_ARCH_ARM, UC_MODE_THUMB, UC_MODE_MCLASS, UC_HOOK_CODE, \
    UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_MEM_WRITE
from unicorn.arm_const import *

HERE = Path(__file__).parent
IMAGE = HERE / "firmware" / "RY02_3.00.33_250117.bin"
SDK = HERE / "sdk-ref" / "8762e"

APP_BASE, OTA_HDR = 0x824000, 0x50          # app image sits after a 0x50-byte OTA wrapper
HDR_SIZE = 0x400                            # Realtek image header in front of the code
OTA_TMP, OTA_TMP_SIZE = 0x84D000, 0x25000   # where the firmware itself stages an update (seen in its flash writes); size is a guess
STOCK_PAYLOAD_SHA256 = "e0c2bc0d34d9ea69"   # first 16 hex digits of the untouched 3.00.33 code, for the integrity model below
APP_GLOBALS = 0x207C00                      # below this, RAM belongs to the ROM
HEAP, HEAP_END = 0x220000, 0x240000         # our own heap, outside the chip's real 88K of RAM
EXIT = 0xFFFF0                              # fake return address: "this call chain is done"
DELAY_US, DELAY_MS = 0xFFF00, 0xFFF10       # fake ROM addresses for RAM function pointers
UNTIL, SLICE = 0xFFFFFFF0, 2_000_000
FOREVER = 0xFFFFFFFF
BLOCK = object()
CPU_HZ = 40_000_000  # assumption: timers run from the 40 MHz system clock
RTC_REGS = {0x00: "CR0", 0x04: "INT_CLR", 0x08: "INT_SR", 0x0C: "PRESCALER", 0x10: "COMP0", 0x14: "COMP1",
            0x18: "COMP2", 0x1C: "COMP3", 0x38: "PRE_COMP", 0x3C: "BACKUP"}
TIMER_VEC = {"TIM2": 20, "TIM3": 19, "TIM4": 48, "TIM5": 49}          # RAM vector table slots
# chip-ID bits the firmware checks before it will use a device: (i2c address, register) -> bits always read as set
OTA_UUID = "de5bf728"                        # Colmi firmware-update service
ID_BITS = {(0x44, 0x00): 0x27, (0x07, 0x0A): 0xE0}
PPG, PPG_DC, PULSE_BPM = 0x44, 200_000, 75  # optical heart-rate sensor: fake light level and pulse rate
# found by trial: with queue entries this far apart the firmware reports the pulse rate that was fed in.
# The sensor's real queue format is not known, so this is a calibration, not a fact about the chip.
PPG_STEP_MS = 17.24
ACCEL, ACCEL_HZ = 0x19, 25                   # LIS3DH-style accelerometer; the firmware sets 25 Hz (reg 0x20 = 0x37)
GPIO_GROUP_VEC = {0: 46, 1: 45, 2: 43, 3: 42}                         # pin % 4 -> GPIO_GroupN slot
ADC_RAW = 0xA00     # arbitrary mid-scale reading; the battery voltage this maps to is not calibrated
REGS = [UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_R4, UC_ARM_REG_R5,
        UC_ARM_REG_R6, UC_ARM_REG_R7, UC_ARM_REG_R8, UC_ARM_REG_R9, UC_ARM_REG_R10, UC_ARM_REG_R11,
        UC_ARM_REG_R12, UC_ARM_REG_SP, UC_ARM_REG_LR, UC_ARM_REG_PC]
QUIET = {"__aeabi_memcpy", "__aeabi_memcpy4", "__aeabi_memclr", "__aeabi_memclr4", "__aeabi_memset",
         "memcmp", "strlen", "os_mutex_take", "os_mutex_give", "trace_string", "trace_bdaddr",
         "os_mem_alloc_intern", "os_mem_zalloc_intern", "os_mem_free", "log_buffer", "log_direct",
         "platform_delay_us", "WDG_Restart", "btaon_fast_read_safe", "btaon_fast_write_safe", "btaon_fast_write"}
# guess: SDK default flash map; this ring's real map is unknown apart from the app address
FLASH_BANKS = {0: (0x801000, 0x1000), 1: (0x802000, 0x55000), 3: (0x85B000, 0x25000), 6: (0x857000, 0x4000)}


class Task:
    def __init__(self, name, ctx, on_exit=None):
        self.name, self.ctx, self.on_exit, self.wait = name, ctx, on_exit, None


class Emu:
    def __init__(self, quiet_mem=False, trace=False, image=IMAGE):
        self.quiet_mem = quiet_mem
        self.trace = [] if trace else None
        self.syms = {}
        for n, v in re.findall(r"(\w+)\s*=\s*(0x[0-9a-fA-F]+)", (SDK / "rom_symbol_gcc.axf").read_text()):
            self.syms.setdefault(int(v, 16) & ~1, n)
        self.syms.update({DELAY_US: "platform_delay_us", DELAY_MS: "platform_delay_ms"})
        self.ram_syms = sorted(a for a in self.syms if 0x200000 <= a < 0x300000)
        bases = {}
        for n, v in re.findall(r"#define\s+(\w+?)(?:_REG)?_BASE\s+0x(4\w{7})UL", (SDK / "rtl876x.h").read_text()):
            bases.setdefault(int(v, 16), n)
        self.periph = sorted(bases.items())

        self.uc = uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB | UC_MODE_MCLASS)
        uc.mem_map(0, 0x100000)
        uc.mem_write(0, b"\x70\x47" * 0x80000)             # ROM = all "bx lr"; calls are intercepted anyway
        uc.mem_map(0x200000, 0x40000)
        uc.mem_map(0x800000, 0x80000)
        uc.mem_write(0x800000, b"\xff" * 0x80000)
        uc.mem_write(APP_BASE, Path(image).read_bytes()[OTA_HDR:])
        # Factory config block the firmware expects (signature, length, one item: Bluetooth address at offset 6).
        # Without it the firmware rewrites the block and resets on every boot. The address value is made up.
        mac_item = struct.pack("<HB", 6, 6) + bytes.fromhex("112233445566") + bytes([0xFF]) * 6
        uc.mem_write(0x801400, struct.pack("<IH", 0x8721BEE2, len(mac_item)) + mac_item)
        uc.mem_map(0x40000000, 0x100000)
        uc.mem_map(0xE0000000, 0x100000)
        uc.hook_add(UC_HOOK_CODE, self.on_rom, begin=0, end=0xFFFFF)
        uc.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, self.on_mmio, begin=0x40000000, end=0x400FFFFF)
        uc.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, self.on_rom_ram, begin=0x200000, end=APP_GLOBALS - 1)

        self.now = 0                # virtual milliseconds; only advances when every task is waiting
        self.tasks, self.cur, self.romcall = [], None, False
        self.heap, self.free = HEAP, collections.defaultdict(list)
        self.sizes, self.handles = {}, 0x30000000
        self.queues, self.sems, self.timers = {}, {}, {}
        self.ftl, self.aon = {}, {}
        self.hw = {}                # hardware event sources: name -> {at, interval or None, fire}
        self.accel_drained = self.accel_k = 0
        self.still = False          # True: the fake accelerometer lies flat and motionless
        self.ppg_drained = self.ppg_bytes = 0
        self.services, self.connected, self.subscribed, self.notified = [], False, set(), []
        self.ota, self.ota_writes, self.ota_chunk = None, 0, 200     # phone-side state of a firmware update in progress
        self.gpio_int = 0           # latched GPIO interrupt bits
        self.bus, self.pending = {}, None    # fake I2C/SPI buses and the one with an unprinted transfer
        self.app_ram, self.seen_rom_ram = set(), set()
        self.last, self.repeat = None, 0
        self.sched_started = False
        self.stats = collections.Counter()
        self.resets = 0
        self.rom_ram_init()

    def rom_ram_init(self):
        """What the real ROM leaves in its own RAM before it starts the app."""
        self.w32(self.sym("platform_delay_us"), DELAY_US | 1)
        self.w32(self.sym("platform_delay_ms"), DELAY_MS | 1)
        self.preset(0x2002DC, bytes([1]))   # OTP->stack_en bit; SystemInit returns at once without it

    def reset(self):
        """Chip reset: RAM, peripherals and tasks are lost; flash, saved settings and the sensors' state stay."""
        self.resets += 1
        self.ev(f"CHIP RESET #{self.resets}", "reset")
        self.rom_boot_copy()
        for base, size in ((0x200000, 0x40000), (0x40000000, 0x100000), (0xE0000000, 0x100000)):
            self.uc.mem_write(base, bytes(size))
        self.tasks, self.cur = [], None
        self.heap, self.free, self.sizes = HEAP, collections.defaultdict(list), {}
        self.queues, self.sems, self.timers = {}, {}, {}
        self.hw = {k: h for k, h in self.hw.items() if h["interval"] is None}    # keep outside events, drop chip timers
        self.gpio_int, self.app_ram = 0, set()
        self.services, self.connected, self.subscribed = [], False, set()         # the phone's link drops
        self.rom_ram_init()
        self.boot()

    def rom_boot_copy(self):
        """Model of the ROM's boot step after an update: a ready app image in the temp area replaces the app.

        The real ROM code is not available, so this is how the SDK documents it, not a verified behaviour.
        """
        ic, _, flags, image_id, _, length = struct.unpack("<BBHHHI", self.uc.mem_read(OTA_TMP, 12))
        if image_id == 0x2793 and not flags & 0x80 and flags & 0x100:      # app image, ready, not yet used
            self.uc.mem_write(APP_BASE, bytes(self.uc.mem_read(OTA_TMP, HDR_SIZE + length)))
            self.uc.ctl_remove_cache(APP_BASE, APP_BASE + HDR_SIZE + length)    # drop the emulator's translations of the old code
            self.uc.mem_write(OTA_TMP + 2, struct.pack("<H", flags & ~0x100))
            self.ev(f"ROM boot (modelled): installed new app image, {HDR_SIZE + length} bytes", "reset")

    def preset(self, addr, data):
        """Value the real ROM would have left in its own RAM before starting the app."""
        self.uc.mem_write(addr, data)
        self.app_ram.update(range(addr, addr + len(data)))

    def boot(self):
        """Queue the ROM's boot order for an app image: SystemInit, then the two hooks SystemInit registered."""
        ptr = lambda name: self.r32(self.sym(name))
        self.spawn("boot", APP_BASE + 0x400 | 1, on_exit=lambda: self.spawn(
            "boot", ptr("app_pre_main"), on_exit=lambda: self.spawn("boot", ptr("app_main"))))

    def command(self, ms, payload):
        """Schedule a Colmi command from the phone; pads to 15 bytes and appends the checksum."""
        pkt = payload.ljust(15, bytes(1))
        pkt += bytes([sum(pkt) & 0xFF])
        self.hw[f"cmd{len(self.hw)}"] = {"at": ms, "interval": None, "fire": lambda: self.ble_write(pkt)}

    # ---- small helpers
    def sym(self, name): return next(a for a, n in self.syms.items() if n == name)
    def r(self, reg): return self.uc.reg_read(reg)
    def r32(self, a): return struct.unpack("<I", self.uc.mem_read(a, 4))[0]
    def w32(self, a, v): self.uc.mem_write(a, struct.pack("<I", v & 0xFFFFFFFF))
    def arg(self, i): return self.r(REGS[i]) if i < 4 else self.r32(self.r(UC_ARM_REG_SP) + 4 * (i - 4))

    def cstr(self, a):
        try: return bytes(self.uc.mem_read(a, 128)).split(b"\0")[0].decode("latin1")
        except UcError: return f"<bad ptr {a:#x}>"

    def ev(self, text, kind="info", **data):
        """Print one event line; with tracing on, also keep it as a record for the viewer."""
        if self.trace is not None:
            self.trace.append({"t": self.now, "task": self.cur.name if self.cur else "-", "kind": kind, "text": text} | data)
        if text == self.last:
            self.repeat += 1
            return
        if self.repeat: print(f"{'':19}  (repeated {self.repeat} more times)")
        self.last, self.repeat = text, 0
        print(f"[{self.now:6}ms {self.cur.name if self.cur else '-':<9.9}] {text}")

    def alloc(self, size, zero=True):
        size = (size + 7) & ~7
        if self.free[size]: a = self.free[size].pop()
        else:
            a, self.heap = self.heap, self.heap + size
            assert self.heap <= HEAP_END, "emulator heap exhausted"
        self.sizes[a] = size
        if zero: self.uc.mem_write(a, bytes(size))
        return a

    def handle(self):
        self.handles += 0x10
        return self.handles

    def spawn(self, name, pc, args=(), stack=0x2000, on_exit=None):
        ctx = dict.fromkeys(REGS, 0)
        ctx.update(zip(REGS, args[:4]))
        base = self.alloc(stack)
        top = base + stack - 4 * len(args[4:])           # arguments past the fourth go on the stack
        self.uc.mem_write(top, struct.pack(f"<{len(args[4:])}I", *args[4:]))
        ctx.update({UC_ARM_REG_SP: top, UC_ARM_REG_LR: EXIT | 1, UC_ARM_REG_PC: pc & ~1})
        self.tasks.append(Task(name, ctx, on_exit))
        self.tasks[-1].stack = base

    # ---- hooks
    def on_rom(self, uc, addr, size, _):
        self.romcall = True
        uc.emu_stop()

    def on_mmio(self, uc, access, addr, size, value, _):
        i = bisect.bisect_right(self.periph, (addr, "~")) - 1
        base, name = self.periph[i]
        w = access == UC_MEM_WRITE
        if 0x400001F0 <= addr <= 0x400001F8:            # RTC registers are written through this data/address/strobe port
            if addr == 0x400001F8:
                reg, data = self.r32(0x400001F4), self.r32(0x400001F0)
                self.w32(0x40000100 + reg, data)
                self.ev(f"RTC {RTC_REGS.get(reg, hex(reg))} = {data:#x}")
            return
        if addr == 0x40000130:                          # RTC counter, 32768 Hz divided by the prescaler
            uc.mem_write(addr, struct.pack("<I", self.now * 32768 // 1000 // (self.r32(0x4000010C) + 1) & 0xFFFFFFFF))
            return
        self.stats[("W " if w else "R ") + name] += 1
        if w and self.pending and self.pending != name: self.flush()    # e.g. chip-select released
        model = {"I2C0": self.i2c, "I2C1": self.i2c, "SPI0": self.spi, "SPI1": self.spi, "ADC": self.adc,
                 "GPIO": self.gpio}.get(name, self.hwtimer if name in TIMER_VEC else None)
        if model:
            v = model(name, addr - base, w, value & 0xFFFFFFFF)
            if not w and v is not None: uc.mem_write(addr, v.to_bytes(4, "little")[:size])
            if not w or v is not None: return           # status polls and data bytes are shown as transfers
        if not self.quiet_mem:
            val = value & 0xFFFFFFFF if w else int.from_bytes(uc.mem_read(addr, size), "little")
            self.ev(f"{'W' if w else 'R'} {name}+{addr - base:#05x} {'=' if w else '->'} {val:#x}  pc={self.r(UC_ARM_REG_PC):#x}", "mmio", periph=name)

    def on_rom_ram(self, uc, access, addr, size, value, _):
        if access == UC_MEM_WRITE: self.app_ram.update(range(addr, addr + size))
        elif addr >= 0x200100 and addr not in self.app_ram and addr not in self.seen_rom_ram:   # below: vector table
            self.seen_rom_ram.add(addr)
            i = bisect.bisect_right(self.ram_syms, addr) - 1
            near = f"{self.syms[self.ram_syms[i]]}+{addr - self.ram_syms[i]:#x}" if i >= 0 else "?"
            self.ev(f"reads ROM-owned RAM {addr:#x} ({near}), never initialised  pc={self.r(UC_ARM_REG_PC):#x}")

    # ---- fake buses: DesignWare I2C and SPI blocks, each device a plain register file (reads give 0 until set)
    def busstate(self, name):
        return self.bus.setdefault(name, {"tar": 0, "tx": [], "got": [], "rx": collections.deque(), "ptr": 0, "rd": 0, "devs": {}})

    def flush(self):
        for name, st in self.bus.items():
            if st["tx"] or st["got"]:
                dev = f" dev {st['tar']:#04x}" if name[:3] == "I2C" else ""
                self.ev(f"{name}{dev}: wrote [{bytes(st['tx']).hex(' ')}] read [{bytes(st['got']).hex(' ')}]",
                        "bus", bus=name, dev=st["tar"], tx=bytes(st["tx"]).hex(), rx=bytes(st["got"]).hex())
                st["tx"], st["got"] = [], []
        self.pending = None

    def i2c(self, name, off, w, value):
        st = self.busstate(name)
        regs = st["devs"].setdefault(st["tar"], {})
        if w:
            if off == 0x04: st["tar"] = value & 0x3FF; return 0
            if off != 0x10: return None
            self.pending = name
            if value & 0x100:                           # read command
                v = regs.get(st["ptr"], 0) | ID_BITS.get((st["tar"], st["ptr"]), 0)
                if st["tar"] == ACCEL: v = self.accel_read(st, v)
                if st["tar"] == PPG: v = self.ppg_read(st, v)
                st["rx"].append(v); st["got"].append(v); st["ptr"] += 1
            else:
                b = value & 0xFF
                if st["tx"]: regs[st["ptr"]] = b; st["ptr"] += 1
                else: st["ptr"] = b                     # first byte of a transfer selects the register
                st["tx"].append(b)
            if value & 0x200: self.flush()              # stop condition
            return 0
        if off == 0x10: return st["rx"].popleft() if st["rx"] else 0
        if off == 0x70: return 0x6 | (0x8 if st["rx"] else 0)       # tx fifo empty, rx not empty
        if off == 0x34: return 0x10 | (0x4 if st["rx"] else 0)
        if off == 0x78: return len(st["rx"])
        return {0x74: 0, 0x80: 0}.get(off)

    def accel_read(self, st, v):
        """Fake motion: 1 g of gravity turning around the ring's axis, as if the ring spins on a finger (or lying still)."""
        reg = st["ptr"] & 0x7F
        if reg == 0x2F:                                 # FIFO_SRC: samples waiting (max 31), bit 5 = empty
            n = min(31, (self.now - self.accel_drained) * ACCEL_HZ // 1000)
            self.accel_drained = self.now
            return n or 0x20
        if st["tx"] and st["tx"][0] & 0x80:             # auto-increment burst: status at 0x27, then X, Y, Z
            reg = (st["tx"][0] & 0x7F) + len(st["got"])
        if reg == 0x27: return 0x0F                     # new X, Y and Z data available
        if 0x28 <= reg <= 0x2D:
            i, k = reg - 0x28, self.accel_k
            if i == 5: self.accel_k += 1
            mg = (0, 0, 1000) if self.still else (1000 * math.cos(k * 0.25), 1000 * math.sin(k * 0.25), 60 * math.sin(k * 1.7))
            mg = mg[i // 2]
            raw = (int(mg / 8) << 6) & 0xFFFF           # 10-bit left-justified, 8 mg per step at +-4 g
            return raw >> 8 if i % 2 else raw & 0xFF
        return v

    def ppg_read(self, st, v):
        """Fake pulse: every 24-bit light reading is a steady level with a 1% ripple at PULSE_BPM."""
        wave = lambda ms: int(PPG_DC * (1 + 0.01 * math.sin(2 * math.pi * PULSE_BPM / 60 * ms / 1000)))
        reg = st["ptr"]
        if reg == 0x40:                                 # samples waiting in the 25 Hz queue
            n = min(32, (self.now - self.ppg_drained) * 25 // 1000)
            self.ppg_drained = self.now
            return n
        if st["tx"] and st["tx"][0] == 0x41:            # queue data, 3 bytes per sample, 40 ms apart
            self.ppg_bytes += 1
            return wave(int((self.ppg_bytes - 1) // 3 * PPG_STEP_MS)) >> 8 * ((self.ppg_bytes - 1) % 3) & 0xFF
        for first, last in ((0x02, 0x0D), (0xBC, 0xCA)):
            if first <= reg <= last: return wave(self.now) >> 8 * ((reg - first) % 3) & 0xFF
        return v

    def spi(self, name, off, w, value):
        st = self.busstate(name)
        regs = st["devs"].setdefault(0, {})
        if w:
            if off != 0x60: return None
            self.pending = name
            b, out = value & 0xFF, 0
            if not st["tx"]: st["ptr"], st["rd"] = b & 0x7F, b & 0x80       # first byte: register, top bit = read
            else:
                if st["rd"]: out = regs.get(st["ptr"], 0)
                else: regs[st["ptr"]] = b
                st["ptr"] += 1
            st["tx"].append(b); st["rx"].append(out); st["got"].append(out)
            return 0
        if off == 0x60: return st["rx"].popleft() if st["rx"] else 0
        if off == 0x28: return 0x6 | (0x8 if st["rx"] else 0)
        if off == 0x24: return len(st["rx"])
        return {0x20: 0}.get(off)

    def adc(self, name, off, w, value):
        if w: return None
        if off == 0x0C: return self.r32(0x4001000C) | 0x1F0000     # every "conversion done" flag is always set
        if 0x30 <= off < 0x50:
            self.ev(f"ADC sample read -> {ADC_RAW:#x}", "adc")
            return ADC_RAW
        return None

    def irq(self, name, slot):
        handler = self.r32(0x200000 + 4 * slot)
        if handler: self.spawn("irq:" + name, handler, stack=0x1000)
        else: self.ev(f"IRQ {name}: no handler in vector slot {slot}")

    def raise_pin(self, pin):
        self.ev(f"GPIO pin {pin} interrupt raised", "irq", pin=pin)
        self.gpio_int |= 1 << pin
        self.irq(f"GPIO{pin}", GPIO_GROUP_VEC[pin % 4])

    def gpio(self, name, off, w, value):
        if w:
            if off == 0x4C: self.gpio_int &= ~value     # end-of-interrupt
            return None
        if off == 0x40: return self.gpio_int & self.r32(0x40001030) & ~self.r32(0x40001034)
        if off == 0x44: return self.gpio_int
        return None

    def hwtimer(self, name, off, w, value):
        if w and off == 0x08:                           # control: bit0 enable, bit2 interrupt masked
            load = self.r32(next(b for b, n in self.periph if n == name))
            if value & 1 and not value & 4:
                ms = max(1, (load + 1) * 1000 // CPU_HZ)
                self.hw[name] = {"at": self.now + ms, "interval": ms, "fire": lambda: self.irq(name, TIMER_VEC[name])}
                self.ev(f"{name} periodic interrupt every {ms}ms")
            else: self.hw.pop(name, None)
        return None

    # ---- run loop
    def run(self, limit_ms):
        spins = 0
        while True:
            if self.cur is None:
                if not self.schedule(limit_ms): return
            self.romcall = False
            try:
                self.uc.emu_start(self.r(UC_ARM_REG_PC) | 1, UNTIL, count=SLICE)
            except UcError as e:
                self.ev(f"CRASH {e}: pc={self.r(UC_ARM_REG_PC):#x} lr={self.r(UC_ARM_REG_LR):#x} sp={self.r(UC_ARM_REG_SP):#x}")
                return
            if not self.romcall:
                spins += 1
                if spins == 3:
                    self.ev(f"STUCK: {3 * SLICE} instructions without a ROM call, pc={self.r(UC_ARM_REG_PC):#x} lr={self.r(UC_ARM_REG_LR):#x}")
                    return
                continue
            spins = 0
            self.dispatch(self.r(UC_ARM_REG_PC))

    def dispatch(self, pc):
        lr = self.r(UC_ARM_REG_LR)
        if pc == EXIT:
            t, self.cur = self.cur, None
            self.tasks.remove(t)
            self.free[self.sizes.pop(t.stack)].append(t.stack)
            if t.on_exit: t.on_exit()
            return
        name = self.syms.get(pc, f"rom_{pc:x}")
        self.stats[name] += 1
        fn = getattr(self, "rom_" + name, None)
        if name not in QUIET:
            self.ev(f"{name}({', '.join(f'{self.arg(i):#x}' for i in range(4))})" + ("" if fn else "  [no stand-in, returns 0]"), "rom", name=name)
        ret = fn() if fn else 0
        if ret is BLOCK:
            if self.cur: self.cur.ctx = {reg: self.r(reg) for reg in REGS} | {UC_ARM_REG_PC: lr & ~1}
            self.cur = None
            return
        if ret is not None: self.uc.reg_write(UC_ARM_REG_R0, int(ret) & 0xFFFFFFFF)
        self.uc.reg_write(UC_ARM_REG_PC, lr & ~1)

    def schedule(self, limit_ms):
        # shortcut: round-robin with no priorities or preemption; add them if task ordering starts to matter
        while True:
            ready = [t for t in self.tasks if t.wait is None]
            if ready:
                self.cur = t = ready[0]
                self.tasks.remove(t); self.tasks.append(t)
                for reg, v in t.ctx.items(): self.uc.reg_write(reg, v)
                return True
            due = [tm["at"] for tm in self.timers.values() if tm["at"] is not None]
            due += [t.wait["at"] for t in self.tasks if t.wait.get("at") is not None]
            due += [h["at"] for h in self.hw.values()]
            if not due:
                self.ev("idle: every task is waiting and no timer is armed")
                return False
            self.now = max(self.now, min(due))
            if self.now > limit_ms: return False
            for h, tm in self.timers.items():
                if tm["at"] is not None and tm["at"] <= self.now:
                    tm["at"] = self.now + tm["interval"] if tm["reload"] else None
                    self.spawn("tmr:" + tm["name"], tm["cb"], (h,), stack=0x1000)
            for name, h in list(self.hw.items()):
                if h["at"] <= self.now:
                    if h["interval"]: h["at"] = self.now + h["interval"]
                    else: del self.hw[name]
                    h["fire"]()
            for t in self.tasks:
                if t.wait and t.wait.get("at") is not None and t.wait["at"] <= self.now:
                    t.ctx[UC_ARM_REG_R0], t.wait = t.wait.get("timeout_ret", 0), None

    def block(self, ms, **wait):
        self.cur.wait = wait | {"at": None if ms == FOREVER else self.now + ms}
        return BLOCK

    # ---- ROM stand-ins: memory and strings
    def rom___aeabi_memcpy(self):
        d, s, n = self.arg(0), self.arg(1), self.arg(2)
        self.uc.mem_write(d, bytes(self.uc.mem_read(s, n)))
        return d
    rom___aeabi_memcpy4 = rom___aeabi_memcpy8 = rom___aeabi_memcpy

    def rom___aeabi_memclr(self): self.uc.mem_write(self.arg(0), bytes(self.arg(1)))
    rom___aeabi_memclr4 = rom___aeabi_memclr

    def rom___aeabi_memset(self): self.uc.mem_write(self.arg(0), bytes([self.arg(2) & 0xFF]) * self.arg(1))

    def rom_memcmp(self):
        a, b = bytes(self.uc.mem_read(self.arg(0), self.arg(2))), bytes(self.uc.mem_read(self.arg(1), self.arg(2)))
        return (a > b) - (a < b)

    def rom_strlen(self): return len(self.cstr(self.arg(0)))
    def rom_os_mem_alloc_intern(self): return self.alloc(self.arg(1), zero=False)
    def rom_os_mem_zalloc_intern(self): return self.alloc(self.arg(1))
    def rom_os_mem_free(self):
        a = self.arg(0)
        if a in self.sizes: self.free[self.sizes.pop(a)].append(a)

    # ---- logging
    def varargs(self, first):
        i = first
        while True:
            yield self.arg(i)
            i += 1

    def rom_log_direct(self):
        args = self.varargs(2)
        def rep(m):
            spec = m.group(0)
            if spec[-1] == "%": return "%"
            v = next(args)
            if spec[-1] == "s": return self.cstr(v)
            if spec[-1] == "c": return chr(v & 0xFF)
            if spec[-1] in "di" and v >> 31: v -= 1 << 32
            return (spec[:-1].replace("l", "") + {"i": "d", "u": "d", "p": "x"}.get(spec[-1], spec[-1])) % v
        self.ev("LOG " + re.sub(r"%[-0-9.l]*[sdiuxXcp%]", rep, self.cstr(self.arg(1))).rstrip(), "log")

    def rom_log_buffer(self):
        # the format strings live in a trace section that is not part of the image; only the index is known
        n = self.arg(2)
        self.ev(f"LOG #{self.arg(1):#x} args={[hex(self.arg(3 + i)) for i in range(min(n, 8))]}", "log")

    def rom_trace_string(self): return self.arg(1)
    rom_trace_bdaddr = rom_trace_string

    # ---- tasks, queues, semaphores, timers
    def rom_os_task_create(self):
        name, h = self.cstr(self.arg(1)), self.handle()
        self.w32(self.arg(0), h)
        self.spawn(name or f"task{h:x}", self.arg(2), (self.arg(3),), stack=max(self.arg(4), 0x1000))
        return 1

    def rom_os_sched_start(self):
        self.sched_started = True
        self.tasks.remove(self.cur)         # main() never returns from here on real hardware
        self.cur = None
        return BLOCK

    def rom_os_sched_suspend(self): return 1
    rom_os_sched_resume = rom_os_sched_suspend

    def rom_os_delay(self): return self.block(self.arg(0))
    def rom_platform_delay_ms(self): self.now += self.arg(0)
    def rom_platform_delay_us(self): pass

    def rom_os_msg_queue_create_intern(self):
        h = self.handle()
        self.queues[h] = {"size": self.arg(2), "items": collections.deque()}
        self.w32(self.arg(0), h)
        return 1

    def rom_os_msg_send_intern(self):
        return self.qsend(self.arg(0), bytes(self.uc.mem_read(self.arg(1), self.queues[self.arg(0)]["size"])))

    def qsend(self, h, msg):
        for t in self.tasks:
            if t.wait and t.wait.get("queue") == h:
                self.uc.mem_write(t.wait["buf"], msg)
                t.ctx[UC_ARM_REG_R0], t.wait = 1, None
                return 1
        self.queues[h]["items"].append(msg)
        return 1

    def rom_os_msg_recv_intern(self):
        h, buf, ms = self.arg(0), self.arg(1), self.arg(2)
        if self.queues[h]["items"]:
            self.uc.mem_write(buf, self.queues[h]["items"].popleft())
            return 1
        return self.block(ms, queue=h, buf=buf) if ms else 0

    def rom_os_sem_create(self):
        h = self.handle()
        self.sems[h] = self.arg(1)
        self.w32(self.arg(0), h)
        return 1

    def rom_os_sem_take(self):
        h, ms = self.arg(0), self.arg(1)
        if self.sems[h]:
            self.sems[h] -= 1
            return 1
        return self.block(ms, sem=h) if ms else 0

    def rom_os_sem_give(self):
        h = self.arg(0)
        for t in self.tasks:
            if t.wait and t.wait.get("sem") == h:
                t.ctx[UC_ARM_REG_R0], t.wait = 1, None
                return 1
        self.sems[h] += 1
        return 1

    # shortcut: tasks never preempt each other here, so a mutex can never be contended
    def rom_os_mutex_create(self):
        self.w32(self.arg(0), self.handle())
        return 1
    def rom_os_mutex_take(self): return 1
    rom_os_mutex_give = rom_os_mutex_take

    def rom_os_timer_create(self):
        h = self.handle()
        self.timers[h] = {"name": self.cstr(self.arg(1)), "interval": self.arg(3), "reload": self.arg(4) & 1,
                          "cb": self.arg(5), "at": None}
        self.w32(self.arg(0), h)
        self.ev(f"  timer '{self.timers[h]['name']}' every {self.arg(3)}ms reload={self.arg(4) & 1} cb={self.arg(5):#x}", "timer")
        return 1

    def timer(self): return self.timers.get(self.r32(self.arg(0)))

    def rom_os_timer_start(self):
        tm = self.timer()
        if not tm: return 0
        tm["at"] = self.now + tm["interval"]
        self.ev(f"  timer '{tm['name']}' armed for +{tm['interval']}ms", "timer")
        return 1

    def rom_os_timer_restart(self):
        tm = self.timer()
        if not tm: return 0
        tm["interval"] = self.arg(1)
        return self.rom_os_timer_start()

    def rom_os_timer_stop(self):
        tm = self.timer()
        if tm: tm["at"] = None
        return 1

    def rom_os_timer_delete(self):
        self.timers.pop(self.r32(self.arg(0)), None)
        self.w32(self.arg(0), 0)
        return 1

    def rom_os_timer_state_get(self):
        tm = self.timer()
        self.w32(self.arg(1), int(bool(tm and tm["at"] is not None)))
        return 1

    # ---- flash and settings storage
    def rom_flash_read_locked(self):
        self.uc.mem_write(self.arg(2), bytes(self.uc.mem_read(self.arg(0), self.arg(1))))
        return 1
    rom_flash_split_read_locked = rom_flash_read_locked

    def rom_flash_auto_read_locked(self):
        self.w32(self.arg(1), self.r32(self.arg(0)))
        return 1

    def rom_flash_write_locked(self):
        a, n = self.arg(0), self.arg(1)
        old, new = self.uc.mem_read(a, n), self.uc.mem_read(self.arg(2), n)
        self.uc.mem_write(a, bytes(x & y for x, y in zip(old, new)))    # flash can only clear bits
        return 1

    def rom_flash_auto_write_locked(self):
        self.w32(self.arg(0), self.r32(self.arg(0)) & self.arg(1))
        return 1

    def rom_flash_erase_locked(self):
        self.uc.mem_write(self.arg(1) & ~0xFFF, b"\xff" * 0x1000)
        return 1

    def rom_flash_get_bank_addr(self): return FLASH_BANKS.get(self.arg(0), (0, 0))[0]
    def rom_flash_get_bank_size(self): return FLASH_BANKS.get(self.arg(0), (0, 0))[1]
    def rom_get_header_addr_by_img_id(self): return APP_BASE if self.arg(0) == 0x2793 else 0
    def rom_get_active_ota_bank_addr(self): return FLASH_BANKS[1][0]
    def rom_get_temp_ota_bank_addr_by_img_id(self): return OTA_TMP
    def rom_get_temp_ota_bank_size_by_img_id(self): return OTA_TMP_SIZE
    def rom_is_ota_support_bank_switch(self): return 0
    def rom_check_header_valid(self): return int(struct.unpack("<H", self.uc.mem_read(self.arg(0) + 4, 2))[0] == self.arg(1))

    def rom_check_image_chksum(self):
        # The real ROM compares a CRC16 or SHA-256 of the image with its header (per the SDK); that code is not
        # available and the header digest could not be reproduced.  Worst-case model: only untouched vendor code passes.
        length = struct.unpack("<I", self.uc.mem_read(self.arg(0) + 8, 4))[0]
        ok = hashlib.sha256(self.uc.mem_read(self.arg(0) + HDR_SIZE, length)).hexdigest().startswith(STOCK_PAYLOAD_SHA256)
        self.ev(f"  ROM integrity check (modelled, worst case): {'vendor image, pass' if ok else 'modified image, FAIL'}", "reset")
        return int(ok)

    def rom_dfu_set_ready(self):
        flags = struct.unpack("<H", self.uc.mem_read(self.arg(0) + 2, 2))[0]
        self.uc.mem_write(self.arg(0) + 2, struct.pack("<H", flags & ~0x80))     # clear "not ready"

    def rom_WDG_SystemReset(self):
        self.reset()
        return BLOCK

    def rom_ftl_save(self):
        off, n = self.arg(1), self.arg(2)
        for i, b in enumerate(self.uc.mem_read(self.arg(0), n)): self.ftl[off + i] = b
        return 0

    def rom_ftl_load(self):
        off, n = self.arg(1), self.arg(2)
        if any(off + i not in self.ftl for i in range(n)): return 1    # nothing saved yet: app falls back to defaults
        self.uc.mem_write(self.arg(0), bytes(self.ftl[off + i] for i in range(n)))
        return 0

    # ---- misc
    def rom_btaon_fast_read_safe(self): return self.aon.get(self.arg(0), 0)
    def rom_btaon_fast_write_safe(self): self.aon[self.arg(0)] = self.arg(1)
    rom_btaon_fast_write = rom_btaon_fast_write_safe
    def rom_platform_random(self): return 0x1234 % (self.arg(0) or 1)
    def rom_SystemCall_Stack(self):
        """Bluetooth host gate. Only service registration is understood; every other call just returns success."""
        if self.arg(0) == 0x3108:                       # server_send_data(conn, service, attr, data, len, type)
            msg = bytes(self.uc.mem_read(self.arg(4), self.arg(5)))
            if self.services[self.arg(2) - 1]["uuid"].startswith(OTA_UUID): self.ota_ack(msg)
            else:
                self.notified.append(msg)
                self.ev(f"BLE notify to phone: {msg.hex(' ')}", "ble_tx", data=msg.hex())
            self.uc.mem_write(self.arg(7), bytes([1]))    # the veneer reads the result through this pointer, not r0
        if self.arg(0) == 0x100: self.evt_q, self.io_q = self.arg(1), self.arg(2)     # gap_start_bt_stack
        if self.arg(0) == 0x3102:                       # server_add_service(p_id, table, length, callbacks)
            uuid = bytes(self.uc.mem_read(self.r32(self.arg(2) + 20), 16)) if self.r32(self.arg(2) + 20) else b""
            self.services.append({"uuid": uuid[::-1].hex(), "write": self.arg(5), "cccd": self.arg(6)})   # callbacks struct is passed by value
            self.uc.mem_write(self.arg(1), bytes([len(self.services)]))
            self.uc.mem_write(self.arg(7), bytes([1]))
            self.ev(f"  Bluetooth service {len(self.services)} registered, uuid={uuid[::-1].hex() or '16-bit'}")
        return 0

    def ble_write(self, data, uuid="6e40fff0"):
        """Phone -> ring: deliver one write to a service's write handler (default: the Colmi command service)."""
        found = [(i + 1, svc) for i, svc in enumerate(self.services) if svc["uuid"].startswith(uuid)]
        if not found: return self.ev(f"BLE write dropped, service {uuid} is not registered (chip just reset?)")
        sid, svc = found[0]
        buf = self.alloc(len(data), zero=False)
        self.uc.mem_write(buf, data)
        if uuid == "6e40fff0": self.ev(f"BLE command from phone: {data.hex(' ')}", "ble_rx", data=data.hex())
        if not self.connected:
            self.connected = True
            self.ev("BLE phone connected", "ble_conn")
            self.qsend(self.io_q, struct.pack("<HHBBH", 0, 2, 0, 2, 0))             # BT status: conn 0 is CONNECTED
            self.qsend(self.evt_q, bytes([2]))                                      # EVENT_IO_TO_APP
        if uuid not in self.subscribed:                 # the phone turns notifications on before its first write
            self.subscribed.add(uuid)
            self.spawn("ble:sub", svc["cccd"], (0, sid, 5, 1))
        post = self.alloc(4)
        self.spawn("ble:write", svc["write"], (0, sid, 2, 1, len(data), buf, post),
                   on_exit=lambda: [self.free[self.sizes.pop(a)].append(a) for a in (buf, post) if a in self.sizes])

    # ---- phone side of a firmware update, as the public web flasher does it (sdk-ref/ota/ATC_RF03_Writer.html)
    def ota_send(self, kind, payload=b""):
        pkt = bytes([0xBC, kind]) + struct.pack("<HH", len(payload), crc16(payload)) + payload
        self.ev(f"UPDATE phone sends packet type {kind}, {len(payload)} payload bytes", "ble_rx", data=pkt[:8].hex())
        for n, i in enumerate(range(0, len(pkt), self.ota_chunk)):      # 200-byte writes by default, a few ms apart
            self.ota_writes += 1
            self.hw[f"ota{self.ota_writes}"] = {"at": self.now + 5 * (n + 1), "interval": None,
                                                "fire": lambda part=pkt[i:i + self.ota_chunk]: self.ble_write(part, OTA_UUID)}

    def ota_start(self, ms, image):
        def begin():
            self.ota = {"image": image, "state": 1, "part": 0}
            self.ota_send(1)
        self.hw["ota"] = {"at": ms, "interval": None, "fire": begin}

    def ota_ack(self, msg):
        o, kind = self.ota, msg[1]
        self.ev(f"UPDATE ring answers type {kind}: {msg.hex(' ')}", "ble_tx", data=msg.hex())
        if not o or kind != o["state"]: return
        img = o["image"]
        if kind == 1:
            self.ota_send(2, bytes([1]) + struct.pack("<IHH", len(img), crc16(img), sum(img) & 0xFFFF))
            o["state"] = 2
        elif kind in (2, 3):
            if kind == 3: o["part"] += 1
            chunk = img[o["part"] * 1024:o["part"] * 1024 + 1024]
            if chunk: self.ota_send(3, struct.pack("<H", o["part"] + 1) + chunk)
            else: self.ota_send(4)
            o["state"] = 3 if chunk else 4
        elif kind == 4:
            self.ota_send(5)
            o["state"] = 5


def crc16(data):
    """CRC-16/MODBUS; 0xFFFF for empty input, as the update protocol expects."""
    crc = 0xFFFF
    for byte in data:
        crc ^= byte
        for _ in range(8): crc = crc >> 1 ^ 0xA001 if crc & 1 else crc >> 1
    return crc


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--ms", type=int, default=3000, help="virtual milliseconds to run")
    ap.add_argument("--quiet-mem", action="store_true", help="do not print each peripheral register access")
    ap.add_argument("--pin", action="append", default=[], metavar="MS:PIN", help="raise a GPIO interrupt, e.g. 3000:13")
    ap.add_argument("--image", default=IMAGE, help="update file to boot instead of the stock firmware")
    ap.add_argument("--trace", metavar="FILE", help="write the run as a .js file for viewer.html, e.g. trace.js")
    ap.add_argument("--bpm", type=int, default=PULSE_BPM, help="pulse rate of the fake heart-rate signal")
    ap.add_argument("--ota", metavar="MS:FILE", help="have the phone install an update file over Bluetooth")
    ap.add_argument("--cmd", action="append", default=[], metavar="MS:HEX", help="send a Colmi command, e.g. 2000:03 (padded and checksummed to 16 bytes)")
    a = ap.parse_args()
    globals()["PULSE_BPM"] = a.bpm
    e = Emu(a.quiet_mem, trace=bool(a.trace), image=a.image)
    for i, spec in enumerate(a.pin):
        ms, pin = map(int, spec.split(":"))
        e.hw[f"pin{i}"] = {"at": ms, "interval": None, "fire": lambda pin=pin: e.raise_pin(pin)}
    for spec in a.cmd:
        ms, hx = spec.split(":")
        e.command(int(ms), bytes.fromhex(hx))
    if a.ota:
        ms, _, path = a.ota.partition(":")
        e.ota_start(int(ms), Path(path).read_bytes())
    e.boot()
    e.run(a.ms)
    e.ev("stopped")
    if a.trace:     # a script, not JSON, so viewer.html can load it from disk without a web server
        Path(a.trace).write_text("window.TRACE = " + json.dumps({"args": sys.argv[1:], "events": e.trace}) + ";", encoding="utf-8")
    print("\ncall and register counts:")
    for k, v in e.stats.most_common(): print(f"  {v:6}  {k}")
    assert e.sched_started, "firmware never reached os_sched_start"


if __name__ == "__main__":
    sys.exit(main())
