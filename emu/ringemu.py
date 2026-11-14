"""Runs the Colmi RY02 app image (Realtek RTL8762E), with the chip's ROM where it can.

Without firmware/rom_rtl8762.bin (or with RINGEMU_NO_ROM=1) the ROM never executes: every ROM function the app
calls is replaced by a Python stand-in looked up by name in the SDK symbol table.  With the dump, the functions in
ROM_REAL (heap, libc helpers, reset and fault paths) run from it; Bluetooth, flash, logging and the hardware stay
stand-ins (see README).  The kernel is a Python model unless RINGEMU_KERNEL=real (ROM needed): then the ROM's
FreeRTOS runs on a Cortex-M0+ exception layer (SysTick, PendSV, NVIC) with time from executed instructions.  With
RINGEMU_STACK=real (real kernel + firmware/flash_ring.bin) the ring's Bluetooth upper stack runs too, down to the ROM's
hci_if API, below which a Python controller and phone answer.
Every peripheral register access is logged.  Sensors, the phone and interrupts are faked.

  python ringemu.py --ms 5000                      boot only
  python ringemu.py --ms 9000 --pin 3000:22        accelerometer interrupt at 3 s: motion sampling starts
  python ringemu.py --ms 34000 --cmd 3000:6901     phone starts real-time heart rate at 3 s
  python ringemu.py --ms 6000 --pin 3000:13        charger interrupt at 3 s
  python ringemu.py --ms 16000 --ota 3000:firmware/RY02_3.00.33_fullaccel.bin --pin 11000:22 --cmd 11500:a104
                                                   phone installs the patched firmware, then streams motion
  python ringemu.py --image FILE ...               boot another update file directly
Add --quiet-mem to hide raw register lines, --trace trace.js to record the run for viewer.html.
"""
import argparse, bisect, collections, hashlib, json, math, os, re, struct, sys
from pathlib import Path
from unicorn import Uc, UcError, UC_ARCH_ARM, UC_MODE_THUMB, UC_HOOK_CODE, UC_HOOK_BLOCK, UC_HOOK_INTR, \
    UC_HOOK_MEM_READ, UC_HOOK_MEM_WRITE, UC_MEM_WRITE, UC_PROT_READ, UC_PROT_EXEC
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
ROM_HARDFAULT = 0xFFF20                     # fake ROM HardFault handler, in RAM vector slot 3 (0x20000C) from boot
CALL_RET = 0xFFF30                          # fake return address of Emu.call
RAM_END = 0x216000                          # the chip's 88 KB of RAM end here (SDK mem_config.h)
# The ROM dump (FW16b, untracked: Realtek's code).  Optional: without it everything below ROM_REAL is a stand-in.
ROM_FILE = None if os.environ.get("RINGEMU_NO_ROM") else HERE / "firmware" / "rom_rtl8762.bin"
BUF_RAM = 0x280000                          # the chip's buffer RAM; the ROM's second heap (type 1) is at 0x280790..0x284000
# ROM functions that run from the dump.  Everything they call that is not listed (kernel, logging, AON, watchdog,
# flash) is still a stand-in; unnamed ROM helpers reached from these run for real.
ROM_REAL = {"__aeabi_memcpy", "__aeabi_memcpy4", "__aeabi_memcpy8", "__rt_memcpy", "memcpy", "__aeabi_memset", "_memset_w",
            "_memset", "__aeabi_memclr", "__rt_memclr", "__aeabi_memclr4", "__aeabi_memclr8", "__rt_memclr_w", "memcmp",
            "strlen", "os_mem_alloc_intern", "os_mem_zalloc_intern", "os_mem_aligned_alloc_intern", "os_mem_free",
            "os_mem_aligned_free", "os_mem_peek", "pvPortMalloc", "pvPortMalloc_auto_type", "vPortFree",
            "xPortGetFreeHeapSize", "xPortGetMinimumEverFreeHeapSize", "update_ram_layout", "reset_reason_get",
            "WDG_SystemReset", "vAssertHandler"}
# Kernel mode.  "model": tasks, queues, timers and delays are Python stand-ins (round robin, no preemption).
# "real" (needs the ROM): the ROM's FreeRTOS and its osif wrappers run, on the exception layer below (SysTick,
# PendSV, NVIC); the app's tasks run under the real scheduler.
KERNEL = os.environ.get("RINGEMU_KERNEL", "model")
KERNEL_ROM = ((0xE884, 0x115D2), (0x12C30, 0x14000))   # FreeRTOS lists..heap, osif; real in the real-kernel mode
# Bluetooth stack.  "model": SystemCall_Stack is a Python stand-in.  "real" (real kernel + the ring's flash dump): the
# upper stack image (0x80E000, UpperStackTask) runs from the dump; below it the ROM's hci_if API is the boundary and a
# Python controller and phone answer there (docs/FIRMWARE.md "Upper stack").
STACK = os.environ.get("RINGEMU_STACK", "model")
FLASH_FILE = None if os.environ.get("RINGEMU_NO_ROM") else HERE / "firmware" / "flash_ring.bin"   # personal logs inside: untracked
STACK_SPANS = ((0x802000, 0x400), (0x80E000, 0x14600))     # OTA header (image table), upper stack code + .data image
PATCH_RAM = HERE / "firmware" / "patch_ram.bin"   # ROM patch RAM part read off a running ring (0x203800..); flash copy is AES-encrypted
HCI_IF = {0x177A4: "hci_if_open", 0x177CC: "hci_if_close", 0x17804: "hci_if_write", 0x178CC: "hci_if_rx_done",
          0x178FA: "hci_if_rx_done", 0x7A92: "ftl_load_intern", 0x79BE: "ftl_save_intern"}  # unnamed ROM entries the stack calls
LE_ACL_LEN, LE_ACL_BUFS = 251, 10   # the ROM lower stack's answer to LE Read Buffer Size (rombootprobe.py --hci)
# Command Complete parameters the ROM's lower stack returns (ROM default config, after the status byte) for the commands
# the upper stack sends before a link: recorded with rombootprobe.py --hci, checked by test_rom_lower_stack_answers_hci.
ROM_HCI = {0x1001: bytes.fromhex("0a0c000a5d006287"), 0x1003: bytes.fromhex("fffffffedbff7b87"),
           0x1002: bytes.fromhex("b3ffbb03c0feffff3fffe49ff30fa8fe07e783ff1c00000061f7ffff7f000000fec0f9ffffffffe7ff030000f801") + bytes(18),
           0x1005: bytes.fromhex("fd030000000000"), 0x2002: struct.pack("<HB", LE_ACL_LEN, LE_ACL_BUFS),
           0x2003: bytes.fromhex("fd7ffe030e000000"), 0x201C: bytes.fromhex("ffffffffff030000"), 0x200F: b"\x08",
           0x202A: b"\x08", 0x204B: bytes.fromhex("a600")}
CONN_MS = 49                        # connection interval: the phone's 48.75 ms, in whole ms
ROM_DATA = (0x42E6C, 0x2000E8, 0x10E0)      # the reset handler's .data copy (0x8D52): ROM source, RAM, length
ROM_BSS = (0x2011C8, 0x1F8C)                # and its .bss clear; it stops at the reset-reason word 0x203154
SCS = 0xE000E000                            # Cortex-M system control space: SysTick, NVIC, SCB
EXC_PAGE = 0xFFFFF000                       # mapped so that a branch to EXC_RETURN (0xFFFFFFF1/9/D) is caught
BT_STUB, BT_DONE = 0x23E000, 0xFFF40        # real-kernel mode: code of the Bluetooth-stack stand-in task; its "done" call
BOOT_NEXT = 0xFFF50                         # real-kernel mode: fake return address between the app's boot entries
# Flash timing with interrupts off (the ROM's flash lock): typical SPI NOR figures, not measured on this ring
FLASH_ERASE_MS, FLASH_PAGE_MS = 45, 0.7     # 4 KB sector erase; one 256-byte page program
# Endless loops in the ROM, where the chip waits for its watchdog: name -> address
ROM_SPINS = {"wdg_reset_spin": 0x12B08,     # end of WDG_SystemReset: watchdog armed with a tiny timeout, reset follows
             "assert_spin": 0x8D38,         # vAssertHandler with asserts on: interrupts off, waits forever
             "hardfault_spin": 0x4782}      # HardFault handler when its reset bit (0x2003C2 bit 1) is clear
SPIN = b"\xfe\xe7"  # b . : in the HardFault handler (the patch's fault hook, FW16g) a hang until the watchdog
AON_LATCH = range(0x1C0, 0x1CC, 2)  # watchdog latch: pc >> 1 (lo, hi), lr (lo, hi), psr (lo, hi); read-only, survives a reset
WDG_TIMEOUT_MS = 4000  # assumption: the SDK's default ROM watchdog (4 s, reset all); whether it runs on the ring is not known
HEAP_WORDS = 0x2014D8   # xFreeBytesRemaining[2], xMinimumEverFreeBytesRemaining[2], xHeapTotalSize[2]; [0] data, [1] buffer
UNTIL, SLICE = 0xFFFFFFF0, 2_000_000
FOREVER = 0xFFFFFFFF
BLOCK = object()
CPU_HZ = 40_000_000  # assumption: timers run from the 40 MHz system clock
RTC_REGS = {0x00: "CR0", 0x04: "INT_CLR", 0x08: "INT_SR", 0x0C: "PRESCALER", 0x10: "COMP0", 0x14: "COMP1",
            0x18: "COMP2", 0x1C: "COMP3", 0x38: "PRE_COMP", 0x3C: "BACKUP"}
TIMER_VEC = {"TIM2": 20, "TIM3": 19, "TIM4": 48, "TIM5": 49}          # RAM vector table slots
SHARED_VEC = {48: 24, 49: 24}   # second-level slots -> their NVIC line (Timer4_5_IRQn 8); ROM 0x47E8 reads each timer's status
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
         "platform_delay_us", "WDG_Restart", "btaon_fast_read_safe", "btaon_fast_write_safe", "btaon_fast_write",
         "vTaskSuspendAll", "xTaskResumeAll", "btaon_fast_read_safe_8b", "btaon_fast_write_8b", "btaon_fast_write_safe_8b",
         "bt_done", "boot_next", "log_snoop", "trace_binary", "hci_if_write", "hci_if_rx_done"}
# guess: SDK default flash map; this ring's real map is unknown apart from the app address
FLASH_BANKS = {0: (0x801000, 0x1000), 1: (0x802000, 0x55000), 3: (0x85B000, 0x25000), 6: (0x857000, 0x4000)}


class Task:
    def __init__(self, name, ctx, on_exit=None):
        self.name, self.ctx, self.on_exit, self.wait = name, ctx, on_exit, None


class Emu:
    def __init__(self, quiet_mem=False, trace=False, image=IMAGE, rom=ROM_FILE, kernel=None, stack=None):
        self.quiet_mem = quiet_mem
        self.rom = Path(rom).read_bytes() if rom and Path(rom).exists() else None
        self.kernel = kernel or KERNEL
        assert self.kernel in ("model", "real"), self.kernel
        if self.kernel == "real" and not self.rom:
            raise RuntimeError("the real kernel runs from the ROM dump: firmware/rom_rtl8762.bin missing or RINGEMU_NO_ROM set")
        self.real = self.kernel == "real"
        self.stack = stack or (STACK if self.real else "model")
        assert self.stack in ("model", "real"), self.stack
        if self.stack == "real" and not (self.real and FLASH_FILE and FLASH_FILE.exists()):
            raise RuntimeError("the real upper stack needs the real kernel and the ring's flash dump firmware/flash_ring.bin")
        self.upper = self.stack == "real"
        self.trace = [] if trace else None
        self.syms = {}
        for n, v in re.findall(r"(\w+)\s*=\s*(0x[0-9a-fA-F]+)", (SDK / "rom_symbol_gcc.axf").read_text()):
            self.syms.setdefault(int(v, 16) & ~1, n)
        real = {a for a, n in self.syms.items() if n in ROM_REAL}
        if self.real:
            real |= {a for a, n in self.syms.items() if any(lo <= a < hi for lo, hi in KERNEL_ROM)
                     or n in ("RamVectorTableInit", "RamVectorTableUpdate", "ulSetInterruptMaskFromISR", "vClearInterruptMaskFromISR")}
        if self.upper:      # the app's gate into the stack runs (it calls the stack's dispatcher at 0x2016AC); hci_if stops
            real.add(self.sym("SystemCall_Stack"))
            self.syms.update(HCI_IF)
        self.syms.update({DELAY_US: "platform_delay_us", DELAY_MS: "platform_delay_ms", ROM_HARDFAULT: "HardFault_Handler",
                          CALL_RET: "call_ret", BT_DONE: "bt_done", BOOT_NEXT: "boot_next"})
        if self.rom:
            self.syms.update({a: n for n, a in ROM_SPINS.items()})
            # ROM addresses where execution stops for a stand-in: every named entry not in ROM_REAL, and the spins
            self.stops = {a for a in self.syms if a < len(self.rom) and a not in real}
        self.hardfault_vec = 0x101 if self.rom else ROM_HARDFAULT | 1      # what the ROM leaves in vector slot 3
        self.hung = None            # set while the chip spins with interrupts off (assert, fault): only the watchdog runs
        self.ram_syms = sorted(a for a in self.syms if 0x200000 <= a < 0x300000)
        bases = {}
        for n, v in re.findall(r"#define\s+(\w+?)(?:_REG)?_BASE\s+0x(4\w{7})UL", (SDK / "rtl876x.h").read_text()):
            bases.setdefault(int(v, 16), n)
        self.periph = sorted(bases.items())

        # The chip is a Cortex-M0+: of the 32-bit instructions it only has bl, msr and mrs.  UC_MODE_MCLASS would
        # force a Cortex-M33, which also runs the long jumps (b.w) that crash the real ring.
        self.uc = uc = Uc(UC_ARCH_ARM, UC_MODE_THUMB)
        uc.ctl_set_cpu_model(UC_CPU_ARM_CORTEX_M0)
        uc.mem_map(0, 0x100000)
        uc.mem_write(0, b"\x70\x47" * 0x80000)             # ROM = all "bx lr"; calls are intercepted anyway
        if self.rom: uc.mem_write(0, self.rom)               # past the dump's end it stays "bx lr", and stops (on_rom)
        uc.mem_protect(0, 0x100000, UC_PROT_READ | UC_PROT_EXEC)    # a store into ROM faults, as on the ring (FW17)
        uc.mem_map(0x200000, 0x40000)
        uc.mem_map(BUF_RAM, 0x10000)
        uc.mem_map(0x800000, 0x80000)
        uc.mem_write(0x800000, b"\xff" * 0x80000)
        uc.mem_write(APP_BASE, Path(image).read_bytes()[OTA_HDR:])
        if self.upper:      # only the image table and the upper stack image are taken from the dump (no logs, no FTL)
            flash = FLASH_FILE.read_bytes()
            for a, n in STACK_SPANS: uc.mem_write(a, flash[a - 0x800000:a - 0x800000 + n])
            if PATCH_RAM.exists(): uc.mem_write(0x803000, flash[0x3000:0xD000])    # the patch's plain flash part
        # Factory config block the firmware expects (signature, length, one item: Bluetooth address at offset 6).
        # Without it the firmware rewrites the block and resets on every boot. The address value is made up.
        mac_item = struct.pack("<HB", 6, 6) + bytes.fromhex("112233445566") + bytes([0xFF]) * 6
        uc.mem_write(0x801400, struct.pack("<IH", 0x8721BEE2, len(mac_item)) + mac_item)
        uc.mem_map(0x40000000, 0x100000)
        uc.mem_map(0xE0000000, 0x100000)
        if self.real:       # a hook per stop address: the kernel's ROM code runs at full speed in between
            for a in self.stops | {0x29C, 0x208, 0x11124}: uc.hook_add(UC_HOOK_CODE, self.on_rom, begin=a, end=a)
            uc.hook_add(UC_HOOK_CODE, self.on_rom, begin=len(self.rom), end=0xFFFFF)
            uc.mem_map(EXC_PAGE, 0x1000)
            uc.hook_add(UC_HOOK_BLOCK, self.on_block)
            uc.hook_add(UC_HOOK_MEM_READ | UC_HOOK_MEM_WRITE, self.on_scs, begin=SCS, end=SCS + 0xFFF)
            uc.hook_add(UC_HOOK_INTR, self.on_intr)
            for a, name in self.syms.items():      # the osif calls show in the log as the stand-ins' calls do
                if name.startswith("os_") and 0x12C30 <= a < 0x14000 and name not in QUIET:
                    uc.hook_add(UC_HOOK_CODE, lambda uc, addr, s, _: self.ev(f"{self.syms[addr]}({', '.join(f'{self.arg(i):#x}' for i in range(4))})", "rom", name=self.syms[addr]), begin=a, end=a)
        else: uc.hook_add(UC_HOOK_CODE, self.on_rom, begin=0, end=0xFFFFF)
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
        self.recording = None       # [(x, y, z) in mg, ...]: replayed, looping, instead of the fake motion
        self.click_src, self.last_tap = 0, None     # fake accelerometer's tap detector, see tap()
        self.ppg_drained = self.ppg_bytes = 0
        self.services, self.connected, self.subscribed, self.notified = [], False, set(), []
        self.ota, self.ota_writes, self.ota_chunk = None, 0, 200     # phone-side state of a firmware update in progress
        self.gpio_int = 0           # latched GPIO interrupt bits
        self.tim_int = set()        # timers with an interrupt waiting (status register 0x10)
        self.bus, self.pending = {}, None    # fake I2C/SPI buses and the one with an unprinted transfer
        self.app_ram, self.seen_rom_ram = set(), set()
        self.last, self.repeat = None, 0
        self.sched_started = False
        self.stats = collections.Counter()
        self.resets = 0
        self.cyc, self.wdg_kick, self.in_call = 0, 0, 0     # real kernel: CPU cycles so far; last watchdog kick (ms)
        self.exc_reset()
        self.rom_ram_init()

    def rom_ram_init(self):
        """What the real ROM leaves in its own RAM before it starts the app."""
        if self.real:       # the reset handler's own steps (0x8D52, 0x8D74): .data, .bss, RAM vector table
            src, dst, n = ROM_DATA
            self.uc.mem_write(dst, self.rom[src:src + n])
            self.uc.mem_write(ROM_BSS[0], bytes(ROM_BSS[1]))
            self.uc.mem_write(0x200000, self.rom[0x42D84:0x42D84 + 0xE8])
            self.w32(SCS + 0xD08, 0x200000)             # VTOR, as RamVectorTableInit(0x200000) sets it
            self.w32(0x2011CC, 0x200000)                # and the table address RamVectorTableUpdate uses
            self.app_ram.update(range(0x2000E8, 0x203158))
        self.w32(self.sym("platform_delay_us"), DELAY_US | 1)
        self.w32(self.sym("platform_delay_ms"), DELAY_MS | 1)
        self.preset(0x2002DC, bytes([self.uc.mem_read(0x2002DC, 1)[0] | 1]))   # OTP->stack_en; SystemInit returns at once without it
        if self.real:       # 0x2003C2 keeps the ROM default 0x28 (the ring reads the same): a HardFault spins
            # The RAM layout the app's SystemInit sets again (update_ram_layout(0x7000, 0x7400)); on the ring the ROM
            # has it from the flash config before os_init allocates (the ring's heap total 0x73F8 agrees).
            self.uc.mem_write(0x200384, struct.pack("<2I", 0x7000, 0x7400))
            self.call(0x12B2D)
            self.call(0x12B55)
            if self.upper and PATCH_RAM.exists():     # as ROM 0x4B60(0x2792) does: load part in place, blx header.exe_base
                self.uc.mem_write(0x203800, PATCH_RAM.read_bytes())     # shortcut: never run (no dump yet); unverified
                self.call(0x203801)
            self.call(self.sym("os_init"))
            self.call(self.sym("os_timer_init"))
            return
        self.preset(0x20000C, struct.pack("<I", self.hardfault_vec))    # the ROM's vector table copy: its HardFault handler
        if not self.rom: return
        # The ROM's .data image (0x2000E8..0x2011C8, from ROM 0x42E6C) lies past the dump, so its config defaults are
        # unknown; these are the ones the real ROM code here depends on.  Its .bss (0x2011C8..0x203154) is zero.
        self.app_ram.update(range(0x2011C8, 0x203158))  # and the reset-reason word, read on purpose at every boot
        self.preset(0x200380, struct.pack("<I", APP_GLOBALS))   # OTP heap base; update_ram_layout adds 0x7000: heap 0x20EC00
        self.preset(0x20038C, struct.pack("<I", 0x3870))        # buffer heap size: the ring's xHeapTotalSize[1] is 0x3868
        self.preset(0x2003C2, bytes([2]))   # assumption: a HardFault resets (WDG_SystemReset(3, 5)) instead of spinning
        self.call(0x12B2D)                  # boot: reset reason from the magic at 0x203154 and AON register 0x15
        self.call(0x12B55)                  # boot: magic and reason 1 back in place for the next reset

    def reset(self, mode=None):
        """Chip reset: the ROM's RAM, peripherals and tasks are lost; flash, saved settings and the sensors' state stay.
        Assumption, not known for the chip: the app's RAM (APP_GLOBALS..RAM_END) keeps its bytes through a reset.
        With the ROM: the reset-reason word 0x203154 survives (the ring shows it does); mode 3 (the watchdog's
        "reset all", per the SDK's names) also clears the always-on registers, other modes keep them."""
        self.resets += 1
        self.ev(f"CHIP RESET #{self.resets}" + (f" (watchdog mode {mode})" if mode is not None else ""), "reset")
        self.rom_boot_copy()
        magic = bytes(self.uc.mem_read(0x203154, 4))
        for base, size in ((0x200000, APP_GLOBALS - 0x200000), (RAM_END, 0x240000 - RAM_END), (BUF_RAM, 0x10000),
                           (0x40000000, 0x100000), (0xE0000000, 0x100000)):
            self.uc.mem_write(base, bytes(size))
        if self.rom: self.uc.mem_write(0x203154, magic)
        if mode == 3: self.aon = {k: v for k, v in self.aon.items() if k in AON_LATCH}   # SDK: "partial AON" survives
        self.hung = None
        self.tasks, self.cur = [], None
        self.heap, self.free, self.sizes = HEAP, collections.defaultdict(list), {}
        self.queues, self.sems, self.timers = {}, {}, {}
        self.hw = {k: h for k, h in self.hw.items() if h["interval"] is None or h.get("keep")}    # keep outside events, drop chip timers
        self.gpio_int, self.app_ram, self.tim_int = 0, set(), set()
        self.services, self.connected, self.subscribed = [], False, set()         # the phone's link drops
        self.exc_reset()
        self.wdg_kick = self.now
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
        if self.real:       # one CPU on the ROM's stack (MSP), thread mode; app_main starts the scheduler and never returns
            self.bt_task()
            self.boot_chain = [lambda: APP_BASE + 0x400 | 1, lambda: ptr("app_pre_main"), lambda: ptr("app_main")]
            if self.upper:  # ROM 0x55F6: upperstack_entry (set by SystemInit from the image table) before app_main
                self.boot_chain.insert(2, lambda: ptr("upperstack_entry"))
                self.hci = {"cb": 0, "conn": None, "bufs": {}}
            self.uc.reg_write(UC_ARM_REG_SP, 0x203800)
            self.uc.reg_write(UC_ARM_REG_LR, BOOT_NEXT | 1)
            self.uc.reg_write(UC_ARM_REG_PC, BOOT_NEXT)
            return
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

    def spawn(self, name, pc, args=(), stack=0x2000, on_exit=None, base=None):
        if self.real:       # an outside callback (Bluetooth stack, phone): the stack's stand-in task runs it
            self.bt_out.append(("call", pc | 1, args, on_exit))
            return
        ctx = dict.fromkeys(REGS, 0)
        ctx.update(zip(REGS, args[:4]))
        base = base or self.alloc(stack)
        top = base + stack - 4 * len(args[4:])           # arguments past the fourth go on the stack
        self.uc.mem_write(top, struct.pack(f"<{len(args[4:])}I", *args[4:]))
        ctx.update({UC_ARM_REG_SP: top, UC_ARM_REG_LR: EXIT | 1, UC_ARM_REG_PC: pc & ~1})
        self.tasks.append(Task(name, ctx, on_exit))
        self.tasks[-1].stack = base

    # ---- hooks
    def on_rom(self, uc, addr, size, _):
        if self.rom and addr < len(self.rom) and addr not in self.stops:
            if addr in (0x29C, 0x208): self.ev(f"{self.syms[addr]}({', '.join(f'{self.arg(i):#x}' for i in range(4))})", "rom", name=self.syms[addr])
            if addr == 0x11124 and not self.r32(0x2016C8):     # set = auto-type's first try; the buffer heap is next
                self.ev(f"HEAP: allocation failed, type {self.arg(0)}, {self.arg(1)} bytes, {self.arg(2)} free", "heap")
            return                                      # real ROM code runs
        self.romcall = True
        uc.emu_stop()

    def call(self, addr, *args, isr=False):
        """Run a real ROM function to its return, from Python, between slices; stand-ins it reaches run as usual.
        Arguments past the fourth go on the stack.  isr: run it as an interrupt handler would (IPSR set, so the
        osif wrappers take their FromISR paths); the caller makes sure interrupts are on."""
        saved = {reg: self.r(reg) for reg in REGS}
        banks = [self.r(r) for r in (UC_ARM_REG_XPSR, UC_ARM_REG_MSP, UC_ARM_REG_PSP)]
        if isr: self.uc.reg_write(UC_ARM_REG_IPSR, 16 + 31)     # a pseudo interrupt line nothing else uses
        for reg, v in zip(REGS, args[:4]): self.uc.reg_write(reg, v)
        if args[4:]: self.uc.mem_write(HEAP_END - 0x100, struct.pack(f"<{len(args[4:])}I", *args[4:]))
        self.uc.reg_write(UC_ARM_REG_SP, HEAP_END - 0x100)   # scratch stack at the top of the emulator's own heap
        self.uc.reg_write(UC_ARM_REG_LR, CALL_RET | 1)
        self.in_call += 1
        pc = addr
        while pc != CALL_RET:
            self.uc.emu_start(pc | 1, UNTIL, count=SLICE)
            pc = self.r(UC_ARM_REG_PC)
            if pc != CALL_RET:
                assert self.dispatch(pc) is not BLOCK, f"Emu.call({addr:#x}) reached a blocking stand-in"
                pc = self.r(UC_ARM_REG_PC)
        self.in_call -= 1
        ret = self.r(UC_ARM_REG_R0)
        self.uc.reg_write(UC_ARM_REG_XPSR, banks[0])        # IPSR first: it picks which bank SP below is written to
        self.uc.reg_write(UC_ARM_REG_MSP, banks[1]); self.uc.reg_write(UC_ARM_REG_PSP, banks[2])
        for reg, v in saved.items(): self.uc.reg_write(reg, v)
        return ret

    def latch(self, pc, lr, psr=1 << 24):
        """The watchdog firing latches the CPU's pc (bit 0 dropped), lr and psr into AON 0x1C0..0x1CA, 16 bits a register
        (SDK print_reset_reason).  psr is a model: Thumb bit only."""
        self.aon.update(zip(AON_LATCH, (pc >> 1 & 0xFFFF, pc >> 17 & 0xFFFF, lr & 0xFFFF, lr >> 16, psr & 0xFFFF, psr >> 16)))

    def rom_wdg_reset_spin(self):
        self.latch(self.r(UC_ARM_REG_PC), self.r(UC_ARM_REG_LR))
        self.reset(mode=self.r(UC_ARM_REG_R6))         # r6: the mode WDG_SystemReset was called with
        return BLOCK

    def hang(self, why):
        """Interrupts off, the CPU spinning: nothing runs and the phone hears nothing until the watchdog resets the chip."""
        self.ev(f"HANG ({why}): interrupts off, chip silent; watchdog reset in {WDG_TIMEOUT_MS} ms (assumed)", "reset")
        self.hung = why
        if self.real: return BLOCK      # the CPU stays on the spin; run_real's watchdog ends it (last kick + 4 s)
        pc, lr = self.r(UC_ARM_REG_PC), self.r(UC_ARM_REG_LR)
        self.hw["wdg"] = {"at": self.now + WDG_TIMEOUT_MS, "interval": None, "fire": lambda: (self.latch(pc, lr), self.reset(mode=3))}
        return self.block(FOREVER)

    def rom_assert_spin(self): return self.hang("ROM assert")
    def rom_hardfault_spin(self): return self.hang("HardFault")

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
        if reg == 0x39:                                 # CLICK_SRC, cleared by reading it
            v, self.click_src = self.click_src, 0
            return v
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
            if self.recording: mg = self.recording[k % len(self.recording)]
            mg = mg[i // 2]
            raw = (int(mg // 8) << 6) & 0xFFFF          # 10-bit left-justified, 8 mg per step at +-4 g
            return raw >> 8 if i % 2 else raw & 0xFF
        return v

    def accel_regs(self):
        return next((st["devs"][ACCEL] for st in self.bus.values() if ACCEL in st["devs"]), {})

    def click(self, mg=4000):
        """One jolt of mg.  Our model of a LIS3DH click detector, not the chip: a jolt above CLICK_THS (31 mg a step
        at +-4 g) sets the single-click bit if CLICK_CFG enables single clicks.  Jolts within TIME_LATENCY of that
        one are ignored; the next one up to TIME_WINDOW later also sets the double-click bit if double clicks are
        enabled then (1 step = 1/25 s).  Either raises the interrupt pin when CTRL_REG3 routes clicks to it.
        CLICK_SRC keeps its bits until it is read, latched or not."""
        regs = self.accel_regs()
        cfg, step = regs.get(0x38, 0), 1000 // ACCEL_HZ
        gap = self.now - self.last_tap if self.last_tap is not None else None
        if mg <= (regs.get(0x3A, 0) & 0x7F) * 4000 // 128 or gap is not None and gap < regs.get(0x3C, 0) * step: return
        double = cfg & 0x2A and gap is not None and gap <= (regs.get(0x3C, 0) + regs.get(0x3D, 0)) * step
        src = (0x50 if cfg & 0x15 else 0) | (0x60 if double else 0)     # IA | SCLICK, IA | DCLICK
        self.last_tap, self.click_src = None if double else self.now, self.click_src | src
        self.ev(f"tap: CLICK_SRC {self.click_src:#04x}", "irq")
        if src and regs.get(0x22, 0) & 0x80: self.raise_pin(22)

    def tap(self, at_ms):
        """Schedule one tap on the ring."""
        self.hw[f"tap{at_ms}"] = {"at": at_ms, "interval": None, "fire": self.click}

    def wear(self, from_ms):
        """The ring is on a moving finger from then on: the fake motion changes 250 mg between samples, which is
        a jolt above the stock CLICK_THS of 5 (156 mg) on every sample."""
        self.hw["wear"] = {"at": from_ms, "interval": 1000 // ACCEL_HZ, "fire": lambda: self.still or self.click(250), "keep": True}

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
        if self.real:       # vector slot n is exception n; the NVIC decides when it runs
            if slot in SHARED_VEC: self.tim_int.add(name)
            self.pend(SHARED_VEC.get(slot, slot))
            return
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
        if off == 0x10 and not w: return int(name in self.tim_int)     # interrupt status (the TIM4/5 dispatcher reads it)
        if off == 0x0C: self.tim_int.discard(name)                      # end of interrupt
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
        if self.real: return self.run_real(limit_ms)
        spins = 0
        while True:
            if self.cur is None:
                if not self.schedule(limit_ms): return
            self.romcall = False
            try:
                self.uc.emu_start(self.r(UC_ARM_REG_PC) | 1, UNTIL, count=SLICE)
            except UcError as e:
                self.ev(f"CRASH {e}: pc={self.r(UC_ARM_REG_PC):#x} lr={self.r(UC_ARM_REG_LR):#x} sp={self.r(UC_ARM_REG_SP):#x}")
                if not self.cur or self.cur.name == "HardFault": return     # a fault in the fault handler: lockup
                self.hardfault()
                continue
            if not self.romcall:
                if self.cur.name == "HardFault" and self.uc.mem_read(self.r(UC_ARM_REG_PC), 2) == SPIN:
                    self.hang("HardFault: b . in the handler")
                    self.cur = None
                    continue
                spins += 1
                if spins == 3:
                    self.ev(f"STUCK: {3 * SLICE} instructions without a ROM call, pc={self.r(UC_ARM_REG_PC):#x} lr={self.r(UC_ARM_REG_LR):#x}")
                    return
                continue
            spins = 0
            self.dispatch(self.r(UC_ARM_REG_PC))

    def hardfault(self):
        """Exception entry as on a Cortex-M0+ for a task (thread mode, PSP): r0-r3, r12, lr, pc, xpsr stacked below the
        task's sp, the handler from RAM vector slot 3 runs on its own stack with lr = EXC_RETURN 0xFFFFFFFD."""
        frame = self.r(UC_ARM_REG_SP) - 32
        regs = [self.r(r) for r in REGS[:4]] + [self.r(UC_ARM_REG_R12), self.r(UC_ARM_REG_LR), self.r(UC_ARM_REG_PC), 1 << 24]
        self.uc.mem_write(frame, struct.pack("<8I", *regs))
        self.uc.reg_write(UC_ARM_REG_PSP, frame)
        self.tasks.remove(self.cur)
        self.cur = None
        kept = {r: self.r(r) for r in REGS[4:12]}          # r4-r11: exception entry leaves them as they were
        self.spawn("HardFault", self.r32(0x20000C), stack=0x400)
        self.tasks[-1].ctx.update(kept | {UC_ARM_REG_LR: 0xFFFFFFFD})

    def rom_HardFault_Handler(self):
        """Model: the stock fault handling ends in a chip reset (the real ring restarts); the ROM's handler is not available."""
        self.reset()
        return BLOCK

    # ---- Cortex-M0+ exceptions (real-kernel mode).  Unicorn runs msr/mrs, cps and wfi but takes no exception: no
    # stacking, no EXC_RETURN, no NVIC, no SysTick.  This layer does what the core and the NVIC do, at translation-block
    # boundaries (cps, msr and isb end a block, so a pend or an unmask takes effect right after them).
    def exc_reset(self):
        self.exc_pend, self.exc_active, self.nv_en, self.exc_live = set(), [], 0, False
        self.st_next, self.st_flag = None, False        # SysTick: cycle of its next wrap while it runs; COUNTFLAG
        self.bt_out, self.bt_exit = collections.deque(), collections.deque()
        self.sysreset, self.stop_cyc, self.idle_tcb, self.idle_until = False, 0, None, None
        for reg, v in ((UC_ARM_REG_XPSR, 1 << 24), (UC_ARM_REG_CONTROL, 0), (UC_ARM_REG_PRIMASK, 0), (UC_ARM_REG_MSP, 0x203800)):
            self.uc.reg_write(reg, v)

    def pend(self, n, on=True):
        """Set or clear exception n pending (None: only recheck); exc_live = something pending is enabled, which the
        per-block check reads instead of the full rule."""
        if n is not None: (self.exc_pend.add if on else self.exc_pend.discard)(n)
        self.exc_live = any(n < 16 or self.nv_en >> (n - 16) & 1 for n in self.exc_pend)

    def exc_prio(self, n):
        """Priority of exception n, lower wins: NMI -2, HardFault -1, SVCall / PendSV / SysTick from SHPR2/3, IRQs
        from NVIC_IPR.  The M0+ keeps 2 bits."""
        if n <= 3: return n - 4
        return self.uc.mem_read(SCS + {11: 0xD1F, 14: 0xD22, 15: 0xD23}.get(n, 0x400 + n - 16), 1)[0] & 0xC0

    def exc_ready(self):
        """The pending exception the core takes now, or None: enabled, and above the running priority (PRIMASK = 0)."""
        if not self.exc_live: return None
        cur = min((self.exc_prio(a) for a in self.exc_active), default=256)
        if self.r(UC_ARM_REG_PRIMASK): cur = min(cur, 0)
        n = min((n for n in self.exc_pend if n < 16 or self.nv_en >> (n - 16) & 1), key=lambda n: (self.exc_prio(n), n), default=None)
        return n if n is not None and self.exc_prio(n) < cur else None

    def exc_enter(self, n):
        """Entry: r0-r3, r12, lr, pc, xPSR onto the active stack (8-byte aligned; xPSR bit 9 = one padding word), handler
        mode on MSP, lr = EXC_RETURN, pc = the vector at VTOR + 4n."""
        handler, ctrl, sp = bool(self.exc_active), self.r(UC_ARM_REG_CONTROL), self.r(UC_ARM_REG_SP)
        frame = (sp - 32) & ~7
        regs = [self.r(r) for r in (UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_R12, UC_ARM_REG_LR, UC_ARM_REG_PC)]
        self.uc.mem_write(frame, struct.pack("<8I", *regs, self.r(UC_ARM_REG_XPSR) & ~0x200 | 1 << 24 | (0x200 if sp & 4 else 0)))
        self.uc.reg_write(UC_ARM_REG_SP, frame)
        self.uc.reg_write(UC_ARM_REG_IPSR, n)               # handler mode: SP is MSP from here
        self.uc.reg_write(UC_ARM_REG_CONTROL, ctrl & ~2)
        self.uc.reg_write(UC_ARM_REG_LR, 0xFFFFFFF1 if handler else 0xFFFFFFFD if ctrl & 2 else 0xFFFFFFF9)
        self.pend(n, False); self.exc_active.append(n)
        self.stats[f"exception {n}"] += 1
        self.uc.reg_write(UC_ARM_REG_PC, self.r32(self.r32(SCS + 0xD08) + 4 * n) & ~1)

    def exc_return(self, excret):
        """A branch to EXC_RETURN in handler mode: unstack from MSP or PSP (bit 2), back to thread or handler mode."""
        n = self.exc_active.pop()
        assert self.r(UC_ARM_REG_IPSR) == n, f"exception return from {self.r(UC_ARM_REG_IPSR)}, active {n}"
        if excret & 4: self.uc.reg_write(UC_ARM_REG_CONTROL, self.r(UC_ARM_REG_CONTROL) | 2)
        sp = self.r(UC_ARM_REG_PSP if excret & 4 else UC_ARM_REG_MSP)
        *regs, xpsr = struct.unpack("<8I", self.uc.mem_read(sp, 32))
        self.uc.reg_write(UC_ARM_REG_XPSR, xpsr & ~0x200)  # flags and IPSR; IPSR 0 = thread mode, SP the bank CONTROL picks
        self.uc.reg_write(UC_ARM_REG_SP, sp + 32 + (4 if xpsr & 0x200 else 0))
        for reg, v in zip((UC_ARM_REG_R0, UC_ARM_REG_R1, UC_ARM_REG_R2, UC_ARM_REG_R3, UC_ARM_REG_R12, UC_ARM_REG_LR, UC_ARM_REG_PC), regs):
            self.uc.reg_write(reg, v)

    def on_block(self, uc, addr, size, _):
        if addr >= EXC_PAGE: return uc.emu_stop()          # EXC_RETURN 0xFFFFFFF9 / 0xFFFFFFFD (0xFFFFFFF1 is UNTIL)
        if size == 2 and 3 in self.exc_active and not self.hung and uc.mem_read(addr, 2) == SPIN:   # only NMI is above
            self.hang("HardFault: b . in the handler")
            return uc.emu_stop()
        self.cyc += size >> 1   # shortcut: one cycle per 16-bit instruction (the M0+ takes 2 for loads and taken branches); calibrate if timing gets close
        if not self.in_call and (self.cyc >= self.stop_cyc or self.exc_live and self.exc_ready() is not None): uc.emu_stop()

    def on_intr(self, uc, intno, _):
        if intno == 2: self.pend(11)                       # svc: SVCall, taken before the next instruction
        elif intno != 8: self.ev(f"CPU exception {intno} at pc={self.r(UC_ARM_REG_PC):#x}")    # 8: EXC_RETURN in handler mode
        uc.emu_stop()

    def on_scs(self, uc, access, addr, size, value, _):
        """SysTick (0x010..0x018), NVIC set/clear registers (0x100..0x280), ICSR (0xD04), AIRCR (0xD0C).  Writes change
        the state here; reads are answered from it.  Priorities and VTOR are plain memory."""
        off = addr - SCS
        csr = value if access == UC_MEM_WRITE and off == 0x010 else self.r32(SCS + 0x10)
        scale = 1 if csr & 4 else CPU_HZ // self.r32(0x2003C8)      # CLKSOURCE clear: the reference clock the ROM set LOAD from (32 kHz)
        period = (self.r32(SCS + 0x14) + 1) * scale
        if access == UC_MEM_WRITE:
            if off == 0x010: self.st_next = (self.st_next or self.cyc + period) if value & 1 else None
            elif off == 0x018 and self.st_next is not None: self.st_next = self.cyc + period
            elif off == 0x100: self.nv_en |= value
            elif off == 0x180: self.nv_en &= ~value
            elif off in (0x200, 0x280):
                for i in range(32):
                    if value >> i & 1: self.pend(16 + i, off == 0x200)
            elif off == 0xD04:
                for bit, n, on in ((31, 2, 1), (28, 14, 1), (27, 14, 0), (26, 15, 1), (25, 15, 0)):
                    if value >> bit & 1: self.pend(n, on)
            elif off == 0xD0C and value >> 16 == 0x05FA and value & 4: self.sysreset = True
            self.pend(None)
            return
        if off == 0x010: v, self.st_flag = self.r32(addr) & 7 | self.st_flag << 16, False
        elif off == 0x018: v = (self.st_next - self.cyc) % period // scale if self.st_next is not None else 0
        elif off in (0x100, 0x180): v = self.nv_en
        elif off in (0x200, 0x280): v = sum(1 << n - 16 for n in self.exc_pend if n >= 16)
        elif off == 0xD04: v = (self.exc_active[-1] if self.exc_active else 0) | (14 in self.exc_pend) << 28 | (15 in self.exc_pend) << 26
        else: return
        uc.mem_write(addr, struct.pack("<I", v)[:size])

    def run_real(self, limit_ms):
        """Real kernel: one CPU, time from executed instructions (CPU_HZ); the idle task and hangs skip to the next event."""
        while True:
            self.now = self.cyc * 1000 // CPU_HZ
            if self.sysreset:
                self.sysreset = False
                self.reset()
                continue
            if self.st_next is not None and self.cyc >= self.st_next:
                period = (self.r32(SCS + 0x14) + 1) * (1 if self.r32(SCS + 0x10) & 4 else CPU_HZ // self.r32(0x2003C8))     # SysTick wrapped (once or more: one pend)
                self.st_next += ((self.cyc - self.st_next) // period + 1) * period
                self.st_flag = True
                if self.r32(SCS + 0x10) & 2: self.pend(15)
            for name, h in list(self.hw.items()):
                if h["at"] <= self.now:
                    if h["interval"]: h["at"] = self.now + h["interval"]
                    else: del self.hw[name]
                    h["fire"]()
            if self.uc.mem_read(0x20037A, 1)[0] & 0x10 and self.now >= self.wdg_kick + WDG_TIMEOUT_MS:     # watchdog on and starved
                self.ev(f"WATCHDOG: no kick since {self.wdg_kick} ms, pc={self.r(UC_ARM_REG_PC):#x}" + (f" ({self.hung})" if self.hung else ""), "reset")
                self.latch(self.r(UC_ARM_REG_PC), self.r(UC_ARM_REG_LR))
                self.reset(mode=3)
                continue
            if self.now > limit_ms: return
            if not self.hung:
                n = self.exc_ready()
                if n is not None:
                    self.exc_enter(n)
                    continue
                if self.bt_out and self.r32(BT_STUB + 0x40) and not self.exc_active and not self.r(UC_ARM_REG_PRIMASK):
                    self.bt_deliver()
                    continue
            nxt = min([self.st_next or FOREVER * CPU_HZ, (limit_ms + 1) * CPU_HZ // 1000, (self.wdg_kick + WDG_TIMEOUT_MS) * CPU_HZ // 1000]
                      + [-(-h["at"] * CPU_HZ // 1000) for h in self.hw.values()])
            idle = self.is_idle()                           # the idle task first gets 4000 cycles (cleanup, hook, kick)
            self.idle_until = (self.idle_until or self.cyc + 4000) if idle else None
            if self.hung or idle and self.cyc >= self.idle_until:      # nothing runs until the next event: skip there
                self.cyc, self.idle_until = max(self.cyc, nxt), None
                continue
            self.stop_cyc = min(nxt, self.idle_until) if idle else nxt
            self.romcall = False
            try:
                self.uc.emu_start(self.r(UC_ARM_REG_PC) | 1, UNTIL)
            except UcError as e:
                self.ev(f"FAULT {e}: pc={self.r(UC_ARM_REG_PC):#x} lr={self.r(UC_ARM_REG_LR):#x} sp={self.r(UC_ARM_REG_SP):#x}", "fault")
                if 3 in self.exc_active: self.hang("lockup: a fault in the HardFault handler")
                else: self.exc_enter(3)
                continue
            pc = self.r(UC_ARM_REG_PC)
            if pc >= EXC_PAGE: self.exc_return(pc | 1)
            elif self.romcall: self.dispatch(pc)

    def is_idle(self):
        """The idle task holds the CPU in thread mode and nothing is pending."""
        if self.idle_tcb is None:
            self.idle_tcb = self.r32(0x201340 + 0x2C) or None     # xIdleTaskHandle (read by xTaskGetIdleTaskHandle 0xFE84)
            self.sched_started = self.idle_tcb is not None
        return bool(self.idle_tcb) and not self.exc_active and not self.exc_live and not self.bt_out and self.r32(0x201340) == self.idle_tcb

    # The Bluetooth stack's task, as a stand-in: its callbacks into the app (writes, notification on, "sent") run in
    # a real FreeRTOS task, fed from a real queue by a pseudo interrupt.  Loop: xQueueReceive(q, buf, forever); then
    # fn(a0..a6) with a4..a6 on the stack; then bt_done (Python: the caller's on_exit).
    BT_CODE = (0xB08C, 0x480B, 0x6800, 0xA904, 0x2200, 0x43D2, 0x4B09, 0x4798, 0x9809, 0x9000, 0x980A, 0x9001, 0x980B,
               0x9002, 0x9805, 0x9906, 0x9A07, 0x9B08, 0x9C04, 0x47A0, 0x4C03, 0x47A0, 0xE7E9, 0xBF00)
    BT_PRIO = 6     # the ROM's low_stack_task (0x14628) and timer task; UpperStackTask is 5 (ROM config 0x2002E6)

    def bt_task(self):
        q = self.call(self.sym("xQueueGenericCreate"), 32, 32, 0)
        self.uc.mem_write(BT_STUB, struct.pack(f"<{len(self.BT_CODE)}H3I", *self.BT_CODE, BT_STUB + 0x40, self.sym("xQueueReceive") | 1, BT_DONE | 1))
        self.w32(BT_STUB + 0x40, q)
        self.uc.mem_write(BT_STUB + 0x50, b"bt_model\0")
        assert q and self.call(self.sym("xTaskCreate"), BT_STUB | 1, BT_STUB + 0x50, 256, 0, self.BT_PRIO, BT_STUB + 0x44) == 1

    def bt_deliver(self):
        """One Bluetooth-stack event from a pseudo interrupt (interrupts are on here): a message into one of the app's
        queues, or a callback for the stack's stand-in task."""
        kind, *item = self.bt_out.popleft()
        if kind == "send": q, msg = item
        else:
            q, msg = self.r32(BT_STUB + 0x40), struct.pack("<8I", item[0], *(list(item[1]) + [0] * 7)[:7])
        self.uc.mem_write(BT_STUB + 0x60, msg)
        self.w32(BT_STUB + 0x80, 0)
        if not self.call(self.sym("xQueueGenericSendFromISR"), q, BT_STUB + 0x60, BT_STUB + 0x80, 0, isr=True):
            self.ev(f"Bluetooth stand-in: queue {q:#x} full, {kind} dropped")
        elif kind == "call": self.bt_exit.append(item[2])
        if self.r32(BT_STUB + 0x80): self.pend(14)                # a higher-priority task woke: PendSV

    def rom_bt_done(self):
        on_exit = self.bt_exit.popleft() if self.bt_exit else None
        if on_exit: on_exit()

    def rom_boot_next(self):
        """Real kernel: the ROM's hand-over to the app, one entry after the other (SystemInit, app_pre_main, app_main)."""
        nxt = self.boot_chain.pop(0)()
        self.uc.reg_write(UC_ARM_REG_LR, (BOOT_NEXT if self.boot_chain else EXIT) | 1)
        self.uc.reg_write(UC_ARM_REG_PC, nxt & ~1)
        return BLOCK

    def dispatch(self, pc):
        lr = self.r(UC_ARM_REG_LR)
        if self.real:
            self.now = self.cyc * 1000 // CPU_HZ
            if pc == EXIT: return self.hang("app_main returned")
        if pc == EXIT:
            t, self.cur = self.cur, None
            self.tasks.remove(t)
            if t.stack in self.sizes: self.free[self.sizes.pop(t.stack)].append(t.stack)
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
            return BLOCK
        if ret is not None: self.uc.reg_write(UC_ARM_REG_R0, int(ret) & 0xFFFFFFFF)
        self.uc.reg_write(UC_ARM_REG_PC, lr & ~1)

    def schedule(self, limit_ms):
        # shortcut: round-robin with no priorities or preemption; add them if task ordering starts to matter
        while True:
            ready = [t for t in self.tasks if t.wait is None and not self.hung]
            if ready:
                self.cur = t = ready[0]
                self.tasks.remove(t); self.tasks.append(t)
                for reg, v in t.ctx.items(): self.uc.reg_write(reg, v)
                return True
            due = [tm["at"] for tm in self.timers.values() if tm["at"] is not None and not self.hung]
            due += [t.wait["at"] for t in self.tasks if t.wait and t.wait.get("at") is not None]
            due += [h["at"] for h in self.hw.values()]
            if not due:
                self.ev("idle: every task is waiting and no timer is armed")
                return False
            self.now = max(self.now, min(due))
            if self.now > limit_ms: return False
            for h, tm in self.timers.items():
                if tm["at"] is not None and tm["at"] <= self.now and not self.hung:
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
    def chip_alloc(self, size):
        """With the ROM: a kernel object's memory from the real heap, as xTaskCreate / xQueueGenericCreate take it
        (pvPortMalloc_auto_type: data heap, else buffer heap).  The kernel itself is still modelled."""
        if not self.rom: return 0
        a = self.call(self.sym("pvPortMalloc_auto_type"), size)
        assert a, f"chip heap: no room for a {size}-byte kernel object"
        return a

    def rom_os_task_create(self):
        name, h, size = self.cstr(self.arg(1)), self.handle(), self.arg(4)
        self.w32(self.arg(0), h)
        if self.rom:        # real sizes: the stack (bytes, whole words), then the 0x58-byte TCB
            stack = self.chip_alloc(size & ~3)
            self.chip_alloc(0x58)
            self.spawn(name or f"task{h:x}", self.arg(2), (self.arg(3),), stack=size & ~3, base=stack)
        else: self.spawn(name or f"task{h:x}", self.arg(2), (self.arg(3),), stack=max(size, 0x1000))
        return 1

    def rom_os_sched_start(self):
        self.sched_started = True
        self.tasks.remove(self.cur)         # main() never returns from here on real hardware
        self.cur = None
        return BLOCK

    def rom_os_sched_suspend(self): return 1
    rom_os_sched_resume = rom_os_sched_suspend

    def rom_os_delay(self): return self.block(self.arg(0))
    def busy(self, ms):
        """The CPU spends ms with nothing else running (a busy wait, or the flash lock with interrupts off).  Real
        kernel: time passes, interrupts that come due wait until it is over, SysTick pends once however many ticks."""
        if self.real: self.cyc += int(ms * CPU_HZ / 1000)
        else: self.now += int(ms)
    def rom_platform_delay_ms(self): self.busy(self.arg(0))
    def rom_platform_delay_us(self):
        if self.real: self.busy(self.arg(0) / 1000)

    def rom_os_msg_queue_create_intern(self):
        h = self.handle()
        self.chip_alloc(0x50 + self.arg(1) * self.arg(2))     # Queue_t, then the items
        self.queues[h] = {"size": self.arg(2), "items": collections.deque()}
        self.w32(self.arg(0), h)
        return 1

    def rom_os_msg_send_intern(self):
        return self.qsend(self.arg(0), bytes(self.uc.mem_read(self.arg(1), self.queues[self.arg(0)]["size"])))

    def qsend(self, h, msg):
        if self.real:       # into the app's real queue, from the same pseudo interrupt, in order with the callbacks
            self.bt_out.append(("send", h, msg))
            return 1
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
        self.chip_alloc(0x50)                           # a queue with no item storage
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
        self.chip_alloc(0x50)
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
        if self.real: self.busy(FLASH_PAGE_MS * -(-n // 256))
        return 1

    def rom_flash_auto_write_locked(self):
        self.w32(self.arg(0), self.r32(self.arg(0)) & self.arg(1))
        return 1

    def rom_flash_erase_locked(self):
        self.uc.mem_write(self.arg(1) & ~0xFFF, b"\xff" * 0x1000)
        if self.real:
            self.ev(f"flash erase {self.arg(1) & ~0xFFF:#x}: interrupts off for {self.flash_erase_ms} ms", "flash")
            self.busy(self.flash_erase_ms)
        return 1
    flash_erase_ms = FLASH_ERASE_MS
    def rom_WDG_Restart(self): self.wdg_kick = self.now

    def rom_flash_get_bank_addr(self): return FLASH_BANKS.get(self.arg(0), (0, 0))[0]
    def rom_flash_get_bank_size(self): return FLASH_BANKS.get(self.arg(0), (0, 0))[1]
    def rom_get_header_addr_by_img_id(self): return {0x2793: APP_BASE, 0x279A: 0x80E000 * self.upper}.get(self.arg(0), 0)
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
        """As the SDK documents it (system_rtl876x.c): the app's callback in app_cb_wdg_reset gets (mode, reason) first."""
        cb = self.r32(self.sym("app_cb_wdg_reset"))
        done = lambda: (self.latch(0x12B08, 0x12B09), self.reset())     # the real one's spin, as the ROM run latches it
        if not cb:
            done()
            return BLOCK
        self.spawn("wdg_cb", cb, (self.arg(0), self.arg(1)), on_exit=done)
        return self.block(FOREVER)

    def rom_ftl_save(self):
        off, n = self.arg(1), self.arg(2)
        for i, b in enumerate(self.uc.mem_read(self.arg(0), n)): self.ftl[off + i] = b
        return 0

    def rom_ftl_load(self):
        off, n = self.arg(1), self.arg(2)
        if any(off + i not in self.ftl for i in range(n)): return 1    # nothing saved yet: app falls back to defaults
        self.uc.mem_write(self.arg(0), bytes(self.ftl[off + i] for i in range(n)))
        return 0
    rom_ftl_load_intern, rom_ftl_save_intern = rom_ftl_load, rom_ftl_save     # the stack's calls (ROM 0x7A92, 0x79BE)

    # ---- misc
    # AON: 16-bit registers at even offsets; the 8-bit accessors take the odd offset as the high byte (ROM 0x32FB0)
    def rom_btaon_fast_read_safe(self): return self.aon.get(self.arg(0) & ~1, 0)
    def rom_btaon_fast_write_safe(self): self.aon[self.arg(0) & ~1] = self.arg(1) & 0xFFFF
    rom_btaon_fast_write = rom_btaon_fast_write_safe
    def rom_btaon_fast_read_safe_8b(self): return self.aon.get(self.arg(0) & ~1, 0) >> 8 * (self.arg(0) & 1) & 0xFF
    def rom_btaon_fast_write_8b(self):
        reg, sh = self.arg(0) & ~1, 8 * (self.arg(0) & 1)
        self.aon[reg] = self.aon.get(reg, 0) & ~(0xFF << sh) | (self.arg(1) & 0xFF) << sh
    rom_btaon_fast_write_safe_8b = rom_btaon_fast_write_8b
    # print_reset_reason's boot self-test (only when the latched lr is 0): WDG_Config(1, 0, INTERRUPT_CPU), WDG_Enable, 1 ms.
    # Model: the latch then holds the return address into print_reset_reason (the SDK names lr 0x7BB5, a ROM delay).
    def rom_WDG_Config(self): self.wdg_mode = self.arg(2)
    def rom_WDG_Enable(self):
        if getattr(self, "wdg_mode", None) == 0: self.latch(self.r(UC_ARM_REG_LR) & ~1, self.r(UC_ARM_REG_LR))
    # the modelled kernel never preempts, so the real heap code's scheduler lock has nothing to do
    def rom_vTaskSuspendAll(self): pass
    def rom_xTaskResumeAll(self): return 0
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

    # ---- stack="real": the ROM's hci_if API is the boundary.  Below it on the chip: low_stack_task (ROM 0x1460B, prio 6),
    # the link layer and the radio; here a Python controller and phone.  Calls into the stack's hci_if_callback
    # (evt 0 opened, 2 received, 3 sent) run in the stand-in task, which has low_stack_task's priority 6.
    link_rate, link_stalls, sup_ms = None, (), 20000     # notifications/s the phone takes (None: 6 an event); no-traffic windows

    def lower(self, evt, buf=0, n=0, status=1):
        self.bt_out.append(("call", self.hci["cb"] | 1, (evt, status, buf, n), None))

    def later(self, fn, ms=CONN_MS):
        gen = self.resets
        self.hw[f"hci{len(self.hw)}:{self.now}"] = {"at": self.now + ms, "interval": None, "fire": lambda: gen == self.resets and fn()}

    def rom_hci_if_open(self):
        self.hci["cb"] = self.arg(0)
        self.lower(0)
        return 1

    def rom_hci_if_close(self): return 1
    def rom_hci_if_rx_done(self): return self.rom_os_mem_free() or 1

    def hci_rx(self, kind, data):
        """Controller -> stack: an event (H4 type 4) or ACL data (type 2; the stack wants it 0x13 bytes into the buffer)."""
        off = 0x13 if kind == 2 else 0
        buf = self.alloc(off + 1 + len(data))
        self.uc.mem_write(buf, bytes([kind]))
        self.uc.mem_write(buf + off, bytes([kind]) + data)
        self.lower(2, buf, off + 1 + len(data))

    def hci_event(self, code, params): self.hci_rx(4, bytes([code, len(params)]) + params)

    def rom_hci_if_write(self):
        """Stack -> controller (0x17805): a command (type 1, opcode at +1) or ACL data (type 2, header at +9: hci_write
        leaves 8 bytes in front).  The buffer goes back (evt 3) at once for a command; for ACL data when the packet has
        left over the air: the stack's ACL credit comes back only then (hci_handle_lower_data_rsp 0x81804E), it does
        not use Number Of Completed Packets.  Assumption: the lower stack holds the stack's buffer until then (zero copy)."""
        buf, n = self.arg(0), self.arg(1)
        pkt = bytes(self.uc.mem_read(buf, n))
        if pkt[0] == 2 and self.hci["conn"]: self.hci["conn"]["down"].append((pkt[9:], buf, n))
        else: self.lower(3, buf, n)
        if pkt[0] == 1: self.hci_command(struct.unpack_from("<H", pkt, 1)[0], pkt[4:])
        return 1

    def hci_command(self, op, p):
        h = p[:2]
        done = {0x2016: lambda: self.hci_event(0x3E, b"\x04\x00" + h + bytes(8)),                 # LE remote features
                0x041D: lambda: self.hci_event(0x0C, b"\x00" + h + struct.pack("<BHH", 9, 0x5D, 0)),  # remote version
                0x2013: lambda: self.hci_event(0x3E, b"\x03\x00" + h + struct.pack("<H", 39) + p[6:10]),  # conn update
                0x2032: lambda: self.hci_event(0x3E, b"\x0C\x00" + h + b"\x01\x01"),             # PHY update
                0x0406: lambda: self.link_down(0x16)}
        if op in done:      # Command Status now, the completion one connection event later
            self.hci_event(0x0F, struct.pack("<BBH", 0, 1, op))
            return self.later(done[op])
        ret = {**ROM_HCI, 0x1009: bytes.fromhex("665544332211"),
               0x2018: bytes(range(8)), 0x202F: struct.pack("<4H", 251, 2120, 251, 2120),
               0x2023: struct.pack("<2H", 27, 328), 0x2022: h, 0x1405: h + b"\xc4", 0x2015: h + b"\xff" * 5,
               0x201A: h, 0x201B: h, 0xFD82: p}.get(op, b"")
        self.hci_event(0x0E, struct.pack("<BHB", 1, op, 0) + ret)    # Command Complete, status 0, one command credit

    def link_up(self):
        c = self.hci["conn"] = {"handle": 0x10, "down": collections.deque(), "up": collections.deque(), "tokens": 0.0,
                                "last": self.now, "wait": False, "reqs": [], "pending": [], "sub": set(), "found": False}
        self.services, self.connected = [], True
        self.ev("BLE phone connected", "ble_conn")
        self.hci_event(0x3E, struct.pack("<BBHBB6sHHHB", 1, 0, c["handle"], 1, 0, bytes(6), 39, 0, self.sup_ms // 10, 0))
        self.hw["conn"] = {"at": self.now + CONN_MS, "interval": CONN_MS, "fire": self.conn_event}
        self.att_req(struct.pack("<BHHH", 0x10, 1, 0xFFFF, 0x2800))     # discovery: primary services

    def link_down(self, reason):
        c, self.hci["conn"] = self.hci["conn"], None
        if not c: return
        self.hw.pop("conn", None)
        self.connected, self.subscribed = False, set()
        self.ev(f"BLE phone disconnected (reason {reason:#x}), {len(c['down'])} packets were waiting in the controller", "ble_conn")
        for acl, buf, n in c["down"]: self.lower(3, buf, n)        # the lower stack flushes them
        self.hci_event(0x05, struct.pack("<BHB", 0, c["handle"], reason))

    def conn_event(self):
        """One connection event: unless the link stalls, the ring sends what the phone takes (each packet's buffer and
        credit back to the stack), then the phone's packets come in."""
        c = self.hci["conn"]
        if any(a <= self.now < b for a, b in self.link_stalls):
            if self.now - c["last"] >= self.sup_ms: self.link_down(0x08)      # supervision timeout
            return
        c["last"] = self.now
        c["tokens"] = min(c["tokens"] + (self.link_rate or 1000) * CONN_MS / 1000, 6)
        while c["down"] and c["tokens"] >= 1:
            c["tokens"] -= 1
            acl, buf, n = c["down"].popleft()
            self.lower(3, buf, n)
            self.phone_rx(acl)
        while c["up"]:
            att = c["up"].popleft()
            self.hci_rx(2, struct.pack("<HHHH", c["handle"] | 0x2000, len(att) + 4, len(att), 4) + att)

    def att_req(self, pdu):
        """Phone -> ring ATT; requests one at a time, as ATT allows."""
        self.hci["conn"]["reqs"].append(pdu)
        self.att_next()

    def att_next(self):
        c = self.hci["conn"]
        if c and not c["wait"] and c["reqs"]:
            pdu = c["reqs"].pop(0)
            c["wait"] = True
            c["up"].append(pdu)

    def phone_rx(self, acl):
        """Ring -> phone: one ACL packet (no fragments: notifications fit the 27-byte buffers)."""
        _, _, _, cid = struct.unpack_from("<HHHH", acl)
        att, c = acl[8:], self.hci["conn"]
        if cid != 4 or not att: return
        if att[0] == 0x1B:                                  # notification
            handle, msg = struct.unpack_from("<H", att, 1)[0], att[3:]
            svc = next((s for s in self.services if s["start"] <= handle <= s["end"]), None)
            if svc and svc["uuid"].startswith(OTA_UUID): return self.ota_ack(msg)
            self.notified.append(msg)
            return self.ev(f"BLE notify to phone: {msg.hex(' ')}", "ble_tx", data=msg.hex())
        if att[0] == 0x1D: c["up"].append(b"\x1e")            # indication: confirm
        if att[0] == 0x11:                                  # read by group type: (start, end, service uuid)...
            n = att[1]
            for i in range(2, len(att), n):
                s, e = struct.unpack_from("<HH", att, i)
                self.services.append({"uuid": att[i + 4:i + n][::-1].hex(), "start": s, "end": e})
            if self.services[-1]["end"] < 0xFFFF: c["reqs"].insert(0, struct.pack("<BHHH", 0x10, self.services[-1]["end"] + 1, 0xFFFF, 0x2800))
            else: c["found"] = True
        if att[0] == 0x01 and att[1] == 0x10: c["found"] = True   # attribute not found: discovery done
        if att[0] in (0x01, 0x11, 0x13):
            c["wait"] = False
            if c["found"]: self.phone_flush()
            self.att_next()

    def phone_flush(self):
        """Writes the phone was asked for, once it knows the services: notifications on (CCCD = service start + 5), then
        the write to the value at start + 2, as the Realtek attribute tables lay them out."""
        c = self.hci["conn"]
        while c["pending"]:
            uuid, data = c["pending"].pop(0)
            svc = next((s for s in self.services if s["uuid"].startswith(uuid)), None)
            if not svc:
                self.ev(f"BLE write dropped, service {uuid} not found on the ring")
                continue
            if uuid not in c["sub"]:
                c["sub"].add(uuid)
                c["reqs"].append(struct.pack("<BH", 0x12, svc["start"] + 5) + b"\x01\x00")
            c["reqs"].append(struct.pack("<BH", 0x12, svc["start"] + 2) + data)

    def ble_write(self, data, uuid="6e40fff0"):
        """Phone -> ring: deliver one write to a service's write handler (default: the Colmi command service)."""
        if self.upper:
            if uuid == "6e40fff0": self.ev(f"BLE command from phone: {data.hex(' ')}", "ble_rx", data=data.hex())
            if not self.hci["conn"]: self.link_up()
            self.hci["conn"]["pending"].append((uuid, data))
            if self.hci["conn"]["found"]: self.phone_flush()
            return self.att_next()
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

    def ble_disconnect(self):
        """The phone's link drops (walked out of range): conn 0 goes DISCONNECTED, cause remote user terminated."""
        if self.upper: return self.link_down(0x13)
        self.connected, self.subscribed = False, set()
        self.ev("BLE phone disconnected", "ble_conn")
        self.qsend(self.io_q, struct.pack("<HHBBH", 0, 2, 0, 0, 0x113))
        self.qsend(self.evt_q, bytes([2]))

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
